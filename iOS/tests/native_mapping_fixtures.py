"""Synthetic measured-coordinate inputs shared by extractor integration tests."""
from copy import deepcopy
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import json
import struct
import sys

from PIL import Image, PngImagePlugin

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from framebuffer_coordinates import map_framebuffer


def mapped_files(png_path, geometry_path, raw_path, operation="clockwise"):
    metadata = json.loads(geometry_path.read_text(encoding="utf-8"))
    width, height = metadata["capturedWindowWidthPx"], metadata["capturedWindowHeightPx"]
    scale = metadata["displayScale"]
    raw_size = (height, width) if operation in ("clockwise", "counterclockwise") else (width, height)
    origin, a, b = {
        "identity": ([0, 0], [1, 0], [0, 1]),
        "clockwise": ([0, width], [0, -1], [1, 0]),
        "counterclockwise": ([height, 0], [0, 1], [-1, 0]),
    }[operation]
    window, content = metadata["windowBoundsPoints"], metadata["contentBoundsInWindowPoints"]
    def corners(rect):
        x, y, w, h = rect
        return [[x, y], [x+w, y], [x+w, y+h], [x, y+h]]
    def convert(point):
        x, y = (point[0]-window[0])*scale, (point[1]-window[1])*scale
        return [(origin[i]+a[i]*x+b[i]*y)/scale for i in range(2)]
    center = [window[0]+window[2]/2, window[1]+window[3]/2]
    samples = [center, [center[0]+1/scale, center[1]], [center[0], center[1]+1/scale]]
    mapping = {"schemaVersion": 1, "cornerOrder": ["TL", "TR", "BR", "BL"],
        "windowBoundsPoints": window, "contentBoundsInWindowPoints": content,
        "fixedBoundsPoints": [0, 0, raw_size[0]/scale, raw_size[1]/scale],
        "screenNativeBoundsPixels": [0, 0, *raw_size], "displayScale": scale, "nativeDisplayScale": scale,
        "windowCornersInWindowPoints": corners(window), "windowCornersInFixedPoints": list(map(convert, corners(window))),
        "roundTripsInWindowPoints": corners(window), "contentCornersInWindowPoints": corners(content),
        "contentCornersInFixedPoints": list(map(convert, corners(content))), "contentRoundTripsInWindowPoints": corners(content),
        "sampleOrder": ["center", "centerPlusOnePixelX", "centerPlusOnePixelY"],
        "sampleWindowPoints": samples, "sampleFixedPoints": list(map(convert, samples)),
        "sampleRoundTripsInWindowPoints": deepcopy(samples)}
    metadata.update(fixedCoordinateMapping=deepcopy(mapping), fixedCoordinateMappingAfterCapture=deepcopy(mapping),
                    fixedCoordinateMappingStableDuringCapture=True, screenNativeBoundsPixels=[0, 0, *raw_size])
    pixels = Image.new("RGBA", raw_size)
    for y in range(raw_size[1]):
        for x in range(raw_size[0]):
            pixels.putpixel((x, y), ((x*11) % 256, (y*7) % 256, ((x+y)*3) % 256, ((x+y)*5) % 256))
    info = PngImagePlugin.PngInfo()
    info.add(b"gAMA", struct.pack(">I", 45455))
    encoded = BytesIO()
    pixels.save(encoded, format="PNG", pnginfo=info)
    raw = encoded.getvalue()
    window_png, certificate = map_framebuffer(raw, metadata, (width, height))
    metadata["captureSurface"] = "simctl framebuffer with XCTest-driven UIKit window; certified synthetic coordinate mapping"
    metadata["nativeCapture"] = {"schemaVersion": 1, "status": "captured", "source": "simctl framebuffer",
        "widthPx": width, "heightPx": height, "pngOrientation": 1, "sha256": sha256(window_png).hexdigest(),
        "rawWidthPx": raw_size[0], "rawHeightPx": raw_size[1], "rawSha256": sha256(raw).hexdigest(),
        "imageTransformed": operation != "identity", "coordinateMapping": certificate}
    png_path.write_bytes(window_png)
    raw_path.write_bytes(raw)
    geometry_path.write_text(json.dumps(metadata), encoding="utf-8")
    return metadata
