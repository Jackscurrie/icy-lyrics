@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.icyTypography
import com.icy.lyrics.ui.standaloneFontCollectionFace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosAndroidFontTest {
  @Test fun recordComposeHeaderMetricsWithTheProductionTypography() = runAndroidReferenceUiTest(AndroidReferenceProfile.Portrait) {
    val assets = DeterministicFixtureAssets()
    val platform = assets.platform()
    var samples: List<JsonObject>? = null
    mainClock.autoAdvance = false
    setContent {
      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val typography = icyTypography()
        val text = "Play something in Spotify"
        val measurements = listOf(400, 700).map { weight ->
          val style = typography.headlineMedium.copy(fontWeight = FontWeight(weight))
          val result = measurer.measure(text, style, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = 20_000))
          buildJsonObject {
            put("requestedWeight", weight)
            put("fontSizeSp", style.fontSize.value)
            put("fontSizePx", with(density) { style.fontSize.toPx() })
            put("lineHeightSp", style.lineHeight.value)
            put("letterSpacingSp", style.letterSpacing.value)
            put("widthPx", result.size.width)
            put("heightPx", result.size.height)
            put("lineLeftPx", result.getLineLeft(0))
            put("lineRightPx", result.getLineRight(0))
            put("lineBottomPx", result.getLineBottom(0))
            put("firstBaselinePx", result.firstBaseline)
            put("lastBaselinePx", result.lastBaseline)
            put("characterBounds", JsonArray(text.indices.map { offset ->
              val bounds = result.getBoundingBox(offset)
              buildJsonObject {
                put("offset", offset); put("left", bounds.left); put("top", bounds.top)
                put("right", bounds.right); put("bottom", bounds.bottom)
              }
            }))
          }
        }
        SideEffect { samples = measurements }
      }
    }
    // This isolated diagnostic has its own test clock and does not advance either capture sequence.
    mainClock.advanceTimeByFrame()
    waitForIdle()
    val report = buildJsonObject {
      put("schemaVersion", 1)
      put("text", "Play something in Spotify")
      put("density", 2.625f)
      put("fontScale", 1f)
      put("scope", "Compose TextMeasurer with unchanged production headlineMedium and bundled Roboto")
      put("samples", JsonArray(assertNotNull(samples)))
    }
    assets.write(assets.outputRoot / "native-compose-font-metrics.json", report.toString().encodeToByteArray())
    println("Native Compose Roboto metrics: $report")
  }

  @Test fun originalCjkFacesLoadWithTheirOriginalNamesGlyphsAndWeightAxes() {
    val original = DeterministicFixtureAssets().read("font/NotoSansCJK-Regular.ttc")
    listOf("Noto Sans CJK JP", "Noto Sans CJK KR", "Noto Sans CJK SC").forEachIndexed { index, name ->
      val data = Data.makeFromBytes(standaloneFontCollectionFace(original, index))
      val face = try { assertNotNull(FontMgr.default.makeFromData(data, 0), "Original face $index") }
      finally { data.close() }
      try {
        assertEquals(name, face.familyName)
        assertTrue(face.getUTF32Glyph(0x4e16).toInt() != 0, "Original CJK glyph must exist")
        for (weight in listOf(400f, 700f)) {
          val variant = face.makeClone(arrayOf(FontVariation("wght", weight)), 0)
          try {
            assertEquals(name, variant.familyName)
            assertEquals(weight, assertNotNull(variant.variations).single { it.tag == "wght" }.value)
          } finally { variant.close() }
        }
      } finally { face.close() }
    }
  }

  @Test fun recordOriginalRobotoNativeMetricsWithoutChangingProductionTypography() {
    val assets = DeterministicFixtureAssets()
    val data = Data.makeFromBytes(assets.read("font/Roboto-Regular.ttf"))
    val face = try { assertNotNull(FontMgr.default.makeFromData(data, 0)) } finally { data.close() }
    val samples = try {
      listOf(400f, 700f).flatMap { weight ->
        val variant = face.makeClone(arrayOf(FontVariation("wght", weight), FontVariation("wdth", 100f), FontVariation("ital", 0f)))
        try {
          val font = Font(variant, 28f * 2.625f)
          try {
            val defaultSubpixel = font.isSubpixel
            listOf(false, true).map { subpixel ->
              font.isSubpixel = subpixel
              buildJsonObject {
                put("requestedWeight", weight)
                put("family", variant.familyName)
                put("fontStyleWeight", variant.fontStyle.weight)
                put("sizePx", font.size)
                put("unshapedTextAdvancePx", font.measureTextWidth("Play something in Spotify"))
                put("unshapedGlyphAdvanceSumPx", font.getWidths(font.getStringGlyphs("Play something in Spotify")).sum())
                put("isLinearMetrics", font.isLinearMetrics)
                put("isSubpixel", font.isSubpixel)
                put("defaultIsSubpixel", defaultSubpixel)
                put("hinting", font.hinting.toString())
                put("variationCoordinates", buildJsonObject {
                  variant.variations.orEmpty().forEach { put(it.tag, it.value) }
                })
              }
            }
          } finally { font.close() }
        } finally { variant.close() }
      }
    } finally { face.close() }
    val report = buildJsonObject {
      put("schemaVersion", 1)
      put("text", "Play something in Spotify")
      put("scope", "Unshaped Skia/CoreText font metrics; no Compose paragraph kerning or pixel-parity claim")
      put("samples", JsonArray(samples))
    }
    assets.write(assets.outputRoot / "native-font-metrics.json", report.toString().encodeToByteArray())
    println("Native Roboto metrics: $report")
  }
}
