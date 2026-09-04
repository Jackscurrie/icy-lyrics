"""Integrity/collector tests, using synthetic metadata only; no pretend GPU execution."""
from copy import deepcopy
from io import BytesIO
from pathlib import Path
import json
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageCms, PngImagePlugin

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import generate_xcode_project as project
import collect_ios_kawarp as probe


class KawarpProbeContractTests(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads(probe.CONTRACT.read_text())
        self.expected = self.contract["frames"][0]
        self.run_id = "2D50A936-9F60-453E-82FB-667C220C1F8B"

    def frame(self):
        config = {key: self.expected[key] for key in ("uniformFloat32Values", "uniformFloat32BitsHex", "uniformLittleEndianBytesHex")}
        config.update(shaderSha256=self.contract["shaderSha256"], paintAntiAlias=True, paintDither=False,
                      childSampling="SamplingMode.DEFAULT = NEAREST/NONE", childTileModes="CLAMP/CLAMP", childLocalMatrix="identity")
        frame = {"catalog": probe.CATALOG, "ready": True, "runId": self.run_id, "id": self.expected["id"],
                 "appearanceParityVerified": False, "drawWidthPx": 256, "drawHeightPx": 512,
                 "captureWidthPx": 256, "captureHeightPx": 512, "density": 3, "configuration": config,
                 "captureSurface": probe.XCUI_SURFACE, "capturePngOrientation": 1,
                 "nativeGeometry": {"screenScale": 3, "surfaceBoundsInWindowPoints": [1, 2, 256 / 3, 512 / 3],
                                    "metalLayers": [{"deviceName": "Synthetic test only", "drawableWidthPx": 256, "drawableHeightPx": 512}]}}
        self.seal(frame)
        return frame

    def framebuffer(self):
        frame = self.frame()
        frame.update(captureSurface=probe.SIMCTL_SURFACE, captureWidthPx=600, captureHeightPx=720)
        frame["nativeGeometry"].update(windowBoundsPoints=[0, 0, 200, 240], interfaceOrientationRawValue=1)
        self.seal(frame)
        return frame

    @staticmethod
    def seal(frame):
        for name in ("configuration", "nativeGeometry"):
            text = json.dumps(frame[name], separators=(",", ":"))
            frame[name + "CanonicalJson"] = text
            frame[name + "Sha256"] = probe.digest(text.encode())

    def test_reference_input_and_all_float_bits_are_locked(self):
        input_bytes = (probe.CONTRACT.parent / "input-artwork.png").read_bytes()
        _, metadata = probe.decode_png(input_bytes, (256, 256))
        self.assertEqual(self.contract["sourceArtwork"]["pngSha256"], metadata["pngSha256"])
        self.assertEqual(self.contract["sourceArtwork"]["rgbaSha256"], metadata["rgbaSha256"])
        self.assertEqual(probe.REFERENCE_SHA256, probe.digest(probe.REFERENCE.read_bytes()))
        self.assertEqual([f"{size}-phase-{time}" for size in ("256x512", "512x256") for time in (0, 1, 3, 12)],
                         [item["id"] for item in self.contract["frames"]])
        import struct
        for frame in self.contract["frames"]:
            self.assertEqual(frame["uniformLittleEndianBytesHex"], struct.pack("<7f", *frame["uniformFloat32Values"]).hex())

    def test_real_metric_contract_accepts_fractional_points_and_exact_native_pixels(self):
        probe.validate_frame(self.frame(), self.expected, self.run_id, self.contract)

    def test_simctl_full_framebuffer_and_measured_child_are_separate_exact_rectangles(self):
        frame = self.framebuffer()
        probe.validate_frame(frame, self.expected, self.run_id, self.contract)
        self.assertEqual([3, 6, 259, 518], probe.capture_rectangle(frame, self.expected))
        # Nonzero UIKit window coordinate origin is subtracted, not guessed away.
        frame["nativeGeometry"]["windowBoundsPoints"][:2] = [5, 7]
        frame["nativeGeometry"]["surfaceBoundsInWindowPoints"][:2] = [6, 9]
        self.seal(frame)
        probe.validate_frame(frame, self.expected, self.run_id, self.contract)
        self.assertEqual([3, 6, 259, 518], probe.capture_rectangle(frame, self.expected))

    def test_simctl_crop_preserves_original_png_icc_color_chunks_and_indexed_pixels(self):
        frame = self.framebuffer()
        source = Image.new("RGBA", (600, 720))
        for y in range(source.height):
            for x in range(source.width):
                source.putpixel((x, y), (x % 256, y % 256, (x + y) % 256, 255))
        icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        chunks = PngImagePlugin.PngInfo()
        chunks.add(b"gAMA", struct.pack(">I", 45455))
        chunks.add(b"cHRM", struct.pack(">8I", 31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000))
        encoded = BytesIO()
        source.save(encoded, format="PNG", pnginfo=chunks, icc_profile=icc)
        original = encoded.getvalue()
        frame["capturePngSha256"] = probe.digest(original)
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp)
            pixels, info, capture = probe.extract_capture(original, frame, self.expected, destination)
            self.assertEqual(original, (destination / "native-full-framebuffer.png").read_bytes())
            self.assertEqual(source.crop((3, 6, 259, 518)).tobytes(), pixels)
            self.assertEqual((256, 512), (info["widthPx"], info["heightPx"]))
            self.assertEqual(probe.digest(icc), info["pngColor"]["iccProfileSha256"])
            self.assertEqual(0.45455, info["pngColor"]["gamma"])
            self.assertEqual(["cHRM", "gAMA"], [chunk["type"] for chunk in info["pngColor"]["colorChunks"]])
            self.assertEqual(info["pngColor"], capture["originalPng"]["pngColor"])
            self.assertEqual(probe.digest(original), capture["originalPng"]["pngSha256"])
            self.assertTrue(capture["entireMeasuredChildIncluded"])
            self.assertTrue(capture["rgbaMatchesIndexedSourceSubset"])
            self.assertFalse(capture["resizingApplied"])

    def test_simctl_wrong_full_dimensions_orientation_outside_or_fractional_child_rejected(self):
        changes = [("frame", "captureWidthPx", 1178), ("frame", "captureHeightPx", 0),
                   ("frame", "capturePngOrientation", 6), ("frame", "capturePngOrientation", True),
                   ("frame", "captureSurface", "unverified framebuffer"),
                   ("native", "interfaceOrientationRawValue", 3),
                   ("native", "windowBoundsPoints", [0, 0, 200, 240.01]),
                   ("native", "surfaceBoundsInWindowPoints", [-1, 2, 256 / 3, 512 / 3]),
                   ("native", "surfaceBoundsInWindowPoints", [1, 100, 256 / 3, 512 / 3]),
                   ("native", "surfaceBoundsInWindowPoints", [1.00000001, 2, 256 / 3, 512 / 3]),
                   ("native", "surfaceBoundsInWindowPoints", [1, 2, 255 / 3, 512 / 3])]
        for location, key, value in changes:
            with self.subTest(key=key, value=value):
                frame = self.framebuffer()
                (frame if location == "frame" else frame["nativeGeometry"])[key] = value
                self.seal(frame)
                with self.assertRaises(ValueError):
                    probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def test_pixel_edges_allow_only_machine_roundoff_not_fractional_geometry(self):
        self.assertEqual(256, probe.integer_pixel((256 / 3) * 3, "edge"))
        self.assertEqual(256, probe.integer_pixel(255.99999999999994, "edge"))
        for value in (255.99999, 256.00001, 256.25, float("nan"), True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                probe.integer_pixel(value, "edge")

    def test_raster_or_wrong_size_metal_layer_cannot_count_as_gpu_evidence(self):
        for layers in ([], [{"deviceName": "missing", "drawableWidthPx": 256, "drawableHeightPx": 512}],
                       [{"deviceName": "Synthetic", "drawableWidthPx": 512, "drawableHeightPx": 256}]):
            with self.subTest(layers=layers):
                frame = self.frame()
                frame["nativeGeometry"]["metalLayers"] = layers
                self.seal(frame)
                with self.assertRaisesRegex(ValueError, "Metal"):
                    probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def modern_frame(self, *, cmp=True):
        frame = self.frame()
        frame.update(geometryMeasurementSequence=2, geometryMeasurementSequenceAfterCapture=3,
                     nativeGeometryStableDuringCapture=True)
        native = frame["nativeGeometry"]
        native["metalEvidenceSchemaVersion"] = 1
        native["metalLayers"][0].update(readerContractValid=True, eligibleForProbe=True, hasDevice=True,
            visible=True, requiresPresentedContents=cmp, contentsPresent=cmp,
            reader="CMPMetalLayer exported properties; CMP1.11.1" if cmp else "CAMetalLayer public properties")
        self.seal(frame)
        return frame

    def test_pinned_cmp_layer_requires_actual_device_drawable_and_presented_contents(self):
        for cmp in (False, True):
            probe.validate_frame(self.modern_frame(cmp=cmp), self.expected, self.run_id, self.contract)
        for key, value in (("hasDevice", False), ("deviceName", "missing"), ("drawableWidthPx", 512),
                           ("contentsPresent", False), ("visible", False), ("eligibleForProbe", False),
                           ("readerContractValid", False), ("reader", "guessed Metal renderer"),
                           ("requiresPresentedContents", False)):
            with self.subTest(key=key):
                frame = self.modern_frame()
                frame["nativeGeometry"]["metalLayers"][0][key] = value
                self.seal(frame)
                with self.assertRaisesRegex(ValueError, "Metal"):
                    probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def test_modern_geometry_requires_a_new_actual_measurement_after_capture(self):
        for key, value in (("geometryMeasurementSequence", True), ("geometryMeasurementSequence", 0),
                           ("geometryMeasurementSequenceAfterCapture", 2), ("geometryMeasurementSequenceAfterCapture", None),
                           ("nativeGeometryStableDuringCapture", False)):
            with self.subTest(key=key, value=value):
                frame = self.modern_frame()
                frame[key] = value
                with self.assertRaisesRegex(ValueError, "fresh stable"):
                    probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def partial_environment(self, root):
        attachments = root / "attachments"
        attachments.mkdir()
        filename = "CFCB1B1F-3597-4305-B5A4-84A4B187C3BF.json"
        record = {"testIdentifier": "KawarpGpuCaptureTests/testEightProductionShaderUniformPhasesOnUIKitMetal()",
                  "attachments": [{"exportedFileName": filename, "isAssociatedWithFailure": False,
                    "suggestedHumanReadableName": "kawarp-gpu-catalog_0_2E554FDD-177A-47E6-A5B6-24BA8D71DAF3.json"}]}
        (attachments / "manifest.json").write_text(json.dumps([record]))
        catalog = {"catalog": probe.CATALOG, "runId": self.run_id,
                   "cases": [item["id"] for item in self.contract["frames"]], "appearanceParityVerified": False}
        (attachments / filename).write_text(json.dumps(catalog))
        container = root / "container"
        case = container / "Documents/KawarpGpuProbe" / self.run_id / catalog["cases"][0]
        case.mkdir(parents=True)
        return attachments, container, case, record, catalog

    def test_partial_failed_run_preserves_only_actual_allowlisted_bytes_and_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            attachments, container, case, _, _ = self.partial_environment(root)
            original = b'\x00unchanged preprocessing diagnostic\xff'
            (case / "processed-artwork.rgba").write_bytes(original)
            draw = b'{"ready":false,"reason":"no layer observed"}'
            (case / "native-draw.json").write_bytes(draw)
            (case / "private.txt").write_text("not probe evidence")
            (container / "owner-private.txt").write_text("never exported")
            output = root / "evidence-partial-diagnostics"
            report = probe.preserve_partial_diagnostics(attachments, container, output, "Require exactly eight actual frame attachments")
            self.assertEqual("partial-failed-run-diagnostics", report["status"])
            self.assertFalse(report["completeEightFrameEvidence"])
            self.assertFalse(report["appearanceParityVerified"])
            self.assertIn("eight", report["fullCollectionError"])
            self.assertEqual(original, (output / case.name / "processed-artwork.rgba").read_bytes())
            self.assertEqual(draw, (output / case.name / "native-draw.json").read_bytes())
            self.assertEqual({"native-draw.json", "processed-artwork.rgba"}, {x["name"] for x in report["cases"][0]["copied"]})
            self.assertEqual(probe.digest(original), next(x["sha256"] for x in report["cases"][0]["copied"] if x["name"] == "processed-artwork.rgba"))
            self.assertTrue(all(row["missing"] == list(probe.PROBE_FILES) for row in report["cases"][1:]))
            self.assertFalse((output / "comparison.json").exists())
            self.assertEqual([], list(output.rglob("private.txt")) + list(output.rglob("owner-private.txt")))
            with self.assertRaisesRegex(ValueError, "already exists"):
                probe.preserve_partial_diagnostics(attachments, container, output, "another failure")

    def test_partial_catalog_rejects_wrong_missing_duplicate_or_unsafe_manifest_identity(self):
        for variant in ("missing", "duplicate", "wrong-test", "failure-attachment", "unsafe-path", "wrong-cases", "wrong-run"):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                attachments, container, _, record, catalog = self.partial_environment(root)
                filename = record["attachments"][0]["exportedFileName"]
                records = [record]
                if variant == "missing": records = []
                elif variant == "duplicate": records.append(deepcopy(record))
                elif variant == "wrong-test": record["testIdentifier"] = "AnotherTest/testSomething()"
                elif variant == "failure-attachment": record["attachments"][0]["isAssociatedWithFailure"] = True
                elif variant == "unsafe-path": record["attachments"][0]["exportedFileName"] = "../catalog.json"
                elif variant == "wrong-cases": catalog["cases"] = catalog["cases"][:-1]
                elif variant == "wrong-run": catalog["runId"] = "guessed-run"
                (attachments / "manifest.json").write_text(json.dumps(records))
                (attachments / filename).write_text(json.dumps(catalog))
                with self.assertRaises(ValueError):
                    probe.preserve_partial_diagnostics(attachments, container, root / "output", "capture failed")
                self.assertFalse((root / "output").exists())

    def test_partial_cannot_use_another_runs_files_or_linked_probe_data(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            attachments, container, case, _, catalog = self.partial_environment(root)
            other = case.parent.with_name("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
            case.parent.rename(other)
            with self.assertRaisesRegex(ValueError, "run directory is missing"):
                probe.preserve_partial_diagnostics(attachments, container, root / "output", "capture failed")
            other.rename(case.parent)
            outside = root / "private.txt"
            outside.write_text("never copied")
            try:
                (case / "input-artwork.png").symlink_to(outside)
            except OSError:
                self.skipTest("Host does not permit creating test symlinks")
            report = probe.preserve_partial_diagnostics(attachments, container, root / "output", "capture failed")
            self.assertEqual(["input-artwork.png: symlink"], report["cases"][0]["rejected"])
            self.assertFalse((root / "output" / case.name / "input-artwork.png").exists())

    def test_cli_preserves_partial_diagnostics_and_still_raises_original_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            attachments, container, case, _, _ = self.partial_environment(root)
            (case / "native-draw.json").write_text('{"ready":false}')
            args = ["collect_ios_kawarp.py", "--attachments", str(attachments), "--container", str(container), "--output", str(root / "evidence")]
            with patch.object(sys, "argv", args), patch("builtins.print"), self.assertRaisesRegex(ValueError, "exactly eight"):
                probe.main()
            report = json.loads((root / "evidence-partial-diagnostics/partial-diagnostics.json").read_text())
            self.assertFalse(report["completeEightFrameEvidence"])

    def test_changed_uniform_bytes_shader_and_geometry_hash_are_rejected(self):
        for kind in ("uniform", "shader", "geometry"):
            with self.subTest(kind=kind):
                frame = self.frame()
                if kind == "uniform": frame["configuration"]["uniformLittleEndianBytesHex"] = "00"
                elif kind == "shader": frame["configuration"]["shaderSha256"] = "0" * 64
                else: frame["nativeGeometry"]["screenScale"] = 2
                with self.assertRaises(ValueError):
                    probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def test_fake_density_or_fractional_pixel_origin_is_rejected(self):
        for kind in ("density", "origin"):
            frame = self.frame()
            if kind == "density": frame["density"] = 2.625
            else: frame["nativeGeometry"]["surfaceBoundsInWindowPoints"][0] = 1.1
            self.seal(frame)
            with self.assertRaises(ValueError):
                probe.validate_frame(frame, self.expected, self.run_id, self.contract)

    def test_exact_pixel_comparison_exposes_one_channel_difference_without_threshold(self):
        reference = bytes([30, 40, 50, 255, 60, 70, 80, 255])
        changed = bytes([31, 40, 50, 255, 60, 70, 80, 255])
        report, visible = probe.difference(changed, reference)
        self.assertEqual({"matchesEveryPixel": False, "changedPixelCount": 1, "totalPixelCount": 2,
                          "maximumChannelDifference": 1}, report)
        self.assertEqual(bytes([1, 0, 0, 255, 0, 0, 0, 255]), visible)

    def test_png_is_never_resized_to_expected_dimensions(self):
        out = BytesIO()
        Image.new("RGBA", (2, 3), (1, 2, 3, 255)).save(out, format="PNG")
        with self.assertRaisesRegex(ValueError, "dimensions"):
            probe.decode_png(out.getvalue(), (3, 2))

    def test_pixel_formats_requiring_conversion_and_wrong_capture_hash_are_rejected(self):
        for mode in ("P", "I;16"):
            out = BytesIO()
            Image.new(mode, (256, 512)).save(out, format="PNG")
            with self.assertRaisesRegex(ValueError, "8-bit RGB/RGBA"):
                probe.decode_png(out.getvalue(), (256, 512))
        out = BytesIO()
        Image.new("RGBA", (256, 512), (1, 2, 3, 255)).save(out, format="PNG")
        frame = self.frame() | {"capturePngSha256": "0" * 64}
        with tempfile.TemporaryDirectory() as temp, self.assertRaisesRegex(ValueError, "screenshot hash"):
            probe.extract_capture(out.getvalue(), frame, self.expected, Path(temp))

    def test_kawarp_bundle_is_separate_and_resources_require_explicit_debug_switch(self):
        def names(output):
            return {item.attrib["BlueprintName"] for item in ET.fromstring(output).findall("./TestAction/Testables/TestableReference/BuildableReference")}
        self.assertEqual({"IcyLyricsTests", "IcyLyricsUITests"}, names(project.scheme_output))
        self.assertEqual({"IcyLyricsExtendedUITests"}, names(project.extended_scheme_output))
        self.assertEqual({"IcyLyricsKawarpUITests"}, names(project.kawarp_scheme_output))
        target = project.objects[project.uid("target:IcyLyricsKawarpUITests")]
        self.assertEqual([project.app_target], [project.objects[ref]["target"] for ref in target["dependencies"]])
        script = project.objects[project.uid("kawarpprobeassets")]["shellScript"]
        self.assertIn('[ "$CONFIGURATION" = Debug ] && [ "${ICY_KAWARP_PROBE:-NO}" = YES ]', script)
        self.assertIn('/bin/rm -rf "$probe_assets"', script)

    def test_collector_copies_only_matching_run_files_and_reports_exact_eight_frame_comparison(self):
        self.verify_synthetic_collection(framebuffer=False)

    def test_collector_compares_all_eight_entire_children_from_original_full_framebuffers(self):
        self.verify_synthetic_collection(framebuffer=True)

    def verify_synthetic_collection(self, *, framebuffer):
        # Synthetic app/container metadata exercises collection, not UIKit/Metal.
        with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(probe.REFERENCE) as archive:
            root = Path(temp)
            attachments = root / "attachments"
            attachments.mkdir()
            container = root / "container"
            container.mkdir()
            (container / "owner-private-data.txt").write_text("must never be exported")
            (attachments / "catalog.json").write_text(json.dumps({"catalog": probe.CATALOG, "runId": self.run_id,
                "cases": [item["id"] for item in self.contract["frames"]]}))
            prefix = "android36-kawarp-gpu-phases/"
            for expected in self.contract["frames"]:
                case_id = expected["id"]
                directory = container / "Documents/KawarpGpuProbe" / self.run_id / case_id
                directory.mkdir(parents=True)
                for name in ("input-artwork.png", "input-artwork.rgba", "processed-artwork.png", "processed-artwork.rgba"):
                    (directory / name).write_bytes(archive.read(prefix + name))
                (directory / "native-kawarp.sksl").write_bytes(archive.read(prefix + "original-kawarp.agsl"))
                frame = self.framebuffer() if framebuffer else self.frame()
                width, height = expected["drawWidthPx"], expected["drawHeightPx"]
                frame.update(id=case_id, drawWidthPx=width, drawHeightPx=height)
                if not framebuffer:
                    frame.update(captureWidthPx=width, captureHeightPx=height)
                for key in ("uniformFloat32Values", "uniformFloat32BitsHex", "uniformLittleEndianBytesHex"):
                    frame["configuration"][key] = expected[key]
                frame["configuration"]["processedRgbaSha256"] = self.contract["processedArtwork"]["rgbaSha256"]
                frame["nativeGeometry"]["surfaceBoundsInWindowPoints"] = [1, 2, width / 3, height / 3]
                frame["nativeGeometry"]["metalLayers"][0].update(drawableWidthPx=width, drawableHeightPx=height)
                frame["preparation"] = {"processedRgbaSha256": self.contract["processedArtwork"]["rgbaSha256"]}
                self.seal(frame)
                png = archive.read(prefix + case_id + ".png")
                if framebuffer:
                    # Synthetic full-frame context proves only the collector's
                    # indexing, never that UIKit produced these Android pixels.
                    with Image.open(BytesIO(png)) as child:
                        full = Image.new("RGBA", (600, 720), (12, 13, 14, 255))
                        full.paste(child, (3, 6))
                    encoded = BytesIO()
                    full.save(encoded, format="PNG")
                    png = encoded.getvalue()
                frame["capturePngSha256"] = probe.digest(png)
                (attachments / (case_id + ".png")).write_bytes(png)
                (attachments / (case_id + ".json")).write_text(json.dumps(frame))
                (directory / "native-draw.json").write_text(json.dumps(frame))
                (directory / "preparation.json").write_text(json.dumps(frame["preparation"]))
            result = probe.collect(attachments, container, root / "output")
            self.assertEqual(8, len(result["frames"]))
            self.assertTrue(result["completeEightFrameEvidence"])
            self.assertTrue(result["matchesEveryGpuPixel"])
            self.assertFalse(result["appearanceParityVerified"])
            self.assertEqual([], list((root / "output").rglob("owner-private-data.txt")))
            backend = "simctl-framebuffer-measured-child" if framebuffer else "legacy-xcui-child"
            self.assertTrue(all(frame["capture"]["backend"] == backend for frame in result["frames"]))
            self.assertTrue(all(frame["capture"]["rgbaMatchesIndexedSourceSubset"] for frame in result["frames"]))


if __name__ == "__main__":
    unittest.main()
