@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.rememberIcyTextMeasurer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Compose paragraph pixels; these small probes do not replace app screenshot acceptance. */
class IosShaderSpanAlphaPixelTest {
  @Test fun productionMeasurerPreservesAndroidInheritedAlphaAndExplicitSpanAlpha() =
    runAndroidReferenceUiTest(AndroidReferenceProfile.Portrait) {
      val assets = DeterministicFixtureAssets()
      val platform = assets.platform()
      val cases = listOf(
        ProbeCase("inherited-half", .5f, Float.NaN, 102, 51),
        ProbeCase("opaque-base", 1f, Float.NaN, 102, 102),
        ProbeCase("explicit-quarter", .5f, .25f, 26, 26),
      )
      var layouts: List<ProbeLayouts>? = null
      var observedDensity: Density? = null
      mainClock.autoAdvance = false
      setContent {
        CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
          val density = LocalDensity.current
          val stock = rememberTextMeasurer()
          val adapted = rememberIcyTextMeasurer(cacheSize = cases.size)
          val measured = cases.map { sample ->
            // Both gradient stops have exactly the same RGBA value: no spatial
            // gradient/raster edge can explain a different opaque glyph interior.
            val brush = Brush.linearGradient(
              listOf(Color.White.copy(alpha = .4f), Color.White.copy(alpha = .4f)),
              start = Offset.Zero, end = Offset(WIDTH.toFloat(), 0f),
            )
            val text = buildAnnotatedString {
              withStyle(SpanStyle(brush = brush, alpha = sample.spanAlpha)) { append("HH") }
            }
            val style = TextStyle(color = Color.White.copy(alpha = sample.baseAlpha),
              fontSize = 64.sp, fontWeight = FontWeight.Bold)
            val stockStyle = platform.textStyle(style.copy(fontFamily = platform.fontFamily))
            ProbeLayouts(sample,
              stock.measure(text, stockStyle, softWrap = false, maxLines = 1,
                constraints = Constraints(maxWidth = WIDTH - 16)),
              adapted.measure(text, style, softWrap = false, maxLines = 1,
                constraints = Constraints(maxWidth = WIDTH - 16)))
          }
          SideEffect { layouts = measured; observedDensity = density }
        }
      }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      val density = assertNotNull(observedDensity)
      val rendered = assertNotNull(layouts).map { measured ->
        assertEquals(measured.stock.size, measured.adapted.size, "Alpha must not change text geometry")
        RenderedProbe(measured.sample,
          render(assets, "${measured.sample.id}-stock", measured.stock, density),
          render(assets, "${measured.sample.id}-adapted", measured.adapted, density))
      }
      val report = buildJsonObject {
        put("schemaVersion", 1)
        put("scope", "Small actual Compose TextMeasurer/production IcyTextMeasurer alpha probes; not full app acceptance")
        put("appearanceParityVerified", false)
        put("gradientStopAlpha", .4f)
        put("background", "opaque black")
        put("widthPx", WIDTH); put("heightPx", HEIGHT)
        put("samples", JsonArray(rendered.map { sample -> buildJsonObject {
          put("id", sample.sample.id); put("baseAlpha", sample.sample.baseAlpha)
          if (sample.sample.spanAlpha.isNaN()) put("spanAlpha", JsonNull)
          else put("spanAlpha", sample.sample.spanAlpha)
          put("stock", pixelReport(sample.stock)); put("adapted", pixelReport(sample.adapted))
        } }))
      }
      assets.write(assets.outputRoot / "native-shader-alpha" / "report.json", report.toString().encodeToByteArray())
      for (sample in rendered) {
        assertPixels(sample.stock, sample.sample.stockMaximum, "${sample.sample.id} stock")
        assertPixels(sample.adapted, sample.sample.adaptedMaximum, "${sample.sample.id} production adapter")
        if (sample.sample.stockMaximum == sample.sample.adaptedMaximum) {
          assertContentEquals(sample.stock, sample.adapted, "${sample.sample.id} must remain unchanged")
        }
      }
    }

  private fun render(assets: DeterministicFixtureAssets, id: String, layout: TextLayoutResult, density: Density): IntArray {
    val bitmap = ImageBitmap(WIDTH, HEIGHT)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
      drawRect(Color.Black)
      drawText(layout, topLeft = Offset(8f, 8f))
    }
    val pixels = IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    try {
      val encoded = assertNotNull(image.encodeToData(EncodedImageFormat.PNG))
      try { assets.write(assets.outputRoot / "native-shader-alpha" / "$id.png", encoded.bytes) }
      finally { encoded.close() }
    } finally { image.close() }
    return pixels
  }

  private fun pixelReport(pixels: IntArray) = buildJsonObject {
    put("maximumRed", pixels.maxOf { (it ushr 16) and 255 })
    put("maximumGreen", pixels.maxOf { (it ushr 8) and 255 })
    put("maximumBlue", pixels.maxOf { it and 255 })
    put("opaquePixelCount", pixels.count { it ushr 24 == 255 })
    val maximum = pixels.maxOf { it and 255 }
    put("maximumInteriorPixelCount", pixels.count { (it and 255) == maximum })
  }

  private fun assertPixels(pixels: IntArray, expected: Int, label: String) {
    assertTrue(pixels.all { it ushr 24 == 255 }, "$label must be opaque over black")
    assertEquals(expected, pixels.maxOf { (it ushr 16) and 255 }, "$label red interior")
    assertEquals(expected, pixels.maxOf { (it ushr 8) and 255 }, "$label green interior")
    assertEquals(expected, pixels.maxOf { it and 255 }, "$label blue interior")
    assertTrue(pixels.count { (it and 0x00ffffff) == (expected * 0x010101) } > 16,
      "$label needs a solid glyph interior, not one antialiased edge pixel")
  }

  private data class ProbeCase(val id: String, val baseAlpha: Float, val spanAlpha: Float,
                             val stockMaximum: Int, val adaptedMaximum: Int)
  private data class ProbeLayouts(val sample: ProbeCase, val stock: TextLayoutResult, val adapted: TextLayoutResult)
  private data class RenderedProbe(val sample: ProbeCase, val stock: IntArray, val adapted: IntArray)
  private companion object { const val WIDTH = 512; const val HEIGHT = 256 }
}
