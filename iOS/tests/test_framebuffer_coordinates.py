"""Synthetic byte/coordinate proofs; these are not native visual acceptance."""
from copy import deepcopy
import hashlib
import importlib.util
import io
import math
from pathlib import Path
import struct
import unittest
import zlib

from PIL import Image

SPEC = importlib.util.spec_from_file_location("coordinates", Path(__file__).resolve().parents[1]/"scripts/framebuffer_coordinates.py")
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


def exif(size):
    # Actual simctl TIFF field arrangement: ExifIFD, ColorSpace, PixelX/YDimension.
    data = bytearray.fromhex("4d4d002a00000008000187690004000000010000001a000000000003a00100030000000100010000a0020004000000010000049ba003000400000001000009fc00000000")
    struct.pack_into(">I", data, 48, size[0])
    struct.pack_into(">I", data, 60, size[1])
    return bytes(data)


def png(size=(2, 3), rgba=None, extras=None, compression=6):
    w, h = size
    rgba = bytes((i*17+3) % 256 for i in range(w*h*4)) if rgba is None else rgba
    extras = [(b"sRGB", b"\0"), (b"eXIf", exif(size))] if extras is None else extras
    scan = b"".join(b"\0"+rgba[y*w*4:(y+1)*w*4] for y in range(h))
    return module.SIGNATURE + module._chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + b"".join(module._chunk(*c) for c in extras) + module._chunk(b"IDAT", zlib.compress(scan, compression)) + module._chunk(b"IEND", b"")


def measurements(operation="clockwise", scale=3):
    raw = (2, 3)
    origin, a, b, size = {
        "identity": ([0, 0], [1, 0], [0, 1], raw),
        "clockwise": ([0, 3], [0, -1], [1, 0], (3, 2)),
        "counterclockwise": ([2, 0], [0, 1], [-1, 0], (3, 2)),
        "half": ([2, 3], [-1, 0], [0, -1], raw),
    }[operation]
    wr = [0, 0, size[0]/scale, size[1]/scale]
    corners = module._corners(wr)
    def convert(p):
        return [(origin[i]+a[i]*p[0]*scale+b[i]*p[1]*scale)/scale for i in range(2)]
    center = [wr[2]/2, wr[3]/2]
    samples = [center, [center[0]+1/scale, center[1]], [center[0], center[1]+1/scale]]
    m = {"schemaVersion": 1, "cornerOrder": ["TL", "TR", "BR", "BL"],
         "windowBoundsPoints": wr, "contentBoundsInWindowPoints": wr[:],
         "fixedBoundsPoints": [0, 0, raw[0]/scale, raw[1]/scale],
         "screenNativeBoundsPixels": [0, 0, *raw], "displayScale": scale, "nativeDisplayScale": scale,
         "windowCornersInWindowPoints": corners, "windowCornersInFixedPoints": list(map(convert, corners)),
         "roundTripsInWindowPoints": deepcopy(corners), "contentCornersInWindowPoints": deepcopy(corners),
         "contentCornersInFixedPoints": list(map(convert, corners)), "contentRoundTripsInWindowPoints": deepcopy(corners),
         "sampleOrder": ["center", "centerPlusOnePixelX", "centerPlusOnePixelY"],
         "sampleWindowPoints": samples, "sampleFixedPoints": list(map(convert, samples)),
         "sampleRoundTripsInWindowPoints": deepcopy(samples)}
    metadata = {"fixedCoordinateMapping": m}
    for key in ("windowBoundsPoints", "contentBoundsInWindowPoints", "screenNativeBoundsPixels", "displayScale", "nativeDisplayScale"):
        metadata[key] = deepcopy(m[key])
    return metadata, size


class FramebufferCoordinatesTest(unittest.TestCase):
    def test_both_quarter_turns_are_exact_measured_pixel_permutations(self):
        raw = png()
        source = module._parse_png(raw)["rgba"]
        # Independent explicit source indices in each output row, not a second
        # invocation of the implementation's transpose selection.
        for operation, indices in [("clockwise", [4, 2, 0, 5, 3, 1]), ("counterclockwise", [1, 3, 5, 0, 2, 4])]:
            with self.subTest(operation=operation):
                metadata, size = measurements(operation)
                window, cert = module.map_framebuffer(raw, metadata, size)
                expected = b"".join(source[i*4:i*4+4] for i in indices)
                self.assertEqual(module._parse_png(window)["rgba"], expected)
                self.assertEqual(cert["inverseRgbaSha256"], hashlib.sha256(source).hexdigest())
                self.assertTrue(cert["inversePixelsVerified"])
                self.assertTrue(cert["imageTransformed"])
                self.assertEqual(module.verify_framebuffer_mapping(raw, window, metadata, cert), cert)
                self.assertEqual(module._parse_png(raw)["size"], (2, 3))

    def test_identity_preserves_every_original_png_byte(self):
        raw = png(compression=0)
        metadata, size = measurements("identity")
        result, cert = module.map_framebuffer(raw, metadata, size)
        self.assertIs(result, raw)
        self.assertEqual(cert["operation"], "identity")
        self.assertFalse(cert["imageTransformed"])
        module.verify_framebuffer_mapping(raw, raw, metadata, cert)
        with self.assertRaisesRegex(ValueError, "Identity"):
            module.verify_framebuffer_mapping(raw, png(compression=9), metadata, cert)

    def test_half_turn_is_measured_not_inferred_from_orientation(self):
        metadata, size = measurements("half")
        metadata["interfaceOrientation"] = 1  # Deliberately irrelevant enum.
        raw = png()
        result, cert = module.map_framebuffer(raw, metadata, size)
        data = module._parse_png(raw)["rgba"]
        self.assertEqual(module._parse_png(result)["rgba"], b"".join(data[i*4:i*4+4] for i in range(5, -1, -1)))
        self.assertEqual(cert["operation"], "halfTurn")

    def test_srgb_and_exif_color_preserved_dimensions_updated_only(self):
        raw = png()
        metadata, size = measurements()
        result, cert = module.map_framebuffer(raw, metadata, size)
        old = dict(module._parse_png(raw)["chunks"])
        new = dict(module._parse_png(result)["chunks"])
        self.assertEqual(new[b"sRGB"], old[b"sRGB"])
        self.assertEqual(new[b"eXIf"], exif(size))
        old_exif = bytearray(old[b"eXIf"])
        old_exif[48:52] = new[b"eXIf"][48:52]
        old_exif[60:64] = new[b"eXIf"][60:64]
        self.assertEqual(bytes(old_exif), new[b"eXIf"])
        self.assertTrue(cert["colorMetadataPreserved"])

    def test_gamma_chromaticities_and_significant_bits_are_preserved(self):
        extra = [(b"gAMA", struct.pack(">I", 45455)), (b"cHRM", struct.pack(">8I", 31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)), (b"sBIT", bytes([8]*4))]
        raw = png(extras=extra)
        metadata, size = measurements()
        result, _ = module.map_framebuffer(raw, metadata, size)
        self.assertEqual(module._ancillary(module._parse_png(result)["chunks"], size, size), extra)

    def test_encoder_compression_difference_does_not_break_independent_proof(self):
        raw = png()
        metadata, size = measurements()
        window, cert = module.map_framebuffer(raw, metadata, size)
        parsed = module._parse_png(window)
        alternative = png(size, parsed["rgba"], module._ancillary(parsed["chunks"], size, size), compression=0)
        self.assertNotEqual(alternative, window)
        cert["windowSha256"] = hashlib.sha256(alternative).hexdigest()
        module.verify_framebuffer_mapping(raw, alternative, metadata, cert)

    def test_tampered_pixel_and_tampered_certificate_are_rejected(self):
        raw = png()
        metadata, size = measurements()
        window, cert = module.map_framebuffer(raw, metadata, size)
        parsed = module._parse_png(window)
        altered = bytearray(parsed["rgba"]); altered[3] ^= 1
        changed = png(size, bytes(altered), module._ancillary(parsed["chunks"], size, size))
        forged = deepcopy(cert); forged["windowSha256"] = hashlib.sha256(changed).hexdigest()
        with self.assertRaisesRegex(ValueError, "pixels"):
            module.verify_framebuffer_mapping(raw, changed, metadata, forged)
        forged = deepcopy(cert); forged["inversePixelsVerified"] = False
        with self.assertRaisesRegex(ValueError, "certificate"):
            module.verify_framebuffer_mapping(raw, window, metadata, forged)

    def test_missing_unordered_skew_mirror_fractional_and_wrong_scale_rejected(self):
        mutations = [
            lambda m: m.pop("sampleFixedPoints"),
            lambda m: m.update(cornerOrder=["TR", "TL", "BR", "BL"]),
            lambda m: m["windowCornersInFixedPoints"][1].__setitem__(0, 0.25),
            lambda m: m["windowCornersInFixedPoints"][0].__setitem__(1, 0.5),
            lambda m: m.update(nativeDisplayScale=2),
            lambda m: m["sampleFixedPoints"][1].__setitem__(0, 0.25),
            lambda m: m["sampleRoundTripsInWindowPoints"][2].__setitem__(1, 0),
            lambda m: m.update(windowCornersInFixedPoints=list(reversed(m["windowCornersInFixedPoints"]))),
            lambda m: m["contentCornersInFixedPoints"][1].__setitem__(0, 0.5),
            lambda m: m.update(displayScale=float("nan")),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                metadata, size = measurements()
                mutate(metadata["fixedCoordinateMapping"])
                with self.assertRaises(ValueError): module.map_framebuffer(png(), metadata, size)

    def test_partial_window_or_raw_size_mismatch_rejected(self):
        metadata, size = measurements()
        metadata["fixedCoordinateMapping"]["screenNativeBoundsPixels"][2] = 4
        with self.assertRaises(ValueError): module.map_framebuffer(png(), metadata, size)
        metadata, size = measurements()
        with self.assertRaises(ValueError): module.map_framebuffer(png(), metadata, (2, 2))

    def test_contradictory_top_level_geometry_rejected(self):
        for key in ("windowBoundsPoints", "contentBoundsInWindowPoints", "screenNativeBoundsPixels", "displayScale", "nativeDisplayScale"):
            with self.subTest(key=key):
                metadata, size = measurements()
                if isinstance(metadata[key], list): metadata[key][2] += 1
                else: metadata[key] += 1
                with self.assertRaisesRegex(ValueError, "top-level"):
                    module.map_framebuffer(png(), metadata, size)

    def test_arithmetic_allowance_is_machine_ulps_not_pixel_tolerance(self):
        metadata, size = measurements()
        p = metadata["fixedCoordinateMapping"]["sampleFixedPoints"][0]
        p[0] = math.nextafter(p[0], math.inf)
        module.map_framebuffer(png(), metadata, size)
        metadata, size = measurements()
        p = metadata["fixedCoordinateMapping"]["sampleFixedPoints"][0]
        p[0] += 5 * math.ulp(1.0)
        with self.assertRaisesRegex(ValueError, "pixel-step"):
            module.map_framebuffer(png(), metadata, size)

    def test_stability_attestation_checked_when_present(self):
        metadata, size = measurements()
        metadata["fixedCoordinateMappingAfterCapture"] = deepcopy(metadata["fixedCoordinateMapping"])
        metadata["fixedCoordinateMappingStableDuringCapture"] = True
        raw = png(); window, cert = module.map_framebuffer(raw, metadata, size)
        module.verify_framebuffer_mapping(raw, window, metadata, cert)
        metadata["fixedCoordinateMappingAfterCapture"]["displayScale"] = 4
        with self.assertRaisesRegex(ValueError, "changed during"):
            module.verify_framebuffer_mapping(raw, window, metadata, cert)

    def test_crc_truncation_trailing_bad_filter_and_unknown_format_rejected(self):
        good = png()
        corrupt = bytearray(good); corrupt[45] ^= 1
        unsupported = module.SIGNATURE+module._chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 3, 8, 2, 0, 0, 0))+good[33:]
        interlace = module.SIGNATURE+module._chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 3, 8, 6, 0, 0, 1))+good[33:]
        unknown = good[:33]+module._chunk(b"cICP", b"\1\1\0\1")+good[33:]
        bad_filter = module.SIGNATURE+good[8:33]+module._chunk(b"IDAT", zlib.compress(b"\5"+b"\0"*26))+module._chunk(b"IEND", b"")
        metadata, size = measurements()
        for raw in (bytes(corrupt), good[:-4], good+b"trailing", unsupported, interlace, unknown, bad_filter):
            with self.subTest(raw=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(ValueError): module.map_framebuffer(raw, metadata, size)

    def test_color_corruption_and_metadata_tampering_rejected(self):
        metadata, size = measurements()
        for extras in ([(b"sRGB", b"\4")], [(b"gAMA", bytes(4))], [(b"iCCP", b"profile\0\0bad-zlib")], [(b"sBIT", b"\0\10\10\10")]):
            with self.subTest(extras=extras):
                with self.assertRaises(ValueError): module.map_framebuffer(png(extras=extras), metadata, size)
        raw = png(); result, cert = module.map_framebuffer(raw, metadata, size)
        parsed = module._parse_png(result)
        extras = module._ancillary(parsed["chunks"], size, size)
        extras[0] = (b"sRGB", b"\1")
        changed = png(size, parsed["rgba"], extras)
        with self.assertRaisesRegex(ValueError, "color interpretation"):
            module.verify_framebuffer_mapping(raw, changed, metadata, cert)

    def test_nondefault_exif_orientation_and_incorrect_exif_dimensions_rejected(self):
        orientation = b"MM\x00*\0\0\0\10\0\1" + struct.pack(">HHI", 0x112, 3, 1)+b"\0\6\0\0"+bytes(4)
        metadata, size = measurements()
        for data in (orientation, exif((8, 9)), exif((2, 3))[:-1]):
            with self.subTest(data=data.hex()):
                with self.assertRaises(ValueError): module.map_framebuffer(png(extras=[(b"eXIf", data)]), metadata, size)


if __name__ == "__main__":
    unittest.main()
