"""Deterministic, dependency-free Xcode project generation; all paths stay under iOS.

Run after adding Swift sources, then commit the project. --check rejects stale output.
"""
from pathlib import Path
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
objects = {}

class Ref(str):
    pass

def uid(key):
    return Ref(hashlib.sha256(key.encode()).hexdigest()[:24].upper())

def add(key, isa, **fields):
    key = uid(key)
    objects[key] = dict(isa=isa, **fields)
    return key

def encode(value, depth=0):
    if isinstance(value, Ref):
        return value
    if isinstance(value, dict):
        return "{\n" + "".join("\t" * (depth+1) + f"{encode(k)} = {encode(v, depth+1)};\n"
                                 for k,v in value.items()) + "\t" * depth + "}"
    if isinstance(value, list):
        return "(" + ", ".join(encode(v, depth) for v in value) + ")"
    if isinstance(value, int):
        return str(value)
    return json.dumps(value)

def file(key, path, kind, **kw):
    return add(key, "PBXFileReference", path=path, lastKnownFileType=kind, sourceTree="SOURCE_ROOT", **kw)

def build_file(key, ref, **kw):
    return add(key, "PBXBuildFile", fileRef=ref, **kw)

config_ref = file("config", "Config.xcconfig", "text.xcconfig")
products = []
groups = []
targets = []
project_id = uid("project")
app_target = uid("target:IcyLyrics")
shared = file("shared", "Frameworks/IcyShared.framework", "wrapper.framework")
spotify = file("spotify", "Frameworks/SpotifyiOS.xcframework", "wrapper.xcframework")
assets = file("assets", "../shared/ui/assets", "folder", name="IcyAssets")
catalog = file("catalog", "IcyLyrics/Assets.xcassets", "folder.assetcatalog")
privacy = file("privacy", "IcyLyrics/PrivacyInfo.xcprivacy", "text.xml")
info = file("info", "IcyLyrics/Info.plist", "text.plist.xml")

def configurations(name, settings):
    values = []
    for variant in ("Debug", "Release"):
        options = dict(settings)
        options.update(SWIFT_OPTIMIZATION_LEVEL="-Onone" if variant == "Debug" else "-O",
                       SWIFT_ACTIVE_COMPILATION_CONDITIONS="DEBUG $(inherited)" if variant == "Debug" else "$(inherited)",
                       ENABLE_TESTABILITY="YES" if variant == "Debug" else "NO",
                       DEBUG_INFORMATION_FORMAT="dwarf" if variant == "Debug" else "dwarf-with-dsym")
        values.append(add(f"config:{name}:{variant}", "XCBuildConfiguration", name=variant,
                          baseConfigurationReference=config_ref, buildSettings=options))
    return add(f"configlist:{name}", "XCConfigurationList", buildConfigurations=values,
               defaultConfigurationIsVisible=0, defaultConfigurationName="Release")

for name, directory, product_type in (
    ("IcyLyrics", "IcyLyrics", "com.apple.product-type.application"),
    ("IcyLyricsTests", "IcyLyricsTests", "com.apple.product-type.bundle.unit-test"),
    ("IcyLyricsUITests", "IcyLyricsUITests", "com.apple.product-type.bundle.ui-testing"),
    ("IcyLyricsExtendedUITests", "IcyLyricsExtendedUITests", "com.apple.product-type.bundle.ui-testing"),
    ("IcyLyricsKawarpUITests", "IcyLyricsKawarpUITests", "com.apple.product-type.bundle.ui-testing"),
):
    is_app = name == "IcyLyrics"
    source_paths = list((APP/directory).rglob("*.swift"))
    if product_type == "com.apple.product-type.bundle.ui-testing":
        source_paths += list((APP/"NativeCapture").glob("*.swift"))
    source_refs = [file("source:"+path.relative_to(APP).as_posix(), path.relative_to(APP).as_posix(), "sourcecode.swift")
                   for path in sorted(source_paths, key=lambda value: value.relative_to(APP).as_posix())]
    groups.append(add("group:"+name,"PBXGroup", name=name, children=source_refs + ([catalog, info, privacy] if is_app else []), sourceTree="<group>"))
    source_phase = add("sources:"+name,"PBXSourcesBuildPhase", buildActionMask=2147483647,
                       files=[build_file("compile:"+name+":"+str(ref),ref) for ref in source_refs], runOnlyForDeploymentPostprocessing=0)
    links = [build_file("link:"+name+str(ref),ref) for ref in ([shared,spotify] if is_app else [])]
    link_phase = add("links:"+name,"PBXFrameworksBuildPhase",buildActionMask=2147483647,files=links,runOnlyForDeploymentPostprocessing=0)
    resources = [build_file("resource:"+str(ref),ref) for ref in ([catalog,privacy] if is_app else [])]
    resource_phase = add("resources:"+name,"PBXResourcesBuildPhase",buildActionMask=2147483647,files=resources,runOnlyForDeploymentPostprocessing=0)
    phases=[source_phase,link_phase,resource_phase]
    if is_app:
        phases.append(add("embed","PBXCopyFilesBuildPhase",buildActionMask=2147483647,dstPath="",dstSubfolderSpec=10,
            files=[build_file("embedspotify",spotify,settings={"ATTRIBUTES":["CodeSignOnCopy","RemoveHeadersOnCopy"]})],
            name="Embed Spotify",runOnlyForDeploymentPostprocessing=0))
        # Folder references keep the source basename; copy under the deliberate runtime name.
        phases.append(add("renameassets","PBXShellScriptBuildPhase",buildActionMask=2147483647,files=[],
            inputPaths=["$(SRCROOT)/../shared/ui/assets"],outputPaths=["$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/IcyAssets"],
            name="Copy canonical visual resources",shellPath="/bin/sh",
            shellScript='set -eu\n/usr/bin/ditto "$SRCROOT/../shared/ui/assets" "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/IcyAssets"\n',
            runOnlyForDeploymentPostprocessing=0))
        phases.append(add("kawarpprobeassets", "PBXShellScriptBuildPhase", buildActionMask=2147483647, files=[],
            inputPaths=["$(SRCROOT)/../tests/fixtures/kawarp"], outputPaths=[], alwaysOutOfDate=1,
            name="Optional Debug Kawarp probe input", shellPath="/bin/sh",
            shellScript='set -eu\nprobe_assets="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}/KawarpProbeAssets"\n'
                'if [ "$CONFIGURATION" = Debug ] && [ "${ICY_KAWARP_PROBE:-NO}" = YES ]; then\n'
                '  /usr/bin/ditto "$SRCROOT/../tests/fixtures/kawarp" "$probe_assets"\n'
                'else\n  /bin/rm -rf "$probe_assets"\nfi\n',
            runOnlyForDeploymentPostprocessing=0))
    product = add("product:"+name,"PBXFileReference",explicitFileType="wrapper.application" if is_app else "wrapper.cfbundle",
                  path=name+(".app" if is_app else ".xctest"),sourceTree="BUILT_PRODUCTS_DIR",includeInIndex=0)
    products.append(product)
    options = dict(PRODUCT_NAME="$(TARGET_NAME)", PRODUCT_BUNDLE_IDENTIFIER="$(ICY_BUNDLE_ID)"+("" if is_app else "."+name),
                   SDKROOT="iphoneos",SUPPORTED_PLATFORMS="iphoneos iphonesimulator",TARGETED_DEVICE_FAMILY="1",
                   ARCHS="arm64",ONLY_ACTIVE_ARCH="YES",
                   IPHONEOS_DEPLOYMENT_TARGET="16.0",SWIFT_VERSION="5.0",SWIFT_STRICT_CONCURRENCY="minimal",
                   CLANG_ENABLE_MODULES="YES",ENABLE_USER_SCRIPT_SANDBOXING="NO",CODE_SIGN_STYLE="Automatic",
                   FRAMEWORK_SEARCH_PATHS=["$(inherited)","$(SRCROOT)/Frameworks"],
                   LD_RUNPATH_SEARCH_PATHS=["$(inherited)","@executable_path/Frameworks","@loader_path/Frameworks"])
    dependencies=[]
    if is_app:
        options.update(INFOPLIST_FILE="IcyLyrics/Info.plist",GENERATE_INFOPLIST_FILE="NO",
                       ASSETCATALOG_COMPILER_APPICON_NAME="AppIcon",
                       OTHER_LDFLAGS=["$(inherited)","-ObjC","-lsqlite3","-lc++","-framework","Metal","-framework","CoreText","-framework","CoreGraphics","-framework","QuartzCore","-framework","Security"])
    else:
        proxy=add("proxy:"+name,"PBXContainerItemProxy",containerPortal=project_id,proxyType=1,remoteGlobalIDString=app_target,remoteInfo="IcyLyrics")
        dependencies=[add("dep:"+name,"PBXTargetDependency",target=app_target,targetProxy=proxy)]
        options.update(GENERATE_INFOPLIST_FILE="YES",TEST_TARGET_NAME="IcyLyrics")
        if name == "IcyLyricsTests":
            options.update(TEST_HOST="$(BUILT_PRODUCTS_DIR)/IcyLyrics.app/IcyLyrics",BUNDLE_LOADER="$(TEST_HOST)")
    targets.append(add("target:"+name,"PBXNativeTarget",buildConfigurationList=configurations(name,options),buildPhases=phases,
                       buildRules=[],dependencies=dependencies,name=name,productName=name,productReference=product,productType=product_type))

product_group=add("products","PBXGroup",name="Products",children=products,sourceTree="<group>")
frameworks=add("frameworks","PBXGroup",name="Frameworks",children=[shared,spotify],sourceTree="<group>")
main_group=add("root","PBXGroup",children=groups+[config_ref,assets,frameworks,product_group],sourceTree="<group>")
add("project","PBXProject",attributes={"BuildIndependentTargetsInParallel":"YES","LastUpgradeCheck":"2640"},
    buildConfigurationList=configurations("project",{"CLANG_ENABLE_OBJC_ARC":"YES","GCC_C_LANGUAGE_STANDARD":"gnu17"}),
    compatibilityVersion="Xcode 14.0",developmentRegion="en",hasScannedForEncodings=0,knownRegions=["en","Base"],
    mainGroup=main_group,productRefGroup=product_group,projectDirPath="",projectRoot="",targets=targets)
output="// !$*UTF8*$!\n"+encode(dict(archiveVersion=1,classes={},objectVersion=56,objects=objects,rootObject=project_id))+"\n"

scheme=ET.Element("Scheme",LastUpgradeVersion="2640",version="1.3")
build=ET.SubElement(scheme,"BuildAction",parallelizeBuildables="YES",buildImplicitDependencies="YES")
entries=ET.SubElement(build,"BuildActionEntries")
def reference(parent,name):
    ET.SubElement(parent,"BuildableReference",BuildableIdentifier="primary",BlueprintIdentifier=uid("target:"+name),
                  BuildableName=name+(".app" if name=="IcyLyrics" else ".xctest"),BlueprintName=name,ReferencedContainer="container:IcyLyrics.xcodeproj")
entry=ET.SubElement(entries,"BuildActionEntry",buildForTesting="YES",buildForRunning="YES",buildForProfiling="YES",buildForArchiving="YES",buildForAnalyzing="YES")
reference(entry,"IcyLyrics")
test=ET.SubElement(scheme,"TestAction",buildConfiguration="Debug",selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB",selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB",shouldUseLaunchSchemeArgsEnv="YES",preferredScreenCaptureFormat="screenshots")
testables=ET.SubElement(test,"Testables")
for name in ("IcyLyricsTests","IcyLyricsUITests"):
    reference(ET.SubElement(testables,"TestableReference",skipped="NO"),name)
launch=ET.SubElement(scheme,"LaunchAction",buildConfiguration="Debug",selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB",selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB",launchStyle="0",useCustomWorkingDirectory="NO",ignoresPersistentStateOnLaunch="NO",debugDocumentVersioning="YES",allowLocationSimulation="YES")
reference(ET.SubElement(launch,"BuildableProductRunnable",runnableDebuggingMode="0"),"IcyLyrics")
ET.SubElement(scheme,"AnalyzeAction",buildConfiguration="Debug")
ET.SubElement(scheme,"ArchiveAction",buildConfiguration="Release",revealArchiveInOrganizer="YES")
ET.indent(scheme)
scheme_output='<?xml version="1.0" encoding="UTF-8"?>\n'+ET.tostring(scheme,encoding="unicode")+"\n"

# A separate test bundle/scheme keeps the original 29 UIKit captures untouched.
extended_scheme = ET.fromstring(scheme_output)
extended_testables = extended_scheme.find("./TestAction/Testables")
extended_testables.clear()
reference(ET.SubElement(extended_testables, "TestableReference", skipped="NO"), "IcyLyricsExtendedUITests")
ET.indent(extended_scheme)
extended_scheme_output = '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(extended_scheme, encoding="unicode") + "\n"

kawarp_scheme = ET.fromstring(scheme_output)
kawarp_testables = kawarp_scheme.find("./TestAction/Testables")
kawarp_testables.clear()
reference(ET.SubElement(kawarp_testables, "TestableReference", skipped="NO"), "IcyLyricsKawarpUITests")
ET.indent(kawarp_scheme)
kawarp_scheme_output = '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(kawarp_scheme, encoding="unicode") + "\n"

def main(argv=None):
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    args=parser.parse_args(argv)
    for path,content in ((APP/"IcyLyrics.xcodeproj/project.pbxproj",output),
                         (APP/"IcyLyrics.xcodeproj/xcshareddata/xcschemes/IcyLyrics.xcscheme",scheme_output),
                         (APP/"IcyLyrics.xcodeproj/xcshareddata/xcschemes/IcyLyricsExtendedParity.xcscheme",extended_scheme_output),
                         (APP/"IcyLyrics.xcodeproj/xcshareddata/xcschemes/IcyLyricsKawarpGpu.xcscheme",kawarp_scheme_output)):
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8")!=content: raise SystemExit(f"Stale generated project: {path}")
        else:
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding="utf-8",newline="\n")

if __name__=="__main__": main()
