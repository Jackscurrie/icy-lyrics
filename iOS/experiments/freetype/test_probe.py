"""Local integrity/result-gate tests; these do not claim a native build."""
import hashlib
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
import zlib

import run_probe as probe


def png(path, width=256, rgba=(20, 100, 230, 255), mode=0):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rows, previous = bytearray(), bytearray(width * 4)
    for y in range(256):
        raw = bytearray(width * 4)
        if 20 <= y < 25:
            for x in range(30, 35):
                raw[x * 4:x * 4 + 4] = bytes(rgba)
        filtered = bytearray(raw)
        for x in range(len(raw)):
            a, b, c = raw[x - 4] if x >= 4 else 0, previous[x], previous[x - 4] if x >= 4 else 0
            predictors = [0, a, b, (a + b) // 2]
            p = a + b - c
            distances = abs(p - a), abs(p - b), abs(p - c)
            predictors.append((a, b, c)[distances.index(min(distances))])
            filtered[x] = (raw[x] - predictors[mode]) & 255
        rows += bytes([mode]) + filtered
        previous = raw
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, 256, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def matrix(self):
        small, wide = self.root / "small", self.root / "wide"
        png(small)
        png(wide, 1400)
        rows = []
        ids = probe.COLOR_IDS | {f"roboto-{weight}-{size:.1f}-linear-{linear}"
                                 for weight in (400, 700) for size in (73.0, 73.5, 84.0) for linear in (0, 1)}
        for backend in ("freetype", "coretext"):
            for name in sorted(ids):
                filename = f"{backend}-{name}.png"
                (self.root / filename).write_bytes((wide if name.startswith("roboto-") else small).read_bytes())
                rows.append({"backend": backend, "id": name, "png": filename,
                             "unshapedGlyphAdvanceSumPx": 100,
                             "fontMetrics": {"ascent": -90, "descent": 20, "leading": 0}})
        (self.root / "metrics.json").write_text(json.dumps({"samples": rows}))
        return rows

    def test_all_png_filter_modes_decode_without_resizing(self):
        for mode in range(5):
            path = self.root / f"{mode}.png"
            png(path, mode=mode)
            evidence = probe.image_evidence(path)
            self.assertEqual([30, 20, 35, 25], evidence["inkBounds"])
            self.assertEqual(25, evidence["chromaPixels"])

    def test_valid_complete_result_has_only_bounded_native_claim(self):
        self.matrix()
        result = probe.validate_results(self.root)
        self.assertEqual(34, result["samples"])
        self.assertFalse(result["iosRuntimeVerified"])
        self.assertFalse(result["composeShapingVerified"])
        self.assertFalse(result["androidPixelParityVerified"])

    def test_blank_freetype_color_glyph_fails(self):
        self.matrix()
        png(self.root / "freetype-colrv1-heart.png", rgba=(0, 0, 0, 0))
        with self.assertRaisesRegex(ValueError, "Blank required"):
            probe.validate_results(self.root)

    def test_monochrome_replacement_fails_color_proof(self):
        self.matrix()
        png(self.root / "freetype-cbdt-us.png", rgba=(255, 255, 255, 255))
        with self.assertRaisesRegex(ValueError, "colored pixels"):
            probe.validate_results(self.root)

    def test_missing_sample_fails_complete_matrix(self):
        rows = self.matrix()
        (self.root / "metrics.json").write_text(json.dumps({"samples": rows[:-1]}))
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            probe.validate_results(self.root)

    def test_coretext_color_load_failure_is_retained_not_claimed_as_pass(self):
        rows = self.matrix()
        row = next(r for r in rows if r["backend"] == "coretext" and r["id"] == "colrv1-heart")
        row.pop("png")
        row["loaded"] = False
        (self.root / "metrics.json").write_text(json.dumps({"samples": rows}))
        evidence = probe.validate_results(self.root)
        self.assertNotIn("coretext/colrv1-heart", evidence["images"])

    def test_nonfinite_metrics_rejected(self):
        rows = self.matrix()
        rows[0]["fontMetrics"]["ascent"] = float("nan")
        (self.root / "metrics.json").write_text(json.dumps({"samples": rows}))
        with self.assertRaises(ValueError):
            probe.validate_results(self.root)

    def test_archive_traversal_and_escaping_symlinks_rejected(self):
        for name, link in [("../outside", None), ("file", "../outside")]:
            archive = self.root / "input.tgz"
            with tarfile.open(archive, "w:gz") as bundle:
                member = tarfile.TarInfo(name)
                if link:
                    member.type = tarfile.SYMTYPE
                    member.linkname = link
                bundle.addfile(member)
            with self.assertRaises(ValueError):
                probe.extract_tar(archive, self.root / "source")
        self.assertFalse((self.root / "outside").exists())

    def test_contained_source_symlink_materializes_identical_bytes(self):
        archive = self.root / "input.tgz"
        with tarfile.open(archive, "w:gz") as bundle:
            member = tarfile.TarInfo("source")
            member.size = 5
            bundle.addfile(member, io.BytesIO(b"hello"))
            link = tarfile.TarInfo("sub/link")
            link.type = tarfile.SYMTYPE
            link.linkname = "../source"
            bundle.addfile(link)
        probe.extract_tar(archive, self.root / "output")
        self.assertEqual(b"hello", (self.root / "output/sub/link").read_bytes())
        self.assertFalse((self.root / "output/sub/link").is_symlink())

    def test_hash_mismatch_refuses_cached_input_without_network(self):
        entry = {"name": "test.tgz", "sha256": "0" * 64, "bytes": 3}
        (self.root / (entry["sha256"] + "-" + entry["name"])).write_bytes(b"bad")
        with self.assertRaisesRegex(ValueError, "differs from lock"):
            probe.fetch(entry, self.root)

    def test_locked_original_fonts_still_match(self):
        lock = json.loads((probe.HERE / "sources.lock.json").read_text())
        for entry in lock["fonts"]:
            self.assertEqual(entry["sha256"], probe.digest(probe.IOS / "shared/ui/assets/font" / entry["file"]))


if __name__ == "__main__":
    unittest.main()
