"""Real age roundtrips and rejection cases; fixtures are not represented as real IPAs."""
from pathlib import Path
import io
import json
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from bootstrap_age import WORK, bootstrap, digest
from owner_transfer import (FILES, LABEL, age_transform, checked_members, inspect_delivery,
                            public_recipient, publish_directory, setup_identity)
from package_ipa import corresponding_source

class AgeTransport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.age, cls.keygen = bootstrap()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="transport-test-", dir=WORK)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.identity = self.root / "identity.agekey"
        self.public = self.root / "recipient.txt"
        self.recipient = setup_identity(self.keygen, self.identity, self.public)
        self.source = self.root / "binary-fixture.dat"
        self.source.write_bytes(bytes(range(256)) * 1024)
        self.encrypted = self.root / "fixture.age"

    def encrypt(self):
        age_transform(self.age, self.source, self.encrypted, recipient=self.recipient)

    def test_real_age_roundtrip_preserves_binary_data_across_multiple_chunks(self):
        self.encrypt()
        output = self.root / "restored.dat"
        age_transform(self.age, self.encrypted, output, identity=self.identity)
        self.assertEqual(digest(self.source), digest(output))
        self.assertNotIn(self.source.read_bytes()[:128], self.encrypted.read_bytes())

    def test_modified_payload_fails_authentication_without_publishing_plaintext(self):
        self.encrypt()
        contents = bytearray(self.encrypted.read_bytes())
        contents[len(contents) // 2] ^= 1
        self.encrypted.write_bytes(contents)
        output = self.root / "must-not-exist.dat"
        with self.assertRaisesRegex(ValueError, "no final output"):
            age_transform(self.age, self.encrypted, output, identity=self.identity)
        self.assertFalse(output.exists())

    def test_wrong_identity_cannot_replace_an_existing_output(self):
        self.encrypt()
        other = self.root / "other/identity.agekey"
        setup_identity(self.keygen, other, self.root / "other/recipient.txt")
        output = self.root / "existing.dat"
        output.write_bytes(b"keep existing output")
        with self.assertRaisesRegex(ValueError, "no final output"):
            age_transform(self.age, self.encrypted, output, identity=other)
        self.assertEqual(b"keep existing output", output.read_bytes())

    def test_truncated_ciphertext_never_publishes_a_partial_final_chunk(self):
        self.encrypt()
        self.encrypted.write_bytes(self.encrypted.read_bytes()[:-1])
        output = self.root / "must-not-exist.dat"
        with self.assertRaisesRegex(ValueError, "no final output"):
            age_transform(self.age, self.encrypted, output, identity=self.identity)
        self.assertFalse(output.exists())

    def test_setup_reuses_the_original_identity_and_recipient(self):
        before = digest(self.identity)
        self.assertEqual(self.recipient, setup_identity(self.keygen, self.identity, self.public))
        self.assertEqual(before, digest(self.identity))
        self.assertEqual(self.recipient, public_recipient(self.public))

    def test_mismatch_refuses_to_replace_either_existing_key(self):
        other = self.root / "other/identity.agekey"
        other_recipient = setup_identity(self.keygen, other, self.root / "other/recipient.txt")
        self.public.write_text(other_recipient + "\n")
        before = digest(self.identity)
        with self.assertRaisesRegex(ValueError, "do not match"):
            setup_identity(self.keygen, self.identity, self.public)
        self.assertEqual(before, digest(self.identity))
        self.assertEqual(other_recipient, public_recipient(self.public))

    def test_missing_private_identity_cannot_silently_rotate_a_public_recipient(self):
        missing = self.root / "missing/identity.agekey"
        with self.assertRaisesRegex(ValueError, "restore the original"):
            setup_identity(self.keygen, missing, self.public)
        self.assertFalse(missing.exists())

class TransferArchiveValidation(unittest.TestCase):
    def archive(self, names, special=None):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w") as archive:
                for name in names:
                    info = zipfile.ZipInfo(name)
                    # ZipInfo on Windows normalizes backslashes during construction.
                    # Preserve the raw central-directory name to test hostile input.
                    info.filename = name
                    if special is not None:
                        info.create_system = 3
                        info.external_attr = special << 16
                    archive.writestr(info, b"fixture")
        stream.seek(0)
        return zipfile.ZipFile(stream)

    def test_only_the_complete_flat_delivery_filename_set_is_accepted(self):
        with self.archive(FILES) as archive:
            self.assertEqual(5, len(checked_members(archive, flat=True)))

    def test_missing_unexpected_nested_and_duplicate_delivery_entries_are_rejected(self):
        for names in (list(FILES)[:-1], [*FILES, "private.agekey"], [*FILES, "build/SOURCE.md"], [*FILES, "SOURCE.md"]):
            with self.subTest(names=names), self.archive(names) as archive, self.assertRaises(ValueError):
                checked_members(archive, flat=True)

    def test_ipa_paths_reject_traversal_aliases_windows_devices_and_control_characters(self):
        for name in ("../escape", "/absolute", "C:/absolute", "Payload/IcyLyrics.app/../escape",
                     "Payload/IcyLyrics.app//alias", "Payload/IcyLyrics.app/./alias",
                     "Payload/IcyLyrics.app/CON.txt", "Payload/IcyLyrics.app/file.",
                     "Payload/IcyLyrics.app/file ", "Payload/IcyLyrics.app/file\nname",
                     "Payload\\IcyLyrics.app\\escape"):
            with self.subTest(name=name), self.archive([name]) as archive, self.assertRaises(ValueError):
                checked_members(archive)

    def test_symlinks_and_special_files_are_rejected(self):
        for mode in (stat.S_IFLNK | 0o777, stat.S_IFIFO | 0o600, stat.S_IFCHR | 0o600):
            with self.subTest(mode=mode), self.archive(["Payload/IcyLyrics.app/file"], mode) as archive, self.assertRaises(ValueError):
                checked_members(archive)

    def test_case_collisions_are_rejected_before_windows_extraction(self):
        with self.archive(["Payload/IcyLyrics.app/File", "Payload/IcyLyrics.app/file"]) as archive, self.assertRaisesRegex(ValueError, "Duplicate"):
            checked_members(archive)

    def test_validated_delivery_cannot_overwrite_a_different_existing_directory(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="publish-test-", dir=WORK) as temporary:
            root = Path(temporary)
            staging, destination = root / "staging", root / "destination"
            staging.mkdir()
            destination.mkdir()
            for name in FILES:
                (staging / name).write_bytes(b"verified fixture")
            (destination / "existing.txt").write_bytes(b"preserve")
            with self.assertRaisesRegex(ValueError, "different files"):
                publish_directory(staging, destination)
            self.assertEqual(b"preserve", (destination / "existing.txt").read_bytes())
            self.assertEqual({"existing.txt"}, {p.name for p in destination.iterdir()})

    def test_decrypted_payload_checksum_is_checked_before_ipa_inspection(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="checksum-test-", dir=WORK) as temporary:
            directory = Path(temporary)
            for name in FILES:
                (directory / name).write_bytes(b"fixture")
            commit = "0" * 40
            report = {"commit": commit, "correspondingSource": corresponding_source(commit), "label": LABEL,
                      "simulator": {"result": "passed", "commit": commit, "sourceFingerprint": "1" * 64},
                      "sha256": "0" * 64, "bytes": 7}
            (directory / "build-report.json").write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, "checksum"):
                inspect_delivery(directory, commit)

if __name__ == "__main__":
    unittest.main()
