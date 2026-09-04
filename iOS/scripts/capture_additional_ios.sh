#!/bin/bash
# Opt-in diagnostics only. Main build verification and delivery remain independent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "${ICY_ADDITIONAL_IOS_PARITY:-false}" == true ]] || { echo 'Additional iPhone parity was not explicitly enabled.' >&2; exit 2; }
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use an Apple Silicon Mac.' >&2; exit 1; }
mkdir -p build/reports/additional-parity
before="$(python3 scripts/source_fingerprint.py)"
extended_status=null
motion_status=null
motion_comparison_status=null
simulator=""
finish_additional() {
  local result=$?
  local after
  after="$(python3 scripts/source_fingerprint.py)"
  python3 - "$result" "$extended_status" "$motion_status" "$motion_comparison_status" "$simulator" "$before" "$after" <<'PY'
from pathlib import Path
import json,subprocess,sys
result,extended,motion,comparison,simulator,before,after=sys.argv[1:]
record={"requested":True,"exitCode":int(result),"extendedUIKitExitCode":json.loads(extended),
        "motionExitCode":json.loads(motion),"motionComparisonExitCode":json.loads(comparison),
        "simulator":simulator,"sourceFingerprintBefore":before,
        "sourceFingerprintAfter":after,"sourceUnchanged":before==after,
        "commit":subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),
        "appearanceParityVerified":False,"defaultVerificationUnchanged":True,
        "scope":"Opt-in extended UIKit and motion evidence. No default verification marker or IPA is created by this step."}
Path("build/reports/additional-parity/run-summary.json").write_text(json.dumps(record,indent=2)+"\n")
PY
}
trap finish_additional EXIT
[[ -s build/reports/retained-ios-simulator.txt ]] || { echo 'Main build did not retain a simulator; additional capture cannot run.' >&2; exit 1; }
simulator="$(cat build/reports/retained-ios-simulator.txt)"
[[ "$simulator" =~ ^[a-fA-F0-9-]{36}$ ]] || { echo 'Invalid retained simulator ID.' >&2; exit 1; }
framework="$ROOT/shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
[[ -s "$framework/IcyShared" && -s "$framework/Headers/IcyShared.h" && -s "$framework/Modules/module.modulemap" ]] || {
  echo 'Main build did not produce a simulator framework; preserving its failure rather than starting another incomplete build.' >&2; exit 1;
}
# GitHub steps do not inherit the main build shell's PATH export. Reuse the
# pinned verification environment (including Pillow) for the strict comparator.
[[ -x "$ROOT/build/python-verification/bin/python3" ]] || {
  echo 'Main build verification Python is missing; additional comparison cannot run.' >&2; exit 1;
}
export PATH="$ROOT/build/python-verification/bin:$PATH"
extended_status=0
if bash scripts/capture_extended_ios.sh "$simulator" 2>&1 | tee build/reports/additional-parity/extended-run.log; then :; else extended_status=$?; fi
# Independent motion diagnostics still run when a UIKit semantic selector fails.
motion_status=0
if bash gradlew --no-daemon -Picy.iosSimulator="$simulator" -Picy.captureMotion=true \
  :shared:ui:iosSimulatorArm64Test --tests 'com.icy.lyrics.ui.parity.DeterministicIosMotionCaptureTest' \
  --console=plain --stacktrace 2>&1 | tee build/reports/additional-parity/motion-run.log; then :; else motion_status=$?; fi
# Compare any complete motion evidence even when another capture stage failed.
# The comparator rejects incomplete sides, different clocks, changed inputs or pixels.
motion_comparison_status=0
if (
  python3 - <<'PY' || exit $?
from pathlib import Path
import json,sys,tempfile
sys.path.insert(0,str(Path("tests").resolve()))
from compare_motion_parity import read_json,unpack_reference_archive
# A unique destination preserves prior references/reports on a deliberate rerun.
parent=Path(tempfile.mkdtemp(prefix="motion-reference-",dir="build"))
archive=Path("tests/evidence/android-motion-v1-reference.zip")
reference=unpack_reference_archive(archive,parent/"evidence")
Path("build/reports/additional-parity/motion-reference-path.txt").write_text(str(reference)+"\n")
Path("build/reports/additional-parity/motion-reference.json").write_text(
    json.dumps(read_json(archive.with_suffix(".json"))|{"extractedReference":str(reference)},indent=2)+"\n")
PY
  python3 tests/compare_motion_parity.py \
    "$(cat build/reports/additional-parity/motion-reference-path.txt)" \
    build/reports/deterministic-ios-captures/mixed-lyrics-motion-v1/android36-420dpi-landscape-v1 \
    --output build/reports/additional-parity/motion-comparison
) 2>&1 | tee build/reports/additional-parity/motion-comparison.log; then :; else motion_comparison_status=$?; fi
after="$(python3 scripts/source_fingerprint.py)"
[[ "$before" == "$after" ]] || { echo 'Sources changed during additional capture.' >&2; exit 1; }
if (( extended_status != 0 )); then exit "$extended_status"; fi
if (( motion_status != 0 )); then exit "$motion_status"; fi
if (( motion_comparison_status != 0 )); then exit "$motion_comparison_status"; fi
