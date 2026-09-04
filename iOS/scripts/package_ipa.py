"""Validate an unsigned arm64 iPhone app, then package a resignable IPA.

The verification marker must come from the same source revision's passing Xcode
simulator run. Packaging never labels an untested build simulator-verified.
"""
from pathlib import Path
import datetime
import hashlib
import json
import plistlib
import posixpath
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from source_fingerprint import fingerprint

ROOT = Path(__file__).resolve().parents[1]

def version_tuple(value):
    parts=tuple(map(int,value.split('.')))
    if not 1<=len(parts)<=3 or any(part<0 for part in parts):
        raise ValueError(f"Invalid OS version: {value}")
    return parts+(0,)*(3-len(parts))

def validate_minimum_os(declared,binaries):
    minimum=version_tuple(declared)
    if minimum!=(16,0,0): raise ValueError("Expected the promised iOS 16.0 deployment minimum")
    for path,record in binaries.items():
        if version_tuple(record["minimumOS"])>minimum:
            raise ValueError(f"Binary requires newer iOS than the app deployment minimum: {path}")

def macho(path):
    data=path.read_bytes()
    if len(data)<32 or data[:4]!=b"\xcf\xfa\xed\xfe":
        raise ValueError(f"Expected a thin 64-bit Mach-O: {path.name}")
    _,cpu,_,kind,ncmds,sizeofcmds,_,_=struct.unpack_from("<8I",data)
    if cpu!=0x0100000C: raise ValueError(f"Expected arm64: {path.name}")
    if sizeofcmds>len(data)-32: raise ValueError("Truncated Mach-O commands")
    platforms=set()
    minimum_versions=set()
    dependencies=[]
    rpaths=[]
    file_segments=0
    encrypted=False
    offset=32
    for _ in range(ncmds):
        if offset+8>32+sizeofcmds: raise ValueError("Truncated Mach-O command")
        cmd,size=struct.unpack_from("<2I",data,offset)
        if size<8 or offset+size>32+sizeofcmds: raise ValueError("Invalid Mach-O command")
        if cmd==0x32:
            if size<24: raise ValueError("Invalid LC_BUILD_VERSION")
            platforms.add(struct.unpack_from("<I",data,offset+8)[0])
            minimum_versions.add(struct.unpack_from("<I",data,offset+12)[0])
        if cmd==0x25: # legacy LC_VERSION_MIN_IPHONEOS, arm64 device only
            if size<16: raise ValueError("Invalid LC_VERSION_MIN_IPHONEOS")
            platforms.add(2)
            minimum_versions.add(struct.unpack_from("<I",data,offset+8)[0])
        if cmd==0x19: # LC_SEGMENT_64: check payload ranges, not just the header.
            if size<72: raise ValueError("Invalid segment command")
            file_offset,file_size=struct.unpack_from("<2Q",data,offset+40)
            sections=struct.unpack_from("<I",data,offset+64)[0]
            if size<72+sections*80: raise ValueError("Truncated segment sections")
            if file_size:
                file_segments+=1
                if file_offset+file_size>len(data): raise ValueError("Truncated Mach-O segment payload")
        if cmd in (0x1D,0x26,0x29,0x2B,0x2E,0x80000033,0x80000034):
            if size<16: raise ValueError("Invalid link-edit command")
            start,length=struct.unpack_from("<2I",data,offset+8)
            if start+length>len(data): raise ValueError("Truncated Mach-O link-edit payload")
        if cmd in (0xC,0x80000018,0x8000001F,0x80000023):
            if size<24: raise ValueError("Invalid dylib command")
            start=struct.unpack_from("<I",data,offset+8)[0]
            if start<24 or start>=size: raise ValueError("Invalid dylib path")
            value=data[offset+start:offset+size]
            if b"\0" not in value: raise ValueError("Unterminated dylib path")
            dependencies.append(value.split(b"\0",1)[0].decode())
        if cmd==0x8000001C: # LC_RPATH
            if size<12: raise ValueError("Invalid rpath command")
            start=struct.unpack_from("<I",data,offset+8)[0]
            if start<12 or start>=size: raise ValueError("Invalid rpath")
            value=data[offset+start:offset+size]
            if b"\0" not in value: raise ValueError("Unterminated rpath")
            rpaths.append(value.split(b"\0",1)[0].decode())
        if cmd==0x2C:
            if size<24: raise ValueError("Invalid encryption command")
            encrypted|=struct.unpack_from("<I",data,offset+16)[0]!=0
        offset+=size
    if offset!=32+sizeofcmds: raise ValueError("Mach-O command count does not match its command data")
    if platforms!={2}: raise ValueError(f"Not an iOS device binary (platform {sorted(platforms)}): {path.name}")
    if len(minimum_versions)!=1: raise ValueError("Conflicting Mach-O minimum OS versions")
    minimum=minimum_versions.pop()
    if encrypted: raise ValueError("An encrypted App Store binary cannot be resigned")
    return {"architecture":"arm64","platform":"iOS device","fileType":kind,"dependencies":dependencies,
            "rpaths":rpaths,"fileBytes":len(data),"fileSegments":file_segments,
            "minimumOS":f"{minimum>>16}.{minimum>>8&255}.{minimum&255}"}

def validate_dependencies(binaries, executable):
    """Every bundled load command must resolve to an inspected Mach-O image."""
    def local_path(value, loader):
        if value=="@executable_path": return "."
        if value.startswith("@executable_path/"): value=value.removeprefix("@executable_path/")
        elif value=="@loader_path": return posixpath.dirname(loader) or "."
        elif value.startswith("@loader_path/"):
            value=posixpath.join(posixpath.dirname(loader),value.removeprefix("@loader_path/"))
        else: return None
        value=posixpath.normpath(value)
        if value==".." or value.startswith(("../","/")): raise ValueError("Dylib path escapes the application")
        return value

    for binary,record in binaries.items():
        for dependency in record["dependencies"]:
            if dependency.startswith(("/System/Library/","/usr/lib/")): continue
            if dependency.startswith("@rpath/"):
                bases=[(binary,path) for path in record["rpaths"]]
                if binary!=executable: bases += [(executable,path) for path in binaries[executable]["rpaths"]]
                candidates=[]
                for loader,path in bases:
                    base=local_path(path,loader)
                    if base is not None:
                        candidates.append(posixpath.normpath(posixpath.join(base,dependency.removeprefix("@rpath/"))))
                if not any(candidate in binaries for candidate in candidates):
                    raise ValueError(f"Missing embedded dependency or runpath for {dependency} in {binary}")
            else:
                candidate=local_path(dependency,binary)
                if candidate not in binaries: raise ValueError(f"Unexpected or missing external dependency: {dependency}")

def validate_app(app):
    info=plistlib.loads((app/"Info.plist").read_bytes())
    assert info["CFBundlePackageType"]=="APPL", "Not an application"
    assert info["UIDeviceFamily"]==[1], "Expected iPhone-only device family"
    validate_minimum_os(info["MinimumOSVersion"],{})
    assert info.get("CFBundleSupportedPlatforms")==["iPhoneOS"], "Simulator app rejected"
    assert not (app/"embedded.mobileprovision").exists(), "Provisioning profiles must stay outside unsigned output"
    assert (app/"Assets.car").is_file() and (app/"Assets.car").stat().st_size>0, "Missing compiled icon catalog"
    assert (app/"PrivacyInfo.xcprivacy").is_file(), "Missing privacy manifest"
    assert all(path.resolve().is_relative_to(app.resolve()) for path in app.rglob("*")), "App contains a path outside its bundle"
    assert (app/"IcyAssets/font/Roboto-Regular.ttf").is_file(), "Missing baseline font"
    for path in (ROOT/"shared/ui/assets").rglob("*"):
        if path.is_file():
            target=app/"IcyAssets"/path.relative_to(ROOT/"shared/ui/assets")
            assert target.is_file() and hashlib.sha256(path.read_bytes()).digest()==hashlib.sha256(target.read_bytes()).digest(), f"Changed or missing visual resource: {path.name}"
    executable=info["CFBundleExecutable"]
    assert Path(executable).name==executable, "Invalid executable path"
    binaries={executable:macho(app/executable)}
    assert binaries[executable]["fileType"]==2,"Main binary must be MH_EXECUTE"
    for framework in (app/"Frameworks").glob("*.framework"):
        metadata=plistlib.loads((framework/"Info.plist").read_bytes())
        if "MinimumOSVersion" in metadata and version_tuple(metadata["MinimumOSVersion"])>(16,0,0):
            raise ValueError(f"Embedded framework requires newer iOS than 16.0: {framework.name}")
        name=metadata["CFBundleExecutable"]
        assert Path(name).name==name,"Invalid framework executable"
        binaries[f"Frameworks/{framework.name}/{name}"]=macho(framework/name)
        assert binaries[f"Frameworks/{framework.name}/{name}"]["fileType"]==6,"Embedded framework must be MH_DYLIB"
    for library in (app/"Frameworks").glob("*.dylib"):
        binaries[f"Frameworks/{library.name}"]=macho(library)
        assert binaries[f"Frameworks/{library.name}"]["fileType"]==6,"Embedded library must be MH_DYLIB"
    assert "Frameworks/SpotifyiOS.framework/SpotifyiOS" in binaries,"Missing Spotify App Remote framework"
    assert all(record["fileSegments"] for record in binaries.values()),"A packaged binary has no file-backed segments"
    validate_minimum_os(info["MinimumOSVersion"],binaries)
    validate_dependencies(binaries,executable)
    forbidden={".p12",".pfx",".jks",".keystore",".mobileprovision",".key",".pem"}
    assert not any(path.suffix.lower() in forbidden for path in app.rglob("*")),"Private signing material in app"
    return info,binaries

def main():
    app=Path(sys.argv[1]).resolve()
    if not app.is_relative_to((ROOT/"build").resolve()): raise SystemExit("Package only an app built under iOS/build")
    verification=json.loads((ROOT/"build/reports/simulator-verification.json").read_text())
    sha=subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip()
    assert verification["result"]=="passed" and verification["commit"]==sha,"Missing matching simulator verification"
    assert verification["sourceFingerprint"]==fingerprint(),"Sources changed after simulator verification"
    info,binaries=validate_app(app)
    delivery=ROOT/"build/delivery"
    delivery.mkdir(parents=True,exist_ok=True)
    ipa=delivery/"IcyLyrics-unsigned.ipa"
    with tempfile.TemporaryDirectory(prefix="ipa-",dir=ROOT/"build") as temp:
        payload=Path(temp)/"Payload"
        payload.mkdir()
        subprocess.run(["ditto",str(app),str(payload/"IcyLyrics.app")],check=True)
        subprocess.run(["ditto","-c","-k","--norsrc","--noextattr","--noacl","--keepParent",str(payload),str(ipa)],check=True)
    with zipfile.ZipFile(ipa) as archive:
        assert archive.testzip() is None,"IPA CRC check failed"
        names=archive.namelist()
        assert all(name.startswith("Payload/") for name in names),"Unexpected archive root"
        assert "Payload/IcyLyrics.app/Info.plist" in names,"Missing Payload app"
        assert all(".." not in Path(name).parts for name in names),"Unsafe archive path"
    digest=hashlib.sha256(ipa.read_bytes()).hexdigest()
    (delivery/"SHA256SUMS.txt").write_text(f"{digest}  {ipa.name}\n")
    report={"label":"simulator-verified IPA; physical iPhone validation pending", "commit":sha,
            "verificationScope":"simulator-tested sources; device binary separately inspected, not executed",
            "createdUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"sha256":digest,"bytes":ipa.stat().st_size,
            "bundleIdentifier":info["CFBundleIdentifier"],"minimumOS":info["MinimumOSVersion"],"binaries":binaries,
            "visualParity":"pending cross-platform comparison and review", "publicBinaryRelease":"not cleared",
            "simulator":verification,"signing":"Main application unsigned; embedded SDK may retain upstream signature. Sideloadly must resign all components."}
    (delivery/"build-report.json").write_text(json.dumps(report,indent=2)+"\n")
    shutil.copy2(ROOT/"docs/INSTALL-WINDOWS.md",delivery)
    print(f"Validated {ipa.name} ({ipa.stat().st_size} bytes), SHA-256 {digest}")

if __name__=="__main__": main()
