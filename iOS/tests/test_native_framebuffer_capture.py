"""Exact-byte transport checks; mocked simctl output is not a native capture claim."""
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from PIL import Image

IOS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(IOS / "scripts"))
SPEC = importlib.util.spec_from_file_location("framebuffer", IOS / "scripts/capture_native_framebuffer.py")
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)
SIMULATOR = "234B15A9-0F01-4569-A3F9-D7B8E8CF91D4"


class NativeFramebufferCaptureTest(unittest.TestCase):
    def setUp(self):
        (IOS / "build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=IOS / "build", prefix="framebuffer-test-")
        self.root = Path(self.temp.name)
        self.container = self.root / "container"
        self.requests = self.container / "Documents/icy-native-capture/requests"
        self.requests.mkdir(parents=True)
        self.responses = self.requests.parent / "responses"
        self.output = self.root / "evidence"
        self.output.mkdir()
        self.request_id = str(uuid.uuid4()).upper()
        self.path = self.requests / (self.request_id + ".json")
        self.payload = {"schemaVersion": 1, "requestId": self.request_id, "scenario": "portrait",
                        "createdAtUnixMs": time.time() * 1000, "expectedWidthPx": 20, "expectedHeightPx": 30,
                        "metadata": {"contentWidthPx": 20, "contentHeightPx": 30, "ready": True}}
        self.path.write_text(json.dumps(self.payload))
        image = Image.new("RGBA", (20, 30), (18, 36, 72, 255))
        output = io.BytesIO()
        image.save(output, format="PNG")
        self.png = output.getvalue()
        self.commands = []

    def tearDown(self):
        self.temp.cleanup()

    def simctl(self, command, **options):
        self.commands.append(command)
        Path(command[-1]).write_bytes(self.png)
        return subprocess.CompletedProcess(command, 0, "", "Wrote screenshot")

    def service(self, run=None):
        return capture.CaptureService(SIMULATOR, self.output, 0, run or self.simctl)

    def response(self):
        return json.loads((self.responses / (self.request_id + ".json")).read_text())

    def test_success_preserves_exact_png_bytes_and_calls_unmasked_framebuffer_command(self):
        service = self.service()
        service.poll(self.container)
        result = self.response()
        self.assertEqual("captured", result["status"])
        self.assertEqual("simctl framebuffer", result["source"])
        self.assertEqual((20, 30), (result["widthPx"], result["heightPx"]))
        self.assertEqual(1, result["pngOrientation"])
        self.assertEqual(hashlib.sha256(self.png).hexdigest(), result["sha256"])
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())
        self.assertEqual(self.png, (self.output / self.request_id / "framebuffer.png").read_bytes())
        self.assertEqual(["xcrun", "simctl", "io", SIMULATOR, "screenshot", "--type=png", "--mask=ignored"], self.commands[0][:-1])
        service.poll(self.container)
        self.assertEqual(1, len(self.commands), "A request is captured only once")

    def test_wrong_dimensions_return_original_png_before_error_ack(self):
        self.payload["expectedWidthPx"] = 19
        self.path.write_text(json.dumps(self.payload))
        self.service().poll(self.container)
        result = self.response()
        self.assertEqual("error", result["status"])
        self.assertIn("dimensions differ", result["error"])
        self.assertEqual((20, 30), (result["widthPx"], result["heightPx"]))
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())

    def landscape_mapping(self):
        window = [[0, 0], [30, 0], [30, 20], [0, 20]]
        fixed = [[0, 30], [0, 0], [20, 0], [20, 30]]
        samples = [[15, 10], [16, 10], [15, 11]]
        return {"schemaVersion": 1, "cornerOrder": ["TL", "TR", "BR", "BL"],
            "windowBoundsPoints": [0, 0, 30, 20], "contentBoundsInWindowPoints": [0, 0, 30, 20],
            "fixedBoundsPoints": [0, 0, 20, 30], "screenNativeBoundsPixels": [0, 0, 20, 30],
            "displayScale": 1, "nativeDisplayScale": 1,
            "windowCornersInWindowPoints": window, "windowCornersInFixedPoints": fixed,
            "roundTripsInWindowPoints": window, "contentCornersInWindowPoints": window,
            "contentCornersInFixedPoints": fixed, "contentRoundTripsInWindowPoints": window,
            "sampleOrder": ["center", "centerPlusOnePixelX", "centerPlusOnePixelY"],
            "sampleWindowPoints": samples, "sampleFixedPoints": [[10, 15], [10, 14], [11, 15]],
            "sampleRoundTripsInWindowPoints": samples}

    def test_measured_landscape_keeps_raw_and_returns_all_pixels_in_window_coordinates(self):
        self.payload.update(expectedWidthPx=30, expectedHeightPx=20)
        mapping = self.landscape_mapping()
        self.payload["metadata"] = {"ready": True, "contentWidthPx": 30, "contentHeightPx": 20,
            "windowBoundsPoints": mapping["windowBoundsPoints"],
            "contentBoundsInWindowPoints": mapping["contentBoundsInWindowPoints"],
            "screenNativeBoundsPixels": mapping["screenNativeBoundsPixels"],
            "displayScale": 1, "nativeDisplayScale": 1, "fixedCoordinateMapping": mapping}
        self.path.write_text(json.dumps(self.payload))
        source = Image.new("RGBA", (20, 30))
        source.putdata([(x, y, x + y, 255) for y in range(30) for x in range(20)])
        encoded = io.BytesIO()
        source.save(encoded, format="PNG")
        self.png = encoded.getvalue()
        self.service().poll(self.container)
        result = self.response()
        self.assertEqual("captured", result["status"], result.get("error"))
        self.assertTrue(result["imageTransformed"])
        self.assertEqual(hashlib.sha256(self.png).hexdigest(), result["rawSha256"])
        self.assertEqual(self.png, (self.responses / (self.request_id + ".raw.png")).read_bytes())
        self.assertEqual(self.png, (self.output / self.request_id / "framebuffer.png").read_bytes())
        derived = (self.responses / (self.request_id + ".png")).read_bytes()
        self.assertEqual(hashlib.sha256(derived).hexdigest(), result["sha256"])
        with Image.open(io.BytesIO(derived)) as window:
            self.assertEqual((30, 20), window.size)
            self.assertEqual(source.getpixel((0, 29)), window.getpixel((0, 0)))
            self.assertEqual(source.getpixel((19, 0)), window.getpixel((29, 19)))
            original_bytes, window_bytes = source.tobytes(), window.tobytes()
            self.assertEqual(sorted(original_bytes[i:i + 4] for i in range(0, len(original_bytes), 4)),
                             sorted(window_bytes[i:i + 4] for i in range(0, len(window_bytes), 4)))

    def test_invalid_measured_mapping_retains_raw_and_never_publishes_a_derivative(self):
        self.payload["metadata"]["fixedCoordinateMapping"] = self.landscape_mapping()
        self.path.write_text(json.dumps(self.payload))
        self.service().poll(self.container)
        self.assertEqual("error", self.response()["status"])
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())
        self.assertFalse((self.responses / (self.request_id + ".raw.png")).exists())
        self.assertFalse((self.output / self.request_id / "window.png").exists())

    def test_orientation_is_recorded_without_rotating_the_png(self):
        exif = Image.Exif()
        exif[274] = 6
        output = io.BytesIO()
        Image.new("RGB", (20, 30), "blue").save(output, format="PNG", exif=exif)
        self.png = output.getvalue()
        self.service().poll(self.container)
        self.assertEqual(6, self.response()["pngOrientation"])
        self.assertEqual((20, 30), (self.response()["widthPx"], self.response()["heightPx"]))
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())

    def test_malformed_output_is_retained_with_hash_and_error(self):
        self.png = b"not a PNG"
        self.service().poll(self.container)
        self.assertEqual("error", self.response()["status"])
        self.assertEqual(hashlib.sha256(self.png).hexdigest(), self.response()["sha256"])
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())

    def test_simctl_failure_is_an_error_not_an_empty_successful_capture(self):
        self.service(lambda command, **kw: subprocess.CompletedProcess(command, 1, "", "not booted")).poll(self.container)
        self.assertEqual("error", self.response()["status"])
        self.assertEqual(1, self.response()["captureExitCode"])
        self.assertFalse((self.responses / (self.request_id + ".png")).exists())

    def test_bad_schema_id_and_boolean_dimensions_never_invoke_simctl(self):
        self.path.unlink()
        service = self.service()
        for change in ({"schemaVersion": 2}, {"requestId": str(uuid.uuid4())}, {"expectedWidthPx": True}):
            self.request_id = str(uuid.uuid4()).upper()
            self.path = self.requests / (self.request_id + ".json")
            self.payload["requestId"] = self.request_id
            self.path.write_text(json.dumps(self.payload | change))
            service.poll(self.container)
            self.assertEqual("error", self.response()["status"])
            self.assertEqual([], self.commands)

    def test_timeout_keeps_any_produced_framebuffer_bytes_and_marks_error(self):
        def timeout(command, **options):
            Path(command[-1]).write_bytes(self.png)
            raise subprocess.TimeoutExpired(command, 30)
        self.service(timeout).poll(self.container)
        self.assertEqual("error", self.response()["status"])
        self.assertEqual((20, 30), (self.response()["widthPx"], self.response()["heightPx"]))
        self.assertEqual(self.png, (self.responses / (self.request_id + ".png")).read_bytes())

    def test_stale_request_and_reused_id_do_not_recapture_or_overwrite(self):
        service = capture.CaptureService(SIMULATOR, self.output, time.time_ns() + 1_000_000, self.simctl)
        service.poll(self.container)
        self.assertEqual([], self.commands)
        self.service().poll(self.container)
        before = (self.responses / (self.request_id + ".png")).read_bytes()
        with self.assertRaisesRegex(ValueError, "already used"):
            self.service().poll(self.container)
        self.assertEqual(before, (self.responses / (self.request_id + ".png")).read_bytes())

    def test_response_symlink_is_rejected_without_writing_to_its_target(self):
        outside = self.root / "outside"
        outside.mkdir()
        try:
            self.responses.symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Symlink creation unavailable: {error}")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.service().poll(self.container)
        self.assertEqual([], list(outside.iterdir()))

    def test_runner_lookup_rejects_another_simulators_or_general_filesystem_path(self):
        fake = lambda command, **kw: subprocess.CompletedProcess(command, 0, str(self.root), "")
        with self.assertRaises(ValueError):
            capture.runner_container(SIMULATOR, "com.icy.lyrics.ios.IcyLyricsUITests.xctrunner", fake)

    def test_runner_lookup_accepts_only_the_requested_simulators_app_container(self):
        home = self.root / "mac-home"
        container = home / "Library/Developer/CoreSimulator/Devices" / SIMULATOR / "data/Containers/Data/Application" / str(uuid.uuid4()).upper()
        container.mkdir(parents=True)
        def lookup(command, **options):
            self.assertEqual(["xcrun", "simctl", "get_app_container", SIMULATOR.lower(),
                              "com.icy.lyrics.ios.Tests.xctrunner", "data"], command)
            return subprocess.CompletedProcess(command, 0, str(container) + "\n", "")
        with patch.object(Path, "home", return_value=home):
            self.assertEqual(container, capture.runner_container(SIMULATOR.lower(), "com.icy.lyrics.ios.Tests.xctrunner", lookup))

    def test_child_exit_code_and_host_summary_are_preserved(self):
        output = self.root / "new-output"
        child = unittest.mock.Mock()
        child.poll.return_value = 7
        child.returncode = 7
        args = ["capture", "--simulator", SIMULATOR, "--runner-bundle-id", "com.icy.lyrics.ios.Tests.xctrunner",
                "--output", str(output), "--", "xcodebuild", "test"]
        with patch.object(sys, "argv", args), patch.object(sys, "platform", "darwin"), patch.object(capture.subprocess, "Popen", return_value=child) as launch:
            self.assertEqual(7, capture.main())
        launch.assert_called_once_with(["xcodebuild", "test"])
        self.assertEqual(7, json.loads((output / "host-summary.json").read_text())["xcodeExitCode"])


if __name__ == "__main__":
    unittest.main()
