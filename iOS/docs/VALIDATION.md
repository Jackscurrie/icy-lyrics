# Validation status

This file distinguishes code checks from rendered/device acceptance. Native Kotlin tests have now run on GitHub's iPhone simulator; Swift application tests, screenshot acceptance and device packaging remain pending. No IPA has been produced yet.

## First macOS results

[Run 33848815538](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33848815538), source revision `a95cf1d`, used Xcode 26.4.1 (17E202) and passed all 90 native Kotlin tests: 83 lyrics/parser/animation tests, 2 real SQLite/preferences persistence tests, and 5 UI math/shader tests. The simulator framework linked successfully. The same revision passed desktop and Android public CI.

Xcode then reported one Swift artwork-bridging error (`NSData` passed where Swift imports `Data`). Removing that cast allowed the Swift application to compile and link in [run 33850344207](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33850344207), revision `17f3bc3`. That run then failed because the test bundles also targeted Intel while the app/framework were ARM64-only. All application/test configurations now explicitly target ARM64.

In [run 33851816799](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33851816799), revision `796333b`, the shared native tests and Swift application/test-bundle compilation passed. App launch then aborted because Compose requires `CADisableMinimumFrameDurationOnPhone=true` in `Info.plist`. That entry is now present with a regression check; the Swift tests and screenshots still require a successful rerun. Earlier native runs exposed SnakeYAML's unsupported oversized-integer path, now avoided by a bounded integer constructor with strict shared regression coverage. No failed test was waived.

## Passed locally

- Shared lyrics JVM verification: 83 tests, including original parser cases and portability/limits/Unicode/integer-boundary cases.
- Android core lyrics regression: 73 original tests against extracted canonical code.
- Android platform regression: 69 tests covering provider behavior, matching, local persistence, migrations/settings and timing.
- Android app regression: 68 tests, including 11 new controller tests for stale imports, late provider responses, cancellation, queue promotion and settings races, plus 4 shared artwork-math tests.
- Android Play debug lint and public/private distribution-boundary verification.
- Both iOS production dependency graphs: 71 native libraries each; both test graphs: 73 each, with no duplicate identities or missing native dependencies. Material3 remains pinned to 1.9.0. Common metadata forwarding duplicates are resolved without removing native binary dependencies.
- Common and iOS Kotlin metadata compilation, including iPhone service/controller/platform/shader source. This is type-checking; it is not a native framework link or an Xcode build.
- Exact Android extraction comparison: 20/20 freshly captured portrait and landscape scenarios, zero changed RGBA pixels across 51,840,000 pixels; no masks, resizing or tolerances. [Report and evidence](../tests/ANDROID-VERIFICATION.md). Dedicated portrait/landscape emulators using the host GPU resolved the earlier Windows renderer failures. Both trees use the original spring-animation path and the same fixture order; original production-source hashes remain unchanged.
- Build/packaging verification: Python checks cover IPA validation, exact committed-source requirements, Xcode generation, strict screenshot comparison and authenticated owner transfer. Simulator binaries, truncated/encrypted binaries, broken library dependencies, raised minimum OS versions, and incomplete test evidence are rejected. Test counts are recorded by the build log.

The capture harness uses 20 offline scenes with deterministic track/artwork/lyrics and a fixed frame time. It captures actual original Android production code from the frozen pre-extraction baseline and the extracted version. Capture/comparison results are generated under `tests/results`; a separate checked-in report records completed measurements. No baseline is created by simply treating the new iOS output as correct.

Twenty-three Swift OAuth/import tests are authored but have not executed successfully yet. Eight additional native managed-import tests and four provider-session cancellation tests also await macOS execution. The native shader-compilation test passed in the macOS run above. Android and iOS bind an import to the track present when the picker opens, so a song change while Files is open cannot attach the result to the newly playing song.

The new offscreen iOS raster capture lane uses the complete preserved Android scenario order, original springs, 2,000 ms controlled-clock advances, matching density/font scale/insets and SHA-verified fonts. Its source and native dependencies have been type-checked, but actual rendering is pending. `tests/compare_ios_parity.py` produces exact RGBA differences and rejects incomplete captures or mismatched geometry. Its report remains separate from native UIKit/Metal acceptance; it cannot approve the production renderer by itself.

## macOS CI gates

The existing GitHub workflow runs the isolated iOS build with explicit Xcode 26.4.1 and JDK 17. It must pass native Kotlin framework linking, native parser/persistence/shader tests, Swift OAuth/import tests, simulator launch and screenshot capture tests, followed by separate device archiving and IPA integrity checks. Failed or missing simulator results cannot produce the “simulator-verified” marker.

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
