"""Compare deterministic iOS raster captures with the preserved Android originals.

Zero tolerance, no cropping or resizing. UIKit/Metal screenshots remain a
separate acceptance gate; matching this raster lane cannot approve that host.
"""
from pathlib import Path
from io import BytesIO
import argparse
import hashlib
import json
import math
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
PORTRAIT = ["onboarding", "empty", "portrait", "portrait-long", "portrait-failed",
            "background-static", "background-disabled", "reduced-motion", "settings",
            "library", "library-empty", "legal", "diagnostics"]
LANDSCAPE = ["landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
             "landscape-mixed-right", "multilingual", "syllables"]
GEOMETRY = ("widthPx", "heightPx", "density", "fontScale", "safeDrawingInsetsPx")
PROFILES = {"android36-420dpi-portrait-v1": PORTRAIT,
            "android36-420dpi-landscape-v1": LANDSCAPE}


def validate_geometry(value):
    for key in ("widthPx", "heightPx"):
        if type(value.get(key)) is not int or value[key] <= 0:
            raise ValueError(f"Invalid {key}")
    for key in ("density", "fontScale"):
        number = value.get(key)
        if type(number) not in (int, float) or not math.isfinite(number) or number <= 0:
            raise ValueError(f"Invalid {key}")
    insets = value.get("safeDrawingInsetsPx")
    if not isinstance(insets, list) or len(insets) != 4 or any(type(x) is not int or x < 0 for x in insets):
        raise ValueError("Invalid safeDrawingInsetsPx")


def compare(reference_png, candidate_png, reference, candidate, difference_path=None):
    """A one-level RGB or alpha change must fail, even when invisible to the eye."""
    validate_geometry(reference)
    validate_geometry(candidate)
    if candidate.get("captureBackend") != "skia-raster":
        raise ValueError("This comparison requires the deterministic Skia raster lane")
    row = {"referenceSha256": hashlib.sha256(reference_png).hexdigest(),
           "candidateSha256": hashlib.sha256(candidate_png).hexdigest(),
           "referenceGeometry": {k: reference[k] for k in GEOMETRY},
           "candidateGeometry": {k: candidate[k] for k in GEOMETRY}}
    if row["referenceGeometry"] != row["candidateGeometry"]:
        return row | {"status": "geometry-mismatch"}
    with Image.open(BytesIO(reference_png)) as before, Image.open(BytesIO(candidate_png)) as after:
        size = (reference["widthPx"], reference["heightPx"])
        if before.format != "PNG" or after.format != "PNG" or before.size != size or after.size != size:
            return row | {"status": "image-metadata-mismatch"}
        if getattr(before, "n_frames", 1) != 1 or getattr(after, "n_frames", 1) != 1:
            raise ValueError("Animated images cannot establish a deterministic frame")
        difference = ImageChops.difference(before.convert("RGBA"), after.convert("RGBA"))
    changed = sum(1 for pixel in difference.get_flattened_data() if any(pixel))
    row.update(status="identical" if changed == 0 else "different", changedPixels=changed,
               totalPixels=size[0] * size[1],
               maxChannelDelta=max(high for _, high in difference.getextrema()),
               meanAbsoluteChannelDelta=sum(ImageStat.Stat(difference).mean) / 4)
    if changed and difference_path is not None:
        # Opaque per-channel maximum also exposes alpha-only differences.
        channels = difference.split()
        visible = channels[0]
        for channel in channels[1:]:
            visible = ImageChops.lighter(visible, channel)
        visible.save(difference_path)
    return row


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--reference-archive", type=Path,
                        default=ROOT / "tests/evidence/android-complete-parity.zip")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/deterministic-comparison")
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    with ZipFile(args.reference_archive) as archive:
        for profile, scenarios in PROFILES.items():
            directory = args.candidate / profile
            try:
                manifest = json.loads((directory / "manifest.json").read_text())
                if manifest.get("complete") is not True:
                    raise ValueError("Incomplete deterministic capture sequence")
                profile_error = None
            except (OSError, ValueError) as error:
                profile_error = str(error)
            for index, scenario in enumerate(scenarios):
                try:
                    if profile_error:
                        raise ValueError(profile_error)
                    reference = json.loads(archive.read(f"baseline/{scenario}.json"))
                    candidate = json.loads((directory / f"{scenario}.json").read_text())
                    if (candidate.get("profileId") != profile or candidate.get("scenario") != scenario
                            or candidate.get("scenarioIndex") != index or candidate.get("scenarioOrder") != scenarios):
                        raise ValueError("Capture does not match the complete ordered fixture profile")
                    candidate_png = (directory / f"{scenario}.png").read_bytes()
                    if candidate.get("pngSha256") != hashlib.sha256(candidate_png).hexdigest():
                        raise ValueError("Capture PNG does not match its recorded hash")
                    row = compare(archive.read(f"baseline/{scenario}.png"), candidate_png, reference, candidate,
                                  args.output / f"{scenario}-diff.png")
                    row["candidateClock"] = {key: candidate.get(key) for key in
                                             ("clockStartMs", "clockTimeMillis", "scenarioIndex", "scenarioOrder")}
                except (OSError, KeyError, ValueError) as error:
                    row = {"status": "invalid-or-missing", "error": str(error)}
                rows.append({"scenario": scenario, "profileId": profile, **row})
    identical = all(row["status"] == "identical" for row in rows)
    report = {"comparison": "Exact full-image RGBA; no masks, resizing, or tolerances.",
              "referenceArchiveSha256": hashlib.sha256(args.reference_archive.read_bytes()).hexdigest(),
              "candidateBackend": "skia-raster", "allIdentical": identical, "scenarios": rows,
              "nativeUIKitMetalAcceptance": "pending separate capture comparison",
              "physicalIPhoneValidation": "pending"}
    (args.output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    lines = ["# Android / deterministic iOS comparison", "", report["comparison"], "",
             "This raster comparison does not approve the native UIKit/Metal host.", "",
             "| Scenario | Result | Changed pixels |", "|---|---|---:|"]
    lines += [f"| {row['scenario']} | {row['status']} | {row.get('changedPixels', '—')} |" for row in rows]
    (args.output / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0 if identical else 1


if __name__ == "__main__":
    raise SystemExit(main())
