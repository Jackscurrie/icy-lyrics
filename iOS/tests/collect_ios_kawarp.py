"""Collect exact UIKit/Metal probe evidence and compare every pixel to original Android.

Only the explicitly named diagnostic files are copied from its separate app
container. This is never a full-app, animation, or device acceptance marker.
"""
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import argparse
import json
import math
import re
import shutil
import zipfile

from PIL import Image
from extract_native_profile import color_metadata, crop_png

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "tests/fixtures/kawarp/contract.json"
REFERENCE = ROOT / "tests/evidence/android36-kawarp-gpu-phases.zip"
REFERENCE_SHA256 = "4e9435349de15a8c39092ae30496440d520f5b3748181ffa2602cb7c13875a7c"
CATALOG = "kawarp-gpu-uniform-phases-v1"
SIMCTL_SURFACE = "simctl framebuffer with measured UIKit child crop"
XCUI_SURFACE = "XCUIElement screenshot of actual pixel-aligned UIKit child surface; no manual crop, resize, mask, or raster replacement"


def digest(data):
    return sha256(data).hexdigest()


def decode_png(data, expected):
    if (len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR"
            or data[24] != 8 or data[25] not in (2, 6)):
        raise ValueError("Only original 8-bit RGB/RGBA PNG data is supported without color conversion")
    with Image.open(BytesIO(data)) as image:
        if image.format != "PNG" or image.size != tuple(expected) or getattr(image, "n_frames", 1) != 1:
            raise ValueError(f"Actual PNG dimensions/type differ: {image.size}, {image.format}; expected {expected}")
        if image.getexif().get(274, 1) != 1:
            raise ValueError("Oriented PNG must not be silently rotated")
        pixels = image.convert("RGBA").tobytes()
        if any(pixels[i] != 255 for i in range(3, len(pixels), 4)):
            raise ValueError("This first paired probe requires opaque decoded pixels")
        return pixels, {"pngSha256": digest(data), "rgbaSha256": digest(pixels),
                        "widthPx": image.width, "heightPx": image.height,
                        "iccProfileSha256": digest(image.info["icc_profile"]) if image.info.get("icc_profile") else None,
                        "pngColor": color_metadata(data, image)}


def finite(value):
    return type(value) in (float, int) and math.isfinite(value)


def integer_pixel(value, name):
    """Accept only integer edges, allowing floating CGFloat serialization noise.

    A physical256px child has width256/3 points at scale3. Its JSON floating
    representation may multiply to255.99999999999994. Four machine ULPs allow
    that representation error, not a geometric or image-comparison tolerance.
    """
    if not finite(value):
        raise ValueError(f"Invalid {name}")
    nearest = round(value)
    if abs(value - nearest) > 4 * max(math.ulp(value), math.ulp(float(nearest))):
        raise ValueError(f"{name} does not map to an integer pixel boundary")
    return nearest


def capture_rectangle(frame, expected):
    """Return the complete child rect in original PNG coordinates, never a mask."""
    width, height = expected["drawWidthPx"], expected["drawHeightPx"]
    capture_width, capture_height = frame.get("captureWidthPx"), frame.get("captureHeightPx")
    if any(type(value) is not int or value <= 0 for value in (capture_width, capture_height)):
        raise ValueError("Capture dimensions must be positive pixel integers")
    if type(frame.get("capturePngOrientation")) is not int or frame["capturePngOrientation"] != 1:
        raise ValueError("Capture PNG orientation must be1; no rotation is allowed")
    surface = frame.get("captureSurface")
    if surface == XCUI_SURFACE:
        if (capture_width, capture_height) != (width, height):
            raise ValueError("Legacy XCUI child capture dimensions must exactly match the viewport")
        return [0, 0, width, height]
    if surface != SIMCTL_SURFACE:
        raise ValueError("Unsupported captureSurface; identify the actual capture path explicitly")
    native = frame["nativeGeometry"]
    if type(native.get("interfaceOrientationRawValue")) is not int or native["interfaceOrientationRawValue"] != 1:
        raise ValueError("Measured simctl framebuffer probe requires portrait interface orientation1")
    window, child, scale = native.get("windowBoundsPoints"), native.get("surfaceBoundsInWindowPoints"), native.get("screenScale")
    if not finite(scale) or scale <= 0:
        raise ValueError("Invalid measured screen scale")
    for name, bounds in (("window", window), ("child", child)):
        if (not isinstance(bounds, list) or len(bounds) != 4 or not all(finite(item) for item in bounds)
                or bounds[2] <= 0 or bounds[3] <= 0):
            raise ValueError(f"Invalid measured {name} bounds")
    measured_window = (integer_pixel(window[2] * scale, "window width"), integer_pixel(window[3] * scale, "window height"))
    if measured_window != (capture_width, capture_height) or capture_width >= capture_height:
        raise ValueError("Full portrait framebuffer dimensions disagree with measured native window and scale")
    left = integer_pixel((child[0] - window[0]) * scale, "child left")
    top = integer_pixel((child[1] - window[1]) * scale, "child top")
    right = integer_pixel((child[0] + child[2] - window[0]) * scale, "child right")
    bottom = integer_pixel((child[1] + child[3] - window[1]) * scale, "child bottom")
    if (right - left, bottom - top) != (width, height):
        raise ValueError("Measured child rectangle differs from the entire requested viewport")
    if left < 0 or top < 0 or right > capture_width or bottom > capture_height:
        raise ValueError("Measured child rectangle lies outside the full framebuffer")
    return [left, top, right, bottom]


def extract_capture(png, frame, expected, destination):
    """Preserve full capture and prove the emitted child is its exact RGBA subset."""
    if digest(png) != frame.get("capturePngSha256"):
        raise ValueError("Captured PNG bytes disagree with attached screenshot hash")
    rectangle = capture_rectangle(frame, expected)
    full_pixels, full_info = decode_png(png, (frame["captureWidthPx"], frame["captureHeightPx"]))
    with Image.open(BytesIO(png)) as source:
        child_png = crop_png(source, png, rectangle)
    pixels, info = decode_png(child_png, (expected["drawWidthPx"], expected["drawHeightPx"]))
    left, top, right, bottom = rectangle
    stride = frame["captureWidthPx"] * 4
    indexed_subset = b"".join(full_pixels[y * stride + left * 4:y * stride + right * 4] for y in range(top, bottom))
    if pixels != indexed_subset or info["pngColor"] != full_info["pngColor"]:
        raise ValueError("Child crop changed the exact indexed pixel subset or color interpretation")
    is_framebuffer = frame["captureSurface"] == SIMCTL_SURFACE
    original_name = "native-full-framebuffer.png" if is_framebuffer else "native-original-xcui-child.png"
    (destination / original_name).write_bytes(png)
    (destination / "native-gpu.png").write_bytes(child_png)
    (destination / "native-gpu.rgba").write_bytes(pixels)
    return pixels, info, {"captureSurface": frame["captureSurface"],
        "backend": "simctl-framebuffer-measured-child" if is_framebuffer else "legacy-xcui-child",
        "originalPng": {"path": str(destination / original_name), "bytes": len(png), **full_info},
        "sourceRectPx": rectangle, "cropCoordinates": "[left, top, right, bottom]; right/bottom exclusive",
        "entireMeasuredChildIncluded": True, "rgbaMatchesIndexedSourceSubset": True,
        "cropApplied": rectangle != [0, 0, frame["captureWidthPx"], frame["captureHeightPx"]],
        "coordinatePolicy": "Integer pixel edges; at most4 machine ULPs of CGFloat serialization error",
        "resizingApplied": False, "maskingApplied": False, "rotationApplied": False, "colorConversionApplied": False}


def validate_frame(frame, expected, run_id, contract):
    if frame.get("catalog") != CATALOG or frame.get("ready") is not True or frame.get("runId") != run_id:
        raise ValueError("Missing real matching draw acknowledgement")
    if frame.get("id") != expected["id"] or frame.get("appearanceParityVerified") is not False:
        raise ValueError("Wrong case or unsupported acceptance claim")
    width, height = expected["drawWidthPx"], expected["drawHeightPx"]
    if (frame.get("drawWidthPx"), frame.get("drawHeightPx")) != (width, height):
        raise ValueError("Compose dimensions must exactly match original Android")
    config = frame["configuration"]
    if config["shaderSha256"] != contract["shaderSha256"]:
        raise ValueError("Different shader source")
    for key in ("uniformFloat32Values", "uniformFloat32BitsHex", "uniformLittleEndianBytesHex"):
        if config.get(key) != expected[key]:
            raise ValueError(f"Different shader {key}")
    if config.get("paintAntiAlias") is not True or config.get("paintDither") is not False:
        raise ValueError("Different production paint policy")
    if (config.get("childSampling"), config.get("childTileModes"), config.get("childLocalMatrix")) != (
            "SamplingMode.DEFAULT = NEAREST/NONE", "CLAMP/CLAMP", "identity"):
        raise ValueError("Different child sampler contract")
    for key in ("configuration", "nativeGeometry"):
        canonical = frame[key + "CanonicalJson"]
        if digest(canonical.encode()) != frame[key + "Sha256"] or json.loads(canonical) != frame[key]:
            raise ValueError(f"Altered {key} metadata")
    native = frame["nativeGeometry"]
    density = native["screenScale"]
    if not finite(density) or density <= 0 or frame.get("density") != density:
        raise ValueError("Effective Compose density differs from actual screen scale")
    bounds = native["surfaceBoundsInWindowPoints"]
    if not isinstance(bounds, list) or len(bounds) != 4 or not all(finite(value) for value in bounds):
        raise ValueError("Invalid measured native bounds")
    scaled = [integer_pixel(value * density, "native surface bound") for value in bounds]
    if scaled[2:] != [width, height]:
        raise ValueError("Native surface is not the exact pixel-aligned viewport")
    capture_rectangle(frame, expected)
    if not any(layer.get("deviceName") not in (None, "", "missing")
               and (layer.get("drawableWidthPx"), layer.get("drawableHeightPx")) == (width, height)
               for layer in native.get("metalLayers", [])):
        raise ValueError("No visible matching Metal layer/device; raster evidence cannot satisfy this probe")


def difference(actual, reference):
    if len(actual) != len(reference):
        raise ValueError("Cannot compare different pixel dimensions")
    channels = bytes(abs(a - b) for a, b in zip(actual, reference))
    changed = sum(any(channels[i:i + 4]) for i in range(0, len(channels), 4))
    visible = bytearray(channels)
    visible[3::4] = b"\xff" * (len(channels) // 4)
    return {"matchesEveryPixel": actual == reference, "changedPixelCount": changed,
            "totalPixelCount": len(actual) // 4, "maximumChannelDifference": max(channels, default=0)}, bytes(visible)


def collect(attachments, container, output):
    contract = json.loads(CONTRACT.read_text())
    records = []
    pngs = {}
    for path in attachments.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() == ".png":
            pngs.setdefault(digest(path.read_bytes()), path)
        elif path.suffix.lower() == ".json":
            try:
                value = json.loads(path.read_text())
                if isinstance(value, dict) and value.get("catalog") == CATALOG:
                    records.append(value)
            except (ValueError, UnicodeError):
                pass
    catalogs = [record for record in records if "cases" in record]
    if len(catalogs) != 1:
        raise ValueError("Require one actual XCTest catalog attachment")
    run_id = catalogs[0]["runId"]
    if not isinstance(run_id, str) or not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", run_id):
        raise ValueError("Invalid diagnostic run UUID")
    ids = [frame["id"] for frame in contract["frames"]]
    if catalogs[0]["cases"] != ids:
        raise ValueError("Different eight-phase catalog")
    frames = [record for record in records if "capturePngSha256" in record]
    if sorted(frame.get("id", "") for frame in frames) != sorted(ids):
        raise ValueError("Require exactly eight actual frame attachments")
    root = (container / "Documents/KawarpGpuProbe" / run_id).resolve(strict=True)
    if not root.is_relative_to(container.resolve(strict=True)):
        raise ValueError("Diagnostic data escapes the separate app container")
    reference_bytes = REFERENCE.read_bytes()
    if digest(reference_bytes) != REFERENCE_SHA256:
        raise ValueError("Original Android archive changed")
    output.mkdir(parents=True, exist_ok=False)
    results = []
    with zipfile.ZipFile(BytesIO(reference_bytes)) as archive:
        for expected in contract["frames"]:
            case_id = expected["id"]
            frame = next(record for record in frames if record["id"] == case_id)
            validate_frame(frame, expected, run_id, contract)
            directory = (root / case_id).resolve(strict=True)
            if not directory.is_relative_to(root):
                raise ValueError("Diagnostic path escapes its run directory")
            dest = output / case_id
            dest.mkdir()
            # Explicit allowlist: never recursively export the app container.
            for name in ("input-artwork.png", "input-artwork.rgba", "processed-artwork.png", "processed-artwork.rgba",
                         "native-kawarp.sksl", "preparation.json", "native-draw.json"):
                source = directory / name
                if source.is_symlink() or not source.is_file():
                    raise ValueError(f"Missing or linked diagnostic file: {name}")
                shutil.copyfile(source, dest / name)
            draw = json.loads((dest / "native-draw.json").read_text())
            if any(draw.get(key) != frame.get(key) for key in ("runId", "id", "configurationSha256", "preparation")) or draw.get("ready") is not True:
                raise ValueError("Container draw did not produce the attached captured state")
            prep = json.loads((dest / "preparation.json").read_text())
            if prep != frame["preparation"]:
                raise ValueError("Preparation record differs from actual draw")
            if digest((dest / "native-kawarp.sksl").read_bytes()) != contract["shaderSha256"]:
                raise ValueError("Exported shader source differs")
            input_bytes = (dest / "input-artwork.png").read_bytes()
            source_pixels, source_info = decode_png(input_bytes, (256, 256))
            if source_info["pngSha256"] != contract["sourceArtwork"]["pngSha256"] or source_info["rgbaSha256"] != contract["sourceArtwork"]["rgbaSha256"]:
                raise ValueError("Native input decode differs from exact captured Android bytes")
            if source_pixels != (dest / "input-artwork.rgba").read_bytes():
                raise ValueError("Input native decoded pixels disagree with its exported PNG")
            processed, processed_info = decode_png((dest / "processed-artwork.png").read_bytes(), (128, 128))
            if processed != (dest / "processed-artwork.rgba").read_bytes() or processed_info["rgbaSha256"] != prep["processedRgbaSha256"]:
                raise ValueError("Processed native pixels disagree with exported evidence")
            if frame["configuration"]["processedRgbaSha256"] != processed_info["rgbaSha256"]:
                raise ValueError("Captured shader used a different processed texture")
            actual_png = pngs[frame["capturePngSha256"]].read_bytes()
            actual, actual_info, capture = extract_capture(actual_png, frame, expected, dest)
            reference = archive.read(f"android36-kawarp-gpu-phases/{case_id}.rgba")
            comparison, diff = difference(actual, reference)
            processing, _ = difference(processed, archive.read("android36-kawarp-gpu-phases/processed-artwork.rgba"))
            Image.frombytes("RGBA", (actual_info["widthPx"], actual_info["heightPx"]), diff).save(dest / "difference.png")
            (dest / "geometry.json").write_text(json.dumps(frame, indent=2) + "\n")
            results.append({"id": case_id, "nativePixels": actual_info, "processedPixels": processed_info, "capture": capture,
                            "preprocessingComparison": processing, "gpuComparison": comparison})
    report = {"catalog": CATALOG, "runId": run_id, "referenceArchiveSha256": REFERENCE_SHA256,
              "completeEightFrameEvidence": True, "matchesEveryGpuPixel": all(item["gpuComparison"]["matchesEveryPixel"] for item in results),
              "processingMatchesEveryPixel": all(item["preprocessingComparison"]["matchesEveryPixel"] for item in results),
              "appearanceParityVerified": False, "frames": results,
              "comparisonPolicy": "Every decoded RGBA pixel of the entire measured child viewport. Full simctl PNG retained; explicit lossless integer child crop only. No resize, mask, rotation, color conversion, comparison tolerance or acceptance threshold.",
              "scope": "Original preprocessing and selected GPU uniforms only. Not full animation or whole-app acceptance."}
    (output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--attachments", type=Path, required=True)
    parser.add_argument("--container", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = collect(args.attachments, args.container, args.output)
    print(json.dumps({key: value for key, value in report.items() if key != "frames"}, indent=2))


if __name__ == "__main__":
    main()
