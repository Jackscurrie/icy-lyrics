#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo 'Use an Apple Silicon macOS runner.' >&2; exit 1; }
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.4.1.app/Contents/Developer}"
[[ "$(xcodebuild -version | head -1)" == 'Xcode 26.4.1' ]] || { echo 'Xcode 26.4.1 is required.' >&2; exit 1; }
java -version 2>&1 | head -1 | grep -Eq '"17[.\"]' || { echo 'JDK 17 is required.' >&2; exit 1; }
python3 scripts/generate_xcode_project.py --check
python3 -m unittest discover -s tests -p 'test_*.py'
python3 scripts/bootstrap_spotify.py
mkdir -p build/reports build/delivery
# A failed rerun must not retain a previous verification marker or merge result bundles.
rm -f build/reports/simulator-verification.json
rm -rf "$ROOT/build/SimulatorTests.xcresult" "$ROOT/build/IcyLyrics.xcarchive"
xcodebuild -version > build/reports/xcode.txt
bash gradlew --no-daemon --version > build/reports/gradle.txt

# Public, non-secret configuration. Empty client IDs permit offline fixture builds.
client="${SPOTIFY_CLIENT_ID:-}"
[[ -z "$client" || "$client" =~ ^[a-zA-Z0-9]{32}$ ]] || { echo 'Invalid Spotify client ID.' >&2; exit 1; }
common=(-project app/IcyLyrics.xcodeproj -scheme IcyLyrics -derivedDataPath build/DerivedData
  "SPOTIFY_CLIENT_ID=$client" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=)

runtime="$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("isAvailable") and x["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]; r.sort(key=lambda x:tuple(map(int,x["version"].split(".")))); assert r,"No iOS simulator runtime"; print(r[-1]["identifier"])')"
device_type="$(xcrun simctl list devicetypes -j | python3 -c 'import json,sys; d=[x for x in json.load(sys.stdin)["devicetypes"] if x["name"]=="iPhone 16"]; assert d,"iPhone 16 simulator type missing"; print(d[0]["identifier"])')"
simulator="$(xcrun simctl create IcyLyricsVerification "$device_type" "$runtime")"
trap 'xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true; xcrun simctl delete "$simulator" >/dev/null 2>&1 || true' EXIT
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b

# Run the shared model/parser, persistence and shader-math tests on the simulator.
bash gradlew --no-daemon -Picy.iosSimulator="$simulator" :shared:lyrics:verificationTest :shared:lyrics:iosSimulatorArm64Test \
  :shared:platform:iosSimulatorArm64Test :shared:ui:iosSimulatorArm64Test \
  :shared:ui:linkDebugFrameworkIosSimulatorArm64 --stacktrace 2>&1 | tee build/reports/kotlin-simulator.log
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto shared/ui/build/bin/iosSimulatorArm64/debugFramework/IcyShared.framework app/Frameworks/IcyShared.framework
# KSP may create schema output during the initial Kotlin build. Capture after it,
# before the Xcode simulator build/tests, and reject source edits during that run.
source_fingerprint="$(python3 scripts/source_fingerprint.py)"

xcodebuild "${common[@]}" -configuration Debug -destination "platform=iOS Simulator,id=$simulator" -parallel-testing-enabled NO \
  -resultBundlePath build/SimulatorTests.xcresult test 2>&1 | tee build/reports/xcode-simulator.log
xcrun xcresulttool export attachments --path build/SimulatorTests.xcresult --output-path build/reports/ios-captures
python3 scripts/record_simulator_result.py build/SimulatorTests.xcresult "$simulator" "$runtime" --source-fingerprint "$source_fingerprint"

# A device build is separate: a simulator .app must never enter the IPA.
bash gradlew --no-daemon :shared:ui:linkReleaseFrameworkIosArm64 --stacktrace 2>&1 | tee build/reports/kotlin-device.log
rm -rf "$ROOT/app/Frameworks/IcyShared.framework"
ditto shared/ui/build/bin/iosArm64/releaseFramework/IcyShared.framework app/Frameworks/IcyShared.framework
xcodebuild "${common[@]}" -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/IcyLyrics.xcarchive archive 2>&1 | tee build/reports/xcode-device.log
python3 scripts/package_ipa.py build/IcyLyrics.xcarchive/Products/Applications/IcyLyrics.app
