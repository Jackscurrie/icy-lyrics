"""Synthetic, small geometry tests; no files here constitute native capture evidence."""
from hashlib import sha256
from pathlib import Path
import json
import struct
import tempfile
import unittest

from PIL import Image, ImageCms, PngImagePlugin
from extract_native_profile import SP_SAMPLE_SIZES, extract, validate_native_geometry


class NativeProfileExtraction(unittest.TestCase):
    def geometry(self):
        return {"ready": True, "captureSurface": "XCTest application window; separate from Android Compose-root capture",
                "scenario": "portrait", "locale": "en_US", "timezone": "America/Los_Angeles",
                "preferredContentSizeCategory": "UICTContentSizeCategoryL", "requestedLargeText": False,
                "libraryDateText": "Sep 3, 2026 at 5:00\u202fAM", "displayScale": 2, "nativeDisplayScale": 2,
                "composeDensity": 2, "fontScale": 1, "contentWidthPx": 16, "contentHeightPx": 22,
                "capturedWindowWidthPx": 20, "capturedWindowHeightPx": 30,
                "windowBoundsPoints": [0, 0, 10, 15], "contentBoundsInWindowPoints": [1, 2, 8, 11],
                "safeDrawingInsetsPx": [1, 2, 1, 2], "contentSafeAreaInsetsPoints": [0.5, 1, 0.5, 1],
                "windowSafeAreaInsetsPoints": [0, 1, 0, 1], "interfaceOrientationRawValue": 1,
                "requestedDeviceOrientationRawValue": 1, "expectedInterfaceOrientationRawValue": 1,
                "settleDelayAfterDrawSeconds": 2}

    def input_files(self, directory, metadata=None):
        png, geometry = directory / "native.png", directory / "native-geometry.json"
        image = Image.new("RGBA", (20, 30))
        for y in range(30):
            for x in range(20):
                image.putpixel((x, y), (x * 11, y * 7, (x + y) * 3, (x + y) * 5))
        info = PngImagePlugin.PngInfo()
        info.add(b"gAMA", struct.pack(">I", 45455))
        image.save(png, pnginfo=info)
        geometry.write_text(json.dumps(metadata or self.geometry(), ensure_ascii=False), encoding="utf-8")
        return png, geometry

    def test_both_crops_preserve_exact_pixel_subsets_and_reference_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, metadata = self.input_files(directory)
            original_png, original_metadata = png.read_bytes(), metadata.read_bytes()
            output = directory / "extracted"
            result = extract(png, metadata, output, safe_area_interior=True)
            self.assertEqual([2, 4, 18, 26], result["crops"][0]["sourceRectPx"])
            self.assertEqual([3, 6, 17, 24], result["crops"][1]["sourceRectPx"])
            with Image.open(png) as original:
                for record in result["crops"]:
                    with Image.open(record["path"]) as cropped:
                        self.assertEqual(original.crop(record["sourceRectPx"]).tobytes(), cropped.tobytes())
                        self.assertEqual(original.info["gamma"], cropped.info["gamma"])
                    self.assertEqual(record["sha256"], sha256(Path(record["path"]).read_bytes()).hexdigest())
            self.assertEqual(original_png, png.read_bytes())
            self.assertEqual(original_metadata, metadata.read_bytes())
            self.assertEqual(sha256(original_png).hexdigest(), result["sourceReferences"]["nativePng"]["sha256"])
            self.assertEqual(str(png.resolve()), result["sourceReferences"]["nativePng"]["path"])
            self.assertEqual("RGBA", result["sourcePngColor"]["mode"])
            self.assertEqual(0.45455, result["sourcePngColor"]["gamma"])
            self.assertEqual([{"type": "gAMA", "dataHex": "0000b18f"}], result["sourcePngColor"]["colorChunks"])
            profile = json.loads((output / "android-viewport-profile.json").read_text(encoding="utf-8"))
            self.assertEqual({"wmSize": "16x22", "wmDensityDpi": 320, "fontScale": 1}, profile["androidSettings"])
            self.assertEqual([1, 2, 1, 2], profile["safeDrawingInsetsPx"])
            self.assertEqual("portrait", profile["orientation"])
            self.assertEqual("Sep 3, 2026 at 5:00\u202fAM", profile["nativeLibraryDateText"])
            self.assertEqual("pending", profile["nativeFontScaling"]["scalingEquivalence"])
            self.assertIsNone(profile["nativeFontScaling"]["spToPxObservations"])
            self.assertEqual("pending native samples", profile["nativeFontScaling"]["comparisonReadiness"])
            self.assertFalse(profile["nativeFontScaling"]["nativeObservationsComplete"])
            self.assertFalse(profile["appearanceParityVerified"])
            self.assertFalse(profile["nativeTiming"]["deterministicClockMatched"])

    def test_native_font_samples_are_preserved_without_deriving_them_from_font_scale(self):
        # Deliberately nonlinear synthetic observations prove extraction retains
        # actual measurements instead of calculating size*density*fontScale.
        samples = {str(size): size * 2 + 0.25 for size in SP_SAMPLE_SIZES}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            metadata = self.geometry() | {"spToPx": samples, "fontScale": 1.5, "requestedLargeText": True}
            png, geometry = self.input_files(directory, metadata)
            result = extract(png, geometry, directory / "out")
            profile = json.loads((directory / "out/android-viewport-profile.json").read_text(encoding="utf-8"))
            scaling = profile["nativeFontScaling"]
            self.assertEqual(samples, scaling["spToPxObservations"])
            self.assertEqual(samples, result["nativeMetadata"]["spToPx"])
            self.assertTrue(scaling["nativeObservationsComplete"])
            self.assertEqual("native samples recorded; awaiting matching Android observations", scaling["comparisonReadiness"])
            self.assertEqual("pending", scaling["scalingEquivalence"])
            self.assertFalse(scaling["fontShapingParityVerified"])

    def test_present_native_font_samples_must_be_complete_positive_finite_numbers(self):
        samples = {str(size): float(size * 2) for size in SP_SAMPLE_SIZES}
        invalid = [None, {}, {key: value for key, value in samples.items() if key != "64"},
                   samples | {"13": 26}, samples | {"12": True}, samples | {"12": "24"},
                   samples | {"12": 0}, samples | {"12": -1},
                   samples | {"12": float("nan")}, samples | {"12": float("inf")}]
        for value in invalid:
            with self.subTest(samples=value), self.assertRaisesRegex(ValueError, "spToPx"):
                validate_native_geometry(self.geometry() | {"spToPx": value}, (20, 30))

    def test_icc_profile_is_preserved_and_its_hash_is_recorded(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry = self.input_files(directory)
            icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
            with Image.open(png) as image:
                pixels = image.copy()
            pixels.save(png, icc_profile=icc)
            result = extract(png, geometry, directory / "out", safe_area_interior=True)
            self.assertEqual(sha256(icc).hexdigest(), result["sourcePngColor"]["iccProfileSha256"])
            for crop in result["crops"]:
                with Image.open(crop["path"]) as image:
                    self.assertEqual(icc, image.info["icc_profile"])
                self.assertEqual(result["sourcePngColor"], crop["pngColor"])

    def test_whole_window_content_keeps_original_png_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            metadata = self.geometry() | {"contentBoundsInWindowPoints": [0, 0, 10, 15],
                                          "contentWidthPx": 20, "contentHeightPx": 30}
            png, geometry = self.input_files(directory, metadata)
            extract(png, geometry, directory / "out")
            self.assertEqual(png.read_bytes(), (directory / "out/full-content.png").read_bytes())
            self.assertFalse((directory / "out/safe-area-interior.png").exists())

    def test_fractional_outside_or_inconsistent_rectangles_are_rejected(self):
        changes = [{"contentBoundsInWindowPoints": [0.25, 2, 8, 11]},
                   {"contentBoundsInWindowPoints": [-1, 2, 8, 11]},
                   {"contentBoundsInWindowPoints": [3, 2, 8, 11]},
                   {"contentBoundsInWindowPoints": [1, 5, 8, 11]},
                   {"contentBoundsInWindowPoints": [1, 2, 8.5, 11]},
                   {"windowBoundsPoints": [0, 0, 10.5, 15]},
                   {"contentWidthPx": 15}, {"capturedWindowHeightPx": 29}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_geometry(self.geometry() | change, (20, 30))

    def test_nonzero_window_bounds_origin_is_used(self):
        metadata = self.geometry() | {"windowBoundsPoints": [5, 6, 10, 15],
                                      "contentBoundsInWindowPoints": [6, 8, 8, 11]}
        self.assertEqual([2, 4, 18, 26], validate_native_geometry(metadata, (20, 30))["fullContentRectPx"])

    def test_invalid_density_font_scale_and_sizes_are_rejected(self):
        changes = [{"displayScale": 0}, {"composeDensity": 3}, {"composeDensity": True},
                   {"fontScale": float("nan")}, {"fontScale": float("inf")}, {"fontScale": -1},
                   {"nativeDisplayScale": 0}, {"contentWidthPx": 0}, {"contentHeightPx": True},
                   {"displayScale": 2.001, "composeDensity": 2.001}, {"requestedLargeText": True}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_geometry(self.geometry() | change, (20, 30))

    def test_insets_must_agree_and_leave_positive_interior(self):
        changes = [{"safeDrawingInsetsPx": [1, -1, 1, 2]}, {"safeDrawingInsetsPx": [1, 2.0, 1, 2]},
                   {"safeDrawingInsetsPx": [1, 3, 1, 2]}, {"safeDrawingInsetsPx": None},
                   {"contentSafeAreaInsetsPoints": [0.25, 1, 0.5, 1]},
                   {"safeDrawingInsetsPx": [8, 2, 8, 2], "contentSafeAreaInsetsPoints": [4, 1, 4, 1]},
                   {"windowSafeAreaInsetsPoints": [0, 15, 0, 1]}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_geometry(self.geometry() | change, (20, 30))

    def test_unready_wrong_backend_and_wrong_orientation_are_rejected(self):
        changes = [{"ready": False}, {"captureSurface": "skia-raster"},
                   {"expectedInterfaceOrientationRawValue": 4}, {"requestedDeviceOrientationRawValue": 4},
                   {"interfaceOrientationRawValue": 4, "expectedInterfaceOrientationRawValue": 4,
                    "requestedDeviceOrientationRawValue": 3}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_geometry(self.geometry() | change, (20, 30))

    def test_landscape_device_and_interface_names_differ_but_raw_values_match(self):
        for raw in (3, 4):
            metadata = self.geometry() | {"windowBoundsPoints": [0, 0, 15, 10],
                "contentBoundsInWindowPoints": [2, 1, 11, 8], "contentWidthPx": 22, "contentHeightPx": 16,
                "capturedWindowWidthPx": 30, "capturedWindowHeightPx": 20,
                "interfaceOrientationRawValue": raw, "expectedInterfaceOrientationRawValue": raw,
                "requestedDeviceOrientationRawValue": raw}
            self.assertEqual([4, 2, 26, 18], validate_native_geometry(metadata, (30, 20))["fullContentRectPx"])
            with self.assertRaises(ValueError):
                validate_native_geometry(metadata | {"requestedDeviceOrientationRawValue": 7 - raw}, (30, 20))

    def test_invalid_capture_produces_no_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry = self.input_files(directory, self.geometry() | {"capturedWindowWidthPx": 21})
            with self.assertRaises(ValueError):
                extract(png, geometry, directory / "out")
            self.assertFalse((directory / "out").exists())

    def test_existing_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry = self.input_files(directory)
            with self.assertRaisesRegex(ValueError, "not be overwritten"):
                extract(png, geometry, directory)
            self.assertTrue(png.exists())

    def test_pixel_formats_that_require_conversion_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry = self.input_files(directory)
            Image.new("I;16", (20, 30), 32769).save(png)
            with self.assertRaisesRegex(ValueError, "without pixel conversion"):
                extract(png, geometry, directory / "out")


if __name__ == "__main__":
    unittest.main()
