"""Certify public UIKit point measurements and permute a fixed-display RGBA PNG.

The raw PNG remains primary evidence. This module neither chooses an orientation
from an enum nor resamples pixels. Only a measured full-window signed-unit basis
with determinant +1 is accepted. Pillow performs byte permutations, without
color management; PNG chunks are parsed/validated and written explicitly so no
color interpretation metadata is silently lost by an image encoder.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import struct
import zlib

from PIL import Image

SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_BYTES = 128 * 1024 * 1024
MAX_PIXELS = 8192 * 8192
COLOR_CHUNKS = {b"sRGB", b"gAMA", b"cHRM", b"iCCP", b"sBIT"}
ANCILLARY = COLOR_CHUNKS | {b"eXIf", b"pHYs", b"tEXt", b"zTXt", b"iTXt", b"tIME"}


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _number(value) -> float:
    if type(value) not in (float, int) or not math.isfinite(value):
        raise ValueError("Coordinate measurements must be finite numbers")
    return float(value)


def _near(actual: float, expected: float) -> bool:
    # Arithmetic/JSON float serialization only: at most four binary64 ULPs at
    # the operand magnitude. This is not a point/pixel geometric tolerance.
    return abs(actual - expected) <= 4 * math.ulp(max(abs(actual), abs(expected), 1.0))


def _equal(actual, expected, label: str) -> None:
    if len(actual) != len(expected) or not all(_near(a, b) for a, b in zip(actual, expected)):
        raise ValueError(f"Measured {label} differs from the certified coordinate mapping")


def _integral(value: float, label: str) -> int:
    rounded = round(value)
    if not _near(value, rounded):
        raise ValueError(f"Nonintegral {label}; pixel interpolation is forbidden")
    return rounded


def _array(value, length: int, label: str) -> list[float]:
    if not isinstance(value, list) or len(value) != length:
        raise ValueError(f"Expected {length} values for {label}")
    return [_number(v) for v in value]


def _points(value, count: int, label: str) -> list[list[float]]:
    if not isinstance(value, list) or len(value) != count:
        raise ValueError(f"Expected {count} measured {label}")
    return [_array(p, 2, label) for p in value]


def _corners(rect):
    x, y, w, h = rect
    if w <= 0 or h <= 0:
        raise ValueError("Coordinate bounds must have positive dimensions")
    return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]


def _geometry(metadata: dict, raw_size: tuple[int, int], expected_size: tuple[int, int]) -> dict:
    if not isinstance(metadata, dict) or not isinstance(metadata.get("fixedCoordinateMapping"), dict):
        raise ValueError("Missing measured fixedCoordinateMapping")
    m = metadata["fixedCoordinateMapping"]
    if type(m.get("schemaVersion")) is not int or m["schemaVersion"] != 1:
        raise ValueError("Unsupported measured coordinate schema")
    if m.get("cornerOrder") != ["TL", "TR", "BR", "BL"]:
        raise ValueError("Missing ordered corner correspondence")
    if m.get("sampleOrder") != ["center", "centerPlusOnePixelX", "centerPlusOnePixelY"]:
        raise ValueError("Missing independently measured center/pixel-step samples")
    if "fixedCoordinateMappingAfterCapture" in metadata or "fixedCoordinateMappingStableDuringCapture" in metadata:
        if metadata.get("fixedCoordinateMappingStableDuringCapture") is not True or metadata.get("fixedCoordinateMappingAfterCapture") != m:
            raise ValueError("Coordinate measurements changed during framebuffer capture")
    for size in (raw_size, expected_size):
        if len(size) != 2 or any(type(v) is not int or not 1 <= v <= 8192 for v in size):
            raise ValueError("Invalid framebuffer dimensions")
    wr = _array(m.get("windowBoundsPoints"), 4, "window bounds")
    fr = _array(m.get("fixedBoundsPoints"), 4, "fixed bounds")
    nr = _array(m.get("screenNativeBoundsPixels"), 4, "native bounds")
    cr = _array(m.get("contentBoundsInWindowPoints"), 4, "content bounds")
    scale = _number(m.get("displayScale"))
    native_scale = _number(m.get("nativeDisplayScale"))
    for name, nested in (("windowBoundsPoints", wr), ("contentBoundsInWindowPoints", cr),
                         ("screenNativeBoundsPixels", nr)):
        _equal(_array(metadata.get(name), 4, name), nested, f"top-level {name}")
    for name, nested in (("displayScale", scale), ("nativeDisplayScale", native_scale)):
        _equal([_number(metadata.get(name))], [nested], f"top-level {name}")
    if scale <= 0 or not _near(scale, native_scale):
        raise ValueError("Display/native scale mismatch requires unsupported resampling")
    _equal(nr, [0, 0, *raw_size], "native pixel bounds")
    _equal(fr[:2], [0, 0], "fixed bounds origin")
    _equal([fr[2] * scale, fr[3] * scale], raw_size, "fixed display size")
    _equal([wr[2] * scale, wr[3] * scale], expected_size, "window pixel size")
    window = _points(m.get("windowCornersInWindowPoints"), 4, "window corners")
    fixed = _points(m.get("windowCornersInFixedPoints"), 4, "fixed corners")
    round_trips = _points(m.get("roundTripsInWindowPoints"), 4, "window round trips")
    for a, b, c in zip(window, _corners(wr), round_trips):
        _equal(a, b, "window corner")
        _equal(c, b, "window round trip")
    fixed_px = [[_integral((p[i] - fr[i]) * scale, "fixed corner") for i in range(2)] for p in fixed]
    origin = fixed_px[0]
    width, height = expected_size
    a = [_integral((fixed_px[1][i] - origin[i]) / width, "X basis") for i in range(2)]
    b = [_integral((fixed_px[3][i] - origin[i]) / height, "Y basis") for i in range(2)]
    if sum(x*x for x in a) != 1 or sum(x*x for x in b) != 1 or a[0]*b[0]+a[1]*b[1] != 0 or a[0]*b[1]-a[1]*b[0] != 1:
        raise ValueError("Mapping must have orthogonal signed-unit axes and determinant +1")
    expected_fixed = [[origin[i] + a[i]*x + b[i]*y for i in range(2)] for x, y in ((0, 0), (width, 0), (width, height), (0, height))]
    if fixed_px != expected_fixed or set(map(tuple, fixed_px)) != {(0, 0), (raw_size[0], 0), raw_size, (0, raw_size[1])}:
        raise ValueError("Mapping does not bijectively cover the complete raw framebuffer")
    if width * height != raw_size[0] * raw_size[1]:
        raise ValueError("Mapping discards or synthesizes framebuffer pixels")

    def measured_fixed(p):
        px, py = (p[0] - wr[0])*scale, (p[1] - wr[1])*scale
        return [fr[i] + (origin[i]+a[i]*px+b[i]*py)/scale for i in range(2)]

    samples = _points(m.get("sampleWindowPoints"), 3, "window samples")
    center = [wr[0]+wr[2]/2, wr[1]+wr[3]/2]
    expected_samples = [center, [center[0]+1/scale, center[1]], [center[0], center[1]+1/scale]]
    for p, expected, fp, rp in zip(samples, expected_samples,
            _points(m.get("sampleFixedPoints"), 3, "fixed samples"),
            _points(m.get("sampleRoundTripsInWindowPoints"), 3, "sample round trips")):
        _equal(p, expected, "pixel-step input sample")
        _equal(fp, measured_fixed(p), "independent pixel-step conversion")
        _equal(rp, p, "sample round trip")
    for p, expected, fp, rp in zip(_points(m.get("contentCornersInWindowPoints"), 4, "content corners"), _corners(cr),
            _points(m.get("contentCornersInFixedPoints"), 4, "fixed content corners"),
            _points(m.get("contentRoundTripsInWindowPoints"), 4, "content round trips")):
        _equal(p, expected, "content corner")
        _equal(fp, measured_fixed(p), "independent content conversion")
        _equal(rp, p, "content round trip")
        if any(p[i] < wr[i] and not _near(p[i], wr[i]) or p[i] > wr[i]+wr[i+2] and not _near(p[i], wr[i]+wr[i+2]) for i in range(2)):
            raise ValueError("Measured content lies outside the complete captured window")
    operations = {(1, 0, 0, 1): "identity", (0, -1, 1, 0): "quarterTurnClockwise",
                  (0, 1, -1, 0): "quarterTurnCounterclockwise", (-1, 0, 0, -1): "halfTurn"}
    return {"operation": operations[tuple(a+b)], "originPx": origin, "xAxis": a, "yAxis": b,
            "windowSizePx": list(expected_size), "rawSizePx": list(raw_size),
            "measuredMappingSha256": _sha(json.dumps(m, sort_keys=True, separators=(",", ":"), allow_nan=False).encode())}


def _exif(data: bytes, source_size: tuple[int, int], target_size: tuple[int, int]) -> bytes:
    """Preserve TIFF bytes/color tags; update only known scalar image dimensions."""
    if len(data) < 8 or data[:2] not in (b"II", b"MM"):
        raise ValueError("Unsupported PNG EXIF byte order/header")
    endian = "<" if data[:2] == b"II" else ">"
    def read(fmt, offset):
        n = struct.calcsize(endian+fmt)
        if offset < 0 or offset+n > len(data):
            raise ValueError("Truncated PNG EXIF structure")
        return struct.unpack_from(endian+fmt, data, offset)
    if read("H", 2)[0] != 42:
        raise ValueError("Unsupported PNG EXIF TIFF format")
    result = bytearray(data)
    pending = [read("I", 4)[0]]
    seen = set()
    widths = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}
    while pending:
        offset = pending.pop()
        if not offset or offset in seen or len(seen) >= 16:
            raise ValueError("Invalid/cyclic PNG EXIF directory")
        seen.add(offset)
        count = read("H", offset)[0]
        if count > 1024:
            raise ValueError("Unbounded PNG EXIF directory")
        for index in range(count):
            at = offset+2+index*12
            tag, kind, count_values, value = read("HHII", at)
            if kind not in widths or count_values > MAX_BYTES:
                raise ValueError("Unsupported PNG EXIF field")
            length = widths[kind]*count_values
            value_at = at+8 if length <= 4 else value
            if value_at+length > len(data):
                raise ValueError("Truncated PNG EXIF value")
            if tag in (0x8769, 0x8825, 0xA005):
                if kind != 4 or count_values != 1:
                    raise ValueError("Invalid PNG EXIF subdirectory")
                pending.append(value)
            if tag == 0x112:
                if kind != 3 or count_values != 1 or read("H", value_at)[0] != 1:
                    raise ValueError("PNG EXIF orientation must be 1; no hidden decoder transform")
            if tag in (0x100, 0x101, 0xA002, 0xA003):
                if kind not in (3, 4) or count_values != 1:
                    raise ValueError("Unsupported PNG EXIF dimension field")
                axis = 0 if tag in (0x100, 0xA002) else 1
                fmt = "H" if kind == 3 else "I"
                if read(fmt, value_at)[0] != source_size[axis]:
                    raise ValueError("PNG EXIF dimension disagrees with the raw PNG")
                struct.pack_into(endian+fmt, result, value_at, target_size[axis])
        next_offset = read("I", offset+2+count*12)[0]
        if next_offset:
            pending.append(next_offset)
    return bytes(result)


def _inflate(data: bytes, limit: int) -> bytes:
    decoder = zlib.decompressobj()
    try:
        result = decoder.decompress(data, limit+1)
    except zlib.error as error:
        raise ValueError("Invalid compressed PNG data") from error
    if len(result) > limit or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Truncated, trailing or oversized compressed PNG data")
    return result


def _parse_png(raw: bytes) -> dict:
    if not isinstance(raw, bytes) or not 20 <= len(raw) <= MAX_BYTES or raw[:8] != SIGNATURE:
        raise ValueError("Unsupported/oversized PNG input")
    chunks, pos, seen, idats = [], 8, set(), []
    ended = False
    idat_ended = False
    while pos < len(raw):
        if len(raw)-pos < 12:
            raise ValueError("Truncated PNG chunk")
        length = struct.unpack_from(">I", raw, pos)[0]
        name = raw[pos+4:pos+8]
        end = pos+12+length
        if end > len(raw) or any(not (65 <= c <= 90 or 97 <= c <= 122) for c in name) or name[2] & 32:
            raise ValueError("Invalid PNG chunk framing/type")
        data = raw[pos+8:pos+8+length]
        if zlib.crc32(name+data) & 0xFFFFFFFF != struct.unpack_from(">I", raw, pos+8+length)[0]:
            raise ValueError("PNG chunk CRC mismatch")
        if not chunks and name != b"IHDR":
            raise ValueError("PNG must begin with IHDR")
        if name not in ({b"IHDR", b"IDAT", b"IEND"} | ANCILLARY):
            raise ValueError(f"Unsupported PNG chunk/format: {name!r}")
        if name in seen and name not in (b"IDAT", b"tEXt", b"zTXt", b"iTXt"):
            raise ValueError("Duplicate singleton PNG chunk")
        if name == b"IHDR":
            if length != 13:
                raise ValueError("Invalid PNG IHDR")
            w, h, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", data)
            if not 0 < w <= 8192 or not 0 < h <= 8192 or w*h > MAX_PIXELS or (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise ValueError("Only bounded noninterlaced RGBA8 PNG pixels are supported")
        if name in COLOR_CHUNKS and idats:
            raise ValueError("PNG color interpretation must precede pixels")
        if name == b"sRGB" and (length != 1 or data[0] > 3):
            raise ValueError("Invalid PNG sRGB rendering intent")
        if name == b"gAMA" and (length != 4 or struct.unpack(">I", data)[0] == 0):
            raise ValueError("Invalid PNG gamma")
        if name == b"cHRM" and length != 32:
            raise ValueError("Invalid PNG chromaticities")
        if name == b"sBIT" and (length != 4 or any(not 1 <= v <= 8 for v in data)):
            raise ValueError("Invalid RGBA8 PNG significant bits")
        if name == b"iCCP":
            zero = data.find(b"\0")
            if not 1 <= zero <= 79 or zero+2 >= length or data[zero+1] != 0:
                raise ValueError("Invalid PNG ICC profile header")
            icc = _inflate(data[zero+2:], 16*1024*1024)
            if len(icc) < 128 or struct.unpack_from(">I", icc)[0] != len(icc) or icc[36:40] != b"acsp" or icc[16:20] != b"RGB ":
                raise ValueError("Unsupported/corrupt PNG RGB ICC profile")
        if name == b"pHYs" and (length != 9 or data[8] > 1):
            raise ValueError("Invalid PNG physical pixel metadata")
        if name == b"pHYs" and struct.unpack_from(">I", data)[0] != struct.unpack_from(">I", data, 4)[0]:
            raise ValueError("Nonsquare PNG physical pixels require unsupported aspect interpretation")
        if name == b"IDAT":
            if idat_ended:
                raise ValueError("PNG IDAT sequence is not contiguous")
            idats.append(data)
        elif idats:
            idat_ended = True
        chunks.append((name, data))
        seen.add(name)
        pos = end
        if name == b"IEND":
            if length or pos != len(raw) or not idats:
                raise ValueError("Invalid/trailing PNG end")
            ended = True
            break
    if not ended or b"sRGB" in seen and b"iCCP" in seen:
        raise ValueError("Incomplete PNG or conflicting sRGB/ICC interpretation")
    size = (w, h)
    for name, data in chunks:
        if name == b"eXIf":
            _exif(data, size, size)
    filtered = _inflate(b"".join(idats), h*(1+w*4))
    if len(filtered) != h*(1+w*4) or any(filtered[y*(1+w*4)] > 4 for y in range(h)):
        raise ValueError("Invalid PNG scanline/filter data")
    try:
        with Image.open(io.BytesIO(raw)) as image:
            if image.mode != "RGBA" or image.size != size or getattr(image, "n_frames", 1) != 1:
                raise ValueError("PNG decoder disagrees with validated RGBA8 format")
            rgba = image.tobytes()
    except (OSError, SyntaxError) as error:
        raise ValueError("PNG pixels cannot be decoded exactly") from error
    if len(rgba) != w*h*4:
        raise ValueError("Incomplete decoded RGBA8 pixels")
    return {"size": size, "rgba": rgba, "chunks": chunks}


def _chunk(name, data):
    return struct.pack(">I", len(data))+name+data+struct.pack(">I", zlib.crc32(name+data) & 0xFFFFFFFF)


def _permute(rgba: bytes, size: tuple[int, int], operation: str, inverse=False) -> bytes:
    # These are channel-byte permutations, not Image.rotate/resample. The choice
    # comes exclusively from the certified ordered point basis above.
    if operation == "identity":
        return rgba
    actions = {"quarterTurnClockwise": Image.Transpose.ROTATE_270,
               "quarterTurnCounterclockwise": Image.Transpose.ROTATE_90,
               "halfTurn": Image.Transpose.ROTATE_180}
    if inverse and operation != "halfTurn":
        operation = "quarterTurnClockwise" if operation == "quarterTurnCounterclockwise" else "quarterTurnCounterclockwise"
    return Image.frombytes("RGBA", size, rgba).transpose(actions[operation]).tobytes()


def _ancillary(chunks, source_size, target_size):
    return [(name, _exif(data, source_size, target_size) if name == b"eXIf" else data)
            for name, data in chunks if name not in (b"IHDR", b"IDAT", b"IEND")]


def _encode(source: dict, rgba: bytes, target_size: tuple[int, int]) -> bytes:
    w, h = target_size
    filtered = b"".join(b"\0"+rgba[y*w*4:(y+1)*w*4] for y in range(h))
    output = [SIGNATURE]
    wrote_idat = False
    for name, data in source["chunks"]:
        if name == b"IHDR":
            data = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
        elif name == b"IDAT":
            if wrote_idat:
                continue
            data = zlib.compress(filtered, 6)
            wrote_idat = True
        elif name == b"eXIf":
            data = _exif(data, source["size"], target_size)
        output.append(_chunk(name, data))
    return b"".join(output)


def _certificate(raw: bytes, window: bytes, source: dict, target: dict, geometry: dict) -> dict:
    expected_ancillary = _ancillary(source["chunks"], source["size"], target["size"])
    actual_ancillary = _ancillary(target["chunks"], target["size"], target["size"])
    if actual_ancillary != expected_ancillary:
        raise ValueError("Derivative changed PNG ancillary/color interpretation bytes")
    forward = _permute(source["rgba"], source["size"], geometry["operation"])
    if target["rgba"] != forward:
        raise ValueError("Derivative pixels are not the measured lossless permutation")
    inverse = _permute(target["rgba"], target["size"], geometry["operation"], inverse=True)
    if inverse != source["rgba"]:
        raise ValueError("Inverse permutation did not recover every original RGBA byte")
    return {"schemaVersion": 1, **geometry,
            "pixelFormat": "RGBA8", "pngOrientation": 1,
            "imageTransformed": geometry["operation"] != "identity",
            "rawSha256": _sha(raw), "windowSha256": _sha(window),
            "rawRgbaSha256": _sha(source["rgba"]), "windowRgbaSha256": _sha(target["rgba"]),
            "inverseRgbaSha256": _sha(inverse), "inversePixelsVerified": True,
            "rawAncillary": [{"type": n.decode("ascii"), "sha256": _sha(d)} for n, d in _ancillary(source["chunks"], source["size"], source["size"])],
            "windowAncillary": [{"type": n.decode("ascii"), "sha256": _sha(d)} for n, d in actual_ancillary],
            "colorMetadataPreserved": True,
            "exifGeometryPolicy": "Only scalar image dimensions updated; orientation1 and all color tags preserved",
            "roundingPolicy": "4 binary64 ULPs at operand magnitude (minimum1), no geometric tolerance"}


def map_framebuffer(raw_png: bytes, metadata: dict, expected_size: tuple[int, int]) -> tuple[bytes, dict]:
    source = _parse_png(raw_png)
    geometry = _geometry(metadata, source["size"], expected_size)
    rgba = _permute(source["rgba"], source["size"], geometry["operation"])
    window = raw_png if geometry["operation"] == "identity" else _encode(source, rgba, expected_size)
    target = source if window is raw_png else _parse_png(window)
    return window, _certificate(raw_png, window, source, target, geometry)


def verify_framebuffer_mapping(raw_png: bytes, window_png: bytes, metadata: dict, certificate: dict) -> dict:
    """Recompute geometry, channels, metadata and inverse proof, not PNG encoding.

    The supplied derivative can have been encoded on another host. Verification
    uses its actual bytes/hash and decoded pixels; it never regenerates a PNG
    and assumes zlib/encoder byte equality across hosts.
    """
    if not isinstance(certificate, dict) or type(certificate.get("schemaVersion")) is not int or certificate["schemaVersion"] != 1:
        raise ValueError("Unsupported framebuffer mapping certificate")
    source, target = _parse_png(raw_png), _parse_png(window_png)
    geometry = _geometry(metadata, source["size"], target["size"])
    if geometry["operation"] == "identity" and raw_png != window_png:
        raise ValueError("Identity capture must preserve original PNG bytes")
    actual = _certificate(raw_png, window_png, source, target, geometry)
    if certificate != actual:
        raise ValueError("Framebuffer mapping certificate does not match independent proof")
    return actual
