#!/usr/bin/env python3
"""Opt-in, hash-pinned standalone Skia/FreeType experiment. Python 3.10+, stdlib only."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import uuid
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
IOS = HERE.parents[1]
COLOR_IDS = {"colrv1-snowflake", "colrv1-musical-note", "colrv1-heart", "cbdt-canada", "cbdt-us"}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def contained(root: Path, relative: str) -> Path:
    name = PurePosixPath(relative)
    if not relative or name.is_absolute() or ".." in name.parts or "\\" in relative or ":" in relative:
        raise ValueError(f"Unsafe relative path: {relative!r}")
    path = root.joinpath(*name.parts)
    path.resolve().relative_to(root.resolve())
    return path


def fetch(entry: dict, downloads: Path) -> Path:
    path = downloads / (entry["sha256"] + "-" + entry["name"])
    if not path.exists():
        partial = path.with_suffix(path.suffix + ".partial-" + uuid.uuid4().hex)
        with urllib.request.urlopen(entry["url"], timeout=120) as source, partial.open("xb") as output:
            shutil.copyfileobj(source, output)
        if digest(partial) != entry["sha256"] or partial.stat().st_size != entry["bytes"]:
            raise ValueError(f"Downloaded bytes differ from locked archive: {entry['name']}")
        partial.replace(path)
    if digest(path) != entry["sha256"] or path.stat().st_size != entry["bytes"]:
        raise ValueError(f"Cached archive differs from lock: {path}")
    return path


def extract_tar(archive: Path, destination: Path, prefix: str = "") -> None:
    """No extractall: reject traversal/devices and materialize only contained file symlinks."""
    links = []
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle:
            name = member.name
            if prefix:
                if name.rstrip("/") == prefix.rstrip("/"):
                    continue
                if not name.startswith(prefix + "/"):
                    raise ValueError(f"Unexpected archive root: {name}")
                name = name[len(prefix) + 1:]
            target = contained(destination, name)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)
            elif member.issym():
                link = member.linkname
                if PurePosixPath(link).is_absolute() or "\\" in link or ":" in link:
                    raise ValueError(f"Unsafe symlink: {name}")
                source = (target.parent / link).resolve()
                source.relative_to(destination.resolve())
                links.append((source, target))
            else:
                raise ValueError(f"Unsupported archive member: {name}")
    for source, target in links:
        if not source.is_file():
            raise ValueError(f"Symlink target is not a contained regular file: {source}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def extract_tool(archive: Path, binary: str, destination: Path) -> None:
    with zipfile.ZipFile(archive) as bundle:
        with bundle.open(binary) as source, destination.open("xb") as output:
            shutil.copyfileobj(source, output)
    destination.chmod(0o755)


def png_pixels(path: Path) -> tuple[int, int, bytes]:
    """Decode the experiment's noninterlaced 8-bit RGB/RGBA PNGs without Pillow."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not a PNG")
    offset, compressed, ihdr = 8, bytearray(), None
    while offset < len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        crc = data[offset + 8 + size:offset + 12 + size]
        if len(crc) != 4 or struct.unpack(">I", crc)[0] != zlib.crc32(kind + payload):
            raise ValueError("Invalid PNG chunk checksum")
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", payload)
        if kind == b"IDAT":
            compressed.extend(payload)
        offset += size + 12
        if kind == b"IEND":
            break
    if not ihdr or ihdr[2:] not in {(8, 6, 0, 0, 0), (8, 2, 0, 0, 0)}:
        raise ValueError("Expected 8-bit noninterlaced RGB/RGBA output")
    width, height = ihdr[:2]
    if width not in (256, 1400) or height != 256:
        raise ValueError("Unexpected native probe dimensions")
    bpp = 4 if ihdr[3] == 6 else 3
    stride = width * bpp
    raw = zlib.decompress(compressed)
    if len(raw) != height * (stride + 1):
        raise ValueError("Unexpected PNG scanline length")
    pixels, previous = bytearray(), bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        mode, row = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        if mode > 4:
            raise ValueError("Unsupported PNG filter")
        for x in range(stride):
            a, b, c = row[x - bpp] if x >= bpp else 0, previous[x], previous[x - bpp] if x >= bpp else 0
            if mode == 0:
                predictor = 0
            elif mode == 1:
                predictor = a
            elif mode == 2:
                predictor = b
            elif mode == 3:
                predictor = (a + b) // 2
            else:
                p = a + b - c
                distances = abs(p - a), abs(p - b), abs(p - c)
                predictor = (a, b, c)[distances.index(min(distances))]
            row[x] = (row[x] + predictor) & 255
        if bpp == 4:
            pixels.extend(row)
        else:
            for x in range(0, stride, 3):
                pixels.extend(row[x:x + 3] + b"\xff")
        previous = row
    return width, height, bytes(pixels)


def image_evidence(path: Path) -> dict:
    width, height, pixels = png_pixels(path)
    ink, chroma, xs, ys = 0, 0, [], []
    for index in range(width * height):
        r, g, b, a = pixels[index * 4:index * 4 + 4]
        if a:
            ink += 1
            xs.append(index % width)
            ys.append(index // width)
        if a > 16 and max(r, g, b) - min(r, g, b) > 8:
            chroma += 1
    return {"sha256": digest(path), "bytes": path.stat().st_size, "width": width, "height": height,
            "nonTransparentPixels": ink, "chromaPixels": chroma,
            "inkBounds": [min(xs), min(ys), max(xs) + 1, max(ys) + 1] if xs else None}


def validate_results(output: Path) -> dict:
    metrics = json.loads((output / "metrics.json").read_text(),
                         parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    seen, images = set(), {}
    samples = metrics["samples"]
    expected = COLOR_IDS | {f"roboto-{weight}-{size:.1f}-linear-{linear}"
                           for weight in (400, 700) for size in (73.0, 73.5, 84.0) for linear in (0, 1)}
    for sample in samples:
        key = sample["backend"], sample["id"]
        if key in seen or key[0] not in {"freetype", "coretext"} or key[1] not in expected:
            raise ValueError(f"Duplicate/unexpected sample: {key}")
        seen.add(key)
        required = key[0] == "freetype" or key[1].startswith("roboto-")
        if "png" not in sample:
            if required:
                raise ValueError(f"Required font/glyph not rendered: {key}")
            continue
        if sample["png"] != f"{key[0]}-{key[1]}.png":
            raise ValueError(f"Unexpected sample filename: {key}")
        evidence = image_evidence(contained(output, sample["png"]))
        images["/".join(key)] = evidence
        if evidence["width"] != (1400 if key[1].startswith("roboto-") else 256):
            raise ValueError(f"Unexpected sample width: {key}")
        advance = sample["unshapedGlyphAdvanceSumPx"]
        if not isinstance(advance, (float, int)) or not 0 < advance < 1400:
            raise ValueError(f"Invalid measured advance: {key}")
        if not all(math.isfinite(sample["fontMetrics"][metric]) for metric in ("ascent", "descent", "leading")):
            raise ValueError(f"Non-finite font metrics: {key}")
        if required and not evidence["nonTransparentPixels"]:
            raise ValueError(f"Blank required glyph pixels: {key}")
        if key[0] == "freetype" and key[1] in COLOR_IDS and not evidence["chromaPixels"]:
            raise ValueError(f"Color font did not produce colored pixels: {key}")
        if required and evidence["inkBounds"]:
            left, top, right, bottom = evidence["inkBounds"]
            if left <= 0 or top <= 0 or right >= evidence["width"] or bottom >= evidence["height"]:
                raise ValueError(f"Required glyph is clipped by probe canvas: {key}")
    if seen != {(backend, case) for backend in ("freetype", "coretext") for case in expected}:
        raise ValueError("Incomplete backend/sample matrix")
    return {"schemaVersion": 1, "status": "standalone-macos-freetype-glyph-probe-passed",
            "verificationScope": "Exact bundled font glyph rendering and raw metrics on macOS CPU only",
            "iosRuntimeVerified": False, "composeShapingVerified": False, "androidPixelParityVerified": False,
            "samples": len(seen), "images": images}


def run_command(command: list[str], cwd: Path, log: Path) -> str:
    print("Running:", command, flush=True)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, text=True)
    text = log.read_text(encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); {log}\n{text[-6000:]}")
    return text


def prepare(work: Path, lock: dict) -> dict:
    downloads = IOS / "build/freetype-probe/downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    sources, tools_dir, fonts = work / "skia", work / "tools", work / "fonts"
    tools_dir.mkdir()
    fonts.mkdir()
    for entry in lock["archives"]:
        archive = fetch(entry, downloads)
        if entry["kind"] == "tar":
            extract_tar(archive, contained(work, entry["destination"]), entry.get("stripPrefix", ""))
        else:
            extract_tool(archive, entry["binary"], tools_dir / entry["binary"])
    deps = (sources / "DEPS").read_text()
    for entry in lock["archives"]:
        if entry.get("depsPath") and f'{entry["repository"]}@{entry["revision"]}' not in deps:
            raise ValueError(f"Dependency does not match pinned Skia DEPS: {entry['name']}")
    for entry in lock["sourceChecks"]:
        if digest(contained(sources, entry["path"])) != entry["sha256"]:
            raise ValueError(f"Pinned build configuration changed: {entry['path']}")
    for entry in lock["fonts"]:
        source = IOS / "shared/ui/assets/font" / entry["file"]
        if digest(source) != entry["sha256"]:
            raise ValueError(f"Original Android font hash differs: {source}")
        shutil.copyfile(source, fonts / entry["file"])
    overlay = sources / "icy_freetype_probe"
    overlay.mkdir()
    for name in ("font_probe.cpp", "BUILD.gn"):
        shutil.copyfile(HERE / name, overlay / name)
    with (sources / "BUILD.gn").open("a", encoding="utf-8") as root_build:
        root_build.write('\n# Isolated Icy Lyrics experiment; no library source changes.\n'
                         'group("icy_freetype_probe_build") { deps = [ "//icy_freetype_probe" ] }\n')
    build = sources / "out/icy-freetype"
    build.mkdir(parents=True)
    shutil.copyfile(HERE / "args.gn", build / "args.gn")
    notices = work / "notices"
    notices.mkdir()
    for index, relative in enumerate(lock["noticeFiles"]):
        shutil.copyfile(contained(sources, relative), notices / (str(index) + "-" + Path(relative).name))
    for name in ("LICENSES.md", "README.md"):
        shutil.copyfile(HERE / name, notices / name)
    for name in ("apache-2.0.txt", "ofl-1.1.txt"):
        shutil.copyfile(IOS / "shared/ui/assets/legal" / name, notices / name)
    for name in ("PROVENANCE.json", "FALLBACK-PROVENANCE.json"):
        shutil.copyfile(IOS / "shared/ui/assets/font" / name, notices / name)
    shutil.copyfile(HERE / "sources.lock.json", work / "sources.lock.json")
    return {"skia": str(sources), "build": str(build), "tools": str(tools_dir), "fonts": str(fonts),
            "sources": {p.name: digest(p) for p in HERE.iterdir() if p.is_file()}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run", action="store_true")
    mode.add_argument("--prepare-only", action="store_true", help="Download/hash/extract only; valid on Windows")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--work-dir", type=Path, help="New output directory under iOS/build; must not exist")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error("--jobs must be 1..8")
    lock = json.loads((HERE / "sources.lock.json").read_text())
    if args.run and (sys.platform != "darwin" or platform.machine() != "arm64"):
        parser.error("Native execution requires an arm64 Mac; Windows can use --prepare-only")
    work = (args.work_dir or (IOS / "build/freetype-probe" /
            (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]))).resolve()
    work.relative_to((IOS / "build").resolve())
    if work == (IOS / "build").resolve():
        parser.error("Use a new directory below iOS/build")
    work.mkdir(parents=True, exist_ok=False)
    state = {"schemaVersion": 1, "status": "preparing", "verificationScope": "Standalone macOS experiment",
             "lockSha256": digest(HERE / "sources.lock.json"), "platform": platform.platform(),
             "pythonVersion": sys.version,
             "skiaRevision": lock["skiaRevision"], "skikoReferenceRevision": lock["skikoReferenceRevision"]}
    try:
        if args.run:
            version = run_command(["xcodebuild", "-version"], work, work / "xcode-version.log")
            if version.splitlines()[0] != "Xcode " + lock["xcodeVersion"]:
                raise ValueError("Select the pinned Xcode " + lock["xcodeVersion"] + " before running")
            run_command(["xcrun", "--sdk", "macosx", "clang++", "--version"], work, work / "clang-version.log")
            run_command(["xcrun", "--sdk", "macosx", "--show-sdk-version"], work, work / "sdk-version.log")
        state.update(prepare(work, lock))
        state["status"] = "sources-prepared-native-execution-pending"
        if args.prepare_only:
            return 0
        sources, build, bin_dir = Path(state["skia"]), Path(state["build"]), Path(state["tools"])
        run_command([str(bin_dir / "gn"), "gen", str(build), "--fail-on-unused-args"], sources, work / "gn.log")
        run_command([str(bin_dir / "gn"), "desc", str(build), "//icy_freetype_probe", "deps", "--all"],
                    sources, work / "dependency-closure.log")
        run_command([str(bin_dir / "ninja"), "-C", str(build), "-j", str(args.jobs), "icy_freetype_probe"],
                    sources, work / "build.log")
        executable = build / "icy_freetype_probe"
        run_command(["xcrun", "lipo", str(executable), "-verify_arch", "arm64"], sources, work / "architecture.log")
        run_command(["otool", "-L", str(executable)], sources, work / "dylibs.log")
        output = work / "captures"
        output.mkdir()
        state["probeExitCode"] = subprocess.run([str(executable), state["fonts"], str(output)], cwd=work).returncode
        if state["probeExitCode"]:
            raise RuntimeError(f"Native probe failed: {state['probeExitCode']}; inspect captures/metrics.json")
        evidence = validate_results(output)
        evidence["executableSha256"] = digest(executable)
        # Context only: these Android Compose advances include shaping and are not equivalent raw metrics.
        android = IOS / "tests/evidence/android36-font-metrics.json"
        shutil.copyfile(android, work / "android36-font-metrics.json")
        evidence["androidReferenceSha256"] = digest(android)
        evidence["androidComparisonScope"] = "Reference retained; unshaped glyph sums are not paragraph advances"
        baseline = json.loads(android.read_text(encoding="utf-8"))
        captures = json.loads((output / "metrics.json").read_text())
        comparisons = []
        for sample in captures["samples"]:
            if sample["id"] not in {"roboto-400-73.5-linear-0", "roboto-700-73.5-linear-0"}:
                continue
            reference = next(row for row in baseline["nativePaint"]
                             if row["familyRequest"] == "default" and not row["subpixelText"]
                             and row["weightRequest"] == sample["requestedWeight"])
            comparisons.append({"backend": sample["backend"], "id": sample["id"],
                                "fontMetricDeltasFromAndroidPaint": {
                                    key: sample["fontMetrics"][key] - reference["fontMetrics"][key]
                                    for key in ("ascent", "descent", "leading")},
                                "rawGlyphAdvanceSumPx": sample["unshapedGlyphAdvanceSumPx"],
                                "androidShapedAdvancePxForContextOnly": reference["shapedAdvance"]})
        write_json(work / "android-context-comparison.json", {
            "scope": "Comparable raw font metrics; raw and shaped advances intentionally kept separate",
            "androidPixelParityVerified": False, "samples": comparisons})
        write_json(work / "validation.json", evidence)
        state["status"] = evidence["status"]
        return 0
    except Exception as error:
        state.update(status="failed", error=str(error))
        print(str(error), file=sys.stderr)
        return 1
    finally:
        write_json(work / "run.json", state)
        print(f"Evidence: {work}")


if __name__ == "__main__":
    raise SystemExit(main())
