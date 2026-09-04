"""Fetch only checksum-pinned official age tools into the ignored delivery area."""
from pathlib import Path
import hashlib
import os
import platform
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/owner-delivery"
VERSION = "v1.3.2"
# Official GitHub release asset digests, checked September 4, 2026.
ASSETS = {
    "windows-amd64": ("zip", "f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7"),
    "darwin-arm64": ("tar.gz", "e2020b073c44f692685a24d6abc378817eb81ffaaf49fd0531ef8565f767f2f5"),
}

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

def within_build(path):
    path = Path(path).absolute()
    if not path.resolve().is_relative_to(ROOT / "build"):
        raise ValueError("Owner delivery output must stay inside iOS/build")
    return path

def host_name():
    system, machine = platform.system(), platform.machine().lower()
    if system == "Windows" and machine in ("amd64", "x86_64"):
        return "windows-amd64"
    if system == "Darwin" and machine in ("arm64", "aarch64"):
        return "darwin-arm64"
    raise ValueError("Pinned owner-transfer tools support Windows x64 or Apple Silicon macOS")

def bootstrap():
    host = host_name()
    extension, checksum = ASSETS[host]
    tools = within_build(WORK / "tools")
    tools.mkdir(parents=True, exist_ok=True)
    name = f"age-{VERSION}-{host}.{extension}"
    archive = tools / name
    if not archive.exists():
        url = f"https://github.com/FiloSottile/age/releases/download/{VERSION}/{name}"
        with tempfile.TemporaryDirectory(prefix="download-", dir=tools) as temporary:
            pending = Path(temporary) / name
            with urllib.request.urlopen(url, timeout=60) as response, pending.open("wb") as output:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > 64 * 1024 * 1024:
                        raise ValueError("age release archive exceeds the download limit")
                    output.write(chunk)
            if digest(pending) != checksum:
                raise ValueError("Official age archive SHA-256 mismatch")
            os.replace(pending, archive)
    if archive.is_symlink() or digest(archive) != checksum:
        raise ValueError("Cached age archive failed its pinned SHA-256; remove that cached archive and retry")
    suffix = ".exe" if host.startswith("windows") else ""
    names = ("age" + suffix, "age-keygen" + suffix, "LICENSE")
    directory = tools / host
    directory.mkdir(exist_ok=True)
    # Extract only these exact regular members; never unpack an archive wholesale.
    if extension == "zip":
        with zipfile.ZipFile(archive) as package:
            members = {name: package.read("age/" + name) for name in names}
    else:
        with tarfile.open(archive) as package:
            members = {}
            for name in names:
                member = package.getmember("age/" + name)
                if not member.isfile():
                    raise ValueError("Non-regular member in the pinned age archive")
                members[name] = package.extractfile(member).read()
    for name, contents in members.items():
        target = within_build(directory / name)
        if target.is_symlink():
            raise ValueError("Refusing a symlink in the age tool cache")
        if not target.exists() or digest(target) != hashlib.sha256(contents).hexdigest():
            with tempfile.NamedTemporaryFile(dir=directory, delete=False) as stream:
                pending = Path(stream.name)
                stream.write(contents)
            try:
                os.chmod(pending, 0o700 if name != "LICENSE" else 0o600)
                os.replace(pending, target)
            finally:
                pending.unlink(missing_ok=True)
    return directory / ("age" + suffix), directory / ("age-keygen" + suffix)

if __name__ == "__main__":
    bootstrap()
    print(f"Verified official age {VERSION} tools for {host_name()}")
