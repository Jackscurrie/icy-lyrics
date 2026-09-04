from copy import deepcopy
from pathlib import Path
import json
import struct
import tempfile
import unittest

from android_viewport_profile import (instrumentation_arguments, load_profile, native_result_directory,
    prior_wm_override, validate_capture, validate_profile, wm_geometry)


def profile():
    return {"schemaVersion": 1, "profileId": "ios-native-evidence", "scenario": "portrait-long",
            "orientation": "portrait", "widthPx": 1170, "heightPx": 2532,
            "density": 3.0, "fontScale": 1.0, "safeDrawingInsetsPx": [0, 141, 0, 102],
            "sourceReferences": {"nativePng": {"path": "capture.png", "sha256": "a" * 64, "bytes": 99},
                                 "nativeGeometry": {"path": "geometry.json", "sha256": "b" * 64, "bytes": 88}}}


def capture(value):
    result = deepcopy(value)
    result["osSafeDrawingInsetsPx"] = [0, 72, 0, 48]
    result["spToPx"] = {str(size): size * 3.0 for size in (12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64)}
    return result


def png(width=1170, height=2532):
    return b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" + struct.pack(">II", width, height)


class NativeViewportProfileTests(unittest.TestCase):
    def test_preserves_source_references_and_unknown_measurements(self):
        value = profile() | {"nativeTiming": {"clock": "real display frames"}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"
            path.write_text(json.dumps(value))
            self.assertEqual(load_profile(path), value)

    def test_rejects_non_integer_android_dpi_instead_of_faking_density(self):
        with self.assertRaisesRegex(ValueError, "integer Android DPI"):
            validate_profile(profile() | {"density": 2.999})

    def test_rejects_invalid_sizes_and_nonfinite_scaling(self):
        for override in ({"widthPx": True}, {"heightPx": 0}, {"density": float("nan")},
                         {"fontScale": float("inf")}, {"fontScale": -1}, {"density": 1e308}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                validate_profile(profile() | override)

    def test_rejects_safe_areas_outside_full_content(self):
        for insets in ([0, -1, 0, 0], [600, 0, 570, 0], [0, 2532, 0, 0], [0, 0, 0]):
            with self.subTest(insets=insets), self.assertRaises(ValueError):
                validate_profile(profile() | {"safeDrawingInsetsPx": insets})

    def test_rejects_orientation_and_convenience_setting_drift(self):
        for override in ({"orientation": "landscape"}, {"androidSettings": {"wmDensityDpi": 420}}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                validate_profile(profile() | override)

    def test_rejects_profile_path_escape(self):
        for identifier in ("../baseline", "/absolute", "profile/subfolder", None):
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                native_result_directory(Path("workspace"), profile() | {"profileId": identifier}, "baseline")

    def test_profile_output_is_separate_from_original_twenty(self):
        result = native_result_directory(Path("workspace"), profile(), "baseline")
        self.assertEqual(result, Path("workspace/iOS/tests/results/android/native/ios-native-evidence/baseline"))
        self.assertNotEqual(result, Path("workspace/iOS/tests/results/android/baseline"))

    def test_scenario_cannot_escape_output_paths_or_inject_control_characters(self):
        for scenario in ("../outside", "/absolute", "path/subfolder", "path\\file", "a\nfile", ""):
            with self.subTest(scenario=scenario),self.assertRaisesRegex(ValueError,"safe captured scenario"):
                validate_profile(profile() | {"scenario":scenario})

    def test_rotation_swaps_wm_natural_geometry_without_resizing_capture(self):
        self.assertEqual(wm_geometry(profile(), 0), ("1170x2532", "480"))
        self.assertEqual(wm_geometry(profile(), 1), ("2532x1170", "480"))
        self.assertEqual(wm_geometry(profile(), 3), ("2532x1170", "480"))

    def test_existing_wm_overrides_are_preserved_for_restoration(self):
        self.assertEqual(prior_wm_override("Physical size: 1080x1920\nOverride size: 1170x2532\n", "size"), "1170x2532")
        self.assertEqual(prior_wm_override("Physical density: 420\nOverride density: 480", "density"), "480")
        self.assertEqual(prior_wm_override("Physical size: 1080x1920", "size"), "reset")

    def test_instrumentation_requests_measured_font_scale_and_each_inset(self):
        arguments = instrumentation_arguments(profile() | {"fontScale": 2.5})
        values = {arguments[i + 1]: arguments[i + 2] for i in range(0, len(arguments), 3)}
        self.assertEqual(values["viewportFontScale"], "2.5")
        self.assertEqual(values["viewportInsetTop"], "141")
        self.assertEqual(values["viewportInsetBottom"], "102")

    def test_effective_injected_insets_can_differ_from_original_os_insets(self):
        validate_capture(profile(), capture(profile()), png())

    def test_mismatched_effective_geometry_fails(self):
        for override in ({"density": 2.625}, {"fontScale": 1.3}, {"safeDrawingInsetsPx": [0, 72, 0, 48]},
                         {"scenario": "settings"}, {"profileId": "wrong"}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                validate_capture(profile(), capture(profile()) | override, png())

    def test_actual_png_must_have_exact_requested_dimensions(self):
        with self.assertRaisesRegex(ValueError, "resizing is prohibited"):
            validate_capture(profile(), capture(profile()), png(1080, 2400))

    def test_native_font_samples_and_os_insets_cannot_be_omitted(self):
        for key in ("osSafeDrawingInsetsPx", "spToPx"):
            measured = capture(profile())
            del measured[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_capture(profile(), measured, png())


if __name__ == "__main__":
    unittest.main()
