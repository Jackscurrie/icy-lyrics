from io import BytesIO
import unittest
from PIL import Image
from compare_ios_parity import compare


class StrictIosParityComparison(unittest.TestCase):
    def png(self, pixel=(24, 38, 72, 255), size=(2, 2)):
        image = Image.new("RGBA", size, (24, 38, 72, 255))
        image.putpixel((0, 0), pixel)
        output = BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    def geometry(self):
        return {"widthPx": 2, "heightPx": 2, "density": 2.625, "fontScale": 1,
                "safeDrawingInsetsPx": [0, 0, 0, 0], "captureBackend": "skia-raster"}

    def test_identical_pixels_pass(self):
        result = compare(self.png(), self.png(), self.geometry(), self.geometry())
        self.assertEqual("identical", result["status"])
        self.assertEqual(0, result["changedPixels"])

    def test_single_level_rgb_and_alpha_changes_fail(self):
        for pixel in ((25, 38, 72, 255), (24, 38, 72, 254)):
            with self.subTest(pixel=pixel):
                result = compare(self.png(), self.png(pixel), self.geometry(), self.geometry())
                self.assertEqual("different", result["status"])
                self.assertEqual(1, result["changedPixels"])
                self.assertEqual(1, result["maxChannelDelta"])

    def test_density_font_or_inset_mismatch_cannot_pass_identical_pixels(self):
        for change in ({"density": 3}, {"fontScale": 1.2}, {"safeDrawingInsetsPx": [0, 1, 0, 0]}):
            with self.subTest(change=change):
                result = compare(self.png(), self.png(), self.geometry(), self.geometry() | change)
                self.assertEqual("geometry-mismatch", result["status"])

    def test_image_dimensions_must_match_metadata(self):
        result = compare(self.png(), self.png(size=(3, 2)), self.geometry(), self.geometry())
        self.assertEqual("image-metadata-mismatch", result["status"])

    def test_window_capture_cannot_substitute_for_raster_scene(self):
        with self.assertRaisesRegex(ValueError, "raster lane"):
            compare(self.png(), self.png(), self.geometry(), self.geometry() | {"captureBackend": "UIKit-window"})

    def test_missing_or_invalid_geometry_is_rejected(self):
        for change in ({"density": float("nan")}, {"widthPx": True}, {"safeDrawingInsetsPx": None}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                compare(self.png(), self.png(), self.geometry(), self.geometry() | change)


if __name__ == "__main__":
    unittest.main()
