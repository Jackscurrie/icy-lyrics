"""Strict, separate comparison of complete preserved-Android/iOS motion evidence."""
from pathlib import Path, PurePosixPath
from io import BytesIO
import argparse
import hashlib
import json
import math
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
SUITE = "mixed-lyrics-motion-v1"
PROFILE = {"id": "android36-420dpi-landscape-v1", "orientation": "landscape",
           "widthPx": 2400, "heightPx": 1080, "density": 2.625, "fontScale": 1,
           "safeDrawingInsetsPx": [0, 63, 0, 63], "safeDrawingInsetsDp": [0, 24, 0, 24]}
GEOMETRY = ("widthPx", "heightPx", "density", "fontScale", "safeDrawingInsetsPx")
SEQUENCES = ["mixed-to-lyrics", "lyrics-to-mixed", "interrupted-mixed-to-lyrics", "reverse-to-mixed"]
MODES = ["MIXED", "LYRICS", "MIXED", "LYRICS", "MIXED"]
OFFSETS = [0, 128, 224, 448, 2000]
INTERRUPTED_OFFSETS = [0, 128, 224]
FRAME_FIELDS = ("id", "frameIndex", "sequence", "phase", "requestedOffsetFromPrimedMs",
                "targetMode", "composedMode", "mediaSide", "trackKey", "rawPositionMs",
                "playbackSpeed", "playbackState", "isPlaying", "actionClockMs", "primedClockMs",
                "captureClockMs", "lastModeCompositionClockMs", "lastFrameTimeNanos",
                "fixtureMounts", "fixtureDisposals", *GEOMETRY)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, label, minimum=0):
    require(type(value) is int and minimum <= value < 2**63, f"Invalid {label}")
    return value


def number(value, label):
    require(type(value) in (int, float) and math.isfinite(value), f"Invalid {label}")
    return value


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contract(ios_root=ROOT):
    """Source text EOL equivalence only; font and image bytes are never normalized."""
    paths = {"referenceSourceManifestSha256": "tests/baseline/android-source-manifest.json",
             "motionFixtureSourceSha256": "shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyMotionFixtureScreen.kt",
             "fixtureDataSourceSha256": "shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyParityFixtures.kt"}
    identity = {"textHashEncoding": "utf8-lf"}
    identity.update({key: digest((ios_root / path).read_bytes().replace(b"\r\n", b"\n")) for key, path in paths.items()})
    asset_root = ios_root / "shared/ui/assets"
    fonts = {path.relative_to(asset_root).as_posix(): digest(path.read_bytes())
             for path in sorted((asset_root / "font").iterdir()) if path.suffix in (".ttf", ".ttc")}
    require(bool(fonts), "No source font assets")
    return identity, fonts


def read_json(path):
    require(path.is_file() and not path.is_symlink(), f"Missing/nonregular evidence: {path.name}")
    require(path.stat().st_size <= 10_000_000, f"Oversized JSON evidence: {path.name}")

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON field: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError(f"Nonfinite JSON value: {value}")

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)


def validate_geometry(value, profile=False):
    require(type(value) is dict, "Invalid geometry object")
    for key in ("widthPx", "heightPx"):
        integer(value.get(key), key, 1)
    for key in ("density", "fontScale"):
        number(value.get(key), key)
    insets = value.get("safeDrawingInsetsPx")
    require(isinstance(insets, list) and len(insets) == 4, "Invalid safe insets")
    for value_px in insets:
        integer(value_px, "safe inset")
    for key in GEOMETRY:
        require(value.get(key) == PROFILE[key], f"Wrong motion profile {key}")
    if profile:
        for key in ("id", "orientation", "safeDrawingInsetsDp"):
            require(value.get(key) == PROFILE[key], f"Wrong motion profile {key}")
        for value_dp in value["safeDrawingInsetsDp"]:
            number(value_dp, "safe inset dp")


def schedule(initial_clock):
    """The exact shared plan; no inferred or shifted clock epochs."""
    clock = initial_clock
    index = 0
    for sequence_index, sequence in enumerate(SEQUENCES):
        offsets = INTERRUPTED_OFFSETS if sequence_index == 2 else OFFSETS
        action = {"sequence": sequence, "fromMode": MODES[sequence_index], "targetMode": MODES[sequence_index + 1],
                  "clockMs": clock, "semanticControl": "Next fullscreen mode" if sequence_index % 2 == 0 else "Previous fullscreen mode",
                  "interruptsSequence": SEQUENCES[2] if sequence_index == 3 else None}
        frames = []
        for offset in [None, *offsets]:
            phase = "before" if offset is None else f"t{offset:04d}"
            frames.append({"id": f"{index:02d}-{sequence}-{phase}", "frameIndex": index,
                           "sequence": sequence, "phase": phase, "requestedOffsetFromPrimedMs": offset,
                           "targetMode": action["targetMode"],
                           "composedMode": action["fromMode"] if offset is None else action["targetMode"],
                           "actionClockMs": clock, "primedClockMs": None if offset is None else clock + 32,
                           "captureClockMs": clock if offset is None else clock + 32 + offset})
            index += 1
        yield action, frames
        clock += 32 + offsets[-1]


def load_side(root, side, backend, expected_contract):
    directory = root / side.lower()
    manifest = read_json(directory / "manifest.json")
    require(type(manifest) is dict, "Manifest must be an object")
    require(manifest.get("complete") is True, "Incomplete motion manifest")
    require(integer(manifest.get("schemaVersion"), "schemaVersion") == 1, "Wrong schema version")
    require(manifest.get("suite") == SUITE and manifest.get("mediaSide") == side, "Wrong motion suite/side")
    require(manifest.get("captureBackend") == backend, "Wrong capture backend")
    validate_geometry(manifest.get("profile", {}), profile=True)
    require(manifest.get("sequenceOrder") == SEQUENCES, "Wrong sequence order")
    for key, expected in (("frameIntervalMs", 16), ("primeFrames", 2), ("fixtureMounts", 1), ("fixtureDisposals", 0)):
        require(integer(manifest.get(key), key) == expected, f"Wrong {key}")
    for key, expected in (("fullSampleOffsetsMs", OFFSETS), ("interruptedSampleOffsetsMs", INTERRUPTED_OFFSETS)):
        require(manifest.get(key) == expected, f"Wrong {key}")
        for offset in manifest[key]:
            integer(offset, key)
    require("fixedFrameTimeNanos" in manifest and manifest["fixedFrameTimeNanos"] is None, "Frozen frame clock cannot verify motion")
    require(manifest.get("backgroundStyle") == "STATIC_BLURRED" and manifest.get("reducedMotion") is False,
            "Wrong background/motion policy")
    identity, fonts = expected_contract
    require(manifest.get("sourceIdentity") == identity, "Source identity differs from the checked-out motion contract")
    require(manifest.get("sourceAssetSha256") == fonts, "Source font assets differ")
    if backend == "skia-raster":
        loaded = manifest.get("loadedAssetSha256", {})
        require(type(loaded) is dict, "Invalid loaded font map")
        require(all(loaded.get(name) == sha for name, sha in fonts.items()), "Loaded iOS font hash differs or is missing")
    else:
        require(manifest.get("androidSystemFontSha256") == fonts, "Android system font hash differs or is missing")

    initial = integer(manifest.get("initialSettleEndClockMs"), "initial settled clock", 1)
    require(initial % 16 == 0, "Initial clock is not frame aligned")
    plan = list(schedule(initial))
    actions = manifest.get("actions")
    require(isinstance(actions, list) and len(actions) == 4, "Incomplete action sequence")
    for actual, (expected, _) in zip(actions, plan):
        require(type(actual) is dict, "Invalid action")
        integer(actual.get("clockMs"), "action clock", 1)
        require({key: actual.get(key) for key in expected} == expected, "Action sequence or controlled clock differs")
    compositions = manifest.get("compositions")
    require(isinstance(compositions, list) and len(compositions) == 5, "Incomplete composition sequence")
    require(all(type(value) is dict for value in compositions), "Invalid composition record")
    require([value.get("mode") for value in compositions] == MODES, "Wrong composition order")
    for index, value in enumerate(compositions):
        clock = integer(value.get("clockMs"), "composition clock", 1)
        if index == 0:
            require(clock <= initial, "Initial composition occurred after its capture")
        else:
            action_clock = actions[index - 1]["clockMs"]
            require(action_clock <= clock <= action_clock + 32, "Composition clock lies outside priming frames")
            require(clock > compositions[index - 1]["clockMs"], "Composition clock did not increase")

    records = manifest.get("frames")
    expected_frames = [(sequence_index, frame) for sequence_index, (_, frames) in enumerate(plan) for frame in frames]
    require(isinstance(records, list) and len(records) == 22, "Incomplete frame sequence")
    for record, (sequence_index, expected) in zip(records, expected_frames):
        require(type(record) is dict, "Invalid frame record")
        for key in ("frameIndex", "actionClockMs", "captureClockMs", "lastModeCompositionClockMs", "lastFrameTimeNanos"):
            integer(record.get(key), key)
        for key in ("primedClockMs", "requestedOffsetFromPrimedMs"):
            if record.get(key) is not None:
                integer(record[key], key)
        require({key: record.get(key) for key in expected} == expected, "Frame order, phase or controlled clock differs")
        require(record.get("mediaSide") == side, "Wrong frame side")
        require(record.get("trackKey") == "spotify:track:0000000000000000000001", "Wrong track identity")
        require(integer(record.get("rawPositionMs"), "rawPositionMs") == 26500, "Playback position changed")
        require(number(record.get("playbackSpeed"), "playbackSpeed") == 0 and record.get("isPlaying") is False
                and integer(record.get("playbackState"), "playbackState") == 2, "Track is not paused")
        require(integer(record.get("fixtureMounts"), "fixtureMounts") == 1 and
                integer(record.get("fixtureDisposals"), "fixtureDisposals") == 0, "Fixture was recreated")
        validate_geometry(record)
        composition_index = sequence_index + (record["phase"] != "before")
        require(record["lastModeCompositionClockMs"] == compositions[composition_index]["clockMs"], "Wrong observed composition timestamp")
        require(0 < record["lastFrameTimeNanos"] <= record["captureClockMs"] * 1_000_000,
                "Observed frame timestamp lies outside capture clock")
        if backend != "skia-raster":
            require(record.get("composeRootCount") == 1 and record.get("dialogRootCount") == 0, "Wrong Android capture surface")
        require(read_json(directory / (record["id"] + ".json")) == record, "Frame JSON differs from manifest")
        png_path = directory / (record["id"] + ".png")
        require(png_path.is_file() and not png_path.is_symlink(), "Missing/nonregular capture PNG")
        require(0 < png_path.stat().st_size <= 64_000_000, "Invalid capture PNG length")
        png = png_path.read_bytes()
        require(integer(record.get("pngBytes"), "pngBytes", 1) == len(png), "Capture PNG length mismatch")
        require(record.get("pngSha256") == digest(png), "Capture PNG hash mismatch")
    return manifest, directory


def validate_pair(reference, candidate):
    for key in ("initialSettleEndClockMs", "actions", "compositions"):
        require(reference[key] == candidate[key], f"Actual {key} mismatch; clock epochs are not normalized")
    for before, after in zip(reference["frames"], candidate["frames"]):
        for key in FRAME_FIELDS:
            require(before.get(key) == after.get(key), f"{before['id']}: actual {key} mismatch; no clock adjustment is allowed")


def unpack_reference_archive(archive, output):
    """Unpack the checked-in, checksum-pinned original evidence without replacing files."""
    archive, output = Path(archive).resolve(), Path(output).resolve()
    require(output.is_relative_to(ROOT / "build"), "Extract reference evidence only under iOS/build")
    require(not output.exists(), "Reference extraction output already exists")
    metadata = read_json(archive.with_suffix(".json"))
    require(metadata.get("schemaVersion") == 1 and metadata.get("suite") == SUITE
            and metadata.get("archive") == archive.name, "Wrong reference archive metadata")
    data = archive.read_bytes()
    require(len(data) == metadata.get("archiveBytes") and digest(data) == metadata.get("archiveSha256"),
            "Reference archive checksum/length mismatch")
    with ZipFile(BytesIO(data)) as package:
        entries = package.infolist()
        require(0 < len(entries) <= 200 and sum(item.file_size for item in entries) <= 64_000_000,
                "Unexpected reference archive size")
        names = set()
        for item in entries:
            path = PurePosixPath(item.filename)
            require(not path.is_absolute() and bool(path.parts) and
                    all(part not in ("", ".", "..") for part in item.filename.split("/")) and
                    "\\" not in item.filename and ":" not in item.filename and
                    not item.is_dir() and ((item.external_attr >> 16) & 0o170000) in (0, 0o100000),
                    "Unsafe reference archive entry")
            require(item.filename.casefold() not in names, "Duplicate reference archive entry")
            names.add(item.filename.casefold())
        output.mkdir(parents=True)
        for item in entries:
            target = output.joinpath(*PurePosixPath(item.filename).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as destination:
                destination.write(package.read(item))
    for side in ("LEFT", "RIGHT"):
        load_side(output / "baseline", side, "Android Compose root / PixelCopy", contract())
    return output / "baseline"


def pixels(png, size):
    require(png[:8] == b"\x89PNG\r\n\x1a\n" and png[12:16] == b"IHDR" and len(png) >= 33,
            "Not a PNG capture")
    require(png[24] == 8 and png[25] in (2, 6), "Capture must be original 8-bit RGB/RGBA PNG")
    with Image.open(BytesIO(png)) as image:
        require(image.format == "PNG" and image.size == size, "PNG geometry differs from recorded profile")
        require(getattr(image, "n_frames", 1) == 1, "Animated PNG cannot represent one controlled frame")
        return image.convert("RGBA")


def compare_pixels(reference_png, candidate_png, size, difference_prefix=None):
    before, after = pixels(reference_png, size), pixels(candidate_png, size)
    difference = ImageChops.difference(before, after)
    maximum = difference.getchannel("R")
    for channel in difference.split()[1:]:
        maximum = ImageChops.lighter(maximum, channel)
    changed = size[0] * size[1] - maximum.histogram()[0]
    result = {"status": "identical" if changed == 0 else "different", "changedPixels": changed,
              "totalPixels": size[0] * size[1], "maxChannelDelta": maximum.getextrema()[1],
              "meanAbsoluteChannelDelta": sum(ImageStat.Stat(difference).mean) / 4}
    if difference_prefix is not None:
        # Preserve all four exact delta channels, plus a visible view of alpha-only changes.
        difference.save(str(difference_prefix) + "-rgba-diff.png")
        maximum.save(str(difference_prefix) + "-maximum-diff.png")
    return result


def compare_suites(reference_root, candidate_root, output, expected_contract=None):
    reference_root, candidate_root, output = (Path(path).resolve() for path in (reference_root, candidate_root, output))
    for source in (reference_root, candidate_root):
        require(output != source and not output.is_relative_to(source) and not source.is_relative_to(output),
                "Comparison output must not overlap input evidence")
    require(not output.exists(), "Output already exists; choose a new comparison directory")
    output.mkdir(parents=True)
    report = {"suite": SUITE, "comparisonComplete": False, "validEvidence": False, "allFramesIdentical": False,
              "comparison": "Exact full RGBA; no resizing, masks, tolerances or clock normalization.",
              "referenceRoot": str(reference_root), "candidateRoot": str(candidate_root), "frames": [], "errors": [],
              "appearanceParityVerified": False, "UIKitMetalAcceptance": "separate pending evidence", "physicalIPhoneValidation": "pending"}

    def save():
        (output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save()
    expected_contract = expected_contract or contract()
    loaded = []
    for side in ("LEFT", "RIGHT"):
        try:
            reference, reference_dir = load_side(reference_root, side, "Android Compose root / PixelCopy", expected_contract)
            candidate, candidate_dir = load_side(candidate_root, side, "skia-raster", expected_contract)
            validate_pair(reference, candidate)
            loaded.append((side, reference, candidate, reference_dir, candidate_dir))
        except (ValueError, OSError, KeyError, TypeError) as error:
            report["errors"].append({"side": side, "error": str(error)})
    if not report["errors"]:
        report["validEvidence"] = True
        for side, reference, candidate, reference_dir, candidate_dir in loaded:
            side_output = output / side.lower()
            side_output.mkdir()
            for before, after in zip(reference["frames"], candidate["frames"]):
                try:
                    row = compare_pixels((reference_dir / (before["id"] + ".png")).read_bytes(),
                                         (candidate_dir / (after["id"] + ".png")).read_bytes(),
                                         (PROFILE["widthPx"], PROFILE["heightPx"]), side_output / before["id"])
                except (ValueError, OSError) as error:
                    row = {"status": "invalid-image", "error": str(error)}
                    report["validEvidence"] = False
                report["frames"].append({"side": side, "id": before["id"], "captureClockMs": before["captureClockMs"],
                                         "referenceSha256": before["pngSha256"], "candidateSha256": after["pngSha256"], **row})
        report["allFramesIdentical"] = report["validEvidence"] and all(row["status"] == "identical" for row in report["frames"])
    report["comparisonComplete"] = True
    save()
    lines = ["# Mixed/lyrics motion comparison", "", report["comparison"], "",
             "This report covers this motion sequence only; it does not approve the whole app or UIKit/Metal.", ""]
    lines += [f"- {error['side']}: rejected — {error['error']}" for error in report["errors"]]
    if report["frames"]:
        lines += ["| Side / frame | Result | Changed pixels |", "|---|---|---:|"]
        lines += [f"| {row['side']} / {row['id']} | {row['status']} | {row.get('changedPixels', '—')} |" for row in report["frames"]]
    (output / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path, help="Android motion directory containing left/ and right/")
    parser.add_argument("candidate", type=Path, help="iOS motion profile directory containing left/ and right/")
    parser.add_argument("--output", required=True, type=Path, help="New nonoverlapping comparison directory")
    args = parser.parse_args(argv)
    try:
        report = compare_suites(args.reference, args.candidate, args.output)
    except (ValueError, OSError) as error:
        parser.exit(2, f"Motion evidence rejected: {error}\n")
    print(f"Valid evidence: {report['validEvidence']}; exact matching frames: "
          f"{sum(row['status'] == 'identical' for row in report['frames'])}/44; report: {args.output / 'REPORT.md'}")
    return 0 if report["allFramesIdentical"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
