# Icy Lyrics for iPhone

The Android V2 presentation is now canonical Kotlin/Compose source under `shared/ui`. Android compiles those same files with its original Android toolchain. This isolated build adds the iPhone adapters, Swift shell, and resignable IPA packaging. There is no App Store submission or paid Apple signing setup.

**Status: source port implemented; desktop/Android CI, 90 native Kotlin simulator tests, and Swift application compilation/linking passed; application tests and appearance acceptance pending.** See [validation status](docs/VALIDATION.md). All 20 archived Android portrait and landscape scenarios match the original pixel-for-pixel after extraction. The simulator app now compiles and links, but its Swift tests and screenshot captures have not passed yet, and physical-iPhone testing has not started. An IPA must not be described as simulator-verified until the complete macOS job passes. Android-identical iPhone appearance remains an acceptance requirement.

All new authored work is inside this folder. The only integrations outside it are edits to existing Android source/build files and the existing `.github/workflows/ci.yml`. Android's existing `play`/`personal` boundary, application ID, database opening, settings keys, launcher edits, and native MediaSession handling are retained.

## Build and use

1. Make this source revision available in a public GitHub repository with Actions enabled. The existing workflow's iPhone job uses the standard ARM64 `macos-26` runner and selects Xcode 26.4.1 explicitly. It skips private repositories to avoid unexpected runner charges.
2. Register an iOS application with Spotify. Set public repository variable `ICY_IOS_SPOTIFY_CLIENT_ID`; register the actual bundle ID and `com.icy.lyrics.ios://spotify-callback`. No client secret belongs in the app or workflow. An empty ID builds offline fixtures but cannot connect real Spotify playback.
3. Run **Public source checks** manually or push the tested revision. `bash iOS/scripts/build_ios.sh` also runs on a compatible Apple Silicon Mac with JDK 17. The script builds/tests the simulator target, then archives a separate device target.
4. The successful workflow uploads reports and an encrypted owner delivery. The matching private age identity remains on the owner's Windows PC. Resolve the [distribution review](docs/DISTRIBUTION.md) before making an SDK-containing binary public; `ICY_IOS_BINARY_DISTRIBUTION_CLEARED=true` additionally enables plaintext IPA artifact upload. It never publishes a GitHub Release automatically.
5. Use [Windows installation and refresh instructions](docs/INSTALL-WINDOWS.md) when the validated IPA is available.

The generated device output is `build/delivery/IcyLyrics-unsigned.ipa`, with `SHA256SUMS.txt`, `build-report.json`, and installation instructions. Simulator screenshots, tests, and build logs are separate verification artifacts. Generated files and credentials stay ignored.

## Development

- [Architecture and behavior](docs/ARCHITECTURE.md)
- [Validation and outstanding acceptance checks](docs/VALIDATION.md)
- [Measured Android screenshot results](tests/ANDROID-VERIFICATION.md) and [original/extracted capture archive](tests/evidence/android-complete-parity.zip)
- `scripts/generate_xcode_project.py`: regenerate the checked-in Xcode project after adding Swift sources; `--check` checks reproducibility.
- `scripts/bootstrap_spotify.py`: fetch the exact, SHA-256-checked Spotify iOS SDK 5.0.1 into ignored `app/Frameworks`.
- `tests/prepare_android_baseline.py`, `tests/capture_android_parity.py`: original-versus-extracted Android capture harness. The baseline uses the frozen pre-extraction source; never recapture it from already-extracted production code.
- `tests/unit`: Android-hosted tests of the common presentation controller. `shared/lyrics/src/commonTest` and platform `iosTest` exercise portable parsing and native persistence.
- `app/Config.local.xcconfig`: optional ignored local override for the public Spotify client ID. Keep the callback scheme stable across refreshes. Build scripts accept `SPOTIFY_CLIENT_ID` without writing it to a file.

Toolchain: Kotlin/compiler 2.4.10, Gradle 9.4.1, JDK 17, Compose Multiplatform 1.11.1, explicitly pinned JetBrains Material3 **1.9.0**. Android remains on its existing toolchain and Material3 implementation. Do not replace the Material3 pin with the Compose plugin's default alias.
