#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use an Apple Silicon macOS runner.' >&2; exit 1; }
mkdir -p build/reports build/delivery
rm -f build/reports/retained-ios-simulator.txt
finish() {
  # Preserve native-generated schemas even when a later test fails. They must
  # be reviewed and committed, not exempted from the exact-source release gate.
  if [[ -d shared/platform/schemas ]]; then
    ditto shared/platform/schemas build/reports/generated-room-schemas || true
  fi
  if [[ -d build/reports/deterministic-ios-captures ]]; then
    # Comparison failures stay visible in their own report. This is an
    # offscreen raster lane, separate from native test/IPA verification.
    python3 tests/compare_ios_parity.py build/reports/deterministic-ios-captures > build/reports/deterministic-comparison.log 2>&1 || true
  fi
  git status --porcelain=v1 --untracked-files=all -- . ../android-v2 ../.github/workflows/ci.yml > build/reports/source-status.txt || true
  if [[ -n "${simulator:-}" ]]; then
    if [[ "${ICY_ADDITIONAL_IOS_PARITY:-false}" == true || "${ICY_KAWARP_GPU_PROBE:-false}" == true ]]; then
      # Explicit workflow-dispatch diagnostics run as a separate step, even
      # after a failed main gate. That step's cleanup owns this simulator.
      printf '%s\n' "$simulator" > build/reports/retained-ios-simulator.txt
    else
      xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true
      xcrun simctl delete "$simulator" >/dev/null 2>&1 || true
    fi
  fi
}
trap finish EXIT
phase() { printf '::notice title=Icy iOS build::%s\n' "$1"; }
phase 'Checking pinned toolchains and preparing dependencies'
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.4.1.app/Contents/Developer}"
# Read the entire output: closing xcodebuild's pipe with head can crash its
# Foundation file handle before it finishes writing the second version line.
xcode_version="$(xcodebuild -version)"
printf '%s\n' "$xcode_version" > build/reports/xcode.txt
[[ "${xcode_version%%$'\n'*}" == 'Xcode 26.4.1' ]] || { echo 'Xcode 26.4.1 is required.' >&2; exit 1; }
java_version="$(java -version 2>&1)"
printf '%s\n' "$java_version" > build/reports/java.txt
grep -Eq '"17[."]' <<< "$java_version" || { echo 'JDK 17 is required.' >&2; exit 1; }
python3 -m venv build/python-verification
build/python-verification/bin/python -m pip install --disable-pip-version-check -r scripts/requirements-verification.txt
export PATH="$ROOT/build/python-verification/bin:$PATH"
python3 scripts/generate_xcode_project.py --check
python3 -m unittest discover -s tests -p 'test_*.py'
python3 scripts/bootstrap_spotify.py
# A failed rerun must not retain a previous verification marker or merge result bundles.
rm -f build/reports/simulator-verification.json
rm -rf "$ROOT/build/SimulatorTests.xcresult" "$ROOT/build/IcyLyrics.xcarchive"
bash gradlew --no-daemon --version > build/reports/gradle.txt

# Public, non-secret configuration. Empty client IDs permit offline fixture builds.
client="${SPOTIFY_CLIENT_ID:-}"
[[ -z "$client" || "$client" =~ ^[a-zA-Z0-9]{32}$ ]] || { echo 'Invalid Spotify client ID.' >&2; exit 1; }
common=(-project app/IcyLyrics.xcodeproj -scheme IcyLyrics -derivedDataPath build/DerivedData
  "SPOTIFY_CLIENT_ID=$client" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=)

runtime="$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("isAvailable") and x["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]; r.sort(key=lambda x:tuple(map(int,x["version"].split(".")))); assert r,"No iOS simulator runtime"; print(r[-1]["identifier"])')"
phase 'Booting the iPhone simulator'
device_type="$(xcrun simctl list devicetypes -j | python3 -c 'import json,sys; d=[x for x in json.load(sys.stdin)["devicetypes"] if x["name"]=="iPhone 16"]; assert d,"iPhone 16 simulator type missing"; print(d[0]["identifier"])')"
simulator="$(xcrun simctl create IcyLyricsVerification "$device_type" "$runtime")"
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b

# Run the shared model/parser, persistence and shader-math tests on the simulator.
# Collect independent failures, including Swift/UI diagnostics, before enforcing
# the complete gate. A framework left by an earlier run must never qualify.
phase 'Running shared native tests and linking the simulator framework'
simulator_framework="$ROOT/shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework"
rm -rf "$simulator_framework"
native_test_status=0
swift_test_status=null
attachment_status=null
framework_ready=false
record_stage_status() {
  printf '{"nativeExitCode":%s,"swiftExitCode":%s,"attachmentExportExitCode":%s,"freshArm64Framework":%s}\n' \
    "$native_test_status" "$swift_test_status" "$attachment_status" "$framework_ready" > build/reports/simulator-stage-results.json
}
if bash gradlew --no-daemon --continue -Picy.iosSimulator="$simulator" :shared:lyrics:verificationTest :shared:lyrics:iosSimulatorArm64Test \
  :shared:platform:iosSimulatorArm64Test :shared:ui:iosSimulatorArm64Test \
  :shared:ui:linkDebugFrameworkIosSimulatorArm64 --stacktrace 2>&1 | tee build/reports/kotlin-simulator.log; then
  :
else
  native_test_status=$?
fi
if [[ -s "$simulator_framework/IcyShared" && -s "$simulator_framework/Headers/IcyShared.h" &&
      -s "$simulator_framework/Modules/module.modulemap" ]] &&
    xcrun lipo "$simulator_framework/IcyShared" -verify_arch arm64; then
  framework_ready=true
else
  record_stage_status
  echo 'No fresh arm64 simulator framework; Swift/UI diagnostics cannot run.' >&2
  if (( native_test_status != 0 )); then exit "$native_test_status"; else exit 1; fi
fi
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto "$simulator_framework" app/Frameworks/IcyShared.framework
# KSP may create schema output during the initial Kotlin build. Capture after it,
# before the Xcode simulator build/tests, and reject source edits during that run.
source_fingerprint="$(python3 scripts/source_fingerprint.py)"

phase 'Running Swift tests and capturing the simulator interface'
swift_test_status=0
mkdir -p build/reports/native-framebuffer
native_capture_output="$(mktemp -d "$ROOT/build/reports/native-framebuffer/default.XXXXXX")"
if python3 scripts/capture_native_framebuffer.py --simulator "$simulator" \
  --runner-bundle-id com.icy.lyrics.ios.IcyLyricsUITests.xctrunner --output "$native_capture_output/host" -- \
  xcodebuild "${common[@]}" -configuration Debug -destination "platform=iOS Simulator,id=$simulator" -parallel-testing-enabled NO \
  -resultBundlePath build/SimulatorTests.xcresult test 2>&1 | tee build/reports/xcode-simulator.log; then
  :
else
  swift_test_status=$?
fi
# Failing tests can contain the only useful launch log or partially drawn image.
# Exporting that evidence must never produce a successful verification marker.
if [[ -d build/SimulatorTests.xcresult ]]; then
  attachment_status=0
  if xcrun xcresulttool export attachments --path build/SimulatorTests.xcresult --output-path build/reports/ios-captures; then
    :
  else
    attachment_status=$?
  fi
fi
record_stage_status
if (( native_test_status != 0 )); then
  echo "Native tests failed ($native_test_status); Swift/UI diagnostics were collected independently. No verification or IPA." >&2
  exit "$native_test_status"
fi
if (( swift_test_status != 0 )); then
  echo "Swift/UI tests failed ($swift_test_status). No verification or IPA." >&2
  exit "$swift_test_status"
fi
if [[ "$attachment_status" != 0 ]]; then
  echo 'Simulator results or attachment export are missing/failed. No verification or IPA.' >&2
  exit 1
fi
python3 scripts/record_simulator_result.py build/SimulatorTests.xcresult "$simulator" "$runtime" --source-fingerprint "$source_fingerprint"

# A device build is separate: a simulator .app must never enter the IPA.
phase 'Linking the device framework and archiving the iPhone application'
bash gradlew --no-daemon :shared:ui:linkReleaseFrameworkIosArm64 --stacktrace 2>&1 | tee build/reports/kotlin-device.log
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto shared/ui/build/bin/iosArm64/releaseFramework/IcyShared.framework app/Frameworks/IcyShared.framework
xcodebuild "${common[@]}" -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/IcyLyrics.xcarchive archive 2>&1 | tee build/reports/xcode-device.log
phase 'Validating and packaging the unsigned iPhone IPA'
python3 scripts/package_ipa.py build/IcyLyrics.xcarchive/Products/Applications/IcyLyrics.app
phase 'Encrypting the validated IPA for the owner'
python3 scripts/owner_transfer.py encrypt
