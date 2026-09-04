# Appearance parity evidence and reproduction

`IcyParityFixtures` provides 20 original, offline scenarios. `IcyParityFixtureScreen` invokes the production `IcyLyricsApp` with a paused 26.5-second track, synthetic artwork, deterministic frame time, and a native adapter. It does not start an account session or download lyrics. iOS uses the same factory through its debug fixture controller.

The Android before/after gate compares the untouched original renderer in `iOS/build/android-baseline` against the extracted canonical renderer. `prepare_android_baseline.py` adds test-only adapters (the snapshot artwork field was originally Android Bitmap) and test dependencies; it does not patch original production source. The comparator verifies those original source hashes against `baseline/android-source-manifest.json`.

Run from the repository root on Windows, with dedicated API 36 portrait and landscape emulators:

```powershell
python iOS/tests/prepare_android_baseline.py iOS/build/android-baseline
# Build :app:assemblePlayDebug and :app:assemblePlayDebugAndroidTest in each Android Gradle root.
python iOS/tests/capture_android_parity.py baseline --serial emulator-5584 --orientation portrait
python iOS/tests/capture_android_parity.py baseline --serial emulator-5582 --orientation landscape
python iOS/tests/capture_android_parity.py extracted --serial emulator-5584 --orientation portrait
python iOS/tests/capture_android_parity.py extracted --serial emulator-5582 --orientation landscape
python iOS/tests/compare_android_parity.py
```

The baseline checkout must already exist from the pre-extraction snapshot; regenerating it from the extracted tree would invalidate this test. APKs are local debug builds. Only the dedicated emulator is used.

Captures are lossless PNGs from Compose's root capture API, without system status/navigation pixels. The root retains the actual safe-drawing inset layout. Portrait is 1080x2400; landscape is 2400x1080; Android density is 420dpi (2.625 pixels per dp), font scale 1. The harness asserts orientation from display dimensions before rendering. On this Windows host, actual emulator rotation and dynamic display resizing crash its renderer; separate emulators are booted at each physical geometry, so landscape is a natural landscape display. Both builds receive the same geometry and insets. This verifies responsive layout extraction, not physical rotation transitions.

The strict comparator accepts only equal RGBA bytes at identical expected dimensions. It does not mask regions, resize images, use perceptual tolerances, or waive differences. It writes per-scenario hashes, changed-pixel counts, diagnostics, and a report under ignored `iOS/tests/results/android/`. Baseline and extracted screenshots remain available there for inspection. Only a successful complete comparison establishes Android extraction parity.

Coverage: onboarding, idle player, portrait, long title/artist, failed lyrics, static/off backgrounds, reduced motion, settings, populated/empty local library, legal, diagnostics, all four landscape modes, reversed mixed media, multilingual static lyrics, and syllable/background-vocal lyrics.

Remaining gates are separate: same-size iOS screenshots, gesture/rotation transitions, live shader/spring animation trajectories, all settings scroll positions/dialogs, accessibility scaling, and physical iPhone output. The current Android reference geometry is 411.42857x914.28571dp, not a standard iPhone viewport. For direct cross-platform pixel comparison, render additional Android references at an actual iPhone geometry such as 1179x2556 at 480dpi (393x852 points at 3x), with explicit matching app-content safe insets; capture native iOS at the same pixels without resampling. Do not interpret Android before/after success as verified iOS identity.

Bundled Roboto and explicit Noto script spans cover the fixture languages. Font loading, regional Han forms, complex emoji ligatures, native text shaping and antialiasing still require iOS image evidence. See `shared/ui/assets/font/README.md` for font provenance and bounds. Platform permission/OAuth sheets are intentional system exceptions.
