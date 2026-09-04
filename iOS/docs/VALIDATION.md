# Validation status

This file distinguishes code checks from rendered/device acceptance. Native Kotlin tests have now run on GitHub's iPhone simulator; Swift application tests, screenshot acceptance and device packaging remain pending. No IPA has been produced yet.

## First macOS results

[Run 33848815538](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33848815538), source revision `a95cf1d`, used Xcode 26.4.1 (17E202) and passed all 90 native Kotlin tests: 83 lyrics/parser/animation tests, 2 real SQLite/preferences persistence tests, and 5 UI math/shader tests. The simulator framework linked successfully. The same revision passed desktop and Android public CI.

Xcode then reported one Swift artwork-bridging error (`NSData` passed where Swift imports `Data`). That cast has been removed; the full Swift application/simulator run must pass before it supplies release evidence. Earlier native runs exposed SnakeYAML's unsupported oversized-integer path, now avoided by a bounded integer constructor with strict shared regression coverage. No failed test was waived.

## Passed locally

- Shared lyrics JVM verification: 83 tests, including original parser cases and portability/limits/Unicode/integer-boundary cases.
- Android core lyrics regression: 73 original tests against extracted canonical code.
- Android platform regression: 69 tests covering provider behavior, matching, local persistence, migrations/settings and timing.
- Android app regression: 68 tests, including 11 new controller tests for stale imports, late provider responses, cancellation, queue promotion and settings races, plus 4 shared artwork-math tests.
- Android Play debug lint and public/private distribution-boundary verification.
- Both iOS dependency graphs: 71 native libraries each with no duplicate identities or missing native dependencies, pinned Material3 and 103 locked configurations. Common metadata forwarding duplicates resolved without removing native binary dependencies.
- Common and iOS Kotlin metadata compilation, including iPhone service/controller/platform/shader source. This is type-checking; it is not a native framework link or an Xcode build.
- Exact Android extraction comparison: 13/13 captured portrait scenarios, zero changed RGBA pixels across 33,696,000 pixels; no masks, resizing or tolerances. [Report and evidence](../tests/ANDROID-VERIFICATION.md). The complete 20-scenario gate intentionally fails because 7 landscape cases remain missing after repeated Windows emulator native exits.
- Build/packaging verification: 31 Python tests, reproducible Xcode project generation, Bash syntax, and inspection of the real Spotify SDK device binary. Simulator binaries, truncated/encrypted binaries, broken library dependencies, raised minimum OS versions, and incomplete test evidence are rejected.

The capture harness uses 20 offline scenes with deterministic track/artwork/lyrics and a fixed frame time. It captures actual original Android production code from the frozen pre-extraction baseline and the extracted version. Capture/comparison results are generated under `tests/results`; a separate checked-in report records completed measurements. No baseline is created by simply treating the new iOS output as correct.

Nineteen Swift OAuth/import tests are authored but have not executed successfully yet. The native shader-compilation test passed in the macOS run above. Android and iOS bind an import to the track present when the picker opens, so a song change while Files is open cannot attach the result to the newly playing song.

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
