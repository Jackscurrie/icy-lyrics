#!/usr/bin/env python3
"""Explicit local-only full Skiko/Skia FreeType package experiment. Python3.10+."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import uuid
import zipfile
import xml.etree.ElementTree as ET

from gn_args import arguments

HERE = Path(__file__).resolve().parent
IOS = HERE.parents[1]
LOCK_PATH = HERE / "sources.lock.json"
LOCK_BYTES = LOCK_PATH.read_bytes()
LOCK = json.loads(LOCK_BYTES)
LOCK_SHA256 = hashlib.sha256(LOCK_BYTES).hexdigest()
INTEGRITY = HERE.parent / "freetype/run_probe.py"
if hashlib.sha256(INTEGRITY.read_bytes()).hexdigest() != LOCK["sharedIntegrityHelperSha256"]:
    raise RuntimeError("Reviewed shared archive-verification helper changed; review and update its lock explicitly")
_spec = importlib.util.spec_from_file_location("icy_verified_archives", INTEGRITY)
verified = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verified)
digest, write_json = verified.digest, verified.write_json
VERSION = LOCK["version"]
LIBRARIES = ("libskresources.a", "libskparagraph.a", "libskia.a", "libicu.a", "libjsonreader.a", "libskottie.a",
             "libsvg.a", "libpng.a", "libwebp_sse41.a", "libsksg.a", "libskunicode_core.a", "libskunicode_icu.a",
             "libwebp.a", "libdng_sdk.a", "libpiex.a", "libharfbuzz.a", "libexpat.a", "libzlib.a", "libjpeg.a", "libskshaper.a")
SDK_SOURCE = "skiko/buildSrc/src/main/kotlin/tasks/configuration/NativeTasksConfiguration.kt"
SDK_OLD = '''fun Project.findXcodeSdkRoot(): String {
    val defaultPath = "/Applications/Xcode.app/Contents/Developer/Platforms"
    if (File(defaultPath).exists()) {
        return defaultPath.also {
            println("findXcodeSdkRoot = $it")
        }
    }

    return (project.property("skiko.ci.xcodehome") as? String)?.let {
        val sdkPath = it + "/Platforms"
        println("findXcodeSdkRoot = $sdkPath")
        sdkPath
    } ?: error("gradle property `skiko.ci.xcodehome` is not set")
}'''
SDK_NEW = '''fun Project.findXcodeSdkRoot(): String {
    // Icy experiment: use exactly the selected Xcode, even when Xcode.app exists.
    val selected = project.findProperty("skiko.ci.xcodehome")?.toString()
        ?: System.getenv("DEVELOPER_DIR")
        ?: error("Select Xcode with skiko.ci.xcodehome or DEVELOPER_DIR")
    val platforms = File(selected, "Platforms")
    require(platforms.isDirectory) { "Selected Xcode Platforms is missing: $platforms" }
    return platforms.absolutePath.also { println("findXcodeSdkRoot = $it") }
}'''
ARM64_OLD = '''    } else if (current_cpu == "arm64") {
      _arch_flags = [
        "-arch",
        "arm64",
        "-arch",
        "arm64e",
      ]'''
ARM64_NEW = '''    } else if (current_cpu == "arm64") {
      # Icy experiment publishes generic arm64, matching Kotlin's iOS targets.
      _arch_flags = [
        "-arch",
        "arm64",
      ]'''


def source_files(work):
    for root in (work / "skia", work / "skiko"):
        def walk(directory):
            # DirEntry retains Windows file attributes; repeated Path.is_file /
            # is_symlink queries otherwise make25k-file source audits needlessly slow.
            with os.scandir(directory) as iterator:
                entries = sorted(iterator, key=lambda entry: entry.name)
            for entry in entries:
                if entry.is_symlink():
                    raise ValueError(f"Unexpected source symlink after safe extraction: {entry.path}")
                if entry.is_dir(follow_symlinks=False):
                    if root.name == "skia" and Path(directory) == root and entry.name == "out":
                        continue
                    if root.name == "skiko" and entry.name in {"build", ".gradle", ".kotlin"}:
                        continue
                    yield from walk(entry.path)
                elif entry.is_file(follow_symlinks=False):
                    yield Path(entry.path)
                else:
                    raise ValueError(f"Unexpected source filesystem object: {entry.path}")
        yield from walk(root)


def snapshot(work):
    return {path.relative_to(work).as_posix(): digest(path) for path in source_files(work)}


def recipe():
    if digest(LOCK_PATH) != LOCK_SHA256:
        raise ValueError("Source lock changed while this driver was running")
    files = [HERE / "gn_args.py", *(HERE / "overlay").glob("*"), *(HERE / "consumer").rglob("*")]
    return {"sdkReplacementSha256": hashlib.sha256(SDK_NEW.encode()).hexdigest(),
            "arm64ReplacementSha256": hashlib.sha256(ARM64_NEW.encode()).hexdigest(),
            "files": {path.relative_to(HERE).as_posix(): digest(path) for path in sorted(files)
                      if path.is_file() and "__pycache__" not in path.parts},
            "producerDistribution": LOCK["producerGradleDistribution"],
            "consumerWrapperFiles": LOCK["consumerWrapperFiles"],
            "consumerWrapperTextNormalization": LOCK.get("consumerWrapperTextNormalization", {})}


def consumer_wrapper_bytes(name):
    data = (IOS / name).read_bytes()
    mode = LOCK.get("consumerWrapperTextNormalization", {}).get(name)
    if mode == "lf":
        data = data.replace(b"\r\n", b"\n")
        if b"\r" in data:
            raise ValueError("Wrapper text contains unsupported bare-CR line endings")
    elif mode is not None:
        raise ValueError("Unknown consumer wrapper text normalization")
    return data


def patch_producer(skiko):
    sdk = skiko / SDK_SOURCE
    old = sdk.read_text()
    if old.count(SDK_OLD) != 1:
        raise ValueError("Pinned Xcode selection patch no longer matches exactly")
    sdk.write_text(old.replace(SDK_OLD, SDK_NEW), encoding="utf-8", newline="\n")
    patches = [{"path": "skiko/" + SDK_SOURCE, "beforeSha256": hashlib.sha256(old.encode()).hexdigest(), "afterSha256": digest(sdk)}]
    wrapper = skiko / "skiko/gradle/wrapper/gradle-wrapper.properties"
    old = wrapper.read_text()
    url = LOCK["producerGradleDistribution"]["url"].replace(":", r"\:")
    if old.count("distributionUrl=" + url + "\n") != 1 or "distributionSha256Sum=" in old:
        raise ValueError("Pinned producer Gradle wrapper no longer matches exactly")
    wrapper.write_text(old + "distributionSha256Sum=" + LOCK["producerGradleDistribution"]["sha256"] + "\n", encoding="utf-8", newline="\n")
    patches.append({"path": "skiko/skiko/gradle/wrapper/gradle-wrapper.properties", "beforeSha256": hashlib.sha256(old.encode()).hexdigest(), "afterSha256": digest(wrapper)})
    for name, destination in (("FreeTypeTypeface.kt", "skiko/src/iosMain/kotlin/org/jetbrains/skia/FreeTypeTypeface.kt"),
                              ("FreeTypeTypeface.cc", "skiko/src/nativeJsMain/cpp/FreeTypeTypeface.cc")):
        target = skiko / destination
        if target.exists():
            raise ValueError("Additive factory would overwrite upstream source")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / "overlay" / name, target)
        patches.append({"path": "skiko/" + destination, "addedSha256": digest(target)})
    return patches


def patch_skia(skia):
    source = skia / "gn/skia/BUILD.gn"
    old = source.read_text()
    if old.count(ARM64_OLD) != 1:
        raise ValueError("Pinned generic-arm64-only patch no longer matches exactly")
    source.write_text(old.replace(ARM64_OLD, ARM64_NEW), encoding="utf-8", newline="\n")
    return {"path": "skia/gn/skia/BUILD.gn", "beforeSha256": hashlib.sha256(old.encode()).hexdigest(),
            "afterSha256": digest(source)}


def validate_prepared(work):
    record = json.loads((work / "prepared.json").read_text())
    if record["lockSha256"] != digest(LOCK_PATH):
        raise ValueError("Prepared sources use a different lock")
    if record["recipe"] != recipe():
        raise ValueError("Experiment recipe changed; prepare fresh sources")
    expected = json.loads((work / "source-files.json").read_text())
    if snapshot(work) != expected:
        raise ValueError("Prepared source content changed after the reviewed additive patch")
    if record["sourceFilesSha256"] != digest(work / "source-files.json"):
        raise ValueError("Prepared source manifest changed")
    for name, value in record["tools"].items():
        if digest(work / "tools" / name) != value:
            raise ValueError(f"Verified build tool changed: {name}")
    for item in LOCK["fonts"]:
        if digest(work / "fonts" / item["file"]) != item["sha256"]:
            raise ValueError("Original font changed")
    for name, expected_hash in record["consumerFiles"].items():
        if digest(work / "consumer" / name) != expected_hash:
            raise ValueError("Prepared consumer source or wrapper changed: " + name)
    for name, expected_hash in record["recipe"]["files"].items():
        if name.startswith("consumer/") and digest(work / name) != expected_hash:
            raise ValueError("Prepared consumer differs from the reviewed recipe: " + name)
        if name.startswith("overlay/"):
            subdirectory = "iosMain/kotlin/org/jetbrains/skia" if name.endswith(".kt") else "nativeJsMain/cpp"
            if digest(work / "skiko/skiko/src" / subdirectory / Path(name).name) != expected_hash:
                raise ValueError("Prepared factory differs from the reviewed recipe")
    return record


def collect_notices(work):
    root = work / "notices"
    root.mkdir()
    files = []
    for path in source_files(work):
        if not re.match(r"^(LICENSE|COPYING|NOTICE|PATENTS|COPYRIGHT|FTL|GPL)([._-].*)?$", path.name, re.I):
            continue
        relative = path.relative_to(work)
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        files.append({"path": relative.as_posix(), "sha256": digest(path), "bytes": path.stat().st_size})
    required = ["skiko/LICENSE", "skia/LICENSE", "skia/third_party/externals/freetype/LICENSE.TXT",
                "skia/third_party/externals/freetype/docs/FTL.TXT"]
    for name in required:
        if name not in {entry["path"] for entry in files}:
            raise ValueError(f"Required bundled-source license is missing: {name}")
    for name in ("apache-2.0.txt", "ofl-1.1.txt"):
        shutil.copyfile(IOS / "shared/ui/assets/legal" / name, root / name)
    for name in ("PROVENANCE.json", "FALLBACK-PROVENANCE.json"):
        shutil.copyfile(IOS / "shared/ui/assets/font" / name, root / name)
    shutil.copyfile(HERE / "NOTICE.md", root / "NOTICE.md")
    write_json(root / "manifest.json", {"scope": "Notices from the entire locked source trees; linked source closure is recorded separately",
                                       "files": files})


def prepare(work, downloads):
    started_recipe = recipe()
    work.mkdir(parents=True, exist_ok=False)
    (work / "tools").mkdir()
    (work / "fonts").mkdir()
    downloads.mkdir(parents=True, exist_ok=True)
    checked = []
    for entry in LOCK["archives"]:
        print("Verifying/extracting", entry["name"], flush=True)
        archive = verified.fetch(entry, downloads)
        checked.append({"name": entry["name"], **verified.verify_archive(entry, archive)})
        write_json(work / "verified-inputs.json", checked)
        if entry["kind"] == "tar":
            verified.extract_tar(archive, verified.contained(work, entry["destination"]), entry.get("stripPrefix", ""))
        else:
            verified.extract_tool(archive, entry["binary"], work / "tools" / entry["binary"])
    deps = (work / "skia/DEPS").read_text()
    for entry in LOCK["archives"]:
        if entry.get("depsPath"):
            pattern = r'[\'"]' + re.escape(entry["depsPath"]) + r'[\'"]\s*:\s*[\'"]' + re.escape(entry["repository"] + "@" + entry["revision"]) + r'[\'"]'
            if not re.search(pattern, deps):
                raise ValueError("External source does not match the pinned Skia DEPS entry: " + entry["name"])
    skiko = work / "skiko"
    patches = [patch_skia(work / "skia"), *patch_producer(skiko)]
    default = skiko / "skiko/src/commonMain/cpp/common/FontMgrDefaultFactory.cc"
    if "return SkFontMgr_New_CoreText(nullptr);" not in default.read_text():
        raise ValueError("Upstream CoreText default must remain unchanged")
    if 'kotlin = "2.2.20"' not in (skiko / "dependencies.toml").read_text():
        raise ValueError("Producer Kotlin pin changed")
    for font in LOCK["fonts"]:
        source = IOS / "shared/ui/assets/font" / font["file"]
        if digest(source) != font["sha256"] or source.stat().st_size != font["bytes"]:
            raise ValueError("Original Android font changed")
        shutil.copyfile(source, work / "fonts" / font["file"])
    shutil.copytree(HERE / "consumer", work / "consumer", ignore=shutil.ignore_patterns("__pycache__"))
    for name, expected_hash in LOCK["consumerWrapperFiles"].items():
        data = consumer_wrapper_bytes(name)
        if hashlib.sha256(data).hexdigest() != expected_hash:
            raise ValueError("Consumer wrapper changed; review its new checksum")
        destination = work / "consumer" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    collect_notices(work)
    write_json(work / "patches.json", patches)
    write_json(work / "source-files.json", snapshot(work))
    shutil.copyfile(LOCK_PATH, work / "sources.lock.json")
    if recipe() != started_recipe:
        raise ValueError("Recipe changed while preparing sources; use a fresh work directory")
    record = {"schemaVersion": 1, "status": "sources-prepared-native-package-pending", "version": VERSION,
              "lockSha256": digest(LOCK_PATH), "sourceFilesSha256": digest(work / "source-files.json"),
              "coreTextDefaultSourceSha256": digest(default), "patches": patches,
              "tools": {p.name: digest(p) for p in (work / "tools").iterdir()},
              "recipe": started_recipe,
              "consumerFiles": {p.relative_to(work / "consumer").as_posix(): digest(p)
                                for p in (work / "consumer").rglob("*") if p.is_file()},
              "experimentSources": {p.relative_to(HERE).as_posix(): digest(p) for p in HERE.rglob("*")
                                    if p.is_file() and "__pycache__" not in p.parts},
              "appDependenciesChanged": False}
    write_json(work / "prepared.json", record)
    return record


def command(argv, cwd, log, env=None):
    print("Running:", argv, flush=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(argv, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT, text=True)
    result_text = log.read_text(encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); {log}\n{result_text[-6000:]}")
    return result_text


def mac_environment(work):
    if sys.platform != "darwin" or platform.machine() != "arm64":
        raise ValueError("Native package builds require an arm64 Mac; Windows can prepare and inspect the GN graph")
    # Local BuildRepo publication needs no credentials. Do not pass workflow
    # tokens, signing values, user Gradle properties or unrelated app settings.
    allowed = {"HOME", "USER", "LOGNAME", "SHELL", "PATH", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "LC_CTYPE",
               "JAVA_HOME", "DEVELOPER_DIR", "CI"}
    env = {key: value for key, value in os.environ.items() if key in allowed}
    developer = env.setdefault("DEVELOPER_DIR", "/Applications/Xcode_26.4.1.app/Contents/Developer")
    version = command(["xcodebuild", "-version"], work, work / "xcode-version.log", env)
    if version.splitlines()[0] != "Xcode " + LOCK["xcodeVersion"]:
        raise ValueError("Select the pinned Xcode")
    paths = {}
    for sdk in ("iphoneos", "iphonesimulator"):
        paths[sdk] = command(["xcrun", "--sdk", sdk, "--show-sdk-path"], work, work / f"{sdk}-path.log", env).strip()
        if not Path(paths[sdk]).resolve().is_relative_to(Path(developer).resolve()):
            raise ValueError("SDK is not under the selected Xcode")
        command(["xcrun", "--sdk", sdk, "--show-sdk-version"], work, work / f"{sdk}-version.log", env)
    clang = command(["xcrun", "--find", "clang"], work, work / "clang-path.log", env).strip()
    clangxx = command(["xcrun", "--find", "clang++"], work, work / "clangxx-path.log", env).strip()
    if not Path(clangxx).resolve().is_relative_to(Path(developer).resolve()):
        raise ValueError("Compiler is not under selected Xcode")
    env["PATH"] = str(Path(clangxx).parent) + os.pathsep + env["PATH"]
    command([clangxx, "--version"], work, work / "clang-version.log", env)
    java = command(["java", "-version"], work, work / "java-version.log", env)
    if not re.search(r'version "17[.\"]', java):
        raise ValueError("Use JDK17 for the pinned producer Gradle")
    record = {"developerDirectory": developer, "xcodeVersion": version.strip(), "sdkPaths": paths,
              "clang": clang, "clangxx": clangxx, "clangSha256": digest(Path(clang)),
              "clangxxSha256": digest(Path(clangxx)), "java": java.strip()}
    write_json(work / "toolchain.json", record)
    return env, record


def gn_graph(work, target, gn, sdk_path, clang, clangxx, env=None):
    build = work / "skia/out" / LOCK["targets"][target]["directory"]
    build.mkdir(parents=True, exist_ok=True)
    (build / "args.gn").write_text(arguments(target, sdk_path, clang, clangxx), encoding="utf-8", newline="\n")
    log_dir = work / "reports" / target
    version = command([str(gn), "--version"], work, log_dir / "gn-version.log", env).strip()
    if version != "2175 (b2afae122eeb)":
        raise ValueError("Use the pinned GN revision2175 (b2afae122eeb)")
    command([str(gn), "gen", str(build), "--fail-on-unused-args"], work / "skia", log_dir / "gn.log", env)
    graph_text = command([str(gn), "desc", str(build), "*", "--format=json"], work / "skia", log_dir / "gn-graph.json", env)
    graph = json.loads(graph_text)
    roots = ["//:skia", "//:modules"]
    visited, files = set(), set()
    def visit(label):
        if label in visited:
            return
        visited.add(label)
        item = graph[label]
        for key in ("sources", "inputs", "public"):
            files.update(value for value in item.get(key, []) if value.startswith("//") and not value.startswith("//out/"))
        script = item.get("script")
        if script and script.startswith("//"):
            files.add(script)
        for key in ("deps", "public_deps", "data_deps"):
            for dependency in item.get(key, []):
                visit(dependency)
    for root in roots:
        visit(root)
    externals = {"/".join(file[2:].split("/")[:3]) for file in files if file.startswith("//third_party/externals/")}
    allowed = {entry["depsPath"] for entry in LOCK["archives"] if entry.get("depsPath")}
    if not externals <= allowed:
        raise ValueError(f"GN needs an unlocked source dependency: {externals - allowed}")
    missing = [file for file in files if not (work / "skia" / file[2:]).is_file()]
    if missing:
        raise ValueError(f"GN references missing verified sources: {missing[:20]}")
    targets = sorted(visited)
    for required in ("//:typeface_freetype", "//:fontmgr_mac_ct", "//modules/skparagraph:skparagraph", "//modules/svg:svg", "//modules/skottie:skottie"):
        if required not in visited:
            raise ValueError("Full production module/font backend is missing: " + required)
    icu = graph["//third_party/icu:icu"]
    expected_minimum = "-miphonesimulator-version-min=12.0" if target == "iosSim" else "-miphoneos-version-min=12.0"
    if expected_minimum not in icu.get("asmflags", []):
        raise ValueError("ICU generated data assembler must use the explicit iOS deployment minimum")
    for label in visited:
        for flag_kind in ("cflags", "asmflags", "ldflags"):
            if "arm64e" in graph[label].get(flag_kind, []):
                raise ValueError("Experiment must build only generic arm64: " + label)
    record = {"target": target, "argsSha256": digest(build / "args.gn"), "targetCount": len(targets),
              "sourceCount": len(files), "externals": sorted(externals), "targets": targets,
              "sources": [{"path": file[2:], "sha256": digest(work / "skia" / file[2:])} for file in sorted(files)],
              "graphTool": {"version": version, "sha256": digest(gn), "host": sys.platform},
              "icuAssemblerFlags": icu["asmflags"],
              "scope": "GN graph and verified source closure only; no native compilation claim"}
    write_json(log_dir / "source-closure.json", record)
    return build, record


def deterministic_zip(destination, paths):
    """Stable member metadata; artifact byte hashes still identify each real build."""
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, source in sorted(paths.items()):
            verified.contained(Path("/archive"), name)
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, source.read_bytes())


def inspect_libraries(work, target, directory, env):
    from native_archive import inspect_archive
    libraries = {}
    for name in LIBRARIES:
        library = directory / name
        libraries[name] = inspect_archive(library, expected_platform=LOCK["targets"][target]["platform"],
                                           allow_empty=name == "libwebp_sse41.a")
    symbols = command(["xcrun", "nm", "-gU", str(directory / "libskia.a")], work,
                      work / "reports" / target / "libskia-defined-symbols.log", env)
    for required in ("FT_Init_FreeType", "SkFontMgr_New_CoreText", "SkFontMgr_New_Custom_Data"):
        if required not in symbols:
            raise ValueError("Built libskia lacks required font backend symbol: " + required)
    return libraries


def build_skia(work, target, jobs, env, toolchain):
    validate_prepared(work)
    sdk = toolchain["sdkPaths"]["iphonesimulator" if target == "iosSim" else "iphoneos"]
    build, graph = gn_graph(work, target, work / "tools/gn", sdk, toolchain["clang"], toolchain["clangxx"], env)
    command([str(work / "tools/ninja"), "-C", str(build), "-j", str(jobs), "skia", "modules"], work / "skia",
            work / "reports" / target / "ninja.log", env)
    validate_prepared(work)
    libraries = inspect_libraries(work, target, build, env)
    record = {"schemaVersion": 1, "target": target, "version": VERSION, "lockSha256": digest(LOCK_PATH),
              "sourceFilesSha256": digest(work / "source-files.json"), "argsSha256": digest(build / "args.gn"),
              "sourceClosureSha256": digest(work / "reports" / target / "source-closure.json"),
              "toolchain": toolchain, "libraries": libraries, "graphSourceCount": graph["sourceCount"],
              "scope": "Full Skia native library build; managed Skiko package and simulator execution not yet implied"}
    report = work / "reports" / target / "native-libraries.json"
    write_json(report, record)
    products = work / "products"
    products.mkdir(exist_ok=True)
    archive = products / ("skia-" + target + "-arm64-" + VERSION + ".zip")
    if archive.exists():
        raise ValueError("Refusing to overwrite an existing native build artifact; use a fresh work directory")
    payload = {"out/" + LOCK["targets"][target]["directory"] + "/" + name: build / name for name in LIBRARIES}
    payload["native-libraries.json"] = report
    payload["args.gn"] = build / "args.gn"
    payload["source-closure.json"] = work / "reports" / target / "source-closure.json"
    payload.update({"notices/" + path.relative_to(work / "notices").as_posix(): path
                    for path in (work / "notices").rglob("*") if path.is_file()})
    deterministic_zip(archive, payload)
    write_json(archive.with_suffix(".json"), {"sha256": digest(archive), "bytes": archive.stat().st_size,
                                           "target": target, "lockSha256": digest(LOCK_PATH)})
    return record


def import_skia_archive(work, archive):
    from native_archive import inspect_archive
    transfer = json.loads(archive.with_suffix(".json").read_text())
    if transfer["sha256"] != digest(archive) or transfer["bytes"] != archive.stat().st_size or transfer["lockSha256"] != digest(LOCK_PATH):
        raise ValueError("Native transfer ZIP does not match its producing job's checksum/size/source record")
    with zipfile.ZipFile(archive) as bundle:
        record = json.loads(bundle.read("native-libraries.json"))
        target = record["target"]
        if transfer["target"] != target:
            raise ValueError("Native transfer target differs from its producing job's record")
        if target not in LOCK["targets"] or record["lockSha256"] != digest(LOCK_PATH) or record["version"] != VERSION:
            raise ValueError("Native artifact belongs to a different source lock, target or version")
        if record["sourceFilesSha256"] != digest(work / "source-files.json"):
            raise ValueError("Native artifact source/patch fingerprint differs")
        prefix = "out/" + LOCK["targets"][target]["directory"] + "/"
        notices = {"notices/" + path.relative_to(work / "notices").as_posix(): path
                   for path in (work / "notices").rglob("*") if path.is_file()}
        if not notices or not (work / "notices/NOTICE.md").is_file():
            raise ValueError("Prepare the complete source notice bundle before importing native artifacts")
        expected = {prefix + name for name in LIBRARIES} | {"native-libraries.json", "args.gn", "source-closure.json"} | set(notices)
        if len(bundle.namelist()) != len(expected) or set(bundle.namelist()) != expected:
            raise ValueError("Unexpected native artifact files")
        for name, source in notices.items():
            if hashlib.sha256(bundle.read(name)).hexdigest() != digest(source):
                raise ValueError("Native artifact source notice differs from reviewed sources: " + name)
        if hashlib.sha256(bundle.read("args.gn")).hexdigest() != record["argsSha256"]:
            raise ValueError("Native build arguments changed")
        if hashlib.sha256(bundle.read("source-closure.json")).hexdigest() != record["sourceClosureSha256"]:
            raise ValueError("Native source closure changed")
        destination = work / "skia/out" / LOCK["targets"][target]["directory"]
        destination.mkdir(parents=True, exist_ok=False)
        for name in LIBRARIES:
            data = bundle.read(prefix + name)
            # Native inventory exposes the exact archive SHA under sha256.
            expected_hash = record["libraries"][name]["sha256"]
            if hashlib.sha256(data).hexdigest() != expected_hash:
                raise ValueError("Native library checksum differs: " + name)
            path = destination / name
            path.write_bytes(data)
            inspect_archive(path, expected_platform=LOCK["targets"][target]["platform"], allow_empty=name == "libwebp_sse41.a")
        (destination / "args.gn").write_bytes(bundle.read("args.gn"))
        report_dir = work / "reports" / target
        report_dir.mkdir(parents=True, exist_ok=True)
        write_json(report_dir / "native-libraries.json", record)
        (report_dir / "source-closure.json").write_bytes(bundle.read("source-closure.json"))
        write_json(report_dir / "import.json", {"archiveSha256": digest(archive), "bytes": archive.stat().st_size,
                                               "source": str(archive), "target": target})
    return target


def producer_properties(work, toolchain):
    return ["-Pskiko.awt.enabled=false", "-Pskiko.wasm.enabled=false", "-Pskiko.android.enabled=false",
            "-Pskiko.native.enabled=false", "-Pskiko.native.ios.enabled=false",
            "-Pskiko.native.ios.arm64.enabled=true", "-Pskiko.native.ios.simulatorArm64.enabled=true",
            "-Pskiko.native.ios.x64.enabled=false", "-Pskiko.native.tvos.enabled=false", "-Pskiko.native.mac.enabled=false",
            "-Pskiko.native.linux.enabled=false", "-Pskia.dir=" + str(work / "skia"),
            "-Pskiko.ci.xcodehome=" + toolchain["developerDirectory"], "-Pdeploy.version=" + VERSION, "-Pdeploy.release=true"]


def resolved_gradle_inputs(home):
    root = home / "caches/modules-2/files-2.1"
    return [{"path": path.relative_to(root).as_posix(), "sha256": digest(path), "bytes": path.stat().st_size}
            for path in sorted(root.rglob("*")) if path.is_file()]


def validate_maven_repository(work, repository):
    from native_archive import inspect_archive
    group = repository / "org/jetbrains/skiko"
    expected_modules = {"skiko", "skiko-iosarm64", "skiko-iossimulatorarm64"}
    actual_modules = {path.name for path in group.iterdir() if path.is_dir()}
    if actual_modules != expected_modules:
        raise ValueError(f"Unexpected Maven publications: {actual_modules}")
    artifacts = []
    inspected = []
    for module in sorted(expected_modules):
        path = group / module / VERSION
        if not path.is_dir():
            raise ValueError("Missing experimental Maven version: " + module)
        for file in sorted(path.iterdir()):
            if not file.is_file():
                raise ValueError("Unexpected directory in Maven version")
            artifacts.append({"path": file.relative_to(repository).as_posix(), "sha256": digest(file), "bytes": file.stat().st_size})
        if module == "skiko":
            continue
        target = "iosSim" if module.endswith("iossimulatorarm64") else "ios"
        klib = path / f"{module}-{VERSION}.klib"
        interop = path / f"{module}-{VERSION}-cinterop-uikit.klib"
        if not klib.is_file() or not interop.is_file():
            raise ValueError("The native and cinterop-uikit KLIBs must both be published")
        with zipfile.ZipFile(klib) as bundle:
            manifests = [name for name in bundle.namelist() if name.endswith("/manifest") or name == "manifest"]
            text = "\n".join(bundle.read(name).decode() for name in manifests)
            if "native_targets=" + LOCK["targets"][target]["nativeTarget"] not in text:
                raise ValueError("KLIB target metadata differs from its artifact coordinate")
            members = [name for name in bundle.namelist() if name.endswith(".a")]
            if len(members) != len(LIBRARIES) + 1:
                raise ValueError(f"Unexpected native include-binary closure in {module}: {members}")
            scratch = work / "reports/klib-native" / target
            scratch.mkdir(parents=True, exist_ok=False)
            names = set()
            for member in members:
                filename = Path(member).name
                if filename in names:
                    raise ValueError("Duplicate native archive basename inside KLIB")
                names.add(filename)
                destination = scratch / filename
                destination.write_bytes(bundle.read(member))
                details = inspect_archive(destination, expected_platform=LOCK["targets"][target]["platform"],
                                          allow_empty=filename == "libwebp_sse41.a")
                inspected.append({"module": module, "member": member, **details})
                source = ((work / "skia/out" / LOCK["targets"][target]["directory"] / filename)
                          if filename in LIBRARIES else
                          work / "skiko/skiko/build/nativeBridges/static" / (target + "-arm64") / filename)
                if not source.is_file() or digest(source) != details["sha256"]:
                    raise ValueError("KLIB includes a different native archive than the inspected build: " + filename)
            if not set(LIBRARIES) <= names or not any(name.startswith("skiko-native-bridges-") for name in names):
                raise ValueError("KLIB native archive set differs from the reviewed bridge configuration")
    return {"version": VERSION, "artifacts": artifacts, "nativeArchives": inspected,
            "scope": "Local Maven artifact byte/architecture validation; consumer execution is a separate stage"}


def package_skiko(work, env, toolchain):
    validate_prepared(work)
    for target in LOCK["targets"]:
        report = json.loads((work / "reports" / target / "native-libraries.json").read_text())
        if report["lockSha256"] != digest(LOCK_PATH) or report["toolchain"]["xcodeVersion"] != toolchain["xcodeVersion"]:
            raise ValueError("Native inputs use a different source lock or Xcode")
        directory = work / "skia/out" / LOCK["targets"][target]["directory"]
        current = inspect_libraries(work, target, directory, env)
        if {name: item["sha256"] for name, item in current.items()} != {name: item["sha256"] for name, item in report["libraries"].items()}:
            raise ValueError("Native libraries changed after their validated build")
    project = work / "skiko/skiko"
    home = work / "gradle-user-home"
    tasks = ["publishKotlinMultiplatformPublicationToBuildRepoRepository", "publishIosArm64PublicationToBuildRepoRepository",
             "publishIosSimulatorArm64PublicationToBuildRepoRepository"]
    command(["bash", "gradlew", "--no-daemon", "--gradle-user-home", str(home), "--max-workers=4", "--stacktrace",
             *tasks, *producer_properties(work, toolchain)], project, work / "reports/producer-gradle.log", env)
    write_json(work / "reports/producer-resolved-inputs.json", resolved_gradle_inputs(home))
    repository = project / "build/repo"
    evidence = validate_maven_repository(work, repository)
    for target in LOCK["targets"]:
        bridge = project / f"build/nativeBridges/static/{target}-arm64/skiko-native-bridges-{target}-arm64.a"
        symbols = command(["xcrun", "nm", "-gU", str(bridge)], work, work / "reports" / target / "bridge-symbols.log", env)
        if "org_jetbrains_skia_FreeTypeTypeface__1nMakeFromData" not in symbols:
            raise ValueError("Published bridge does not define the new managed factory implementation")
    validate_prepared(work)
    write_json(work / "reports/package-validation.json", evidence)
    products = work / "products"
    products.mkdir(exist_ok=True)
    output = products / ("skiko-" + VERSION + "-local-maven.zip")
    paths = {"maven/" + path.relative_to(repository).as_posix(): path for path in repository.rglob("*") if path.is_file()}
    paths.update({"notices/" + path.relative_to(work / "notices").as_posix(): path for path in (work / "notices").rglob("*") if path.is_file()})
    for name in ("sources.lock.json", "patches.json", "prepared.json", "toolchain.json", "verified-inputs.json"):
        paths[name] = work / name
    for name in ("package-validation.json", "producer-resolved-inputs.json"):
        paths["reports/" + name] = work / "reports" / name
    deterministic_zip(output, paths)
    write_json(output.with_suffix(".json"), {"sha256": digest(output), "bytes": output.stat().st_size, "version": VERSION,
                                            "lockSha256": digest(LOCK_PATH), "consumerExecutionVerified": False,
                                            "appDependenciesChanged": False})
    return evidence


def validate_consumer_results(work):
    output = work / "reports/consumer-output"
    tests = []
    reports = sorted((work / "consumer/build/test-results/iosSimulatorArm64Test").glob("TEST-*.xml"))
    for report in reports:
        suite = ET.parse(report).getroot()
        if any(int(suite.get(name, "0")) for name in ("failures", "errors", "skipped")):
            raise ValueError("Native consumer suite reported failures, errors or skipped cases")
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None:
                raise ValueError("Native consumer test failed or was skipped: " + case.get("name", "?"))
            # Kotlin2.4 native JUnit XML appends the target to each method name.
            # Only that exact suffix is accepted, not arbitrary parameter names.
            tests.append(case.get("name", "").removesuffix("[iosSimulatorArm64]"))
    expected_tests = {
        "invalidDataClosedDataAndCollectionIndicesHaveDefinedBehavior",
        "returnedFaceSurvivesDataCloseAndVariationCloneWhileCoreTextDefaultStillWorks",
        "originalColrv1AndCbdtGlyphsRenderColoredPixelsAfterInputDataCloses",
        "originalFlagSequencesShapeThroughTheFullSkParagraphAndHarfBuzzPackage",
    }
    if len(tests) != 4 or set(tests) != expected_tests:
        raise ValueError("Expected all four native consumer tests to execute exactly once")
    ids = {"colrv1-snowflake", "colrv1-musical-note", "colrv1-heart", "cbdt-canada", "cbdt-us", "paragraph-canada", "paragraph-us"}
    if {p.stem for p in output.glob("*.png")} != ids:
        raise ValueError("Missing or unexpected consumer color-font renders")
    images = {}
    for name in sorted(ids):
        png = output / (name + ".png")
        width, height, pixels = verified.png_pixels(png)
        image = verified.image_evidence(png)
        if (width, height) != (256, 256) or image["chromaPixels"] <= 0:
            raise ValueError("Original color font did not render colored pixels: " + name)
        raw = output / (name + ".rgba")
        if raw.read_bytes() != pixels:
            raise ValueError("Exported native PNG differs from its raw RGBA pixels: " + name)
        metadata = json.loads((output / (name + ".json")).read_text())
        if metadata["id"] != name or metadata["chromaPixels"] != image["chromaPixels"]:
            raise ValueError("Native consumer metadata disagrees with observed pixels")
        if metadata["metalVerified"] is not False or metadata["androidPixelParityVerified"] is not False:
            raise ValueError("Raster probe must not imply Metal execution or Android pixel parity")
        images[name] = {**image, "rgbaSha256": digest(raw)}
    metrics = json.loads((output / "font-metrics.json").read_text(),
                         parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    for name in ("normalGlyphAdvanceSum", "boldGlyphAdvanceSum"):
        if not isinstance(metrics[name], (int, float)) or not 0 < metrics[name] < 100000:
            raise ValueError("Invalid native font metric")
    return {"status": "native-consumer-four-tests-passed", "tests": sorted(tests), "images": images,
            "fontMetrics": metrics, "fontMetricsSha256": digest(output / "font-metrics.json"),
            "metalExecutionVerified": False, "androidPixelParityVerified": False, "appDependenciesChanged": False}


def test_consumer(work, simulator, env):
    validate_prepared(work)
    if not re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", simulator):
        raise ValueError("Pass the actual booted iPhone simulator UUID")
    devices = json.loads(command(["xcrun", "simctl", "list", "devices", "booted", "--json"], work,
                                 work / "reports/consumer-simulators.json", env))["devices"]
    matches = [device for group in devices.values() for device in group
               if device["udid"].lower() == simulator.lower() and device["state"] == "Booted"]
    if len(matches) != 1 or not matches[0]["name"].startswith("iPhone"):
        raise ValueError("Selected simulator must be an already booted iPhone")
    repository = work / "skiko/skiko/build/repo"
    package_record = json.loads((work / "reports/package-validation.json").read_text())
    for artifact in package_record["artifacts"]:
        if digest(verified.contained(repository, artifact["path"])) != artifact["sha256"]:
            raise ValueError("Published package changed before consumer execution")
    output = work / "reports/consumer-output"
    output.mkdir(exist_ok=False)
    home = work / "consumer-gradle-user-home"
    command(["bash", "gradlew", "--no-daemon", "--gradle-user-home", str(home), "--max-workers=4", "--stacktrace",
             "iosSimulatorArm64Test", "-Picy.skikoRepo=" + str(repository), "-Picy.simulator=" + simulator,
             "-Picy.fontRoot=" + str(work / "fonts"), "-Picy.outputRoot=" + str(output)], work / "consumer",
             work / "reports/consumer-gradle.log", env)
    evidence = validate_consumer_results(work)
    evidence["simulator"] = matches[0]
    evidence["packageValidationSha256"] = digest(work / "reports/package-validation.json")
    write_json(work / "reports/consumer-resolved-inputs.json", resolved_gradle_inputs(home))
    validate_prepared(work)
    write_json(work / "reports/consumer-validation.json", evidence)
    return evidence


def execute(args, work):
    if args.stage in ("prepare", "all"):
        prepare(work, args.downloads.resolve())
    else:
        validate_prepared(work)
    if args.stage == "prepare":
        print("Prepared", work)
        return
    for archive in args.import_skia:
        import_skia_archive(work, archive.resolve())
    targets = ("ios", "iosSim") if args.target == "both" else (args.target,)
    if args.stage == "graph":
        gn = (args.graph_gn or work / "tools/gn").resolve()
        for target in targets:
            gn_graph(work, target, gn, "/GRAPH-ONLY-NO-APPLE-SDK", "clang", "clang++")
        return
    env, toolchain = mac_environment(work)
    if args.stage in ("skia", "all"):
        for target in targets:
            build_skia(work, target, args.jobs, env, toolchain)
    if args.stage in ("package", "all"):
        package_skiko(work, env, toolchain)
    if args.stage == "consumer" or (args.stage == "all" and args.simulator):
        test_consumer(work, args.simulator, env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("prepare", "graph", "skia", "package", "consumer", "all"), required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--downloads", type=Path, default=IOS / "build/skiko-freetype/downloads")
    parser.add_argument("--target", choices=("ios", "iosSim", "both"), default="both")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--graph-gn", type=Path, help="Explicit locally verified GN for a graph-only host check")
    parser.add_argument("--import-skia", type=Path, action="append", default=[], help="Verified native output ZIP from a separate target job")
    parser.add_argument("--simulator", help="Already booted iPhone simulator UUID; required for consumer stage")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error("--jobs must be1..8")
    if args.stage == "consumer" and not args.simulator:
        parser.error("consumer stage requires --simulator UUID")
    work = args.work_dir.resolve()
    work.relative_to((IOS / "build").resolve())
    if work == (IOS / "build").resolve():
        parser.error("Use an experiment subdirectory under iOS/build")
    record = {"schemaVersion": 1, "id": str(uuid.uuid4()), "stage": args.stage, "target": args.target,
              "startedUtc": dt.datetime.now(dt.timezone.utc).isoformat(), "lockSha256": digest(LOCK_PATH),
              "host": {"platform": sys.platform, "architecture": platform.machine()},
              "driverSha256": digest(Path(__file__)), "status": "running", "appDependenciesChanged": False}
    try:
        execute(args, work)
        record["status"] = "stage-passed"
    except Exception as error:
        record["status"] = "stage-failed"
        record["error"] = str(error)
        raise
    finally:
        record["finishedUtc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        # Preserve failed preparations for diagnosis; never create an app artifact
        # or a simulator/IPA verification marker from this experiment.
        if work.is_dir():
            (work / "runs").mkdir(exist_ok=True)
            write_json(work / "runs" / (record["id"] + ".json"), record)


if __name__ == "__main__":
    main()
