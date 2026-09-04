"""Execute the real simulator-stage shell code with bounded fake build tools."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "scripts/build_ios.sh").read_text()
STAGES = SOURCE[SOURCE.index("# Run the shared model/parser"):SOURCE.index("# A device build is separate")]
BASH = shutil.which("bash")
PRELUDE = r'''
set -euo pipefail
ROOT="$PWD"
simulator=owned-test-simulator
runtime=test-runtime
# Production always has project/configuration arguments. Bash 3.2 (macOS)
# treats an empty array expansion as unbound under set -u, unlike Bash 5.
common=(-project fixture.xcodeproj -scheme IcyLyrics)
mkdir -p build/reports
phase() { :; }
bash() {
  echo native >> events
  if [[ "$MAKE_FRAMEWORK" == 1 ]]; then
    local framework="$ROOT/shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
    mkdir -p "$framework/Headers" "$framework/Modules"
    printf archive > "$framework/IcyShared"
    printf header > "$framework/Headers/IcyShared.h"
    printf module > "$framework/Modules/module.modulemap"
  fi
  return "$NATIVE_EXIT"
}
xcodebuild() {
  echo swift >> events
  if [[ "$MAKE_RESULT" == 1 ]]; then mkdir -p build/SimulatorTests.xcresult; fi
  return "$SWIFT_EXIT"
}
xcrun() {
  if [[ "$1" == lipo ]]; then
    # Apple lipo consumes every argument after -verify_arch as an architecture.
    # The input binary must precede that option, unlike our former permissive fake.
    if [[ $# != 4 || ! -s "$2" || "$3" != -verify_arch || "$4" != arm64 ]]; then
      echo 'Invalid Apple lipo input/architecture argument ordering' >&2
      return 96
    fi
    return "$ARCH_EXIT"
  fi
  if [[ "$1" == xcresulttool ]]; then echo export >> events; return "$ATTACHMENT_EXIT"; fi
  return 97
}
ditto() { mkdir -p "$(dirname "$2")"; cp -R "$1" "$2"; }
python3() {
  if [[ "$1" == scripts/source_fingerprint.py ]]; then echo frozen-source; return 0; fi
  if [[ "$1" == scripts/record_simulator_result.py ]]; then
    echo verifier >> events
    if [[ "$VERIFIER_EXIT" != 0 ]]; then return "$VERIFIER_EXIT"; fi
    printf verified > build/reports/simulator-verification.json
    return 0
  fi
  return 98
}
'''


@unittest.skipUnless(BASH, "Bash is required to execute the production stage gates")
class SimulatorStageGates(unittest.TestCase):
    def _formatMessage(self, msg, standardMsg):
        return super()._formatMessage(msg, standardMsg) + "\n" + getattr(self, "stage_diagnostics", "")

    def run_stages(self, *, stale_framework=False, stages=STAGES, **overrides):
        environment = os.environ | {"NATIVE_EXIT": "0", "SWIFT_EXIT": "0", "ATTACHMENT_EXIT": "0",
                                    "MAKE_FRAMEWORK": "1", "MAKE_RESULT": "1", "ARCH_EXIT": "0", "VERIFIER_EXIT": "0"}
        environment.update({key: str(value) for key, value in overrides.items()})
        with tempfile.TemporaryDirectory(prefix="icy-stage-gates-") as directory:
            root = Path(directory)
            if stale_framework:
                old = root / "shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
                for item in ("IcyShared", "Headers/IcyShared.h", "Modules/module.modulemap"):
                    target = old / item
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("stale")
            completed = subprocess.run([BASH, "-s"], input=PRELUDE + stages + "\necho device >> events\n",
                                       cwd=root, env=environment, text=True, capture_output=True, timeout=20)
            event_path = root / "events"
            report_path = root / "build/reports/simulator-stage-results.json"
            raw_report = report_path.read_text() if report_path.exists() else "<missing>"
            self.stage_diagnostics = (f"Shell: {BASH}\nOverrides: {overrides}\nExit: {completed.returncode}\n"
                                      f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}\n"
                                      f"Stage report:\n{raw_report}")
            self.assertTrue(event_path.exists(), "Stage event log was not written")
            self.assertTrue(report_path.exists(), "Stage status report was not written")
            events = event_path.read_text().splitlines()
            status = json.loads(raw_report)
            marker = (root / "build/reports/simulator-verification.json").exists()
            return completed.returncode, events, status, marker

    def test_native_failure_still_runs_swift_and_exports_but_cannot_verify(self):
        code, events, status, marker = self.run_stages(NATIVE_EXIT=7)
        self.assertEqual(code, 7)
        self.assertEqual(events, ["native", "swift", "export"])
        self.assertEqual(status["nativeExitCode"], 7)
        self.assertEqual(status["swiftExitCode"], 0)
        self.assertFalse(marker)

    def test_both_failures_are_recorded_and_attachments_still_export(self):
        code, events, status, marker = self.run_stages(NATIVE_EXIT=7, SWIFT_EXIT=8)
        self.assertNotEqual(code, 0)
        self.assertEqual(events, ["native", "swift", "export"])
        self.assertEqual(status["swiftExitCode"], 8)
        self.assertEqual(status["attachmentExportExitCode"], 0)
        self.assertFalse(marker)

    def test_swift_failure_without_a_bundle_does_not_attempt_export(self):
        code, events, status, marker = self.run_stages(SWIFT_EXIT=8, MAKE_RESULT=0)
        self.assertEqual(code, 8)
        self.assertEqual(events, ["native", "swift"])
        self.assertIsNone(status["attachmentExportExitCode"])
        self.assertFalse(marker)

    def test_export_failure_blocks_verification_after_successful_tests(self):
        code, events, status, marker = self.run_stages(ATTACHMENT_EXIT=9)
        self.assertNotEqual(code, 0)
        self.assertEqual(events, ["native", "swift", "export"])
        self.assertEqual(status["attachmentExportExitCode"], 9)
        self.assertFalse(marker)

    def test_stale_framework_cannot_run_swift_after_a_failed_build(self):
        code, events, status, marker = self.run_stages(stale_framework=True, NATIVE_EXIT=7, MAKE_FRAMEWORK=0)
        self.assertEqual(code, 7)
        self.assertEqual(events, ["native"])
        self.assertFalse(status["freshArm64Framework"])
        self.assertIsNone(status["swiftExitCode"])
        self.assertFalse(marker)

    def test_success_exit_without_framework_still_fails(self):
        code, events, status, marker = self.run_stages(MAKE_FRAMEWORK=0)
        self.assertNotEqual(code, 0)
        self.assertEqual(events, ["native"])
        self.assertFalse(status["freshArm64Framework"])
        self.assertFalse(marker)

    def test_invalid_framework_architecture_blocks_swift_and_verification(self):
        code, events, status, marker = self.run_stages(ARCH_EXIT=6)
        self.assertNotEqual(code, 0)
        self.assertEqual(events, ["native"])
        self.assertFalse(status["freshArm64Framework"])
        self.assertFalse(marker)

    def test_apple_lipo_rejects_binary_after_variable_length_architecture_list(self):
        wrong_order = STAGES.replace('lipo "$simulator_framework/IcyShared" -verify_arch arm64',
                                     'lipo -verify_arch arm64 "$simulator_framework/IcyShared"')
        self.assertNotEqual(wrong_order, STAGES)
        code, events, status, marker = self.run_stages(stages=wrong_order)
        self.assertNotEqual(code, 0)
        self.assertEqual(events, ["native"])
        self.assertFalse(status["freshArm64Framework"])
        self.assertFalse(marker)
        self.assertIn("Invalid Apple lipo", self.stage_diagnostics)

    def test_only_complete_success_reaches_verifier_and_device_stage(self):
        code, events, status, marker = self.run_stages()
        self.assertEqual(code, 0)
        self.assertEqual(events, ["native", "swift", "export", "verifier", "device"])
        self.assertEqual(status, {"nativeExitCode": 0, "swiftExitCode": 0,
                                 "attachmentExportExitCode": 0, "freshArm64Framework": True})
        self.assertTrue(marker)

    def test_existing_source_fingerprint_verifier_remains_a_required_gate(self):
        code, events, _, marker = self.run_stages(VERIFIER_EXIT=12)
        self.assertEqual(code, 12)
        self.assertNotIn("device", events)
        self.assertFalse(marker)


if __name__ == "__main__":
    unittest.main()
