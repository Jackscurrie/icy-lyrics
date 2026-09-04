"""Pair the 29 default XCTest captures and extract measured Android viewport profiles.

No screenshots are captured, resized, or color-converted. Declared coordinate
permutations are checked against the original raw attachment. Supplied
GitHub metadata/verification files are local evidence, not authenticated claims.
See NATIVE-BATCH-EXTRACTION.md for the input and partial-run contract.
"""
from collections import defaultdict
from hashlib import sha256
from pathlib import Path
import argparse
import json
import re
import sys

from android_viewport_profile import load_profile, native_result_directory
from extract_native_profile import extract

ROOT = Path(__file__).resolve().parents[2]
PORTRAIT = ("onboarding", "empty", "portrait", "portrait-long", "portrait-failed", "background-static",
            "background-disabled", "reduced-motion", "settings", "library", "library-empty", "legal", "diagnostics")
LANDSCAPE = ("landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
             "landscape-mixed-right", "multilingual", "syllables")
EXPECTED = {**{f"{s}-1": "ParityCaptureTests/testPortraitFixtures()" for s in PORTRAIT},
            **{f"{s}-3": "ParityCaptureTests/testLandscapeLeftFixtures()" for s in LANDSCAPE},
            **{f"{s}-4": "ParityCaptureTests/testLandscapeRightFixtures()" for s in LANDSCAPE},
            "portrait-long-1-large-text": "ParityCaptureTests/testLargeTextFixture()",
            "multilingual-3-large-text": "ParityCaptureTests/testLargeTextFixture()"}
STEM = re.compile(r"(?P<scenario>[a-z][a-z0-9-]*)-(?P<orientation>[134])(?P<large>-large-text)?$")
UUID = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
EXPORT_SUFFIX = re.compile(r"_[0-9]+_" + UUID + r"$")


def digest(path):
    path = Path(path).resolve()
    data = path.read_bytes()
    return {"path": str(path), "sha256": sha256(data).hexdigest(), "bytes": len(data)}


def read_object(path):
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return value


def collect(manifest_path):
    """Use xcresulttool's human names, not arbitrary exported UUID filenames."""
    manifest_path = Path(manifest_path).resolve()
    records = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError("Expected xcresulttool export-attachments manifest array")
    found, ignored, used_paths = defaultdict(lambda: defaultdict(list)), [], set()
    for record_index, test in enumerate(records):
        if not isinstance(test, dict) or not isinstance(test.get("attachments"), list):
            raise ValueError("Malformed xcresult test/attachments record")
        for attachment in test["attachments"]:
            if not isinstance(attachment, dict):
                raise ValueError("Malformed xcresult attachment")
            human = attachment.get("suggestedHumanReadableName")
            if not isinstance(human, str):
                raise ValueError("Attachment lacks its suggestedHumanReadableName")
            base, separator, extension = human.rpartition(".")
            base = EXPORT_SUFFIX.sub("", base)
            if base.endswith("-raw-framebuffer"):
                kind, stem = "raw", base.removesuffix("-raw-framebuffer")
            elif base.endswith("-geometry"):
                kind, stem = "geometry", base.removesuffix("-geometry")
            else:
                kind, stem = "png", base
            if not separator or stem not in EXPECTED:
                ignored.append({"test": test.get("testIdentifier"), "attachment": attachment})
                continue
            expected_extension = "json" if kind == "geometry" else "png"
            if extension != expected_extension:
                raise ValueError(f"Wrong attachment type for {stem}: {human}")
            if test.get("testIdentifier") != EXPECTED[stem]:
                raise ValueError(f"Unexpected test identity for {stem}")
            if attachment.get("isAssociatedWithFailure") is not False:
                raise ValueError(f"Failure attachment cannot become reference input: {stem}")
            filename = attachment.get("exportedFileName")
            if (not isinstance(filename, str) or not filename or "/" in filename or "\\" in filename
                    or ":" in filename or Path(filename).name != filename
                    or Path(filename).suffix != "." + expected_extension):
                raise ValueError("Unsafe/wrong-type exported attachment filename")
            path = (manifest_path.parent / filename).resolve()
            if not path.is_relative_to(manifest_path.parent) or not path.is_file():
                raise ValueError(f"Missing/escaped exported file: {filename}")
            if path in used_paths:
                raise ValueError(f"Duplicate/reused exported attachment: {filename}")
            used_paths.add(path)
            found[stem][kind].append({"record": attachment, "path": path, "testRecordIndex": record_index,
                                      "testIdentifierURL": test.get("testIdentifierURL")})
    pairs = {}
    for stem, kinds in found.items():
        if not {"png", "geometry"} <= set(kinds) or not set(kinds) <= {"png", "geometry", "raw"} or any(len(items) != 1 for items in kinds.values()):
            raise ValueError(f"Missing or ambiguous image/geometry pair: {stem}")
        png, geometry = kinds["png"][0], kinds["geometry"][0]
        if png["testRecordIndex"] != geometry["testRecordIndex"]:
            raise ValueError(f"PNG/geometry come from different test records: {stem}")
        for key in ("deviceId", "configurationName"):
            value = png["record"].get(key)
            if not isinstance(value, str) or not value or value != geometry["record"].get(key):
                raise ValueError(f"PNG/geometry {key} differs: {stem}")
        if "raw" in kinds:
            raw = kinds["raw"][0]
            if raw["testRecordIndex"] != png["testRecordIndex"]:
                raise ValueError(f"Raw framebuffer and PNG come from different test records: {stem}")
            for key in ("deviceId", "configurationName"):
                if raw["record"].get(key) != png["record"].get(key):
                    raise ValueError(f"Raw framebuffer and PNG {key} differs: {stem}")
            png["rawFramebuffer"] = raw
        pairs[stem] = (png, geometry)
    identities = {(pair[0]["record"]["deviceId"], pair[0]["record"]["configurationName"]) for pair in pairs.values()}
    if len(identities) > 1:
        raise ValueError("Multiple simulator devices/configurations require separate reviewed batches")
    return pairs, sorted(set(EXPECTED) - set(pairs)), ignored


def provenance(manifest_path, *, artifact_metadata=None, simulator_verification=None, stage_results=None,
               workflow_run=None, source_revision=None, device_id=None):
    """Validate supplied evidence and assertions without claiming remote authentication."""
    reports = Path(manifest_path).resolve().parent.parent
    if simulator_verification is None and (reports / "simulator-verification.json").is_file():
        simulator_verification = reports / "simulator-verification.json"
    if stage_results is None and (reports / "simulator-stage-results.json").is_file():
        stage_results = reports / "simulator-stage-results.json"
    if artifact_metadata is None and simulator_verification is None:
        raise ValueError("Require --artifact-metadata or an existing --simulator-verification marker; operator labels are insufficient")
    evidence, run_id, revision, marker, outcome = {}, None, None, None, "unknown"
    if artifact_metadata is not None:
        artifact = read_object(artifact_metadata)
        run = artifact.get("workflow_run", {})
        if (type(artifact.get("id")) is not int or artifact["id"] <= 0
                or not isinstance(artifact.get("name"), str) or not artifact["name"]
                or type(artifact.get("size_in_bytes")) is not int or artifact["size_in_bytes"] <= 0
                or not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0
                or not isinstance(run.get("head_sha"), str) or not re.fullmatch(r"[0-9a-f]{40}", run["head_sha"])):
            raise ValueError("Invalid GitHub artifact REST metadata object")
        if artifact.get("digest") is not None and not re.fullmatch(r"sha256:[0-9a-f]{64}", str(artifact["digest"])):
            raise ValueError("Invalid GitHub artifact digest")
        run_id, revision = run["id"], run["head_sha"]
        evidence["githubArtifact"] = digest(artifact_metadata) | {"metadata": artifact}
    if simulator_verification is not None:
        marker = read_object(simulator_verification)
        # Reuse the marker producer's acceptance criteria; never trust result alone.
        sys.path.insert(0, str(ROOT / "iOS/scripts"))
        from record_simulator_result import validate_summary
        if (marker.get("result") != "passed" or not isinstance(marker.get("commit"), str)
                or not re.fullmatch(r"[0-9a-f]{40}", marker["commit"])
                or not isinstance(marker.get("simulator"), str) or not marker["simulator"]
                or not isinstance(marker.get("sourceFingerprint"), str) or not marker["sourceFingerprint"]):
            raise ValueError("Invalid simulator verification marker")
        validate_summary(marker.get("summary"), marker["simulator"])
        if device_id is not None and marker["simulator"].casefold() != device_id.casefold():
            raise ValueError("Verification marker simulator differs from capture device")
        if revision is not None and revision != marker["commit"]:
            raise ValueError("Artifact and verification marker revisions differ")
        revision, outcome = marker["commit"], "passed-verification-marker"
        evidence["simulatorVerification"] = digest(simulator_verification) | {"metadata": marker}
    if stage_results is not None:
        stages = read_object(stage_results)
        keys = ("nativeExitCode", "swiftExitCode", "attachmentExportExitCode")
        if (any(stages.get(key) is not None and (type(stages[key]) is not int or stages[key] < 0) for key in keys)
                or type(stages.get("freshArm64Framework")) is not bool):
            raise ValueError("Invalid simulator stage-results metadata")
        failed = any(type(stages.get(key)) is int and stages[key] != 0 for key in keys)
        complete = all(stages.get(key) == 0 for key in keys) and stages["freshArm64Framework"]
        if marker is not None and not complete:
            raise ValueError("Verification marker contradicts incomplete/failed stage results")
        if failed:
            outcome = "failed"
        elif marker is None:
            outcome = "passed-stages-without-verification-marker" if complete else "incomplete"
        evidence["simulatorStages"] = digest(stage_results) | {"metadata": stages}
    if workflow_run is not None and (not re.fullmatch(r"[0-9]+", str(workflow_run))
                                      or run_id is None or int(workflow_run) != run_id):
        raise ValueError("Operator workflow-run assertion lacks matching artifact metadata")
    if source_revision is not None and (not re.fullmatch(r"[0-9a-f]{7,40}", source_revision)
                                        or not revision.startswith(source_revision)):
        raise ValueError("Operator source-revision assertion differs from supplied evidence")
    return {"evidence": evidence, "workflowRunId": run_id, "sourceRevision": revision,
            "operatorAssertions": {"workflowRun": workflow_run, "sourceRevision": source_revision},
            "runOutcome": outcome, "remoteMetadataAuthenticatedByThisTool": False,
            "artifactArchiveDigestVerifiedByThisTool": False, "manifestBoundToArtifactArchiveByThisTool": False,
            "provenanceWarning": "Local metadata files are retained and checked for consistency. This tool does not authenticate GitHub, verify the downloaded ZIP, or prove these attachments came from that artifact. Preserve download/digest verification evidence separately."}


def extract_batch(manifest, output, *, artifact_metadata=None, simulator_verification=None, stage_results=None,
                  workflow_run=None, source_revision=None, allow_partial=False, safe_area_interior=False,
                  serial="emulator-5580"):
    manifest, output = Path(manifest).resolve(), Path(output).resolve()
    if output.exists():
        raise ValueError("Output already exists; preserve it and choose another batch directory")
    pairs, missing, ignored = collect(manifest)
    device_id = next(iter(pairs.values()))[0]["record"]["deviceId"] if pairs else None
    source = provenance(manifest, artifact_metadata=artifact_metadata, simulator_verification=simulator_verification,
                        stage_results=stage_results, workflow_run=workflow_run, source_revision=source_revision,
                        device_id=device_id)
    failed_run = source["runOutcome"] in ("failed", "incomplete")
    if not allow_partial and (missing or failed_run):
        raise ValueError("Default extraction requires all 29 pairs and no known failed/incomplete run; use --allow-partial for explicitly labeled diagnostics")
    output.mkdir(parents=True)
    report = {"schemaVersion": 1, "catalog": "default-native-29", "provenance": source,
              "manifest": digest(manifest), "expectedPairCount": 29, "availablePairCount": len(pairs),
              "missingPairs": missing, "ignoredAttachments": ignored, "partialDiagnosticsAuthorized": allow_partial,
              "completeDefault29": False, "appearanceParityVerified": False, "pairs": [], "groups": []}
    groups, failures = defaultdict(list), []
    for stem, (png, geometry) in sorted(pairs.items()):
        try:
            metadata = read_object(geometry["path"])
            expected = STEM.fullmatch(stem)
            if (metadata.get("scenario") != expected["scenario"]
                    or type(metadata.get("requestedDeviceOrientationRawValue")) is not int
                    or metadata["requestedDeviceOrientationRawValue"] != int(expected["orientation"])
                    or metadata.get("requestedLargeText") is not bool(expected["large"])):
                raise ValueError("Attachment name and geometry identity differ")
            raw = png.get("rawFramebuffer")
            extraction = extract(png["path"], geometry["path"], output / stem, safe_area_interior=safe_area_interior,
                                 raw_framebuffer_path=raw["path"] if raw is not None else None)
            profile_path = output / stem / "android-viewport-profile.json"
            profile = load_profile(profile_path)
            results = native_result_directory(ROOT, profile, "baseline")
            row = {"case": stem, "test": EXPECTED[stem], "testRecordIndex": png["testRecordIndex"],
                   "testIdentifierURL": png["testIdentifierURL"], "pngAttachment": png["record"],
                   "geometryAttachment": geometry["record"], "sourceReferences": extraction["sourceReferences"],
                   "extraction": str(output / stem / "extraction.json"), "profile": str(profile_path),
                   "profileId": profile["profileId"], "scenario": profile["scenario"],
                   "nativeFontSamplesComplete": profile["nativeFontScaling"]["nativeObservationsComplete"],
                   "androidResults": str(results), "existingAndroidScenarioEvidence": (results / (profile["scenario"] + ".png")).exists(),
                   "captureCommandArgv": [sys.executable, str(ROOT / "iOS/tests/capture_android_parity.py"), "baseline",
                       "--serial", serial, "--orientation", profile["orientation"], "--rotation", "0",
                       "--viewport-profile", str(profile_path)], "captureCommandExecuted": False,
                   "sourcePngColor": extraction["sourcePngColor"]}
            if raw is not None:
                row["rawFramebufferAttachment"] = raw["record"]
            if "nativeCoordinateMapping" in extraction:
                row["nativeCoordinateMapping"] = extraction["nativeCoordinateMapping"]
            report["pairs"].append(row)
            groups[profile["profileId"]].append(row)
        except (OSError, ValueError, KeyError) as error:
            failure = {"case": stem, "error": str(error), "nativePng": digest(png["path"]),
                       "nativeGeometry": digest(geometry["path"])}
            if "rawFramebuffer" in png:
                failure["nativeRawFramebuffer"] = digest(png["rawFramebuffer"]["path"])
            failures.append(failure)
    for profile_id, rows in sorted(groups.items()):
        profile = load_profile(rows[0]["profile"])
        report["groups"].append({"profileId": profile_id, "geometry": {key: profile[key] for key in
            ("widthPx", "heightPx", "density", "fontScale", "safeDrawingInsetsPx", "interfaceOrientationRawValue")},
            "cases": [row["case"] for row in rows]})
    report["rejections"] = failures
    report["completeDefault29"] = len(report["pairs"]) == 29 and not missing and not failures
    report["allAvailablePairsGeometryValid"] = bool(pairs) and not failures
    report["captureCompleteness"] = "complete-default-29" if report["completeDefault29"] else "partial"
    report["evidenceClassification"] = ("partial-or-failed-run-diagnostics" if missing or failures or failed_run
                                         else "complete-captures-with-" + source["runOutcome"])
    report["readyForReviewedAndroidCapture"] = bool(report["pairs"]) and not failures
    report["androidCaptureOverwriteRisk"] = any(row["existingAndroidScenarioEvidence"] for row in report["pairs"])
    (output / "batch-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifact-metadata", type=Path)
    parser.add_argument("--simulator-verification", type=Path)
    parser.add_argument("--stage-results", type=Path)
    parser.add_argument("--workflow-run", help="Consistency assertion against artifact metadata, not provenance")
    parser.add_argument("--source-revision", help="Consistency assertion against evidence, not provenance")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--safe-area-interior", action="store_true")
    args = parser.parse_args(argv)
    build, output = (ROOT / "iOS/build").resolve(), args.output.resolve()
    if output == build or not output.is_relative_to(build):
        parser.error("Output must be a fresh child directory under iOS/build")
    try:
        report = extract_batch(**vars(args))
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))
    print(f"Validated {len(report['pairs'])}/29 pairs; {len(report['missingPairs'])} missing, {len(report['rejections'])} rejected. "
          f"Run: {report['provenance']['runOutcome']}. No Android capture or parity comparison was run.")
    return 0 if report["readyForReviewedAndroidCapture"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
