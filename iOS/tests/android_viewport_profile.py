"""Strict inputs for a separate native-size Android capture lane; no image resizing."""
from pathlib import Path
import json
import math
import re
import struct

GEOMETRY = ("widthPx", "heightPx", "density", "fontScale", "safeDrawingInsetsPx")


def validate_profile(profile):
    if not isinstance(profile, dict) or type(profile.get("schemaVersion")) is not int or profile["schemaVersion"] != 1:
        raise ValueError("Unsupported native viewport schema")
    if not isinstance(profile.get("profileId"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", profile["profileId"]):
        raise ValueError("Unsafe native viewport profileId")
    if not isinstance(profile.get("scenario"), str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", profile["scenario"]):
        raise ValueError("Native viewport must name a safe captured scenario")
    for key in ("widthPx", "heightPx"):
        if type(profile.get(key)) is not int or profile[key] <= 0:
            raise ValueError(f"Invalid {key}")
    for key in ("density", "fontScale"):
        value = profile.get(key)
        if type(value) not in (float, int) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"Invalid {key}")
    insets = profile.get("safeDrawingInsetsPx")
    if (not isinstance(insets, list) or len(insets) != 4
            or any(type(x) is not int or x < 0 for x in insets)
            or insets[0] + insets[2] >= profile["widthPx"]
            or insets[1] + insets[3] >= profile["heightPx"]):
        raise ValueError("Invalid safeDrawingInsetsPx")
    orientation = "landscape" if profile["widthPx"] > profile["heightPx"] else "portrait"
    if profile.get("orientation") != orientation:
        raise ValueError("Native viewport orientation disagrees with its dimensions")
    dpi = profile["density"] * 160
    if not math.isfinite(dpi) or int(dpi) != dpi:
        raise ValueError("Native density cannot be represented by integer Android DPI")
    settings = profile.get("androidSettings")
    expected = {"wmSize": f'{profile["widthPx"]}x{profile["heightPx"]}',
                "wmDensityDpi": int(dpi), "fontScale": profile["fontScale"]}
    if settings is not None and (not isinstance(settings, dict) or any(settings.get(k) != v for k, v in expected.items())):
        raise ValueError("androidSettings disagree with measured native geometry")
    return profile


def load_profile(path):
    return validate_profile(json.loads(Path(path).read_text(encoding="utf-8")))


def wm_geometry(profile, rotation):
    """wm size describes natural geometry; rotation 1/3 swaps the resulting viewport."""
    width, height = profile["widthPx"], profile["heightPx"]
    if rotation in (1, 3):
        width, height = height, width
    return f"{width}x{height}", str(round(profile["density"] * 160))


def prior_wm_override(output, kind):
    value = re.search(rf"^Override {kind}:\s*(\S+)\s*$", output, re.MULTILINE)
    return value.group(1) if value else "reset"


def instrumentation_arguments(profile):
    fields = {"viewportProfileId": profile["profileId"], "viewportWidthPx": profile["widthPx"],
              "viewportHeightPx": profile["heightPx"], "viewportDensity": profile["density"],
              "viewportFontScale": profile["fontScale"]}
    fields.update({"viewportInset" + side: value for side, value in
                   zip(("Left", "Top", "Right", "Bottom"), profile["safeDrawingInsetsPx"])})
    return [part for key, value in fields.items() for part in ("-e", key, str(value))]


def native_result_directory(root, profile, tree):
    validate_profile(profile)
    if tree not in ("baseline", "extracted"):
        raise ValueError("Unknown Android tree")
    return Path(root) / "iOS/tests/results/android/native" / profile["profileId"] / tree


def validate_capture(profile, metadata, png):
    if not isinstance(metadata, dict):
        raise ValueError("Invalid Android capture metadata")
    if metadata.get("profileId") != profile["profileId"] or metadata.get("scenario") != profile["scenario"]:
        raise ValueError("Android capture does not belong to the requested native profile/scenario")
    for key in GEOMETRY:
        actual, requested = metadata.get(key), profile[key]
        matches = (type(actual) in (int, float) and math.isclose(actual, requested, rel_tol=0, abs_tol=0.00001)
                   if key in ("density", "fontScale") else actual == requested)
        if not matches:
            raise ValueError(f"Effective Compose {key} does not match the native viewport")
    if len(png) < 24 or png[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
        raise ValueError("Invalid captured PNG header")
    if struct.unpack(">II", png[16:24]) != (profile["widthPx"], profile["heightPx"]):
        raise ValueError("PNG dimensions do not match the native viewport; resizing is prohibited")
    os_insets = metadata.get("osSafeDrawingInsetsPx")
    samples = metadata.get("spToPx")
    if (not isinstance(os_insets, list) or len(os_insets) != 4 or any(type(x) is not int or x < 0 for x in os_insets)
            or not isinstance(samples, dict) or any(type(samples.get(str(size))) not in (float, int)
                or not math.isfinite(samples[str(size)]) or samples[str(size)] <= 0
                for size in (12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64))):
        raise ValueError("Actual OS insets and native Compose font conversions must be recorded")
