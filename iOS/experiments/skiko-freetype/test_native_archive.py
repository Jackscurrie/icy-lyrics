"""Synthetic binary contracts only; real archive acceptance requires a Mac build."""
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest

import native_archive as native


def object_bytes(platform=2, minimum=0x100000, cpu=native.ARM64, subtype=0, file_type=1, commands=None):
    commands = [struct.pack("<6I", 0x32, 24, platform, minimum, 0x1A0400, 0)] if commands is None else commands
    payload = b"".join(commands)
    return struct.pack("<8I", 0xFEEDFACF, cpu, subtype, file_type, len(commands), len(payload), 0, 0) + payload


def member(name: bytes, data: bytes) -> bytes:
    if len(name) > 16: raise ValueError("Use BSD extended name helper")
    header = name.ljust(16) + b"0".ljust(12) + b"0".ljust(6) + b"0".ljust(6) + b"100644".ljust(8) + str(len(data)).encode().ljust(10) + b"`\n"
    return header + data + (b"\n" if len(data) % 2 else b"")


def bsd_member(name: str, data: bytes) -> bytes:
    encoded = name.encode() + b"\0" * ((-len(name.encode())) % 8)
    return member(f"#1/{len(encoded)}".encode(), encoded + data)


class NativeArchiveTest(unittest.TestCase):
    def setUp(self):
        build = Path(__file__).resolve().parents[2] / "build"
        build.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=build, prefix="native-archive-test-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, data, name="libprobe.a"):
        path = self.root / name
        path.write_bytes(data)
        return path

    def inspect(self, data, **options):
        return native.inspect_archive(self.archive(data), expected_platform=options.pop("expected_platform", 2), **options)

    def test_normal_and_bsd_long_names_keep_every_object_and_hash(self):
        obj = object_bytes()
        archive = b"!<arch>\n" + member(b"one.o/", obj) + bsd_member("a-very-long-compiled-object-name.o", obj)
        result = self.inspect(archive)
        self.assertEqual(2, result["objectCount"])
        self.assertEqual([2], result["platforms"])
        self.assertEqual(["16.0.0"], result["minimumOSVersions"])
        self.assertEqual(hashlib.sha256(archive).hexdigest(), result["sha256"])
        self.assertEqual(hashlib.sha256(obj).hexdigest(), result["members"][1]["sha256"])
        self.assertEqual("a-very-long-compiled-object-name.o", result["members"][1]["name"])

    def test_gnu_long_name_references_must_point_to_exact_safe_entry(self):
        names = b"long-compiled-file-name.o/\nsecond-long-file-name.o/\n"
        archive = b"!<arch>\n" + member(b"//", names) + member(b"/0", object_bytes(7))
        self.assertEqual("long-compiled-file-name.o", self.inspect(archive, expected_platform=7)["members"][0]["name"])
        for bad in (b"/1", b"/999"):
            with self.assertRaisesRegex(ValueError, "entry boundary"):
                self.inspect(b"!<arch>\n" + member(b"//", names) + member(bad, object_bytes()))
        with self.assertRaises(ValueError):
            self.inspect(b"!<arch>\n" + member(b"//", b"../escape.o/\n") + member(b"/0", object_bytes()))

    def test_bsd_symbol_index_requires_existing_object_and_terminated_name(self):
        strings = b"_actual_definition\0"
        index_size = 4 + 8 + 4 + len(strings)
        object_offset = 8 + len(member(b"__.SYMDEF", b"\0" * index_size))
        index = struct.pack("<3I", 8, 0, object_offset) + struct.pack("<I", len(strings)) + strings
        archive = b"!<arch>\n" + member(b"__.SYMDEF", index) + member(b"object.o/", object_bytes())
        self.assertEqual(["_actual_definition"], self.inspect(archive)["archiveIndexSymbols"])
        broken = bytearray(index)
        struct.pack_into("<I", broken, 8, object_offset + 1)
        with self.assertRaisesRegex(ValueError, "non-object"):
            self.inspect(b"!<arch>\n" + member(b"__.SYMDEF", broken) + member(b"object.o/", object_bytes()))
        with self.assertRaises(ValueError):
            self.inspect(b"!<arch>\n" + member(b"__.SYMDEF", index[:-1]) + member(b"object.o/", object_bytes()))

    def test_gnu_big_endian_symbol_index_and_bsd64_index(self):
        strings = b"_symbol\0"
        index_size = 8 + len(strings)
        object_offset = 8 + len(member(b"/", b"\0" * index_size))
        gnu = struct.pack(">2I", 1, object_offset) + strings
        self.assertEqual(["_symbol"], self.inspect(b"!<arch>\n" + member(b"/", gnu) + member(b"a.o/", object_bytes()))["archiveIndexSymbols"])
        index_size = 8 + 16 + 8 + len(strings)
        object_offset = 8 + len(bsd_member("__.SYMDEF_64 SORTED", b"\0" * index_size))
        bsd64 = struct.pack("<4Q", 16, 0, object_offset, len(strings)) + strings
        self.assertEqual(["_symbol"], self.inspect(b"!<arch>\n" + bsd_member("__.SYMDEF_64 SORTED", bsd64) + member(b"a.o/", object_bytes()))["archiveIndexSymbols"])

    def test_architecture_platform_file_type_and_newer_minimum_are_rejected(self):
        for options in ({"cpu": 0x01000007}, {"subtype": 2}, {"file_type": 6},
                        {"file_type": 2}, {"platform": 7}, {"platform": 1}, {"minimum": 0x100100}, {"minimum": 0}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.inspect(b"!<arch>\n" + member(b"a.o/", object_bytes(**options)))
        with self.assertRaises(ValueError):
            self.inspect(b"!<arch>\n" + member(b"device.o/", object_bytes(2)) + member(b"sim.o/", object_bytes(7)))

    def test_legacy_version_min_requires_explicit_device_only_justification(self):
        legacy = object_bytes(commands=[struct.pack("<4I", 0x25, 16, 0xF0000, 0x100000)])
        archive = b"!<arch>\n" + member(b"legacy.o/", legacy)
        with self.assertRaisesRegex(ValueError, "explicit device-only"):
            self.inspect(archive)
        result = self.inspect(archive, allow_legacy_device_version_min=True)
        self.assertEqual(["15.0.0"], result["minimumOSVersions"])
        with self.assertRaises(ValueError):
            self.inspect(archive, expected_platform=7, allow_legacy_device_version_min=True)

    def test_thin_fat_bitcode_truncated_headers_payload_and_alignment_fail(self):
        for archive in (b"!<thin>\n", b"!<arch>\nshort", b"!<arch>\n" + member(b"a.o/", object_bytes())[:-1],
                        b"!<arch>\n" + member(b"a.o/", b"BC\xc0\xde"),
                        b"!<arch>\n" + member(b"a.o/", b"\xca\xfe\xba\xbe" + b"\0" * 64),
                        b"!<arch>\n" + member(b"a.o/", b"\xcf\xfa\xed\xfe"),
                        b"!<arch>\n" + member(b"a.o/", object_bytes() + b"x")[:-1] + b"X"):
            with self.subTest(prefix=archive[:30]), self.assertRaises(ValueError):
                self.inspect(archive)

    def test_missing_duplicate_or_truncated_build_version_fails(self):
        version = struct.pack("<6I", 0x32, 24, 2, 0x100000, 0x1A0400, 0)
        for commands in ([], [version, version], [struct.pack("<6I", 0x32, 24, 2, 0x100000, 0x1A0400, 1)],
                         [struct.pack("<2I", 0x32, 128)]):
            with self.subTest(commands=commands), self.assertRaises(ValueError):
                self.inspect(b"!<arch>\n" + member(b"a.o/", object_bytes(commands=commands)))

    def test_declared_segment_and_symbol_payload_cannot_extend_past_file(self):
        version = struct.pack("<6I", 0x32, 24, 2, 0x100000, 0x1A0400, 0)
        segment = struct.pack("<2I16s4Q4I", 0x19, 72, b"__TEXT", 0, 10, 9999, 10, 7, 7, 0, 0)
        symbols = struct.pack("<6I", 2, 24, 9999, 1, 9999, 10)
        for command in (segment, symbols):
            with self.assertRaisesRegex(ValueError, "out-of-range"):
                self.inspect(b"!<arch>\n" + member(b"a.o/", object_bytes(commands=[version, command])))

    def test_optional_command_payloads_and_two_component_minimum_limit(self):
        version = struct.pack("<6I", 0x32, 24, 2, 0x100000, 0x1A0400, 0)
        dynamic = struct.pack("<20I", 0xB, 80, *([0] * 18))
        options = struct.pack("<3I", 0x2D, 16, 1) + b"-lx\0"
        valid = b"!<arch>\n" + member(b"a.o/", object_bytes(commands=[version, dynamic, options]))
        self.assertEqual(1, self.inspect(valid, max_min_os=(16, 0))["objectCount"])
        for bad in (struct.pack("<2I", 0xB, 8), struct.pack("<3I", 0x2D, 16, 2) + b"-lx\0",
                    struct.pack("<2I", 0x1234, 8)):
            with self.assertRaises(ValueError):
                self.inspect(b"!<arch>\n" + member(b"a.o/", object_bytes(commands=[version, bad])))

    def test_empty_archive_allowance_is_explicit_and_bound_to_named_library(self):
        empty = b"!<arch>\n"
        with self.assertRaisesRegex(ValueError, "no Mach-O"):
            self.inspect(empty)
        with self.assertRaisesRegex(ValueError, "restricted"):
            self.inspect(empty, allow_empty=True)
        path = self.archive(empty, "libwebp_sse41.a")
        with self.assertRaises(ValueError):
            native.inspect_archive(path, expected_platform=2)
        result = native.inspect_archive(path, expected_platform=2, allow_empty=True)
        self.assertEqual(0, result["objectCount"])
        self.assertEqual([], result["platforms"])
        self.assertEqual("libwebp_sse41.a", result["emptyAllowance"])


if __name__ == "__main__":
    unittest.main()
