# Deterministic iOS raster captures

These two native tests run the production Compose UI, Skia artwork renderer, Android font bytes,
and original lyric springs using Compose UI Test **1.11.1**. They run with
`:shared:ui:iosSimulatorArm64Test` on macOS; Gradle passes the source asset directory and output
directory through `SIMCTL_CHILD_ICY_DETERMINISTIC_ASSET_ROOT` and
`SIMCTL_CHILD_ICY_DETERMINISTIC_OUTPUT_ROOT` to the simulator process.

The profiles deliberately match the preserved Android API 36 reference, rather than an iPhone:

| Profile directory | Pixels | Density | Font scale | Safe insets, left/top/right/bottom |
| --- | --- | --- | --- | --- |
| `android36-420dpi-portrait-v1` | 1080 × 2400 | 2.625 | 1 | 0, 63, 0, 63 px |
| `android36-420dpi-landscape-v1` | 2400 × 1080 | 2.625 | 1 | 0, 63, 0, 63 px |

Each test uses the full original ordered fixture sequence. Automatic clock advancement is
disabled. The original unconfined composition dispatcher advances exactly 2,000 ms per scenario,
with Compose's 16 ms frame interval. The screenshot cannot advance the clock. The ten-minute
wall-clock timeout bounds each profile without changing virtual animation time. No production
spring is replaced, skipped, or snapped to its target by these tests.

`PinnedComposeTestHarness.kt` isolates the `InternalTestApi` constructor needed to inject measured
safe insets. Its public test clock and image capture APIs remain unchanged. See the pinned
[Compose constructor and raster surface implementation](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.1/compose/ui/ui-test/src/skikoMain/kotlin/androidx/compose/ui/test/ComposeUiTest.skiko.kt#L141).
The native dependency audit rejects a changed UI Test version, duplicate KLIBs, or missing KLIB
dependencies. There is no framework fork.

The test asset loader reads the checked-in bytes from disk and verifies every bundled Android
font against its original SHA-256 provenance. Production still reads the same bytes from
`IcyAssets` in the app bundle. The only other platform adapters are an undrawn no-op back handler
for the offscreen scene and a native `NSDateFormatter` using the production medium-date and
short-time styles under explicit `en_US` / `America/Los_Angeles`. Its real output is drawn and
recorded verbatim; Android date punctuation is never substituted.

Outputs are under the ignored `iOS/build/reports/deterministic-ios-captures` directory. Each profile
contains `{scenario}.png`, `{scenario}.json`, and `manifest.json`. Scenario metadata has top-level
`widthPx`, `heightPx`, `density`, `fontScale`, `safeDrawingInsetsPx`, `captureBackend: "skia-raster"`,
`locale`, `timezone`, `clockTimeMillis`, `clockStartMs`, `scenarioIndex`, `scenarioOrder`, `profileId`,
`scenario`, `pngBytes`, `pngSha256`, and `formattedDates` (epoch-millisecond string to actual native
formatted string). The manifest records the complete ordered capture list, font/asset hashes,
clock configuration, pinned versions, and original Android archive hash. It remains
`complete: false` until every scenario succeeds, and always starts with `appearanceParityVerified:
false`. Producing a PNG is not an appearance-parity assertion.

This lane uses the native iOS **offscreen raster** backend. It does not validate UIKit safe areas,
Metal output, real display timing, or native app integration. Those remain separate native
screenshots and physical-device acceptance gates. Compare PNG pixels without resizing, masking,
or tolerances; report date, font shaping, blur, and renderer differences as failures to investigate.
