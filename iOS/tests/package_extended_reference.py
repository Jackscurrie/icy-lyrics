"""Package one complete Android extended-v1 capture as checksum-pinned evidence."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
CASE_IDS = [
    "portrait-expanded",
    "settings-fullscreen",
    "settings-sources",
    "settings-troubleshooting",
    "settings-privacy",
    "token-consent",
    "legal-lower",
    "legal-agpl",
    "legal-agpl-scrolled",
    "legal-third-party",
    "legal-third-party-scrolled",
]
PRODUCTION_PREFIXES = (
    "android-v2/app/src/main/",
    "android-v2/core/lyrics/src/main/",
    "android-v2/core/platform/src/main/",
    "iOS/shared/lyrics/src/commonMain/",
    "iOS/shared/platform/src/commonMain/",
    "iOS/shared/platform/src/androidMain/",
    "iOS/shared/ui/src/commonMain/",
    "iOS/shared/ui/src/androidMain/",
)
PRODUCTION_FILES = {"android-v2/app/build.gradle.kts"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def repository_files() -> list[str]:
    output = subprocess.check_output(
        [
            "git",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            "android-v2",
            "iOS/shared",
        ],
        cwd=ROOT,
    )
    paths = sorted({item.decode("utf-8") for item in output.split(b"\0") if item})
    return [
        path
        for path in paths
        if path in PRODUCTION_FILES or any(path.startswith(prefix) for prefix in PRODUCTION_PREFIXES)
    ]


def validate_capture(capture: Path) -> tuple[dict, dict]:
    device = json.loads((capture / "device.json").read_text(encoding="utf-8"))
    manifest = json.loads((capture / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("complete") is not True or manifest.get("caseOrder") != CASE_IDS:
        raise ValueError("Extended capture is incomplete or has an unexpected case order")
    if device.get("caseOrder") != CASE_IDS:
        raise ValueError("Extended device record has an unexpected case order")
    for case_id in CASE_IDS:
        record = json.loads((capture / f"{case_id}.json").read_text(encoding="utf-8"))
        if sha256(capture / f"{case_id}.png") != record.get("pngSha256"):
            raise ValueError(f"PNG hash mismatch: {case_id}")
    return device, manifest


def add_file(archive: zipfile.ZipFile, source: Path, name: str) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, source.read_bytes())


def package(capture: Path) -> tuple[Path, dict]:
    device, _ = validate_capture(capture)
    evidence = ROOT / "iOS/tests/evidence"
    archive_path = evidence / "android-extended-v1-reference.zip"
    metadata_path = evidence / "android-extended-v1-reference.json"
    previous_sha = sha256(archive_path) if archive_path.is_file() else None
    if metadata_path.is_file():
        existing_metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if existing_metadata.get("captureRunId") == device["runId"]:
            previous_sha = existing_metadata.get("previousReferenceArchiveSha256")
    base_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    captured_at = datetime.strptime(device["runId"].split("-")[0], "%Y%m%dT%H%M%SZ").replace(
        tzinfo=timezone.utc
    )

    files = repository_files()
    source_manifest = {
        "schemaVersion": 1,
        "captureTree": "android-v2",
        "sourceBaseCommit": base_commit,
        "capturedAtUtc": captured_at.isoformat(),
        "files": [
            {"path": path, "sha256": sha256(ROOT / path), "bytes": (ROOT / path).stat().st_size}
            for path in files
        ],
    }

    with tempfile.TemporaryDirectory(prefix="extended-evidence-", dir=ROOT / "iOS/build") as temp:
        temp_path = Path(temp)
        source_manifest_path = temp_path / "source-manifest.json"
        write_json(source_manifest_path, source_manifest)
        provenance = {
            "schemaVersion": 1,
            "suite": "extended-v1",
            "runId": device["runId"],
            "captureTree": "android-v2",
            "sourceBaseCommit": base_commit,
            "sourceProductionFilesRecorded": len(files),
            "sourceManifestSha256": sha256(source_manifest_path),
            "apkSha256": device["apkSha256"],
            "sourceFilesRawSha256": {
                path: sha256(ROOT / path)
                for path in (
                    "iOS/tests/android/IcyExtendedParityScreenshotTest.kt",
                    "iOS/tests/capture_android_extended.py",
                )
            },
            "previousReferenceArchiveSha256": previous_sha,
            "captureScope": (
                "Current Android 1.1 production UI; 11 additional settled states; whole-display "
                "UiAutomation with real bars, dialogs and backdrop; no crop or resize"
            ),
            "appearanceParityVerified": False,
            "iosComparison": "pending",
        }
        provenance_path = temp_path / "provenance.json"
        write_json(provenance_path, provenance)

        temporary_archive = temp_path / archive_path.name
        with zipfile.ZipFile(temporary_archive, "w", allowZip64=False) as archive:
            for source in sorted(capture.iterdir(), key=lambda item: item.name):
                if source.is_file():
                    add_file(archive, source, f"baseline/{source.name}")
            add_file(archive, provenance_path, "provenance.json")
            add_file(archive, source_manifest_path, "source-manifest.json")
        with zipfile.ZipFile(temporary_archive) as archive:
            if archive.testzip() is not None:
                raise ValueError("Extended evidence archive failed its CRC check")
        archive_path.write_bytes(temporary_archive.read_bytes())

    metadata = {
        "schemaVersion": 1,
        "suite": "extended-v1",
        "archive": archive_path.name,
        "archiveBytes": archive_path.stat().st_size,
        "archiveSha256": sha256(archive_path),
        "caseCount": len(CASE_IDS),
        "caseOrder": CASE_IDS,
        "widthPx": 1080,
        "heightPx": 2400,
        "density": 2.625,
        "fontScale": 1,
        "safeDrawingInsetsPx": [0, 63, 0, 63],
        "fullDisplayCaptureIncludesSystemBars": True,
        "captureTree": "android-v2",
        "captureRunId": device["runId"],
        "previousReferenceArchiveSha256": previous_sha,
        "caseClocksAndActions": "See each raw capture JSON in the archive",
        "sourceProductionFilesRecorded": len(files),
        "appearanceParityVerified": False,
        "iosComparison": "pending",
    }
    write_json(metadata_path, metadata)
    return archive_path, metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    args = parser.parse_args()
    archive, metadata = package(args.capture.resolve())
    print(
        f"Packaged {metadata['caseCount']} cases in {archive} "
        f"({metadata['archiveBytes']} bytes, SHA-256 {metadata['archiveSha256']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
