# Appearance parity evidence and reproduction

`IcyParityFixtures` provides 20 original, offline scenarios. `IcyParityFixtureScreen` invokes the production `IcyLyricsApp` with a paused 26.5-second track, synthetic artwork, fixed artwork/playback clocks, and a native adapter. Lyrics retain the original spring animator. It does not start an account session or download lyrics. iOS uses the same factory through its debug fixture controller.

The Android before/after gate compares the untouched original renderer in `iOS/build/android-baseline` against the extracted canonical renderer. `prepare_android_baseline.py` adds test-only adapters (the snapshot artwork field was originally Android Bitmap) and test dependencies; it does not patch original production source. The comparator verifies those original source hashes against `baseline/android-source-manifest.json`.

Run from the repository root on Windows, with dedicated API 36 portrait and landscape emulators. The verified renderer is `-gpu host -feature -Vulkan` on NVIDIA GeForce RTX 5060 Ti, Android Emulator 36.5.11.0. Start only the required owned emulator, using `-no-window` and a hidden host process; stop it after its before/after captures:

```powershell
python iOS/tests/prepare_android_baseline.py iOS/build/android-baseline
# Build :app:assemblePlayDebug and :app:assemblePlayDebugAndroidTest in each Android Gradle root.
python iOS/tests/capture_android_parity.py baseline --serial emulator-5584 --orientation portrait
python iOS/tests/capture_android_parity.py extracted --serial emulator-5584 --orientation portrait
# Stop portrait AVD; start the dedicated natural-landscape AVD on the same renderer.
python iOS/tests/capture_android_parity.py baseline --serial emulator-5582 --orientation landscape
python iOS/tests/capture_android_parity.py extracted --serial emulator-5582 --orientation landscape
python iOS/tests/compare_android_parity.py
```

The baseline checkout must already exist from the pre-extraction snapshot; regenerating it from the extracted tree would invalidate this test. APKs are local debug builds. Only the dedicated emulator is used.

Captures are lossless PNGs from Compose's root capture API, without system status/navigation pixels. The root retains the actual safe-drawing inset layout. Portrait is 1080x2400; landscape is 2400x1080; Android density is 420dpi (2.625 pixels per dp), font scale 1. The harness asserts orientation from display dimensions before rendering. Deprecated `swiftshader_indirect` repeatedly crashed QEMU on this Windows host; host GLES succeeded. Host-renderer rotation did not apply the requested portrait geometry, so separate AVDs were booted at each physical geometry. Both builds receive the same geometry and insets. This verifies responsive layout extraction, not physical rotation transitions.

Use the complete orientation-specific scenario sequence for both trees: the Android Compose clock has `autoAdvance=false` and advances 2,000ms per fixture. Earlier standalone-versus-sequence syllable captures differed because their spring histories were unequal. `--scenario landscape-artwork` is available for a bounded renderer probe; a standalone result must be compared with an equivalent standalone baseline. `--rotation` selects Android's requested user rotation, while the test still verifies actual geometry. Failed instrumentation logs are separated from successful references; only requested fixture files are pulled. Device records include the actual GLES renderer and installed APK hashes.

The strict comparator accepts only equal RGBA bytes at identical expected dimensions. It does not mask regions, resize images, use perceptual tolerances, or waive differences. It writes per-scenario hashes, changed-pixel counts, diagnostics, and a report under ignored `iOS/tests/results/android/`. Baseline and extracted screenshots remain available there for inspection. The September 4 complete 20-scenario capture passes with zero changed pixels; [ANDROID-VERIFICATION.md](ANDROID-VERIFICATION.md) links the evidence and preserves earlier failed comparisons.

Coverage: onboarding, idle player, portrait, long title/artist, failed lyrics, static/off backgrounds, reduced motion, settings, populated/empty local library, legal, diagnostics, all four landscape modes, reversed mixed media, multilingual static lyrics, and syllable/background-vocal lyrics.

Remaining gates are separate: same-size iOS screenshots, gesture/rotation transitions, live shader/spring animation trajectories, all settings scroll positions/dialogs, accessibility scaling, and physical iPhone output. The current Android reference geometry is 411.42857x914.28571dp, not a standard iPhone viewport. First obtain actual simulator view bounds, pixel scale, content safe insets, font scale, and capture boundaries. Then render additional Android references at that iPhone geometry, for example 1179x2556 at 480dpi for a verified 393x852-point view at 3x, with explicit matching app-content safe insets; capture native iOS at the same pixels without resampling. Add a separate geometry profile to the comparator instead of overwriting or relaxing this existing Android gate. Native display-frame settling and app-owned capture readiness must also be controlled. Do not interpret Android before/after success as verified iOS identity.

Bundled Roboto and explicit Noto script spans cover the fixture languages. Font loading, regional Han forms, complex emoji ligatures, native text shaping and antialiasing still require iOS image evidence. See `shared/ui/assets/font/README.md` for font provenance and bounds. Platform permission/OAuth sheets are intentional system exceptions.
