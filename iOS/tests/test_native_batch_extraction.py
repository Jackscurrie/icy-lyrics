"""Small synthetic pixels in the real xcresult export schema, never capture evidence."""
from copy import deepcopy
from pathlib import Path
import json
import struct
import tempfile
import unittest
import uuid

from PIL import Image, ImageCms, PngImagePlugin
from extract_native_batch import EXPECTED, collect, extract_batch, provenance
from native_mapping_fixtures import mapped_files

DEVICE = "AE42CFCF-8FFB-497C-AAEB-F69DE0081C06"
REVISION = "b8d3a9e19894ea4a633e5bb50300021aa4df4043"


class NativeBatchExtraction(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.captures = self.root / "reports/ios-captures"
        self.captures.mkdir(parents=True)
        self.manifest = self.captures / "manifest.json"
        self.artifact = self.root / "artifact.json"
        self.write(self.artifact, {"id": 9934906639, "name": "icy-ios-verification", "size_in_bytes": 66139652,
                                  "workflow_run": {"id": 33865575594, "head_sha": REVISION},
                                  "digest": "sha256:" + "a" * 64})

    def write(self, path, data):
        path.write_text(json.dumps(data), encoding="utf-8")

    def make_manifest(self, cases=("portrait-1",), *, metadata_changes=None):
        records = {}
        self.case_paths = {}
        for case in cases:
            large = case.endswith("-large-text")
            normal = case.removesuffix("-large-text")
            scenario, orientation = normal.rsplit("-", 1)
            landscape = orientation != "1"
            window = [15, 10] if landscape else [10, 15]
            content = [2, 1, 11, 8] if landscape else [1, 2, 8, 11]
            insets = [1, 0, 1, 0] if landscape else [0, 1, 0, 1]
            geometry = {"ready": True, "captureSurface": "XCTest application window; synthetic test",
                        "scenario": scenario, "locale": "en_US", "timezone": "America/Los_Angeles",
                        "libraryDateText": "Sep 3, 2026 5:00 AM", "preferredContentSizeCategory": "synthetic",
                        "requestedLargeText": large, "displayScale": 3, "nativeDisplayScale": 3,
                        "composeDensity": 3, "fontScale": 1.5 if large else 1,
                        "contentWidthPx": content[2] * 3, "contentHeightPx": content[3] * 3,
                        "capturedWindowWidthPx": window[0] * 3, "capturedWindowHeightPx": window[1] * 3,
                        "windowBoundsPoints": [0, 0] + window, "contentBoundsInWindowPoints": content,
                        "safeDrawingInsetsPx": [x * 3 for x in insets], "contentSafeAreaInsetsPoints": insets,
                        "windowSafeAreaInsetsPoints": insets, "interfaceOrientationRawValue": int(orientation),
                        "requestedDeviceOrientationRawValue": int(orientation),
                        "expectedInterfaceOrientationRawValue": int(orientation), "settleDelayAfterDrawSeconds": 2}
            geometry.update(metadata_changes or {})
            group = records.setdefault(EXPECTED[case], {"testIdentifier": EXPECTED[case],
                "testIdentifierURL": "test://com.apple.xcode/IcyLyrics/IcyLyricsUITests/" + EXPECTED[case].removesuffix("()"),
                "attachments": []})
            png, meta = (self.captures / (str(uuid.uuid4()).upper() + ext) for ext in (".png", ".json"))
            info = PngImagePlugin.PngInfo()
            info.add(b"gAMA", struct.pack(">I", 45455))
            image = Image.new("RGBA", (window[0] * 3, window[1] * 3))
            for y in range(image.height):
                for x in range(image.width):
                    image.putpixel((x, y), (x * 4, y * 4, (x + y) * 2, 255))
            image.save(png, pnginfo=info)
            self.write(meta, geometry)
            for path, name in ((meta, case + "-geometry"), (png, case)):
                group["attachments"].append({"configurationName": "Test Scheme Action", "deviceId": DEVICE,
                    "deviceName": "IcyLyricsVerification", "exportedFileName": path.name,
                    "isAssociatedWithFailure": False,
                    "suggestedHumanReadableName": name + "_0_" + str(uuid.uuid4()).upper() + path.suffix,
                    "timestamp": 1788517919.052})
            self.case_paths[case] = (png, meta)
        values = list(records.values())
        self.write(self.manifest, values)
        return values

    def run_batch(self, **kwargs):
        return extract_batch(self.manifest, self.root / "out", artifact_metadata=self.artifact, **kwargs)

    def mapped_manifest(self, *, include_raw=True):
        case = "landscape-mixed-3"
        records = self.make_manifest((case,))
        png, meta = self.case_paths[case]
        raw = self.captures / (str(uuid.uuid4()).upper() + ".png")
        mapped_files(png, meta, raw)
        raw_attachment = deepcopy(records[0]["attachments"][1])
        raw_attachment.update(exportedFileName=raw.name,
            suggestedHumanReadableName=case + "-raw-framebuffer_0_" + str(uuid.uuid4()).upper() + ".png")
        if include_raw:
            records[0]["attachments"].append(raw_attachment)
        self.write(self.manifest, records)
        return case, records, raw

    def test_mapped_batch_pairs_and_retains_original_framebuffer_attachment(self):
        case, records, raw = self.mapped_manifest()
        pairs, _, ignored = collect(self.manifest)
        self.assertEqual(raw.resolve(), pairs[case][0]["rawFramebuffer"]["path"])
        self.assertEqual([], ignored)
        report = self.run_batch(allow_partial=True)
        self.assertEqual([], report["rejections"])
        row = report["pairs"][0]
        self.assertEqual(records[0]["attachments"][2], row["rawFramebufferAttachment"])
        self.assertTrue(row["nativeCoordinateMapping"]["independentlyVerified"])
        self.assertEqual(raw.read_bytes(), Path(row["nativeCoordinateMapping"]["rawCopy"]).read_bytes())
        self.assertFalse(report["appearanceParityVerified"])

    def test_mapped_batch_without_original_is_diagnostic_rejection_only(self):
        case, _, _ = self.mapped_manifest(include_raw=False)
        report = self.run_batch(allow_partial=True)
        self.assertEqual([], report["pairs"])
        self.assertEqual(case, report["rejections"][0]["case"])
        self.assertIn("original raw-framebuffer", report["rejections"][0]["error"])
        self.assertFalse(report["readyForReviewedAndroidCapture"])
        self.assertFalse((self.root / "out" / case).exists())

    def test_raw_attachment_wrong_identity_type_failure_reuse_or_path_is_rejected(self):
        changes = [("isAssociatedWithFailure", True), ("deviceId", "OTHER"),
            ("configurationName", "Different configuration"), ("exportedFileName", "../raw.png"),
            ("suggestedHumanReadableName", "landscape-mixed-3-raw-framebuffer.json")]
        for field, value in changes:
            with self.subTest(field=field):
                _, records, _ = self.mapped_manifest()
                records[0]["attachments"][2][field] = value
                self.write(self.manifest, records)
                with self.assertRaises(ValueError):
                    collect(self.manifest)
        _, records, _ = self.mapped_manifest()
        records[0]["attachments"][2]["exportedFileName"] = records[0]["attachments"][1]["exportedFileName"]
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "reused"):
            collect(self.manifest)

    def test_duplicate_or_split_retry_raw_attachment_cannot_be_selected(self):
        _, records, raw = self.mapped_manifest()
        duplicate = deepcopy(records[0]["attachments"][2])
        other = self.captures / (str(uuid.uuid4()) + ".png")
        other.write_bytes(raw.read_bytes())
        duplicate["exportedFileName"] = other.name
        records[0]["attachments"].append(duplicate)
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            collect(self.manifest)
        _, records, _ = self.mapped_manifest()
        retry = deepcopy(records[0])
        retry["attachments"] = [records[0]["attachments"].pop()]
        records.append(retry)
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "different test records"):
            collect(self.manifest)

    def test_real_manifest_schema_requires_complete_29_and_groups_measured_geometry(self):
        self.make_manifest(EXPECTED)
        report = self.run_batch(workflow_run="33865575594", source_revision="b8d3a9e")
        self.assertEqual(29, len(report["pairs"]))
        self.assertTrue(report["completeDefault29"])
        self.assertEqual(5, len(report["groups"]))  # Portrait, both orientations, two large-text profiles.
        self.assertFalse(report["appearanceParityVerified"])
        self.assertEqual("unknown", report["provenance"]["runOutcome"])
        self.assertFalse(report["provenance"]["remoteMetadataAuthenticatedByThisTool"])
        self.assertTrue(all(not pair["captureCommandExecuted"] for pair in report["pairs"]))

    def test_missing_default_cases_require_explicit_partial_and_remain_named(self):
        self.make_manifest()
        with self.assertRaisesRegex(ValueError, "all 29"):
            self.run_batch()
        report = self.run_batch(allow_partial=True)
        self.assertEqual(28, len(report["missingPairs"]))
        self.assertIn("multilingual-3-large-text", report["missingPairs"])
        self.assertFalse(report["completeDefault29"])
        self.assertEqual("partial-or-failed-run-diagnostics", report["evidenceClassification"])

    def test_empty_failed_run_produces_only_a_diagnostic_report(self):
        self.make_manifest(())
        stage = self.captures.parent / "simulator-stage-results.json"
        self.write(stage, {"nativeExitCode": 0, "swiftExitCode": 65, "attachmentExportExitCode": 0,
                           "freshArm64Framework": True})
        report = self.run_batch(allow_partial=True)
        self.assertEqual("failed", report["provenance"]["runOutcome"])
        self.assertEqual(29, len(report["missingPairs"]))
        self.assertFalse(report["readyForReviewedAndroidCapture"])
        self.assertFalse(report["allAvailablePairsGeometryValid"])
        self.assertEqual(["batch-report.json"], [p.name for p in (self.root / "out").iterdir()])

    def test_ignored_recordings_and_automatic_failure_jpegs_are_never_paired(self):
        records = self.make_manifest()
        for name in ("Screen Recording 2026-09-04 at 10.30.31 AM.mp4", "Screenshot Failure.jpg",
                     "portrait-1-draw-failure_0_" + str(uuid.uuid4()) + ".json"):
            records[0]["attachments"].append({"suggestedHumanReadableName": name, "isAssociatedWithFailure": True})
        self.write(self.manifest, records)
        pairs, _, ignored = collect(self.manifest)
        self.assertEqual(["portrait-1"], list(pairs))
        self.assertEqual(3, len(ignored))

    def test_duplicate_pair_and_split_retry_records_are_rejected(self):
        records = self.make_manifest()
        original = deepcopy(records)
        duplicate = deepcopy(records[0]["attachments"][1])
        path = self.captures / (str(uuid.uuid4()) + ".png")
        path.write_bytes(self.case_paths["portrait-1"][0].read_bytes())
        duplicate["exportedFileName"] = path.name
        records[0]["attachments"].append(duplicate)
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            collect(self.manifest)
        records = original
        second = deepcopy(records[0])
        second["attachments"] = [records[0]["attachments"].pop()]
        records.append(second)
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "different test records"):
            collect(self.manifest)

    def test_missing_file_or_pair_rejected_before_geometry_extraction(self):
        records = self.make_manifest()
        records[0]["attachments"].pop()
        self.write(self.manifest, records)
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            collect(self.manifest)
        self.make_manifest()
        self.case_paths["portrait-1"][0].unlink()
        with self.assertRaisesRegex(ValueError, "Missing/escaped"):
            collect(self.manifest)

    def test_wrong_test_type_device_configuration_failure_or_unsafe_path_rejected(self):
        changes = [("test", "ParityCaptureTests/testLargeTextFixture()"),
                   ("suggestedHumanReadableName", "portrait-1.jpg"),
                   ("exportedFileName", "wrong.json"), ("exportedFileName", "../outside.png"),
                   ("exportedFileName", "..\\outside.png"), ("exportedFileName", "C:outside.png"),
                   ("isAssociatedWithFailure", True), ("deviceId", "OTHER"),
                   ("configurationName", "Other config")]
        for field, value in changes:
            with self.subTest(field=field, value=value):
                records = self.make_manifest()
                if field == "test":
                    records[0]["testIdentifier"] = value
                else:
                    records[0]["attachments"][1][field] = value
                self.write(self.manifest, records)
                with self.assertRaises(ValueError):
                    collect(self.manifest)

    def test_actual_strict_extractor_crops_pixels_and_preserves_color_evidence(self):
        self.make_manifest()
        png, _ = self.case_paths["portrait-1"]
        icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        with Image.open(png) as image:
            source_pixels = image.copy()
        info = PngImagePlugin.PngInfo()
        info.add(b"gAMA", struct.pack(">I", 45455))
        source_pixels.save(png, pnginfo=info, icc_profile=icc)
        original = png.read_bytes()
        report = self.run_batch(allow_partial=True, safe_area_interior=True)
        row = report["pairs"][0]
        self.assertEqual(0.45455, row["sourcePngColor"]["gamma"])
        self.assertIsNotNone(row["sourcePngColor"]["iccProfileSha256"])
        self.assertEqual("RGBA", row["sourcePngColor"]["mode"])
        with Image.open(self.root / "out/portrait-1/full-content.png") as cropped:
            self.assertEqual((24, 33), cropped.size)
            self.assertEqual(source_pixels.crop((3, 6, 27, 39)).tobytes(), cropped.tobytes())
            self.assertEqual(icc, cropped.info["icc_profile"])
        self.assertEqual(original, png.read_bytes())
        self.assertFalse(row["nativeFontSamplesComplete"])

    def test_geometry_or_identity_failure_is_recorded_not_repaired_or_counted_valid(self):
        for change in ({"capturedWindowWidthPx": 1178}, {"contentBoundsInWindowPoints": [0.1, 2, 8, 11]},
                       {"scenario": "empty"}, {"requestedDeviceOrientationRawValue": True}, {"requestedLargeText": True}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as output:
                self.make_manifest(metadata_changes=change)
                report = extract_batch(self.manifest, Path(output) / "out", artifact_metadata=self.artifact, allow_partial=True)
                self.assertEqual([], report["pairs"])
                self.assertEqual("portrait-1", report["rejections"][0]["case"])
                self.assertFalse(report["completeDefault29"])
                self.assertFalse(report["readyForReviewedAndroidCapture"])

    def test_operator_labels_cannot_supply_or_override_provenance(self):
        self.make_manifest()
        with self.assertRaisesRegex(ValueError, "operator labels are insufficient"):
            provenance(self.manifest, workflow_run="33865575594", source_revision=REVISION)
        for assertion in ({"workflow_run": "1"}, {"source_revision": "abcdef0"}):
            with self.subTest(assertion=assertion), self.assertRaisesRegex(ValueError, "assertion"):
                provenance(self.manifest, artifact_metadata=self.artifact, **assertion)

    def marker(self):
        return {"result": "passed", "commit": REVISION, "sourceFingerprint": "f" * 64, "simulator": DEVICE,
                "summary": {"result": "Passed", "passedTests": 4, "failedTests": 0, "skippedTests": 0,
                    "totalTestCount": 4, "devicesAndConfigurations": [{"device": {"deviceId": DEVICE,
                        "platform": "iOS Simulator", "architecture": "arm64"}}]}}

    def test_existing_marker_is_discovered_and_validated_but_does_not_prove_run_id(self):
        self.make_manifest()
        marker = self.captures.parent / "simulator-verification.json"
        self.write(marker, self.marker())
        source = provenance(self.manifest, device_id=DEVICE)
        self.assertEqual("passed-verification-marker", source["runOutcome"])
        self.assertIsNone(source["workflowRunId"])
        with self.assertRaisesRegex(ValueError, "workflow-run assertion"):
            provenance(self.manifest, workflow_run="33865575594")
        with self.assertRaisesRegex(ValueError, "simulator differs"):
            provenance(self.manifest, device_id="OTHER")
        value = self.marker()
        value["summary"]["failedTests"] = 1
        self.write(marker, value)
        with self.assertRaisesRegex(ValueError, "failures"):
            provenance(self.manifest)

    def test_failed_stage_cannot_coexist_with_a_pass_marker_or_satisfy_default_mode(self):
        self.make_manifest(EXPECTED)
        stage = self.captures.parent / "simulator-stage-results.json"
        self.write(stage, {"nativeExitCode": 0, "swiftExitCode": 65, "attachmentExportExitCode": 0,
                           "freshArm64Framework": True})
        with self.assertRaisesRegex(ValueError, "known failed"):
            self.run_batch()
        report = self.run_batch(allow_partial=True)
        self.assertTrue(report["completeDefault29"])
        self.assertEqual("partial-or-failed-run-diagnostics", report["evidenceClassification"])
        marker = self.captures.parent / "simulator-verification.json"
        self.write(marker, self.marker())
        with self.assertRaisesRegex(ValueError, "contradicts"):
            provenance(self.manifest)


if __name__ == "__main__":
    unittest.main()
