"""Driver contract tests; synthetic binaries never establish native execution."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

import build_package as build
from gn_args import arguments
from native_archive import inspect_archive
from test_native_archive import member, object_bytes


class PackageDriverTest(unittest.TestCase):
    def setUp(self):
        build.IOS.joinpath("build").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=build.IOS / "build", prefix="skiko-package-test-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content if isinstance(content, bytes) else content.encode())
        return path

    def native(self, target):
        return b"!<arch>\n" + member(b"actual.o/", object_bytes(build.LOCK["targets"][target]["platform"]))

    def test_patch_requires_exact_upstream_and_pins_wrapper_without_changing_default_factory(self):
        source = self.write("skiko/" + build.SDK_SOURCE, build.SDK_OLD)
        wrapper = self.write("skiko/skiko/gradle/wrapper/gradle-wrapper.properties",
                             "distributionUrl=" + build.LOCK["producerGradleDistribution"]["url"].replace(":", r"\:") + "\n")
        core = self.write("skiko/skiko/src/commonMain/cpp/common/FontMgrDefaultFactory.cc", "return SkFontMgr_New_CoreText(nullptr);")
        original = core.read_bytes()
        records = build.patch_producer(self.root / "skiko")
        self.assertEqual(4, len(records))
        self.assertNotIn("/Applications/Xcode.app", source.read_text())
        self.assertIn("DEVELOPER_DIR", source.read_text())
        self.assertIn("distributionSha256Sum=" + build.LOCK["producerGradleDistribution"]["sha256"], wrapper.read_text())
        self.assertEqual(original, core.read_bytes())
        self.assertEqual((build.HERE / "overlay/FreeTypeTypeface.cc").read_bytes(),
                         (self.root / "skiko/skiko/src/nativeJsMain/cpp/FreeTypeTypeface.cc").read_bytes())
        with self.assertRaisesRegex(ValueError, "no longer matches"):
            build.patch_producer(self.root / "skiko")

    def test_recipe_changes_when_the_factory_or_consumer_changes(self):
        original = build.recipe()
        with patch.object(build, "digest", side_effect=lambda path: "changed" if path.name == "FreeTypeTypeface.cc" else hashlib.sha256(path.read_bytes()).hexdigest()):
            self.assertNotEqual(original, build.recipe())

    def test_generic_arm64_patch_removes_only_the_extra_architecture(self):
        source = self.write("skia/gn/skia/BUILD.gn", "unchanged-prefix\n" + build.ARM64_OLD + "\nunchanged-suffix")
        build.patch_skia(self.root / "skia")
        self.assertIn("unchanged-prefix", source.read_text())
        self.assertIn("unchanged-suffix", source.read_text())
        self.assertIn('"arm64"', source.read_text())
        self.assertNotIn('"arm64e"', source.read_text())
        with self.assertRaisesRegex(ValueError, "no longer matches"):
            build.patch_skia(self.root / "skia")

    def test_prepared_consumer_must_match_recipe_not_only_its_own_manifest(self):
        copied = self.write("consumer/source.kt", "old copy")
        manifest = self.write("source-files.json", "{}")
        recipe = {"files": {"consumer/source.kt": hashlib.sha256(b"reviewed new source").hexdigest()}}
        record = {"lockSha256": build.digest(build.LOCK_PATH), "recipe": recipe, "sourceFilesSha256": build.digest(manifest),
                  "tools": {}, "consumerFiles": {"source.kt": build.digest(copied)}}
        self.write("prepared.json", json.dumps(record))
        with patch.object(build, "recipe", return_value=recipe), patch.object(build, "snapshot", return_value={}), \
                patch.dict(build.LOCK, {"fonts": []}):
            with self.assertRaisesRegex(ValueError, "differs from the reviewed recipe"):
                build.validate_prepared(self.root)

    def test_explicit_text_wrapper_normalization_is_cross_platform_and_not_binary(self):
        self.write("wrapper.properties", b"first=value\r\nsecond=value\n")
        binary = self.write("wrapper.jar", b"\0\r\n\xff")
        with patch.object(build, "IOS", self.root), patch.dict(build.LOCK, {"consumerWrapperTextNormalization": {"wrapper.properties": "lf"}}):
            self.assertEqual(b"first=value\nsecond=value\n", build.consumer_wrapper_bytes("wrapper.properties"))
            self.assertEqual(binary.read_bytes(), build.consumer_wrapper_bytes("wrapper.jar"))
            self.write("wrapper.properties", b"first=value\rsecond=value")
            with self.assertRaisesRegex(ValueError, "bare-CR"):
                build.consumer_wrapper_bytes("wrapper.properties")

    def test_native_arguments_keep_full_modules_and_exact_platform_target(self):
        device = arguments("ios", "/Xcode Device SDK", "/Xcode/clang", "/Xcode/clang++")
        simulator = arguments("iosSim", "/Xcode Simulator SDK", "/Xcode/clang", "/Xcode/clang++")
        self.assertIn('ios_use_simulator = false', device)
        self.assertIn('ios_use_simulator = true', simulator)
        for text in (device, simulator):
            for flag in ("skia_use_freetype", "skia_use_fonthost_mac", "skia_enable_fontmgr_custom_embedded", "skia_use_metal", "skia_enable_skottie"):
                self.assertIn(flag + " = true", text)
            self.assertIn('ios_min_target = "12.0"', text)
            self.assertNotIn("skia_enable_pdf = false", text)
            self.assertNotIn("skia_use_sfntly", text)
        with self.assertRaises(ValueError):
            arguments("iosX64", "/sdk", "clang", "clang++")

    def test_gn_graph_refuses_an_unlocked_dependency_even_if_present(self):
        self.write("skia/third_party/externals/unreviewed/file.cc", "source")
        gn = self.write("gn", "tool")
        graph = {"//:skia": {"sources": ["//third_party/externals/unreviewed/file.cc"]}, "//:modules": {}}
        with patch.object(build, "command", side_effect=["2175 (b2afae122eeb)", "", json.dumps(graph)]):
            with self.assertRaisesRegex(ValueError, "unlocked source"):
                build.gn_graph(self.root, "ios", gn, "/sdk", "clang", "clang++")

    def test_zip_writer_rejects_traversal_and_refuses_overwrite(self):
        source = self.write("input", "data")
        output = self.root / "output.zip"
        build.deterministic_zip(output, {"safe/file": source})
        with self.assertRaises(FileExistsError):
            build.deterministic_zip(output, {"safe/file": source})
        with self.assertRaises(ValueError):
            build.deterministic_zip(self.root / "bad.zip", {"../escape": source})

    def test_failed_stage_preserves_original_error_and_writes_diagnostic_record(self):
        with patch("sys.argv", ["build_package.py", "--stage", "graph", "--work-dir", str(self.root)]), \
                patch.object(build, "execute", side_effect=ValueError("deliberate native-stage failure")):
            with self.assertRaisesRegex(ValueError, "deliberate native-stage failure"):
                build.main()
        records = list((self.root / "runs").glob("*.json"))
        self.assertEqual(1, len(records))
        self.assertEqual("stage-failed", json.loads(records[0].read_text())["status"])

    def native_input_zip(self, mutate=None):
        target = "ios"
        self.write("source-files.json", "{}")
        paths = {}
        libraries = {}
        prefix = "out/" + build.LOCK["targets"][target]["directory"] + "/"
        for name in build.LIBRARIES:
            source = self.write("input/" + name, self.native(target))
            libraries[name] = inspect_archive(source, expected_platform=2)
            paths[prefix + name] = source
        args = self.write("args.gn", "exact args")
        closure = self.write("source-closure.json", "exact closure")
        record = {"target": target, "version": build.VERSION, "lockSha256": build.digest(build.LOCK_PATH),
                  "sourceFilesSha256": build.digest(self.root / "source-files.json"), "argsSha256": build.digest(args),
                  "sourceClosureSha256": build.digest(closure), "libraries": libraries}
        paths.update({"args.gn": args, "source-closure.json": closure,
                      "native-libraries.json": self.write("native-libraries.json", json.dumps(record))})
        paths["notices/NOTICE.md"] = self.write("notices/NOTICE.md", "Synthetic fixture notice; not native build evidence")
        if mutate:
            mutate(paths, record)
        archive = self.root / "native.zip"
        build.deterministic_zip(archive, paths)
        build.write_json(archive.with_suffix(".json"), {"sha256": build.digest(archive), "bytes": archive.stat().st_size,
                                                        "target": target, "lockSha256": build.digest(build.LOCK_PATH)})
        return archive

    def test_split_job_artifact_import_validates_actual_objects_and_source_identity(self):
        self.assertEqual("ios", build.import_skia_archive(self.root, self.native_input_zip()))
        output = self.root / "skia/out" / build.LOCK["targets"]["ios"]["directory"] / "libskia.a"
        self.assertEqual(2, inspect_archive(output, expected_platform=2)["platforms"][0])

    def test_split_job_import_rejects_library_bytes_changed_after_inventory(self):
        def mutate(paths, record):
            paths["out/" + build.LOCK["targets"]["ios"]["directory"] + "/libskia.a"].write_bytes(self.native("iosSim"))
        with self.assertRaisesRegex(ValueError, "checksum differs"):
            build.import_skia_archive(self.root, self.native_input_zip(mutate))

    def test_split_job_import_rejects_source_closure_changes(self):
        def mutate(paths, record):
            paths["source-closure.json"].write_text("changed")
        with self.assertRaisesRegex(ValueError, "source closure changed"):
            build.import_skia_archive(self.root, self.native_input_zip(mutate))

    def test_split_job_import_rejects_transfer_digest_before_extracting(self):
        archive = self.native_input_zip()
        record = json.loads(archive.with_suffix(".json").read_text())
        record["sha256"] = "0" * 64
        archive.with_suffix(".json").write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "producing job"):
            build.import_skia_archive(self.root, archive)
        self.assertFalse((self.root / "skia/out").exists())

    def test_split_job_import_requires_source_notices_inside_binary_zip(self):
        def mutate(paths, record):
            del paths["notices/NOTICE.md"]
        with self.assertRaisesRegex(ValueError, "Unexpected native artifact"):
            build.import_skia_archive(self.root, self.native_input_zip(mutate))

    def repository(self, omit_interop=False, wrong_platform=False):
        repository = self.root / "repo"
        self.write("repo/org/jetbrains/skiko/skiko/" + build.VERSION + "/skiko.pom", "metadata")
        for target, module in (("ios", "skiko-iosarm64"), ("iosSim", "skiko-iossimulatorarm64")):
            path = repository / "org/jetbrains/skiko" / module / build.VERSION
            path.mkdir(parents=True)
            if not omit_interop:
                (path / f"{module}-{build.VERSION}-cinterop-uikit.klib").write_bytes(b"interop")
            with zipfile.ZipFile(path / f"{module}-{build.VERSION}.klib", "w") as bundle:
                bundle.writestr("default/manifest", "native_targets=" + build.LOCK["targets"][target]["nativeTarget"])
                names = list(build.LIBRARIES) + [f"skiko-native-bridges-{target}-arm64.a"]
                for name in names:
                    content = self.native("iosSim" if wrong_platform else target)
                    bundle.writestr("default/targets/native/" + name, content)
                    source = ("skia/out/" + build.LOCK["targets"][target]["directory"] + "/" + name
                              if name in build.LIBRARIES else "skiko/skiko/build/nativeBridges/static/" + target + "-arm64/" + name)
                    self.write(source, content)
        return repository

    def test_local_publication_contains_both_native_targets_and_all_42_actual_archives(self):
        result = build.validate_maven_repository(self.root, self.repository())
        self.assertEqual(42, len(result["nativeArchives"]))
        self.assertEqual({2, 7}, {platform for item in result["nativeArchives"] for platform in item["platforms"]})

    def test_publication_rejects_missing_uikit_cinterop(self):
        with self.assertRaisesRegex(ValueError, "cinterop-uikit"):
            build.validate_maven_repository(self.root, self.repository(omit_interop=True))

    def test_publication_rejects_platform_mismatch_inside_klib(self):
        with self.assertRaises(ValueError):
            build.validate_maven_repository(self.root, self.repository(wrong_platform=True))

    def test_native_consumer_does_not_accept_skipped_or_missing_tests(self):
        path = "consumer/build/test-results/iosSimulatorArm64Test/TEST-case.xml"
        self.write(path, '<testsuite><testcase name="any"><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, "failed or was skipped"):
            build.validate_consumer_results(self.root)
        self.write(path, '<testsuite/>')
        with self.assertRaisesRegex(ValueError, "all four"):
            build.validate_consumer_results(self.root)

    def consumer_fixture(self):
        names = ["invalidDataClosedDataAndCollectionIndicesHaveDefinedBehavior",
                 "returnedFaceSurvivesDataCloseAndVariationCloneWhileCoreTextDefaultStillWorks",
                 "originalColrv1AndCbdtGlyphsRenderColoredPixelsAfterInputDataCloses",
                 "originalFlagSequencesShapeThroughTheFullSkParagraphAndHarfBuzzPackage"]
        self.write("consumer/build/test-results/iosSimulatorArm64Test/TEST-case.xml",
                   "<testsuite>" + "".join('<testcase name="' + n + '[iosSimulatorArm64]"/>' for n in names) + "</testsuite>")
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        pixels = b"\xff\0\0\xff" * 256 * 256
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 256, 256, 8, 6, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress((b"\0" + pixels[:1024]) * 256)) + chunk(b"IEND", b"")
        ids = ["colrv1-snowflake", "colrv1-musical-note", "colrv1-heart", "cbdt-canada", "cbdt-us", "paragraph-canada", "paragraph-us"]
        for name in ids:
            self.write("reports/consumer-output/" + name + ".png", png)
            self.write("reports/consumer-output/" + name + ".rgba", pixels)
            self.write("reports/consumer-output/" + name + ".json", json.dumps({"id": name, "chromaPixels": 65536,
                       "metalVerified": False, "androidPixelParityVerified": False}))
        self.write("reports/consumer-output/font-metrics.json", '{"normalGlyphAdvanceSum":825,"boldGlyphAdvanceSum":839}')

    def test_native_consumer_validates_png_pixels_independently_without_claiming_metal(self):
        self.consumer_fixture()
        evidence = build.validate_consumer_results(self.root)
        self.assertEqual(7, len(evidence["images"]))
        self.assertFalse(evidence["metalExecutionVerified"])
        self.assertFalse(evidence["androidPixelParityVerified"])

    def test_native_consumer_rejects_png_raw_divergence(self):
        self.consumer_fixture()
        self.write("reports/consumer-output/cbdt-canada.rgba", b"\0" * 256 * 256 * 4)
        with self.assertRaisesRegex(ValueError, "raw RGBA"):
            build.validate_consumer_results(self.root)


if __name__ == "__main__":
    unittest.main()
