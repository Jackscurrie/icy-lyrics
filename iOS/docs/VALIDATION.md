# Validation status

This file distinguishes code checks from rendered/device acceptance. Native Kotlin and Swift application tests have passed on GitHub's iPhone simulator. Complete UIKit capture, screenshot acceptance and device packaging remain pending. No IPA has been produced yet.

## First macOS results

[Run 33848815538](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33848815538), source revision `a95cf1d`, used Xcode 26.4.1 (17E202) and passed all 90 native Kotlin tests: 83 lyrics/parser/animation tests, 2 real SQLite/preferences persistence tests, and 5 UI math/shader tests. The simulator framework linked successfully. The same revision passed desktop and Android public CI.

Xcode then reported one Swift artwork-bridging error (`NSData` passed where Swift imports `Data`). Removing that cast allowed the Swift application to compile and link in [run 33850344207](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33850344207), revision `17f3bc3`. That run then failed because the test bundles also targeted Intel while the app/framework were ARM64-only. All application/test configurations now explicitly target ARM64.

In [run 33851816799](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33851816799), revision `796333b`, the shared native tests and Swift application/test-bundle compilation passed. App launch then aborted because Compose requires `CADisableMinimumFrameDurationOnPhone=true` in `Info.plist`. That entry is now present with a regression check; the Swift tests and screenshots still require a successful rerun. Earlier native runs exposed SnakeYAML's unsupported oversized-integer path, now avoided by a bounded integer constructor with strict shared regression coverage. No failed test was waived.

[Run 33854638926](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33854638926), revision `55d6a58`, passed all 63 Python checks on macOS, the lyrics test tasks and application framework linking. Two newly added native test files had compiler errors (Okio `use` import and generic `plusAssign` inference); both are corrected. Both complete iOS simulator test KLIB compilation tasks subsequently executed successfully on Windows. Native execution and Swift launch remain pending. The existing Android Spotify Client ID is now configured in the GitHub iOS variable following the owner's confirmation of the bundle/callback registration; the next run must include it.

[Run 33855937655](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33855937655), revision `c402dec`, included the configured Spotify Client ID. It passed 83 native lyrics tests, 10 native persistence/import tests and 10 of 11 UI tests, including all four provider-session cancellation cases. The portrait capture sequence completed; five landscape scenes rendered before the multilingual scene failed to load CJK collection face 2. Skia's CoreText loader rejects nonzero collection indices; the adapter now extracts the selected face into an in-memory SFNT while preserving its original font tables. The fix requires another native run. Swift/UI tests did not run on this revision.

The first iOS evidence contains 18 actual raster captures. All 13 completed portrait comparisons differ from Android; no appearance acceptance is claimed. Text widths/rasterization and small static-background RGB differences are under investigation. The incomplete landscape manifest is correctly rejected by the comparator. The original capture artifact is [9930852319](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33855937655/artifacts/9930852319), SHA-256 `0ce16ed75360903f2f1bbe136862a03b3608d75fe5a833f72ea14dbb99db082a`.

Two pinned platform defaults differ: Android's Compose gradient paint enables dithering, and its default `TextMotion.Static` disables fractional glyph positioning. The iOS adapters now request those same policies while retaining all original colors, dimensions, type sizes and font axes. New native font/paragraph metrics and the next capture run must establish their actual effect; these source corrections do not themselves prove identical rendering.

[Run 33859647445](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33859647445), revision `7115b2e`, passed all **108 native Kotlin tests**: 83 lyrics, 10 persistence/import and 15 UI tests, with zero failures or skipped tests. The original CJK faces now load successfully, and all 20 offscreen raster captures completed. The simulator framework linked. A newly added architecture-check command placed its input after `lipo -verify_arch`; Apple treats that trailing input as another architecture. Both command sites now use Apple's input-first ordering, with a regression that rejects the old invocation. Swift/UI execution and IPA packaging did not run on this revision.

All 20 strict image comparisons still report differences. The original artifact is [9932285807](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33859647445/artifacts/9932285807), 12,455,110 bytes, SHA-256 `a8c6c78ae90e825f74eaa56e9dadc9a828cd28643369950f8cd6e6f10b813f9c`. Native Compose measurements show the 28sp header at 829/843 pixels for weights 400/700 versus Android's 825/839; iOS baseline 72.805664 versus Android's 72. The matched positioning policy alone does not fix font metrics. Dithering increased the number of differing raster background pixels; in the unobstructed static background their maximum channel difference remains 3/255. This result requires investigation against production UIKit/Metal and is not an appearance pass.

[Run 33862494216](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33862494216), revision `e247609`, passed the same 108 native Kotlin tests and all **23 Swift tests** (14 authorization and 9 imported-TTML cases). The app launched and produced all 13 portrait captures plus one large-text portrait capture. Three UIKit test functions timed out on their first landscape draw acknowledgement. Recorded video confirms the app rotated; the debug host incorrectly compared raw UIKit safe-area insets against Compose 1.11.1's corner-adapted region. The next run uses the same native region and retains diagnostic metadata when readiness fails.

The captures also expose XCTest's default video path reducing an odd 1179-pixel width to 1178 pixels. Those images are not valid matched native-profile references. Both schemes now request still screenshots, and the tests assert exact native dimensions. No image has been resized to repair this discrepancy. Main evidence: [artifact 9933245884](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33862494216/artifacts/9933245884), 136,463,374 bytes, SHA-256 `96c1b5fc338f35a52d8bbfe035912bba93681b9568cb721b88328eba915d8286`.

The opt-in extended lane rejected the correct simulator because it checked its custom display name instead of its iPhone device type. The motion lane captured its initial frames, then rejected an unexpected 16-ms advance caused by iOS `performClick`; it now invokes the same semantic action used by the Android producer. Both strict checks remain enabled. Additional evidence: [artifact 9933246776](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33862494216/artifacts/9933246776), SHA-256 `aca587aa7c7f352f314d00c7b14c64fc8b6032ffe875b46f8958a6a789335dad`. Neither a verification marker nor an IPA was produced.

## Passed locally

- Shared lyrics JVM verification: 83 tests, including original parser cases and portability/limits/Unicode/integer-boundary cases.
- Android core lyrics regression: 73 original tests against extracted canonical code.
- Android platform regression: 69 tests covering provider behavior, matching, local persistence, migrations/settings and timing.
- Android app regression: 70 tests, including 11 new controller tests for stale imports, late provider responses, cancellation, queue promotion and settings races, 4 shared artwork-math tests, and 2 original CJK table-preservation cases.
- Android Play debug lint and public/private distribution-boundary verification.
- Both iOS production dependency graphs: 71 native libraries each; both test graphs: 73 each, with no duplicate identities or missing native dependencies. Material3 remains pinned to 1.9.0. Common metadata forwarding duplicates are resolved without removing native binary dependencies.
- Common and iOS Kotlin metadata compilation, including iPhone service/controller/platform/shader source. This is type-checking; it is not a native framework link or an Xcode build.
- Exact Android extraction comparison: 20/20 freshly captured portrait and landscape scenarios, zero changed RGBA pixels across 51,840,000 pixels; no masks, resizing or tolerances. [Report and evidence](../tests/ANDROID-VERIFICATION.md). Dedicated portrait/landscape emulators using the host GPU resolved the earlier Windows renderer failures. Both trees use the original spring-animation path and the same fixture order; original production-source hashes remain unchanged.
- Build/packaging verification: Python checks cover IPA validation, exact committed-source requirements, Xcode generation, strict screenshot comparison and authenticated owner transfer. Simulator binaries, truncated/encrypted binaries, broken library dependencies, raised minimum OS versions, and incomplete test evidence are rejected. Test counts are recorded by the build log.

The capture harness uses 20 offline scenes with deterministic track/artwork/lyrics and a fixed frame time. It captures actual original Android production code from the frozen pre-extraction baseline and the extracted version. Capture/comparison results are generated under `tests/results`; a separate checked-in report records completed measurements. No baseline is created by simply treating the new iOS output as correct.

Twenty-three Swift OAuth/import tests passed in run 33862494216. All eight native managed-import tests and four provider-session cancellation tests passed on macOS in run 33855937655. The native shader-compilation test also passed. Android and iOS bind an import to the track present when the picker opens, so a song change while Files is open cannot attach the result to the newly playing song.

The offscreen iOS raster capture lane uses the complete preserved Android scenario order, original springs, 2,000 ms controlled-clock advances, matching density/font scale/insets and SHA-verified fonts. The first native execution is recorded above. `tests/compare_ios_parity.py` produces exact RGBA differences and rejects incomplete captures or mismatched geometry. Its report remains separate from native UIKit/Metal acceptance; it cannot approve the production renderer by itself.

`tests/extract_native_profile.py` accepts actual UIKit screenshot/geometry attachments and produces exact content crops plus an Android viewport profile. It preserves source hashes, pixels and PNG color metadata; an optional safe-area-interior crop is explicitly a narrower comparison. `tests/capture_android_parity.py --viewport-profile ...` applies those measured dimensions, density, font scale and insets to an owned Android emulator, checks the effective Compose values and records native Android sp-to-pixel scaling. These profiles use separate result directories and do not replace the original 20 baselines. The Android instrumentation source compiles; no native-profile capture has run yet. UIKit real-time springs and Android nonlinear large-text scaling still require matching evidence.

## macOS CI gates

The existing GitHub workflow runs the isolated iOS build with explicit Xcode 26.4.1 and JDK 17. It must pass native Kotlin framework linking, native parser/persistence/shader tests, Swift OAuth/import tests, simulator launch and screenshot capture tests, followed by separate device archiving and IPA integrity checks. Failed or missing simulator results cannot produce the “simulator-verified” marker.

When this run produces a fresh ARM64 simulator framework, CI collects independent Swift/UI diagnostics even if a shared test fails. It exports available XCTest attachments after failures, then requires both test stages and attachment export to succeed before verification or packaging. A stale framework cannot qualify.

Native screenshot attachments are evidence, not automatic parity approval. Pair them with the Android baseline at matching logical dimensions, density, font scale, inset contract and clocks. Compare unmasked app-owned pixels strictly, save difference images, and review every mismatch. Do not resize reference images or increase thresholds to hide font/layout/shader differences. Allowed system/safe-area exceptions must be explicit and narrowly bounded.

## Remaining visual acceptance

- Complete same-size Android/iOS comparisons for every app-owned screen and dialog, expanded portrait, compact layouts, both landscape rotations and all four modes.
- Long titles, all supported scripts/emoji, RTL shaping, large text and reduced motion. Bundled Android fonts do not prove complete fallback/shaping equivalence.
- Deterministic animated Kawarp frames, lyric word/letter springs, seek, pause/resume, artwork changes and uninterrupted mixed-to-lyrics transitions. Static fixture captures alone cannot prove these.
- Scrolling/gesture behavior and platform renderer anti-aliasing differences; investigate differences rather than accepting permissive screenshot thresholds.

## Physical iPhone checklist (deferred as agreed)

- Install and launch the resigned IPA with a free Apple ID; refresh before/after seven days; update in place with populated settings and imported TTML.
- Spotify browser callback, app switch, fresh foreground state, disconnection/reconnection, token expiry/rejection, local URI preservation and available transport commands.
- Files-provider import, malformed/oversized content, interruption, stale import after track change, relaunch and upgrade persistence.
- Actual audio output identity/Bluetooth timing behavior, iPhone safe areas and both rotations, performance, thermal load, battery impact and reduced-motion preference.

The public build repository is [Jackscurrie/icy-lyrics](https://github.com/Jackscurrie/icy-lyrics). The macOS gates run through its **Public source checks** workflow; repository access alone does not establish a passing native build or visual acceptance.
