"""Owner-only authenticated transfer; never uploads or prints an age identity."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile

from bootstrap_age import ROOT, WORK, VERSION, bootstrap, digest, within_build
from package_ipa import committed_asset_hashes, corresponding_source, validate_app, validate_committed_source
from source_fingerprint import fingerprint

IDENTITY = WORK / "identity.agekey"
RECIPIENT = ROOT / "owner-recipient.txt"
DELIVERY = ROOT / "build/delivery"
ENCRYPTED = WORK / "encrypted"
FILES = frozenset(("IcyLyrics-unsigned.ipa", "SHA256SUMS.txt", "build-report.json", "SOURCE.md", "INSTALL-WINDOWS.md"))
LABEL = "simulator-verified IPA; physical iPhone validation pending"
MAX_BYTES = 2 * 1024 * 1024 * 1024

def run_tool(arguments):
    result = subprocess.run([str(value) for value in arguments], stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    if result.returncode:
        # Do not relay keygen/identity parser output: it can include private input.
        raise ValueError("age operation failed; no final output was published")
    return result.stdout

def public_recipient(path):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > 128:
        raise ValueError("Expected one plain age public recipient")
    value = path.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", value):
        raise ValueError("Expected one X25519 age public recipient, not an identity or recipient list")
    return value

def recipient_fingerprint(recipient):
    return hashlib.sha256(recipient.encode("ascii")).hexdigest()

def derive_recipient(keygen, identity):
    identity = within_build(identity)
    if not identity.resolve().is_relative_to(WORK) or identity.is_symlink():
        raise ValueError("Private age identities must stay under ignored iOS/build/owner-delivery")
    value = run_tool([keygen, "-y", identity]).decode("ascii").strip()
    if not re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", value):
        raise ValueError("The private file must contain exactly one X25519 age identity")
    return value

def setup_identity(keygen, identity=IDENTITY, recipient_file=RECIPIENT):
    identity = within_build(identity)
    if not identity.resolve().is_relative_to(WORK) or identity.is_symlink():
        raise ValueError("Private identity location must be inside ignored owner-delivery")
    recipient_file = Path(recipient_file)
    if recipient_file.is_symlink():
        raise ValueError("Refusing a symlink recipient file")
    identity.parent.mkdir(parents=True, exist_ok=True)
    lock = identity.parent / "setup.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    try:
        if identity.exists():
            recipient = derive_recipient(keygen, identity)
            if recipient_file.exists() and public_recipient(recipient_file) != recipient:
                raise ValueError("Existing identity and recipient do not match; neither was changed")
        else:
            if recipient_file.exists():
                raise ValueError("Public recipient exists but its private identity is missing; restore the original identity")
            with tempfile.TemporaryDirectory(prefix="keygen-", dir=identity.parent) as temporary:
                pending = Path(temporary) / "identity.agekey"
                run_tool([keygen, "-o", pending])
                os.chmod(pending, 0o600)
                recipient = derive_recipient(keygen, pending)
                os.replace(pending, identity)
        if not recipient_file.exists():
            recipient_file.parent.mkdir(parents=True, exist_ok=True)
            with recipient_file.open("x", encoding="ascii", newline="\n") as stream:
                stream.write(recipient + "\n")
        return recipient
    finally:
        lock.unlink()

def age_transform(age, source, destination, *, recipient=None, identity=None):
    """Authenticate before replacing any final file, including on wrong-key/tamper errors."""
    if (recipient is None) == (identity is None):
        raise ValueError("Select exactly one encryption recipient or decryption identity")
    destination = within_build(destination)
    if destination.is_symlink():
        raise ValueError("Refusing a symlink output")
    WORK.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="transform-", dir=WORK) as temporary:
        pending = Path(temporary) / "pending"
        if recipient is not None:
            arguments = [age, "--encrypt", "-r", recipient, "-o", pending, source]
        else:
            identity = within_build(identity)
            if not identity.resolve().is_relative_to(WORK) or identity.is_symlink():
                raise ValueError("Private identity location must be inside ignored owner-delivery")
            arguments = [age, "--decrypt", "-i", identity, "-o", pending, source]
        run_tool(arguments)
        destination.parent.mkdir(parents=True, exist_ok=True)
        os.replace(pending, destination)

def checked_members(package, *, flat=False):
    seen = set()
    total = 0
    for member in package.infolist():
        name = member.filename
        if member.orig_filename != name:
            raise ValueError("ZIP reader normalized an unsafe original path")
        parts = PurePosixPath(name).parts
        canonical = "/".join(parts) + ("/" if member.is_dir() else "")
        reserved = {"CON", "PRN", "AUX", "NUL"} | {f"{prefix}{number}" for prefix in ("COM", "LPT") for number in range(1, 10)}
        if not name or name != canonical or "\\" in name or ":" in name or any(ord(char) < 32 for char in name) or name.startswith("/"):
            raise ValueError("Unsafe ZIP path")
        if any(part.endswith((".", " ")) or part.split(".")[0].upper() in reserved for part in parts):
            raise ValueError("Unsafe Windows ZIP path")
        if ".." in parts or name.casefold() in seen:
            raise ValueError("Duplicate or unsafe ZIP path")
        seen.add(name.casefold())
        mode = stat.S_IFMT(member.external_attr >> 16)
        if mode not in (0, stat.S_IFREG, stat.S_IFDIR) or member.flag_bits & 1:
            raise ValueError("ZIP links, special files and nested encryption are forbidden")
        if mode == stat.S_IFDIR and not member.is_dir():
            raise ValueError("ZIP directory metadata disagrees with its path")
        if flat:
            if name not in FILES or member.is_dir():
                raise ValueError("Unexpected owner delivery filename")
            limit = MAX_BYTES if name.endswith(".ipa") else 1024 * 1024
        else:
            if name not in ("Payload/", "Payload/IcyLyrics.app/") and not name.startswith("Payload/IcyLyrics.app/"):
                raise ValueError("Unexpected IPA root")
            limit = MAX_BYTES
        if member.file_size > limit:
            raise ValueError("ZIP member exceeds its size limit")
        total += member.file_size
        if total > MAX_BYTES:
            raise ValueError("ZIP expanded size exceeds the delivery limit")
    if flat and seen != {name.casefold() for name in FILES}:
        raise ValueError("Missing required owner delivery files")
    return package.infolist()

def extract_checked(archive, destination, *, flat=False):
    with zipfile.ZipFile(archive) as package:
        members = checked_members(package, flat=flat)
        for member in members:
            target = destination.joinpath(*PurePosixPath(member.filename).parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError("ZIP path escapes the staging directory")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with package.open(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)

def inspect_delivery(directory, expected_commit):
    if {path.name for path in directory.iterdir()} != FILES:
        raise ValueError("Delivery must contain exactly the five validated package files")
    if any(path.is_symlink() or not path.is_file() for path in directory.iterdir()):
        raise ValueError("Delivery files must be regular files")
    for name in FILES - {"IcyLyrics-unsigned.ipa"}:
        if (directory / name).stat().st_size > 1024 * 1024:
            raise ValueError("Delivery metadata exceeds the size limit")
    report = json.loads((directory / "build-report.json").read_text(encoding="utf-8"))
    source = corresponding_source(expected_commit)
    verification = report.get("simulator", {})
    if report.get("commit") != expected_commit or report.get("correspondingSource") != source:
        raise ValueError("Delivery does not match the requested exact source commit")
    if report.get("label") != LABEL or verification.get("result") != "passed" or verification.get("commit") != expected_commit:
        raise ValueError("Delivery lacks matching passing simulator verification")
    if not re.fullmatch(r"[0-9a-f]{64}", verification.get("sourceFingerprint", "")):
        raise ValueError("Missing simulator source fingerprint")
    ipa = directory / "IcyLyrics-unsigned.ipa"
    if not 0 < ipa.stat().st_size <= MAX_BYTES:
        raise ValueError("Invalid IPA size")
    checksum = digest(ipa)
    if report.get("sha256") != checksum or report.get("bytes") != ipa.stat().st_size:
        raise ValueError("Decrypted IPA checksum or byte count does not match its build report")
    if (directory / "SHA256SUMS.txt").read_text().strip() != f"{checksum}  {ipa.name}":
        raise ValueError("IPA checksum file does not match its contents")
    # Inspect the actual device binary and all bundled resources, not only report claims.
    with tempfile.TemporaryDirectory(prefix="inspect-ipa-", dir=WORK) as temporary:
        expanded = Path(temporary)
        extract_checked(ipa, expanded)
        info, binaries = validate_app(expanded / "Payload/IcyLyrics.app", resource_hashes=committed_asset_hashes(expected_commit))
    if binaries != report.get("binaries") or info["CFBundleIdentifier"] != report.get("bundleIdentifier") or info["MinimumOSVersion"] != report.get("minimumOS"):
        raise ValueError("Actual application metadata differs from its build report")
    return report

def current_commit():
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()

def encrypt_delivery(age):
    validate_committed_source(ROOT.parent)
    commit = current_commit()
    recipient = public_recipient(RECIPIENT)
    # The recipient must itself be the committed owner key, not an ignored/local override.
    committed = subprocess.check_output(["git", "show", f"{commit}:iOS/owner-recipient.txt"], cwd=ROOT).decode("ascii").strip()
    if committed != recipient:
        raise ValueError("Owner recipient is not the one at the verified commit")
    WORK.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="encrypt-", dir=WORK) as temporary:
        staging = Path(temporary) / "delivery"
        if {path.name for path in DELIVERY.iterdir()} != FILES:
            raise ValueError("Unexpected or missing files in generated delivery")
        staging.mkdir()
        for name in sorted(FILES):
            if (DELIVERY / name).is_symlink():
                raise ValueError("Refusing delivery symlink")
            shutil.copy2(DELIVERY / name, staging / name)
        report = inspect_delivery(staging, commit)
        marker = json.loads((ROOT / "build/reports/simulator-verification.json").read_text())
        if marker != report["simulator"] or marker["sourceFingerprint"] != fingerprint():
            raise ValueError("Successful delivery does not match this run's simulator/source verification")
        archive = Path(temporary) / "delivery.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as package:
            for name in sorted(FILES):
                package.write(staging / name, name)
        validate_committed_source(ROOT.parent)
        ENCRYPTED.mkdir(parents=True, exist_ok=True)
        allowed = {"IcyLyrics-owner-delivery.zip.age", "SHA256SUMS.txt", "transfer.json"}
        if any(path.name not in allowed or path.is_symlink() or not path.is_file() for path in ENCRYPTED.iterdir()):
            raise ValueError("Encrypted artifact directory contains unexpected files; refusing possible plaintext upload")
        output = ENCRYPTED / "IcyLyrics-owner-delivery.zip.age"
        age_transform(age, archive, output, recipient=recipient)
        checksum = digest(output)
        (ENCRYPTED / "SHA256SUMS.txt").write_text(f"{checksum}  {output.name}\n", encoding="ascii")
        (ENCRYPTED / "transfer.json").write_text(json.dumps({"commit": commit, "ageVersion": VERSION,
            "recipientSha256": recipient_fingerprint(recipient), "ciphertextSha256": checksum,
            "plaintextPublicRelease": "not authorized by this encrypted transfer"}, indent=2) + "\n", encoding="utf-8")
        return output

def publish_directory(staging, destination):
    destination = within_build(destination)
    if destination.exists():
        if destination.is_symlink() or not destination.is_dir():
            raise ValueError("Refusing existing non-directory output")
        contents = list(destination.iterdir())
        if not contents:
            destination.rmdir()
        elif {path.name for path in contents} == FILES and all(not path.is_symlink() and path.is_file() and digest(path) == digest(staging / path.name) for path in contents):
            return  # Same authenticated delivery: idempotent, no overwriting.
        else:
            raise ValueError("Output already contains different files; choose a new --output under iOS/build")
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging.rename(destination)

def decrypt_delivery(age, keygen, encrypted, destination=DELIVERY):
    recipient = derive_recipient(keygen, IDENTITY)
    if recipient != public_recipient(RECIPIENT):
        raise ValueError("Existing private identity and public recipient do not match")
    if Path(encrypted).stat().st_size > MAX_BYTES:
        raise ValueError("Encrypted delivery exceeds the size limit")
    with tempfile.TemporaryDirectory(prefix="decrypt-", dir=WORK) as temporary:
        archive = Path(temporary) / "authenticated.zip"
        age_transform(age, encrypted, archive, identity=IDENTITY)
        staging = Path(temporary) / "delivery"
        staging.mkdir()
        extract_checked(archive, staging, flat=True)
        report = inspect_delivery(staging, current_commit())
        publish_directory(staging, destination)
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("setup")
    commands.add_parser("encrypt")
    decrypt = commands.add_parser("decrypt")
    decrypt.add_argument("input", type=Path)
    decrypt.add_argument("--output", type=Path, default=DELIVERY)
    args = parser.parse_args()
    age, keygen = bootstrap()
    if args.command == "setup":
        if os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS"):
            raise ValueError("Generate the owner's private identity locally, never in CI")
        recipient = setup_identity(keygen)
        print("Owner identity ready locally; public recipient SHA-256: " + recipient_fingerprint(recipient))
    elif args.command == "encrypt":
        output = encrypt_delivery(age)
        print("Encrypted owner delivery ready: " + str(output.relative_to(ROOT)))
    else:
        report = decrypt_delivery(age, keygen, args.input, args.output)
        print("Authenticated delivery verified for commit " + report["commit"] + "; IPA SHA-256 " + report["sha256"])

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, AssertionError) as error:
        raise SystemExit(str(error)) from None
