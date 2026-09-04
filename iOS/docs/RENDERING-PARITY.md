# Rendering differences under investigation

The shared layouts remain the Android reference. A successful simulator build
does not approve their appearance. The complete coverage checklist is
[VISUAL-ACCEPTANCE.md](VISUAL-ACCEPTANCE.md).

The 20 complete raster captures from revision `7115b2e` establish these specific
differences at the Android 2.625-density reference profile:

| Difference | Evidence and implementation boundary |
|---|---|
| Inactive lyric brightness | Original captures showed glyph interiors at RGB 54 versus 108, and 23 versus 46, on black. Android's shader span retains the base text-paint alpha when span alpha is unspecified; Skiko resets it to one. In revision `b8d3a9e`, the actual native inherited-alpha probe changes from stock 102 to adapted 51 for base alpha 0.5 and gradient alpha 0.4. Opaque-base output remains 102 and explicit-quarter-span output remains 26. The corrected policy is observed; full scenes still differ. |
| Header advances | Android widths are 825/839 px at weights 400/700; iOS widths are 829/843. Android shapes at the integer physical size while drawing at the original fractional size. Globally reducing font size would also shrink glyph outlines and is not a faithful fix. |
| Line metrics | Android's integer font/line-height arithmetic gives baseline 72; iOS reports 72.805664. Multilingual line positions accumulate differences. This needs layout-metric compatibility, not arbitrary per-screen offsets. |
| Color emoji | Actual image regions confirm that the three original emoji are blank on iOS. The original main emoji font uses COLRv1/CPAL with no ordinary outlines or COLRv0 fallback for those glyphs. The separate flags font uses CBDT PNG strikes. Retain the original data and investigate a compatible font backend; substituting Apple emoji would change the design. |
| Lyric glow | Android converts a blur radius to Gaussian sigma; CMP's Skiko text path takes sigma directly. The iOS adapter applies Android's conversion to base and span shadows. Actual native revision `b8d3a9e` reduces differences substantially at all four tested radii, with 262–466 pixels still different and maximum channel differences 1–2; the measured table below preserves those residuals. |
| Library timestamp | Android's actual MEDIUM/SHORT patterns, symbols and digits were measured for 881 locales. The iOS adapter uses those profiles with Foundation Gregorian components. Revision `b8d3a9e` matched all 144 native formatted examples exactly across 18 locales and America/Los_Angeles/UTC. This verifies the tested strings, not every profile, locale extension, font shaping, or screenshot; see [date evidence](../tests/DATE-FORMAT-PARITY.md). |
| Static background | Dithering did not produce identical raster pixels. The measured unobstructed differences remain at most 3/255 per channel. Keep the full differences and compare production UIKit/Metal at a matched native profile. |
| Kawarp child sampling | Actual Android hardware RuntimeShader pixels confirm nearest-neighbor sampling for a default BitmapShader child, even with the outer paint's filtering enabled. The iOS child now uses Skiko's matching default sampling; artwork preprocessing retains linear resizing. The two control images and metadata are preserved in [the evidence archive](../tests/evidence/android36-runtime-child-sampling.zip). A native runtime pixel test and full GPU frames remain separate gates. |

Measured icon regions are present and pixel-identical. An initial preview-based
impression of missing icons was disproved by exact RGBA inspection. Artwork fill
bounds also match in the five reviewed portrait/landscape layouts.

## Actual native adapter and motion results

In [run 33865575594](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33865575594), revision `b8d3a9e`, all 20 static raster comparisons still differ. The separate complete 44-frame motion comparison also passes its strict source, fixture, asset, action, clock and geometry validation, then reports differences in every frame. It compares all 114,048,000 pixels, of which 81,129,632 differ. This remains Skia raster evidence, separate from production UIKit/Metal.

The original eight Android shadow probe PNGs were compared with native base/span outputs at the same 768×384 geometry, density 2.625, 64sp bold text, colors, offset and radius. Base/span pairs produce identical results at each radius:

| Radius in pixels | Stock changed pixels | Stock maximum channel difference | Adapted changed pixels | Adapted maximum channel difference |
|---|---:|---:|---:|---:|
| 0 | 1,848 | 75 | 362 | 2 |
| 4 | 12,777 | 56 | 466 | 1 |
| 15 | 30,078 | 78 | 263 | 1 |
| 30 | 55,143 | 53 | 262 | 1 |

The adapted peak grays match Android at 255/255/236/161. Native nonzero support is 7698/10910/21118/35249 pixels versus Android 7698/10910/21119/35250. Native baseline 155.98438 differs from Android 156. The additional native `base-after-typography` images equal corresponding native base images, providing a control against double conversion. No residual difference is waived.

The motion sequence uses the same composition, original springs, semantic actions and frame clocks on both platforms. Initial MIXED and final reversed MIXED images are byte-identical within each platform/side; both sides converge to the same settled LYRICS image within each platform. The cross-platform images still differ. In the left initial MIXED image, 1,673,157 pixels differ; 1,584,879 have maximum channel differences of 1–3, while 58,644 exceed 15. These are descriptive bins, not thresholds or masks. Inspected transition images retain the outgoing media layer and matching line-wrap structure; glyph widths and broad low-amplitude background differences remain visible. Exact intermediate motion, text, and renderer parity are not established.

The native date and shader-alpha reports are in the run's `deterministic-ios-captures/native-date-format` and `native-shader-alpha` directories; shadow originals are retained in [the Android archive](../tests/evidence/android36-text-shadows.zip). The main artifact is [9934906639](https://github.com/Jackscurrie/icy-lyrics/actions/runs/33865575594/artifacts/9934906639), SHA-256 `9b26f469cb04b8cb4acf893b5941d47214f5294b2adb634d8f21fa7c9700a9af`. Default UIKit screenshot geometry failed again, leaving no valid 29-case native batch. The new host framebuffer capture path must produce actual correctly sized images before measured native-profile or GPU acceptance can proceed.

The native iPhone profile must be measured separately. At density 3, many authored
integer-sp sizes have integer physical sizes, so the fractional-size discrepancy
above may not occur for those roles. This does not establish native parity.

Pinned implementation evidence:

- Android Compose ui-text 1.11.0 `ShaderBrushSpan.android.kt` calls `setAlpha`;
  `AndroidTextPaint.android.kt` leaves alpha unchanged for NaN. The exact sources
  come from its Maven source archive. CMP 1.11.1
  [SkiaTextPaint](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.1/compose/ui/ui-text/src/skikoMain/kotlin/androidx/compose/ui/text/platform/SkiaTextPaint.skiko.kt)
  explicitly substitutes alpha one for NaN.
- AOSP's [Minikin size preparation](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r2/libs/hwui/hwui/MinikinUtils.cpp#41)
  and [positioned text drawing](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r2/libs/hwui/hwui/Canvas.cpp#89)
  separate shaping and drawing sizes. These sources explain the observed policy;
  the exact Google Play emulator's Skia commit has not been established.
- Skiko's pinned [iOS font manager](https://github.com/JetBrains/skiko/blob/9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0/skiko/src/commonMain/cpp/common/FontMgrDefaultFactory.cc#L32)
  selects CoreText. No portable FreeType/Fontations manager is present in the
  inspected device or simulator archives.

The early raw `Font.measureTextWidth(String)` diagnostic is not a valid reference:
its pinned native wrapper mixes UTF-8 storage and a UTF-16 length/encoding. That
call has been removed. The separate typed glyph-array advances and actual Compose
layout measurements do not use that faulty string path.
