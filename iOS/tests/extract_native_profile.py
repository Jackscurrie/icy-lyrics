"""Extract measured native content pixels and prepare an Android viewport profile.

Requires an actual XCTest PNG and its matching geometry attachment. Crops never
resize, rotate, mask, recolor, or approve appearance parity. The optional safe-area
interior excludes app-painted edge pixels as well as any system overlays there.
"""
from decimal import Decimal
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import argparse
import json
import math
import struct

from PIL import Image, PngImagePlugin

SP_SAMPLE_SIZES = (12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64)


def number(value, name, *, positive=False):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    if positive and value <= 0:
        raise ValueError(f"{name} must be positive")
    return Decimal(str(value))


def integer(value, name, *, positive=False):
    if type(value) is not int or (value <= 0 if positive else value < 0):
        raise ValueError(f"{name} must be a {'positive' if positive else 'nonnegative'} integer")
    return value


def pixel(value, name):
    # Decimal arithmetic preserves the supplied measurements. Fractional pixel
    # edges are rejected; the tool never silently rounds their crop coordinates.
    if value != value.to_integral_value():
        raise ValueError(f"{name} does not map to an integer pixel boundary: {value}")
    return int(value)


def four(value, name, *, rectangle=False):
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError(f"{name} must contain four numbers")
    result = [number(item, name) for item in value]
    if rectangle:
        if result[2] <= 0 or result[3] <= 0:
            raise ValueError(f"{name} must have positive width and height")
    elif any(item < 0 for item in result):
        raise ValueError(f"{name} cannot contain negative insets")
    return result


def text(metadata, key):
    value = metadata.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Missing {key}")
    return value


def native_font_samples(metadata):
    # Older capture attachments predate these measurements and remain usable
    # for geometry. Never derive or invent their absent font conversions.
    if "spToPx" not in metadata:
        return None
    samples = metadata["spToPx"]
    if not isinstance(samples, dict) or set(samples) != {str(size) for size in SP_SAMPLE_SIZES}:
        raise ValueError("spToPx must contain the complete native font sample set")
    for size in SP_SAMPLE_SIZES:
        number(samples[str(size)], f"spToPx[{size}]", positive=True)
    return {str(size): samples[str(size)] for size in SP_SAMPLE_SIZES}


def validate_native_geometry(metadata, image_size):
    if not isinstance(metadata, dict) or metadata.get("ready") is not True:
        raise ValueError("A completed native draw acknowledgement is required")
    if not text(metadata, "captureSurface").startswith("XCTest application window"):
        raise ValueError("Expected native XCTest application-window metadata")
    for key in ("scenario", "locale", "timezone", "preferredContentSizeCategory", "libraryDateText"):
        text(metadata, key)
    scale = number(metadata.get("displayScale"), "displayScale", positive=True)
    density = number(metadata.get("composeDensity"), "composeDensity", positive=True)
    number(metadata.get("nativeDisplayScale"), "nativeDisplayScale", positive=True)
    number(metadata.get("fontScale"), "fontScale", positive=True)
    native_font_samples(metadata)
    if density != scale:
        raise ValueError("Compose density does not match the native content display scale")
    density_dpi = pixel(density * 160, "Android density DPI")
    if type(metadata.get("requestedLargeText")) is not bool:
        raise ValueError("requestedLargeText must be recorded as a boolean")
    if metadata["requestedLargeText"] and metadata["fontScale"] <= 1:
        raise ValueError("Large-text capture did not produce an increased font scale")

    width = integer(metadata.get("contentWidthPx"), "contentWidthPx", positive=True)
    height = integer(metadata.get("contentHeightPx"), "contentHeightPx", positive=True)
    window_width = integer(metadata.get("capturedWindowWidthPx"), "capturedWindowWidthPx", positive=True)
    window_height = integer(metadata.get("capturedWindowHeightPx"), "capturedWindowHeightPx", positive=True)
    if image_size != (window_width, window_height):
        raise ValueError("PNG dimensions disagree with captured-window metadata")
    window = four(metadata.get("windowBoundsPoints"), "windowBoundsPoints", rectangle=True)
    content = four(metadata.get("contentBoundsInWindowPoints"), "contentBoundsInWindowPoints", rectangle=True)
    if (pixel(window[2] * scale, "window width"), pixel(window[3] * scale, "window height")) != image_size:
        raise ValueError("Window bounds and display scale disagree with the PNG dimensions")
    if (pixel(content[2] * scale, "content width"), pixel(content[3] * scale, "content height")) != (width, height):
        raise ValueError("Content bounds disagree with recorded Compose pixel dimensions")
    x = pixel((content[0] - window[0]) * scale, "content left")
    y = pixel((content[1] - window[1]) * scale, "content top")
    if x < 0 or y < 0 or x + width > window_width or y + height > window_height:
        raise ValueError("Content rectangle lies outside the captured window")

    supplied_insets = metadata.get("safeDrawingInsetsPx")
    if not isinstance(supplied_insets, list) or len(supplied_insets) != 4:
        raise ValueError("safeDrawingInsetsPx must contain four integer insets")
    insets = [integer(item, "safeDrawingInsetsPx") for item in supplied_insets]
    raw_safe_points = four(metadata.get("contentSafeAreaInsetsPoints"), "contentSafeAreaInsetsPoints")
    points = raw_safe_points
    if "contentSafeDrawingInsetsPoints" in metadata:
        source = text(metadata, "safeDrawingInsetsSource")
        if source not in ("UIKit safeAreaInsets", "UIKit safeArea with vertical corner adaptation"):
            raise ValueError("Unrecognized native safe-drawing inset source")
        points = four(metadata["contentSafeDrawingInsetsPoints"], "contentSafeDrawingInsetsPoints")
        if source == "UIKit safeAreaInsets" and points != raw_safe_points:
            raise ValueError("Raw safe-area source must match its recorded insets")
    conversion = metadata.get("safeDrawingInsetsPixelConversion")
    if conversion is None:
        expected_insets = [pixel(item * scale, "native safe inset") for item in points]
    elif conversion == "Float32 points * Float32 displayScale, roundToInt":
        def float32(value):
            return struct.unpack("f", struct.pack("f", float(value)))[0]
        expected_insets = [math.floor(float32(float32(item) * float32(scale)) + 0.5) for item in points]
    else:
        raise ValueError("Unrecognized native inset pixel conversion")
    if expected_insets != insets:
        raise ValueError("Native safe-area insets disagree with drawn Compose insets")
    left, top, right, bottom = insets
    if left + right >= width or top + bottom >= height:
        raise ValueError("Safe-area interior must have positive dimensions")
    window_insets = four(metadata.get("windowSafeAreaInsetsPoints"), "windowSafeAreaInsetsPoints")
    if window_insets[0] + window_insets[2] >= window[2] or window_insets[1] + window_insets[3] >= window[3]:
        raise ValueError("Window safe-area insets exceed its bounds")

    interface = metadata.get("interfaceOrientationRawValue")
    requested = metadata.get("requestedDeviceOrientationRawValue")
    expected = metadata.get("expectedInterfaceOrientationRawValue")
    if any(type(item) is not int for item in (interface, requested, expected)):
        raise ValueError("Native orientation values must be integers")
    # UIKit reverses the landscape enum names, but explicitly assigns the same
    # raw values: UIInterfaceOrientationLandscapeRight = UIDeviceOrientationLandscapeLeft.
    if interface not in (1, 3, 4) or interface != expected or requested != interface:
        raise ValueError("Requested device orientation and observed interface orientation disagree")
    if (width > height) != (interface in (3, 4)) or (window_width > window_height) != (interface in (3, 4)):
        raise ValueError("Native dimensions disagree with the recorded orientation")
    number(metadata.get("settleDelayAfterDrawSeconds"), "settleDelayAfterDrawSeconds", positive=True)
    return {"fullContentRectPx": [x, y, x + width, y + height],
            "safeAreaInteriorRectPx": [x + left, y + top, x + width - right, y + height - bottom],
            "densityDpi": density_dpi}


def png_chunks(png):
    offset = 8
    while offset < len(png):
        length = struct.unpack_from(">I", png, offset)[0]
        kind = png[offset + 4:offset + 8]
        yield kind, png[offset + 8:offset + 8 + length]
        if kind == b"IEND":
            break
        offset += length + 12


def color_metadata(png, image):
    profile = image.info.get("icc_profile")
    return {"mode": image.mode, "bitDepth": png[24], "pngColorType": png[25],
            "iccProfileSha256": sha256(profile).hexdigest() if profile else None,
            "iccProfileBytes": len(profile) if profile else 0,
            "gamma": image.info.get("gamma"), "srgbRenderingIntent": image.info.get("srgb"),
            "colorChunks": sorted([{"type": kind.decode("ascii"), "dataHex": data.hex()}
                for kind, data in png_chunks(png)
                if kind in (b"cHRM", b"gAMA", b"sRGB", b"sBIT", b"cICP", b"mDCV", b"cLLI", b"tRNS")],
                key=lambda item: item["type"]),
            "colorConversionApplied": False,
            "crossPlatformColorInterpretation": "pending; compare ICC/color metadata before interpreting raw channel differences"}


def color_chunks(png):
    """Retain the original PNG color interpretation without transforming pixels."""
    info = PngImagePlugin.PngInfo()
    for kind, data in png_chunks(png):
        if kind in (b"cHRM", b"gAMA", b"sRGB", b"sBIT", b"cICP"):
            info.add(kind, data)
    return info


def crop_png(source, original_png, rectangle):
    if rectangle == [0, 0, source.width, source.height]:
        return original_png
    cropped = source.crop(tuple(rectangle))
    encoded = BytesIO()
    cropped.save(encoded, format="PNG", pnginfo=color_chunks(original_png),
                 icc_profile=source.info.get("icc_profile"))
    result = encoded.getvalue()
    with Image.open(BytesIO(result)) as verification:
        if verification.mode != cropped.mode or verification.tobytes() != cropped.tobytes():
            raise ValueError("PNG encoding changed cropped pixel data")
        if color_metadata(result, verification) != color_metadata(original_png, source):
            raise ValueError("PNG encoding changed color interpretation metadata; this capture format needs a preserving crop path")
    return result


def extract(png_path, geometry_path, output_directory, *, safe_area_interior=False):
    png_path, geometry_path, output_directory = (Path(item).resolve() for item in
                                                (png_path, geometry_path, output_directory))
    png, geometry = png_path.read_bytes(), geometry_path.read_bytes()
    metadata = json.loads(geometry.decode("utf-8"))
    if output_directory.exists() and (not output_directory.is_dir() or any(output_directory.iterdir())):
        raise ValueError("Output directory must be new or empty; existing evidence will not be overwritten")
    # Pillow can reduce some 16-bit/color formats to 8-bit. Reject them explicitly.
    if len(png) < 33 or png[:8] != b"\x89PNG\r\n\x1a\n" or png[12:16] != b"IHDR" or png[24] != 8 or png[25] not in (2, 6):
        raise ValueError("Only original 8-bit RGB/RGBA PNG captures are supported without pixel conversion")
    with Image.open(BytesIO(png)) as source:
        if source.format != "PNG" or getattr(source, "n_frames", 1) != 1 or source.mode not in ("RGB", "RGBA"):
            raise ValueError("Expected a single-frame RGB/RGBA native PNG")
        if source.getexif().get(274, 1) != 1:
            raise ValueError("PNG requires an orientation transform; no automatic rotation is allowed")
        source.load()
        source_color = color_metadata(png, source)
        measured = validate_native_geometry(metadata, source.size)
        images = {"full-content.png": (measured["fullContentRectPx"], "Entire recorded Compose content rectangle")}
        if safe_area_interior:
            images["safe-area-interior.png"] = (measured["safeAreaInteriorRectPx"],
                "Restricted safe-area interior; excludes app-painted edges as well as system-reserved areas")
        payloads = {name: crop_png(source, png, box) for name, (box, _) in images.items()}
    references = {"nativePng": {"path": str(png_path), "sha256": sha256(png).hexdigest(), "bytes": len(png)},
                  "nativeGeometry": {"path": str(geometry_path), "sha256": sha256(geometry).hexdigest(), "bytes": len(geometry)}}
    viewport = {"widthPx": metadata["contentWidthPx"], "heightPx": metadata["contentHeightPx"],
                "density": metadata["composeDensity"], "fontScale": metadata["fontScale"],
                "safeDrawingInsetsPx": metadata["safeDrawingInsetsPx"],
                "orientation": "landscape" if metadata["interfaceOrientationRawValue"] in (3, 4) else "portrait",
                "interfaceOrientationRawValue": metadata["interfaceOrientationRawValue"]}
    profile_id = "ios-native-" + sha256(json.dumps(viewport, sort_keys=True).encode()).hexdigest()[:16]
    font_samples = native_font_samples(metadata)
    profile = {"schemaVersion": 1, "profileId": profile_id, "scenario": metadata["scenario"], **viewport,
               "sourceReferences": references, "sourceCaptureBackend": "xctest-application-window",
               "androidSettings": {"wmSize": f"{viewport['widthPx']}x{viewport['heightPx']}",
                                   "wmDensityDpi": measured["densityDpi"], "fontScale": viewport["fontScale"]},
               "androidInsetDispatch": {"required": True, "safeDrawingInsetsPx": viewport["safeDrawingInsetsPx"],
                                        "recordEffectiveComposeInsets": True, "preserveRawAndroidWindowInsets": True},
               "locale": metadata["locale"], "timezone": metadata["timezone"],
               "nativeLibraryDateText": metadata["libraryDateText"],
               "nativeFontScaling": {"requestedLargeText": metadata["requestedLargeText"],
                    "preferredContentSizeCategory": metadata["preferredContentSizeCategory"],
                    "spToPxObservations": font_samples, "sampleSizesSp": list(SP_SAMPLE_SIZES),
                    "nativeObservationsComplete": font_samples is not None,
                    "comparisonReadiness": ("native samples recorded; awaiting matching Android observations"
                                            if font_samples is not None else "pending native samples"),
                    "scalingEquivalence": "pending", "fontShapingParityVerified": False,
                    "warning": "Equal fontScale values do not establish equal large-text sizing; Android 14+ uses nonlinear scaling. Record actual sp-to-px conversions on both platforms."},
               "nativeTiming": {"settleDelayAfterDrawSeconds": metadata["settleDelayAfterDrawSeconds"],
                                "clock": "real display frames", "deterministicClockMatched": False},
               "systemOverlayReview": "pending inspection of actual native image",
               "appearanceParityVerified": False}
    extraction = {"schemaVersion": 1, "complete": True, "profileId": profile_id, "sourceReferences": references,
                  "sourcePngColor": source_color,
                  "nativeMetadata": metadata, "operation": "Integer-boundary PNG crops; no resize, rotation, mask, or recoloring",
                  "cropCoordinates": "[left, top, right, bottom], pixels relative to the original window PNG; right/bottom exclusive",
                  "crops": [{"path": str(output_directory / name), "sourceRectPx": box, "scope": scope,
                             "widthPx": box[2] - box[0], "heightPx": box[3] - box[1],
                             "pngColor": source_color,
                             "sha256": sha256(payloads[name]).hexdigest(), "bytes": len(payloads[name])}
                            for name, (box, scope) in images.items()],
                  "appearanceParityVerified": False}
    documents = {name: json.dumps(record, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
                 for name, record in (("android-viewport-profile.json", profile), ("extraction.json", extraction))}
    output_directory.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        (output_directory / name).write_bytes(data)
    for name, document in documents.items():
        (output_directory / name).write_text(document, encoding="utf-8")
    return extraction


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("native_png", type=Path)
    parser.add_argument("geometry_json", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--safe-area-interior", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = extract(args.native_png, args.geometry_json, args.output, safe_area_interior=args.safe_area_interior)
    except (OSError, ValueError, struct.error) as error:
        parser.error(str(error))
    print(f"Extracted {len(result['crops'])} measured native crop(s); profile {result['profileId']}. Appearance parity remains unverified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
