from copy import deepcopy
from io import BytesIO
from pathlib import Path
import json
import tempfile
import unittest
from zipfile import ZipFile

from PIL import Image

from compare_motion_parity import (
    ROOT, PROFILE, SUITE, SEQUENCES, MODES, OFFSETS, INTERRUPTED_OFFSETS,
    compare_pixels, compare_suites, digest, load_side, schedule, validate_pair, unpack_reference_archive,
)


class MotionComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.images = {}
        for mode, color in (("MIXED", (24, 38, 72, 255)), ("LYRICS", (24, 39, 72, 255))):
            image = Image.new("RGBA", (2400, 1080), color)
            output = BytesIO()
            image.save(output, format="PNG")
            cls.images[mode] = output.getvalue()

    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="motion-comparator-test-", dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.reference = self.directory / "android"
        self.candidate = self.directory / "ios"
        self.identity = {"textHashEncoding": "utf8-lf", "referenceSourceManifestSha256": "a" * 64,
                         "motionFixtureSourceSha256": "b" * 64, "fixtureDataSourceSha256": "c" * 64}
        self.fonts = {"font/Roboto-Regular.ttf": "d" * 64}
        self.expected_contract = self.identity, self.fonts
        for root, backend in ((self.reference, "Android Compose root / PixelCopy"), (self.candidate, "skia-raster")):
            for side in ("LEFT", "RIGHT"):
                self.write_manifest(root, side, self.manifest(side, backend))

    def manifest(self, side, backend):
        plan = list(schedule(2016))
        compositions = [{"mode": "MIXED", "clockMs": 16}] + [
            {"mode": action["targetMode"], "clockMs": action["clockMs"] + 16} for action, _ in plan]
        frames = []
        for sequence_index, (_, planned) in enumerate(plan):
            for frame in planned:
                png = self.images[frame["composedMode"]]
                frames.append(frame | {key: deepcopy(PROFILE[key]) for key in
                              ("widthPx", "heightPx", "density", "fontScale", "safeDrawingInsetsPx")} | {
                    "mediaSide": side, "trackKey": "spotify:track:0000000000000000000001",
                    "rawPositionMs": 26500, "playbackSpeed": 0, "playbackState": 2, "isPlaying": False,
                    "lastModeCompositionClockMs": compositions[sequence_index + (frame["phase"] != "before")]["clockMs"],
                    "lastFrameTimeNanos": frame["captureClockMs"] * 1_000_000,
                    "fixtureMounts": 1, "fixtureDisposals": 0, "pngBytes": len(png), "pngSha256": digest(png),
                    "composeRootCount": 1, "dialogRootCount": 0,
                })
        return {"schemaVersion": 1, "suite": SUITE, "complete": True, "mediaSide": side,
                "captureBackend": backend, "profile": deepcopy(PROFILE), "sequenceOrder": SEQUENCES.copy(),
                "frameIntervalMs": 16, "primeFrames": 2, "fixtureMounts": 1, "fixtureDisposals": 0,
                "fullSampleOffsetsMs": OFFSETS.copy(), "interruptedSampleOffsetsMs": INTERRUPTED_OFFSETS.copy(),
                "fixedFrameTimeNanos": None, "backgroundStyle": "STATIC_BLURRED", "reducedMotion": False,
                "sourceIdentity": self.identity.copy(), "sourceAssetSha256": self.fonts.copy(),
                "loadedAssetSha256": self.fonts.copy(), "androidSystemFontSha256": self.fonts.copy(),
                "initialSettleEndClockMs": 2016, "actions": [action for action, _ in plan],
                "compositions": compositions, "frames": frames}

    def write_manifest(self, root, side, manifest, images=True):
        directory = root / side.lower()
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        for frame in manifest["frames"]:
            (directory / (frame["id"] + ".json")).write_text(json.dumps(frame), encoding="utf-8")
            if images:
                (directory / (frame["id"] + ".png")).write_bytes(self.images[frame["composedMode"]])

    def load_candidate(self):
        return load_side(self.candidate, "LEFT", "skia-raster", self.expected_contract)[0]

    def test_complete_matching_sequence_and_exact_actual_clocks_validate(self):
        before, _ = load_side(self.reference, "LEFT", "Android Compose root / PixelCopy", self.expected_contract)
        after = self.load_candidate()
        validate_pair(before, after)
        self.assertEqual(22, len(after["frames"]))
        self.assertEqual(8368, after["frames"][-1]["captureClockMs"])

    def test_incomplete_wrong_side_order_profile_and_recreated_fixture_rejected(self):
        changes = [({"complete": False}, "Incomplete"), ({"mediaSide": "RIGHT"}, "side"),
                   ({"sequenceOrder": list(reversed(SEQUENCES))}, "sequence"),
                   ({"fixtureMounts": 2}, "fixtureMounts"),
                   ({"profile": PROFILE | {"density": 3}}, "profile")]
        for change, expected in changes:
            with self.subTest(change=change):
                value = self.manifest("LEFT", "skia-raster") | change
                self.write_manifest(self.candidate, "LEFT", value)
                with self.assertRaisesRegex(ValueError, expected):
                    self.load_candidate()

    def test_source_font_and_loaded_asset_mismatches_rejected(self):
        for change in ({"sourceIdentity": self.identity | {"fixtureDataSourceSha256": "f" * 64}},
                       {"sourceAssetSha256": self.fonts | {"font/Roboto-Regular.ttf": "e" * 64}},
                       {"loadedAssetSha256": {}}):
            with self.subTest(change=change):
                self.write_manifest(self.candidate, "LEFT", self.manifest("LEFT", "skia-raster") | change)
                with self.assertRaises(ValueError):
                    self.load_candidate()

    def test_android_system_font_bytes_are_required(self):
        value = self.manifest("LEFT", "Android Compose root / PixelCopy")
        value["androidSystemFontSha256"] = {}
        self.write_manifest(self.reference, "LEFT", value)
        with self.assertRaisesRegex(ValueError, "system font"):
            load_side(self.reference, "LEFT", "Android Compose root / PixelCopy", self.expected_contract)

    def test_wrong_position_paused_state_or_frame_order_rejected(self):
        for change in ({"rawPositionMs": 26501}, {"playbackState": 3}, {"isPlaying": True},
                       {"playbackSpeed": False}, {"id": "../outside"}, {"frameIndex": True}):
            with self.subTest(change=change):
                value = self.manifest("LEFT", "skia-raster")
                value["frames"][0].update(change)
                # Unsafe id is rejected before reading a path; never materialize it in the fixture writer.
                (self.candidate / "left/manifest.json").write_text(json.dumps(value))
                with self.assertRaises(ValueError):
                    self.load_candidate()

    def test_hash_tampering_and_standalone_record_mismatch_rejected(self):
        value = self.manifest("LEFT", "skia-raster")
        frame = value["frames"][0]
        png = self.candidate / "left" / (frame["id"] + ".png")
        content = bytearray(png.read_bytes())
        content[-1] ^= 1
        png.write_bytes(content)
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.load_candidate()
        self.write_manifest(self.candidate, "LEFT", value)
        record = self.candidate / "left" / (frame["id"] + ".json")
        record.write_text(json.dumps(frame | {"pngBytes": 99}))
        with self.assertRaisesRegex(ValueError, "JSON differs"):
            self.load_candidate()

    def test_inconsistent_controlled_clock_rejected(self):
        value = self.manifest("LEFT", "skia-raster")
        value["frames"][2]["captureClockMs"] += 1
        self.write_manifest(self.candidate, "LEFT", value)
        with self.assertRaisesRegex(ValueError, "controlled clock"):
            self.load_candidate()

    def test_equally_spaced_but_shifted_clock_epoch_is_not_normalized(self):
        value = self.manifest("LEFT", "skia-raster")
        value["initialSettleEndClockMs"] += 16
        for action in value["actions"]:
            action["clockMs"] += 16
        for composition in value["compositions"]:
            composition["clockMs"] += 16
        for frame in value["frames"]:
            for key in ("actionClockMs", "primedClockMs", "captureClockMs", "lastModeCompositionClockMs"):
                if frame[key] is not None:
                    frame[key] += 16
            frame["lastFrameTimeNanos"] += 16_000_000
        self.write_manifest(self.candidate, "LEFT", value)
        candidate = self.load_candidate()  # Internally consistent, but not the same observation time.
        reference, _ = load_side(self.reference, "LEFT", "Android Compose root / PixelCopy", self.expected_contract)
        with self.assertRaisesRegex(ValueError, "clock epochs are not normalized"):
            validate_pair(reference, candidate)

    def test_observed_frame_timestamp_mismatch_rejected_even_when_capture_clock_matches(self):
        candidate = self.load_candidate()
        candidate["frames"][0]["lastFrameTimeNanos"] -= 16_000_000
        reference, _ = load_side(self.reference, "LEFT", "Android Compose root / PixelCopy", self.expected_contract)
        with self.assertRaisesRegex(ValueError, "lastFrameTimeNanos mismatch"):
            validate_pair(reference, candidate)

    def test_invalid_side_rejects_whole_pair_before_pixel_comparison(self):
        value = self.manifest("RIGHT", "skia-raster") | {"complete": False}
        self.write_manifest(self.candidate, "RIGHT", value)
        report = compare_suites(self.reference, self.candidate, self.directory / "report", self.expected_contract)
        self.assertFalse(report["validEvidence"])
        self.assertFalse(report["allFramesIdentical"])
        self.assertEqual([], report["frames"])
        self.assertEqual("RIGHT", report["errors"][0]["side"])

    def test_full_report_keeps_all_44_frames_and_one_changed_pixel_is_failure(self):
        value = self.manifest("RIGHT", "skia-raster")
        frame = value["frames"][3]
        with Image.open(BytesIO(self.images[frame["composedMode"]])) as image:
            pixel = image.getpixel((100, 100))
            image.putpixel((100, 100), (pixel[0] + 1, *pixel[1:]))
            output = BytesIO()
            image.save(output, format="PNG")
        changed_png = output.getvalue()
        frame.update(pngBytes=len(changed_png), pngSha256=digest(changed_png))
        self.write_manifest(self.candidate, "RIGHT", value, images=False)
        image_path = self.candidate / "right" / (frame["id"] + ".png")
        image_path.write_bytes(changed_png)
        output_root = self.directory / "complete-report"
        report = compare_suites(self.reference, self.candidate, output_root, self.expected_contract)
        self.assertTrue(report["validEvidence"])
        self.assertTrue(report["comparisonComplete"])
        self.assertFalse(report["allFramesIdentical"])
        self.assertFalse(report["appearanceParityVerified"])
        self.assertEqual(44, len(report["frames"]))
        different = [row for row in report["frames"] if row["status"] != "identical"]
        self.assertEqual([(frame["id"], "RIGHT", 1)],
                         [(row["id"], row["side"], row["changedPixels"]) for row in different])
        self.assertEqual(44, len(list(output_root.glob("*/*-rgba-diff.png"))))
        self.assertEqual(44, len(list(output_root.glob("*/*-maximum-diff.png"))))
        self.assertEqual(changed_png, image_path.read_bytes())
        self.assertEqual(report, json.loads((output_root / "comparison.json").read_text()))

    def test_one_level_rgba_changes_are_reported_exactly_without_modifying_inputs(self):
        def png(pixel):
            image = Image.new("RGBA", (2, 2), (24, 38, 72, 255))
            image.putpixel((0, 0), pixel)
            output = BytesIO()
            image.save(output, format="PNG")
            return output.getvalue()
        before = png((24, 38, 72, 255))
        self.assertEqual("identical", compare_pixels(before, before, (2, 2))["status"])
        for index, pixel in enumerate(((25, 38, 72, 255), (24, 38, 72, 254))):
            after = png(pixel)
            original_hashes = digest(before), digest(after)
            prefix = self.directory / f"diff-{index}"
            result = compare_pixels(before, after, (2, 2), prefix)
            self.assertEqual("different", result["status"])
            self.assertEqual(1, result["changedPixels"])
            self.assertEqual(1, result["maxChannelDelta"])
            self.assertEqual(original_hashes, (digest(before), digest(after)))
            with Image.open(str(prefix) + "-rgba-diff.png") as difference:
                self.assertEqual("RGBA", difference.mode)
                self.assertEqual((1, 0, 0, 0) if index == 0 else (0, 0, 0, 1), difference.getpixel((0, 0)))

    def test_dimension_mismatch_and_output_overlap_cannot_be_accepted(self):
        with self.assertRaisesRegex(ValueError, "geometry"):
            compare_pixels(self.images["MIXED"], self.images["MIXED"], (2, 2))
        with self.assertRaisesRegex(ValueError, "overlap"):
            compare_suites(self.reference, self.candidate, self.candidate / "report", self.expected_contract)
        self.assertFalse((self.candidate / "report").exists())


class MotionReferenceArchiveTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="motion-archive-test-", dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_actual_checked_in_reference_has_all_44_valid_original_frames(self):
        reference = unpack_reference_archive(ROOT / "tests/evidence/android-motion-v1-reference.zip",
                                             self.directory / "reference")
        for side in ("left", "right"):
            manifest = json.loads((reference / side / "manifest.json").read_text())
            self.assertTrue(manifest["complete"])
            self.assertEqual(22, len(manifest["frames"]))
        with self.assertRaisesRegex(ValueError, "already exists"):
            unpack_reference_archive(ROOT / "tests/evidence/android-motion-v1-reference.zip", reference.parent)

    def test_changed_archive_checksum_and_unsafe_path_are_rejected_before_extraction(self):
        archive = self.directory / "reference.zip"
        with ZipFile(archive, "w") as package:
            package.writestr("../outside.txt", "unexpected")
        metadata = {"schemaVersion": 1, "suite": SUITE, "archive": archive.name,
                    "archiveBytes": archive.stat().st_size, "archiveSha256": "0" * 64}
        for expected in ("checksum", "Unsafe"):
            archive.with_suffix(".json").write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, expected):
                unpack_reference_archive(archive, self.directory / "reference")
            self.assertFalse((self.directory / "reference").exists())
            metadata["archiveSha256"] = digest(archive.read_bytes())


if __name__ == "__main__":
    unittest.main()
