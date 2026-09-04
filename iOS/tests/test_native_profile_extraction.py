"""Synthetic, small geometry tests; no files here constitute native capture evidence."""
from hashlib import sha256
from pathlib import Path
import json
import struct
import tempfile
import unittest

from PIL import Image, ImageCms, PngImagePlugin
from extract_native_profile import SP_SAMPLE_SIZES, extract, validate_native_geometry
from native_mapping_fixtures import mapped_files


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

    def mapped_input(self, directory, operation="clockwise"):
        png, geometry = self.input_files(directory)
        raw = directory / "original-framebuffer.png"
        metadata = mapped_files(png, geometry, raw, operation)
        return png, geometry, raw, metadata

    def test_measured_mapping_independently_verifies_and_retains_both_originals(self):
        for operation in ("clockwise", "counterclockwise"):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                png, geometry, raw, metadata = self.mapped_input(directory, operation)
                original, window = raw.read_bytes(), png.read_bytes()
                result = extract(png, geometry, directory / "out", raw_framebuffer_path=raw)
                mapping = result["nativeCoordinateMapping"]
                self.assertTrue(mapping["independentlyVerified"])
                self.assertTrue(mapping["rawAttachmentRequired"])
                self.assertTrue(mapping["certificate"]["inversePixelsVerified"])
                self.assertEqual(metadata["nativeCapture"]["coordinateMapping"], mapping["certificate"])
                self.assertEqual(original, Path(mapping["rawCopy"]).read_bytes())
                self.assertEqual(window, Path(mapping["windowCopy"]).read_bytes())
                self.assertEqual(original, raw.read_bytes())
                self.assertEqual(window, png.read_bytes())
                self.assertEqual(sha256(original).hexdigest(), result["sourceReferences"]["nativeRawFramebuffer"]["sha256"])
                with Image.open(png) as image, Image.open(directory / "out/full-content.png") as crop:
                    self.assertEqual(image.crop((2, 4, 18, 26)).tobytes(), crop.tobytes())
                profile = json.loads((directory / "out/android-viewport-profile.json").read_text())
                self.assertEqual(mapping, profile["nativeCoordinateMapping"])
                self.assertFalse(profile["appearanceParityVerified"])

    def test_measured_identity_reuses_same_original_without_second_attachment(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry, raw, _ = self.mapped_input(directory, "identity")
            self.assertEqual(raw.read_bytes(), png.read_bytes())
            result = extract(png, geometry, directory / "out")
            mapping = result["nativeCoordinateMapping"]
            self.assertFalse(mapping["imageTransformed"])
            self.assertFalse(mapping["rawAttachmentRequired"])
            self.assertEqual("identity", mapping["certificate"]["operation"])
            self.assertEqual(str(png.resolve()), result["sourceReferences"]["nativeRawFramebuffer"]["path"])

    def test_transformed_missing_raw_is_rejected_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry, _, _ = self.mapped_input(directory)
            with self.assertRaisesRegex(ValueError, "original raw-framebuffer"):
                extract(png, geometry, directory / "out")
            self.assertFalse((directory / "out").exists())

    def test_dangling_new_format_fields_cannot_take_legacy_capture_path(self):
        for field in ("fixedCoordinateMapping", "fixedCoordinateMappingAfterCapture",
                      "fixedCoordinateMappingStableDuringCapture", "rawSha256", "rawWidthPx", "rawHeightPx"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                png, geometry, _, metadata = self.mapped_input(directory, "identity")
                mapping_value = metadata.get(field, metadata["nativeCapture"].get(field))
                for key in ("fixedCoordinateMapping", "fixedCoordinateMappingAfterCapture", "fixedCoordinateMappingStableDuringCapture"):
                    metadata.pop(key)
                for key in ("coordinateMapping", "rawSha256", "rawWidthPx", "rawHeightPx"):
                    metadata["nativeCapture"].pop(key)
                target = metadata["nativeCapture"] if field.startswith("raw") else metadata
                target[field] = mapping_value
                geometry.write_text(json.dumps(metadata), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "certificate"):
                    extract(png, geometry, directory / "out")
                self.assertFalse((directory / "out").exists())

    def test_mapping_flags_certificate_and_stable_measurements_are_required(self):
        mutations = [
            lambda m: m["nativeCapture"].pop("coordinateMapping"),
            lambda m: m["nativeCapture"].pop("imageTransformed"),
            lambda m: m["nativeCapture"].update(imageTransformed="true"),
            lambda m: m["nativeCapture"].update(imageTransformed=False),
            lambda m: m["nativeCapture"]["coordinateMapping"].update(inversePixelsVerified=False),
            lambda m: m.pop("fixedCoordinateMappingAfterCapture"),
            lambda m: m.update(fixedCoordinateMappingStableDuringCapture=False),
            lambda m: m["fixedCoordinateMappingAfterCapture"].update(displayScale=3),
            lambda m: m["fixedCoordinateMappingAfterCapture"].update(schemaVersion=True),
            lambda m: m.update(screenNativeBoundsPixels=[0, 0, 31, 20]),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                png, geometry, raw, metadata = self.mapped_input(directory)
                mutate(metadata)
                geometry.write_text(json.dumps(metadata), encoding="utf-8")
                with self.assertRaises(ValueError):
                    extract(png, geometry, directory / "out", raw_framebuffer_path=raw)
                self.assertFalse((directory / "out").exists())

    def test_forged_window_pixel_with_updated_hashes_cannot_pass_mapping_proof(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry, raw, metadata = self.mapped_input(directory)
            with Image.open(png) as image:
                altered = image.copy()
            altered.putpixel((3, 4), (255, 1, 2, 3))
            info = PngImagePlugin.PngInfo()
            info.add(b"gAMA", struct.pack(">I", 45455))
            altered.save(png, pnginfo=info)
            forged_hash = sha256(png.read_bytes()).hexdigest()
            metadata["nativeCapture"]["sha256"] = forged_hash
            metadata["nativeCapture"]["coordinateMapping"]["windowSha256"] = forged_hash
            geometry.write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "pixels"):
                extract(png, geometry, directory / "out", raw_framebuffer_path=raw)
            self.assertFalse((directory / "out").exists())

    def test_wrong_original_with_matching_outer_hash_cannot_pass_mapping_proof(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry, raw, metadata = self.mapped_input(directory)
            with Image.open(raw) as image:
                altered = image.copy()
            altered.putpixel((0, 0), (255, 254, 253, 252))
            info = PngImagePlugin.PngInfo()
            info.add(b"gAMA", struct.pack(">I", 45455))
            altered.save(raw, pnginfo=info)
            metadata["nativeCapture"]["rawSha256"] = sha256(raw.read_bytes()).hexdigest()
            geometry.write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaises(ValueError):
                extract(png, geometry, directory / "out", raw_framebuffer_path=raw)
            self.assertFalse((directory / "out").exists())

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

    def test_host_framebuffer_requires_matching_original_png_acknowledgement(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            png, geometry = self.input_files(directory)
            capture = {"schemaVersion": 1, "status": "captured", "source": "simctl framebuffer",
                       "widthPx": 20, "heightPx": 30, "pngOrientation": 1,
                       "sha256": sha256(png.read_bytes()).hexdigest()}
            metadata = self.geometry() | {
                "captureSurface": "simctl framebuffer with XCTest-driven UIKit window; unchanged native pixels",
                "nativeCapture": capture}
            geometry.write_text(json.dumps(metadata), encoding="utf-8")
            result = extract(png, geometry, directory / "valid")
            self.assertEqual(capture, result["nativeMetadata"]["nativeCapture"])
            profile = json.loads((directory / "valid/android-viewport-profile.json").read_text(encoding="utf-8"))
            self.assertEqual("simctl-framebuffer-with-xctest-geometry", profile["sourceCaptureBackend"])
            with Image.open(png) as original, Image.open(directory / "valid/full-content.png") as cropped:
                self.assertEqual(original.crop((2, 4, 18, 26)).tobytes(), cropped.tobytes())
            for invalid in (None, {}, capture | {"status": "error"}, capture | {"source": "video"},
                            capture | {"widthPx": 19}, capture | {"pngOrientation": 6},
                            capture | {"sha256": "0" * 64}):
                with self.subTest(capture=invalid):
                    geometry.write_text(json.dumps(metadata | {"nativeCapture": invalid}), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        extract(png, geometry, directory / "rejected")
                    self.assertFalse((directory / "rejected").exists())

    def test_host_acknowledgement_cannot_be_mixed_with_legacy_capture_surface(self):
        with self.assertRaisesRegex(ValueError, "matching capture surface"):
            validate_native_geometry(self.geometry() | {"nativeCapture": None}, (20, 30))

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

    def test_corner_adaptation_uses_separately_measured_native_region(self):
        metadata = self.geometry() | {
            "contentSafeDrawingInsetsPoints": [0.5, 2, 0.5, 2],
            "safeDrawingInsetsPx": [1, 4, 1, 4],
            "safeDrawingInsetsSource": "UIKit safeArea with vertical corner adaptation",
        }
        result = validate_native_geometry(metadata, (20, 30))
        self.assertEqual([3, 8, 17, 22], result["safeAreaInteriorRectPx"])
        self.assertEqual([0.5, 1, 0.5, 1], metadata["contentSafeAreaInsetsPoints"])
        for change in ({"safeDrawingInsetsSource": "guessed"},
                       {"safeDrawingInsetsSource": "UIKit safeAreaInsets"},
                       {"safeDrawingInsetsPx": [1, 2, 1, 2]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_native_geometry(metadata | change, (20, 30))

    def test_video_rounded_odd_width_cannot_be_accepted_as_native_geometry(self):
        metadata = self.geometry() | {
            "displayScale": 3, "nativeDisplayScale": 3, "composeDensity": 3,
            "windowBoundsPoints": [0, 0, 393, 852], "contentBoundsInWindowPoints": [0, 0, 393, 852],
            "contentWidthPx": 1179, "contentHeightPx": 2556,
            "capturedWindowWidthPx": 1178, "capturedWindowHeightPx": 2556,
        }
        with self.assertRaisesRegex(ValueError, "Window bounds"):
            validate_native_geometry(metadata, (1178, 2556))

    def test_native_inset_conversion_preserves_compose_float32_rounding(self):
        metadata = self.geometry() | {
            "contentSafeDrawingInsetsPoints": [0.5, 1.2, 0.5, 1.2],
            "safeDrawingInsetsSource": "UIKit safeArea with vertical corner adaptation",
            "safeDrawingInsetsPixelConversion": "Float32 points * Float32 displayScale, roundToInt",
        }
        self.assertEqual([3, 6, 17, 24], validate_native_geometry(metadata, (20, 30))["safeAreaInteriorRectPx"])
        with self.assertRaises(ValueError):
            validate_native_geometry(metadata | {"safeDrawingInsetsPx": [1, 3, 1, 3]}, (20, 30))

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
