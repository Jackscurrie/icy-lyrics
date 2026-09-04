# Rendering differences under investigation

The shared layouts remain the Android reference. A successful simulator build
does not approve their appearance. The complete coverage checklist is
[VISUAL-ACCEPTANCE.md](VISUAL-ACCEPTANCE.md).

The 20 complete raster captures from revision `7115b2e` establish these specific
differences at the Android 2.625-density reference profile:

| Difference | Evidence and implementation boundary |
|---|---|
| Inactive lyric brightness | Opaque glyph interiors are RGB 54 versus 108, and 23 versus 46, on black. Android's shader span retains the base text-paint alpha when span alpha is unspecified; Skiko resets it to one. The iOS canvas-text adapter now supplies the inherited eight-bit paint alpha explicitly for the existing flat shader spans. Native pixel and full-scene verification remain pending. |
| Header advances | Android widths are 825/839 px at weights 400/700; iOS widths are 829/843. Android shapes at the integer physical size while drawing at the original fractional size. Globally reducing font size would also shrink glyph outlines and is not a faithful fix. |
| Line metrics | Android's integer font/line-height arithmetic gives baseline 72; iOS reports 72.805664. Multilingual line positions accumulate differences. This needs layout-metric compatibility, not arbitrary per-screen offsets. |
| Color emoji | Actual image regions confirm that the three original emoji are blank on iOS. The original main emoji font uses COLRv1/CPAL with no ordinary outlines or COLRv0 fallback for those glyphs. The separate flags font uses CBDT PNG strikes. Retain the original data and investigate a compatible font backend; substituting Apple emoji would change the design. |
| Lyric glow | Android converts a blur radius to Gaussian sigma; CMP's Skiko text path takes sigma directly. An iOS-only canvas adapter now performs Android's conversion for base and span shadows. Eight actual Android radius probes are preserved; matching native execution remains pending. |
| Library timestamp | Android's actual MEDIUM/SHORT patterns, symbols and digits were measured for 881 locales, including 144 formatted examples. The iOS adapter uses that data with Foundation Gregorian components. Native verification remains pending; see [date evidence](../tests/DATE-FORMAT-PARITY.md). |
| Static background | Dithering did not produce identical raster pixels. The measured unobstructed differences remain at most 3/255 per channel. Keep the full differences and compare production UIKit/Metal at a matched native profile. |

Measured icon regions are present and pixel-identical. An initial preview-based
impression of missing icons was disproved by exact RGBA inspection. Artwork fill
bounds also match in the five reviewed portrait/landscape layouts.

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
