#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use an Apple Silicon Mac.' >&2; exit 1; }
[[ $# == 1 && "$1" =~ ^[a-fA-F0-9-]{36}$ ]] || {
  echo 'Usage: bash scripts/capture_extended_ios.sh BOOTED_IPHONE_SIMULATOR_UUID' >&2; exit 2;
}
simulator="$1"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.4.1.app/Contents/Developer}"
version="$(xcodebuild -version)"
[[ "${version%%$'\n'*}" == 'Xcode 26.4.1' ]] || { echo 'Xcode 26.4.1 is required.' >&2; exit 1; }
xcrun simctl list devices available -j | python3 scripts/validate_booted_iphone.py "$simulator"
python3 scripts/generate_xcode_project.py --check
python3 scripts/bootstrap_spotify.py
mkdir -p build/reports/extended-ios
output="$(mktemp -d "$ROOT/build/reports/extended-ios/extended-v1.XXXXXX")"
printf '%s\n' "$version" > "$output/xcode.txt"
# Explicitly prepare this source's simulator framework; the default build may
# have subsequently staged the device framework. No ordinary test suite runs.
bash gradlew --no-daemon :shared:ui:linkDebugFrameworkIosSimulatorArm64 --stacktrace 2>&1 | tee "$output/kotlin-framework.log"
framework="$ROOT/shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
[[ -s "$framework/IcyShared" && -s "$framework/Headers/IcyShared.h" && -s "$framework/Modules/module.modulemap" ]]
xcrun lipo "$framework/IcyShared" -verify_arch arm64
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto "$framework" app/Frameworks/IcyShared.framework
fingerprint="$(python3 scripts/source_fingerprint.py)"
client="${SPOTIFY_CLIENT_ID:-}"
[[ -z "$client" || "$client" =~ ^[a-zA-Z0-9]{32}$ ]] || { echo 'Invalid public Spotify client ID.' >&2; exit 1; }
status=0
if python3 scripts/capture_native_framebuffer.py --simulator "$simulator" \
  --runner-bundle-id com.icy.lyrics.ios.IcyLyricsExtendedUITests.xctrunner --output "$output/native-framebuffer" -- \
  xcodebuild -project app/IcyLyrics.xcodeproj -scheme IcyLyricsExtendedParity -configuration Debug \
  -derivedDataPath build/ExtendedDerivedData -destination "platform=iOS Simulator,id=$simulator" \
  -parallel-testing-enabled NO -resultBundlePath "$output/ExtendedParity.xcresult" \
  "SPOTIFY_CLIENT_ID=$client" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= test \
  2>&1 | tee "$output/xcode-extended.log"; then :; else status=$?; fi
attachments=null
summary=null
if [[ -d "$output/ExtendedParity.xcresult" ]]; then
  attachments=0
  if xcrun xcresulttool export attachments --path "$output/ExtendedParity.xcresult" --output-path "$output/attachments"; then :; else attachments=$?; fi
  summary=0
  if xcrun xcresulttool get test-results summary --format json --path "$output/ExtendedParity.xcresult" > "$output/test-summary.json"; then :; else summary=$?; fi
fi
after="$(python3 scripts/source_fingerprint.py)"
python3 - "$output" "$simulator" "$status" "$attachments" "$summary" "$fingerprint" "$after" <<'PY'
from pathlib import Path
import json,subprocess,sys
out,device,status,attachments,summary,before,after=sys.argv[1:]
record={"catalog":"extended-v1","scheme":"IcyLyricsExtendedParity","simulator":device,
        "xcodeExitCode":int(status),"attachmentExportExitCode":json.loads(attachments),
        "summaryExitCode":json.loads(summary),"sourceFingerprintBefore":before,"sourceFingerprintAfter":after,
        "sourceUnchanged":before==after,"commit":subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),
        "appearanceParityVerified":False,"physicalIPhoneValidation":"pending",
        "scope":"Opt-in extended UI evidence only. Does not create an IPA or default simulator-verification marker."}
Path(out,"extended-capture-report.json").write_text(json.dumps(record,indent=2)+"\n")
PY
echo "Extended UI evidence: $output"
if (( status != 0 )); then exit "$status"; fi
[[ "$attachments" == 0 && "$summary" == 0 && "$fingerprint" == "$after" ]] || {
  echo 'Extended capture evidence is incomplete or its sources changed.' >&2; exit 1;
}
