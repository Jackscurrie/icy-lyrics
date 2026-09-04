"""Exercise opt-in simulator ownership and independent additional capture ordering."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "scripts/build_ios.sh").read_text()
FINISH = BUILD[BUILD.index("finish() {"):BUILD.index("trap finish EXIT")]
ADDITIONAL = (ROOT / "scripts/capture_additional_ios.sh").read_text()
STAGES = ADDITIONAL[ADDITIONAL.index("extended_status=0\n"):]
PYTHON_ENV = ADDITIONAL[ADDITIONAL.index("# GitHub steps do not inherit"):ADDITIONAL.index("extended_status=0\n")]
WORKFLOW = (ROOT.parent / ".github/workflows/ci.yml").read_text()
BASH = shutil.which("bash")


@unittest.skipUnless(BASH, "Bash required for actual shell gate execution")
class AdditionalCaptureOptInTests(unittest.TestCase):
    def run_shell(self, source, **environment):
        (ROOT / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="icy-opt-in-", dir=ROOT / "build") as directory:
            result = subprocess.run([BASH, "-s"], input="set -euo pipefail\n" + source,
                cwd=directory, env=os.environ | {key: str(value) for key, value in environment.items()},
                text=True, capture_output=True, timeout=20)
            files = {str(file.relative_to(directory)).replace("\\", "/"): file.read_text()
                     for file in Path(directory).rglob("*") if file.is_file()}
            self.diagnostics = f"exit={result.returncode}\nstdout={result.stdout}\nstderr={result.stderr}\nfiles={files}"
            return result.returncode, files

    def finish(self, enabled, status=0, gpu="false"):
        prefix = '''
mkdir -p build/reports
simulator=12345678-1234-1234-1234-123456789abc
git() { :; }
xcrun() { printf '%s\\n' "$*" >> actions; }
'''
        return self.run_shell(prefix + FINISH + f"\ntrap finish EXIT\nexit {status}\n",
                              ICY_ADDITIONAL_IOS_PARITY=enabled, ICY_KAWARP_GPU_PROBE=gpu)

    def test_default_finish_deletes_simulator_without_handoff(self):
        code, files = self.finish("false")
        self.assertEqual(code, 0, self.diagnostics)
        self.assertIn("simctl shutdown", files.get("actions", ""), self.diagnostics)
        self.assertIn("simctl delete", files.get("actions", ""), self.diagnostics)
        self.assertNotIn("build/reports/retained-ios-simulator.txt", files, self.diagnostics)

    def test_explicit_opt_in_retains_simulator_even_after_failed_main_gate(self):
        code, files = self.finish("true", status=7)
        self.assertEqual(code, 7, self.diagnostics)
        self.assertEqual(files.get("build/reports/retained-ios-simulator.txt"), "12345678-1234-1234-1234-123456789abc\n", self.diagnostics)
        self.assertNotIn("actions", files, self.diagnostics)

    def test_gpu_only_retains_simulator_after_failure_without_enabling_motion(self):
        code, files = self.finish("false", status=7, gpu="true")
        self.assertEqual(code, 7, self.diagnostics)
        self.assertEqual(files.get("build/reports/retained-ios-simulator.txt"),
                         "12345678-1234-1234-1234-123456789abc\n", self.diagnostics)
        self.assertNotIn("actions", files, self.diagnostics)

    def stages(self, **overrides):
        prefix = '''
mkdir -p build/reports/additional-parity
simulator=12345678-1234-1234-1234-123456789abc
before=fingerprint
bash() {
  if [[ "$1" == scripts/capture_extended_ios.sh ]]; then echo extended >> events; return "$EXTENDED_EXIT"; fi
  if [[ "$1" == gradlew ]]; then
    [[ "$*" == *"-Picy.captureMotion=true"* && "$*" == *"--tests com.icy.lyrics.ui.parity.DeterministicIosMotionCaptureTest"* ]] || return 99
    echo motion >> events; return "$MOTION_EXIT"
  fi
  return 98
}
python3() {
  if [[ "$1" == scripts/source_fingerprint.py ]]; then
    if [[ "$SOURCE_CHANGED" == 1 ]]; then echo changed; else echo fingerprint; fi
    return 0
  fi
  if [[ "$1" == - ]]; then
    cat > /dev/null
    echo reference >> events
    if [[ "$REFERENCE_EXIT" != 0 ]]; then return "$REFERENCE_EXIT"; fi
    printf 'build/verified-reference/baseline\\n' > build/reports/additional-parity/motion-reference-path.txt
    return 0
  fi
  if [[ "$1" == tests/compare_motion_parity.py ]]; then
    [[ "$2" == build/verified-reference/baseline && "$3" == build/reports/deterministic-ios-captures/mixed-lyrics-motion-v1/android36-420dpi-landscape-v1 && "$4" == --output && "$5" == build/reports/additional-parity/motion-comparison ]] || return 97
    echo comparison >> events
    return "$COMPARISON_EXIT"
  fi
  return 98
}
'''
        return self.run_shell(prefix + STAGES + "\necho finished >> events\n",
                              **({"EXTENDED_EXIT": 0, "MOTION_EXIT": 0, "REFERENCE_EXIT": 0,
                                  "COMPARISON_EXIT": 0, "SOURCE_CHANGED": 0} | overrides))

    def test_motion_runs_after_extended_failure_without_success_result(self):
        code, files = self.stages(EXTENDED_EXIT=8)
        self.assertEqual(code, 8, self.diagnostics)
        self.assertEqual(files.get("events"), "extended\nmotion\nreference\ncomparison\n", self.diagnostics)

    def test_motion_failure_is_not_ignored(self):
        code, files = self.stages(MOTION_EXIT=9)
        self.assertEqual(code, 9, self.diagnostics)
        self.assertEqual(files.get("events"), "extended\nmotion\nreference\ncomparison\n", self.diagnostics)

    def test_pixel_or_clock_comparison_failure_is_not_ignored(self):
        code, files = self.stages(COMPARISON_EXIT=1)
        self.assertEqual(code, 1, self.diagnostics)
        self.assertEqual(files.get("events"), "extended\nmotion\nreference\ncomparison\n", self.diagnostics)

    def test_reference_failure_prevents_comparison_and_propagates(self):
        code, files = self.stages(REFERENCE_EXIT=10)
        self.assertEqual(code, 10, self.diagnostics)
        self.assertEqual(files.get("events"), "extended\nmotion\nreference\n", self.diagnostics)

    def test_original_capture_failure_keeps_precedence_over_comparison_failure(self):
        code, files = self.stages(EXTENDED_EXIT=8, MOTION_EXIT=9, COMPARISON_EXIT=1)
        self.assertEqual(code, 8, self.diagnostics)
        self.assertIn("comparison", files.get("events", ""), self.diagnostics)

    def test_source_changes_prevent_complete_capture(self):
        code, files = self.stages(SOURCE_CHANGED=1)
        self.assertNotEqual(code, 0, self.diagnostics)
        self.assertNotIn("finished", files.get("events", ""), self.diagnostics)

    def test_capture_and_comparison_success_are_required_for_complete_additional_run(self):
        code, files = self.stages()
        self.assertEqual(code, 0, self.diagnostics)
        self.assertEqual(files.get("events"), "extended\nmotion\nreference\ncomparison\nfinished\n", self.diagnostics)

    def test_separate_step_selects_the_prepared_verification_python(self):
        prefix = '''
ROOT="$PWD"
mkdir -p build/python-verification/bin
printf '#!/bin/sh\\nprintf prepared-verification-python\\n' > build/python-verification/bin/python3
chmod +x build/python-verification/bin/python3
'''
        code, files = self.run_shell(prefix + PYTHON_ENV + "\npython3 > selected-python\n")
        self.assertEqual(code, 0, self.diagnostics)
        self.assertEqual("prepared-verification-python", files.get("selected-python"), self.diagnostics)

    def test_missing_main_verification_python_blocks_additional_comparison(self):
        code, files = self.run_shell('ROOT="$PWD"\n' + PYTHON_ENV + "\necho unexpected > selected-python\n")
        self.assertNotEqual(code, 0, self.diagnostics)
        self.assertNotIn("selected-python", files, self.diagnostics)
        self.assertIn("Main build verification Python is missing", self.diagnostics)

    def test_dispatch_is_false_by_default_and_not_enabled_for_android(self):
        dispatch = WORKFLOW.split("workflow_dispatch:", 1)[1].split("permissions:", 1)[0]
        self.assertIn("additional_ios_parity:", dispatch)
        self.assertIn("type: boolean", dispatch)
        self.assertIn("default: false", dispatch)
        self.assertNotIn("ICY_ADDITIONAL_IOS_PARITY", WORKFLOW.split("android-play:", 1)[1].split("ios-sideload:", 1)[0])
        ios = WORKFLOW.split("ios-sideload:", 1)[1]
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.additional_ios_parity", ios)
        self.assertIn("if: always() && !cancelled()", ios)
        self.assertIn("!iOS/build/reports/extended-ios/**", ios)
        self.assertIn("!iOS/build/reports/motion-ios-tests/**", ios)
        self.assertIn("name: icy-ios-additional-parity", ios)

    def test_delivery_depends_on_main_build_and_preserves_plaintext_license_gate(self):
        main = WORKFLOW.split("name: Verify simulator and package the unsigned device application", 1)[1].split("- name:", 1)[0]
        self.assertIn("id: ios_build", main)
        encrypted = WORKFLOW.split("name: Upload encrypted delivery for the owner", 1)[1].split("- name:", 1)[0]
        plaintext = WORKFLOW.split("name: Upload resignable IPA after distribution review", 1)[1].split("- name:", 1)[0]
        for step in (encrypted, plaintext):
            self.assertIn("!cancelled() && steps.ios_build.outcome == ''success''", step)
            self.assertNotIn("if: success()", step)
        self.assertIn("vars.ICY_IOS_BINARY_DISTRIBUTION_CLEARED == ''true''", plaintext)


if __name__ == "__main__":
    unittest.main()
