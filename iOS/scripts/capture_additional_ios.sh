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
simulator=""
finish_additional() {
  local result=$?
  local after
  after="$(python3 scripts/source_fingerprint.py)"
  python3 - "$result" "$extended_status" "$motion_status" "$simulator" "$before" "$after" <<'PY'
from pathlib import Path
import json,subprocess,sys
result,extended,motion,simulator,before,after=sys.argv[1:]
record={"requested":True,"exitCode":int(result),"extendedUIKitExitCode":json.loads(extended),
        "motionExitCode":json.loads(motion),"simulator":simulator,"sourceFingerprintBefore":before,
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
extended_status=0
if bash scripts/capture_extended_ios.sh "$simulator" 2>&1 | tee build/reports/additional-parity/extended-run.log; then :; else extended_status=$?; fi
# Independent motion diagnostics still run when a UIKit semantic selector fails.
motion_status=0
if bash gradlew --no-daemon -Picy.iosSimulator="$simulator" -Picy.captureMotion=true \
  :shared:ui:iosSimulatorArm64Test --tests 'com.icy.lyrics.ui.parity.DeterministicIosMotionCaptureTest' \
  --console=plain --stacktrace 2>&1 | tee build/reports/additional-parity/motion-run.log; then :; else motion_status=$?; fi
after="$(python3 scripts/source_fingerprint.py)"
[[ "$before" == "$after" ]] || { echo 'Sources changed during additional capture.' >&2; exit 1; }
if (( extended_status != 0 )); then exit "$extended_status"; fi
if (( motion_status != 0 )); then exit "$motion_status"; fi
