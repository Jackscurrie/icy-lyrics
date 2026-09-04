#!/bin/bash
# Separate fixed-uniform diagnostic. Never creates an app-verification marker or IPA.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "${ICY_KAWARP_GPU_PROBE:-false}" == true ]] || { echo 'Set ICY_KAWARP_GPU_PROBE=true to request this diagnostic.' >&2; exit 2; }
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use an Apple Silicon Mac.' >&2; exit 1; }
[[ $# == 1 && "$1" =~ ^[a-fA-F0-9-]{36}$ ]] || { echo 'Usage: capture_kawarp_ios.sh BOOTED_IPHONE_UUID' >&2; exit 2; }
simulator="$1"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.4.1.app/Contents/Developer}"
version="$(xcodebuild -version)"
[[ "${version%%$'\n'*}" == 'Xcode 26.4.1' ]] || { echo 'Xcode 26.4.1 is required.' >&2; exit 1; }
python3 -c 'from PIL import Image' || { echo 'Use the existing build/python-verification environment with pinned Pillow.' >&2; exit 1; }
xcrun simctl list devices available -j | python3 scripts/validate_booted_iphone.py "$simulator"
python3 scripts/generate_xcode_project.py --check
python3 scripts/bootstrap_spotify.py
mkdir -p build/reports/kawarp-ios
output="$(mktemp -d "$ROOT/build/reports/kawarp-ios/uniform-phases-v1.XXXXXX")"
printf '%s\n' "$version" > "$output/xcode.txt"
test_status=null
attachments_status=null
summary_status=null
evidence_status=null
fingerprint=""
finish_probe() {
  local result=$?
  local after
  after="$(python3 scripts/source_fingerprint.py)"
  python3 - "$output" "$result" "$test_status" "$attachments_status" "$summary_status" "$evidence_status" "$fingerprint" "$after" "$simulator" <<'PY'
from pathlib import Path
import json,subprocess,sys
out,result,test,attachments,summary,evidence,before,after,simulator=sys.argv[1:]
record={"catalog":"kawarp-gpu-uniform-phases-v1","scheme":"IcyLyricsKawarpGpu","exitCode":int(result),
        "xcodeExitCode":json.loads(test),"attachmentExportExitCode":json.loads(attachments),
        "summaryExitCode":json.loads(summary),"evidenceExitCode":json.loads(evidence),"simulator":simulator,
        "sourceFingerprintBefore":before,"sourceFingerprintAfter":after,"sourceUnchanged":bool(before) and before==after,
        "commit":subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),
        "appearanceParityVerified":False,"physicalIPhoneValidation":"pending",
        "scope":"Opt-in real UIKit/Metal eight uniform phases only; no playback clock, crossfade, lifecycle, full animation or default app verification."}
Path(out,"run.json").write_text(json.dumps(record,indent=2)+"\n")
PY
}
trap finish_probe EXIT
# The current source's simulator framework is required; a device framework from
# the main archive step must never be accidentally staged for these tests.
bash gradlew --no-daemon :shared:ui:linkDebugFrameworkIosSimulatorArm64 --stacktrace 2>&1 | tee "$output/kotlin-framework.log"
framework="$ROOT/shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
[[ -s "$framework/IcyShared" && -s "$framework/Headers/IcyShared.h" && -s "$framework/Modules/module.modulemap" ]]
xcrun lipo "$framework/IcyShared" -verify_arch arm64
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto "$framework" app/Frameworks/IcyShared.framework
fingerprint="$(python3 scripts/source_fingerprint.py)"
test_status=0
# A distinct debug bundle gives the probe its own container, separate from user imports/auth.
if python3 scripts/capture_native_framebuffer.py --simulator "$simulator" \
  --runner-bundle-id com.icy.lyrics.ios.kawarpprobe.IcyLyricsKawarpUITests.xctrunner --output "$output/native-framebuffer" -- \
  xcodebuild -project app/IcyLyrics.xcodeproj -scheme IcyLyricsKawarpGpu -configuration Debug \
  -derivedDataPath build/KawarpDerivedData -destination "platform=iOS Simulator,id=$simulator" \
  -parallel-testing-enabled NO -resultBundlePath "$output/KawarpGpu.xcresult" \
  ICY_KAWARP_PROBE=YES ICY_BUNDLE_ID=com.icy.lyrics.ios.kawarpprobe SPOTIFY_CLIENT_ID= \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= test \
  2>&1 | tee "$output/xcode-kawarp.log"; then :; else test_status=$?; fi
if [[ -d "$output/KawarpGpu.xcresult" ]]; then
  attachments_status=0
  if xcrun xcresulttool export attachments --path "$output/KawarpGpu.xcresult" --output-path "$output/attachments"; then :; else attachments_status=$?; fi
  summary_status=0
  if xcrun xcresulttool get test-results summary --format json --path "$output/KawarpGpu.xcresult" > "$output/test-summary.json"; then :; else summary_status=$?; fi
fi
evidence_status=0
if container="$(xcrun simctl get_app_container "$simulator" com.icy.lyrics.ios.kawarpprobe data)"; then
  if python3 tests/collect_ios_kawarp.py --attachments "$output/attachments" \
    --container "$container" --output "$output/evidence"; then :; else evidence_status=$?; fi
else evidence_status=1; fi
echo "Kawarp GPU evidence: $output"
if (( test_status != 0 )); then exit "$test_status"; fi
[[ "$attachments_status" == 0 && "$summary_status" == 0 && "$evidence_status" == 0 ]] || { echo 'Kawarp evidence is incomplete.' >&2; exit 1; }
[[ "$fingerprint" == "$(python3 scripts/source_fingerprint.py)" ]] || { echo 'Source changed during Kawarp capture.' >&2; exit 1; }
