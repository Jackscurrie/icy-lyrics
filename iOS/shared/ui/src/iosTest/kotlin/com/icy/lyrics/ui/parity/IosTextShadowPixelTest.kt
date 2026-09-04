@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.androidSpanShadowsForLayout
import com.icy.lyrics.ui.rememberIcyTextMeasurer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosTextShadowPixelTest {
  @Test fun realParagraphBaseAndSpanShadowsShareOneAndroidBlurConversion() =
    runAndroidReferenceUiTest(AndroidReferenceProfile.Portrait) {
      val assets = DeterministicFixtureAssets()
      val platform = assets.platform()
      var measurements: List<ShadowLayouts>? = null
      var observedDensity: Density? = null
      mainClock.autoAdvance = false
      setContent {
        CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
          val density = LocalDensity.current
          val stock = rememberTextMeasurer(cacheSize = 16)
          val adapted = rememberIcyTextMeasurer(cacheSize = 16)
          val measured = RADII.flatMap { radius ->
            listOf("base", "span", "base-after-typography").map { placement ->
              val shadow = Shadow(Color.White, Offset(320f, 0f), radius)
              val text = if (placement == "span") buildAnnotatedString {
                withStyle(SpanStyle(shadow = shadow)) { append("H") }
              } else AnnotatedString("H")
              val authored = TextStyle(color = Color.Black, fontSize = 64.sp,
                fontWeight = FontWeight.Bold, shadow = if (placement == "span") null else shadow)
              // Typography may already adapt rasterization; it must not convert
              // authored shadow radius before the one layout boundary.
              val style = if (placement == "base-after-typography") platform.textStyle(authored) else authored
              val stockStyle = platform.textStyle(style.copy(fontFamily = platform.fontFamily))
              ShadowLayouts(radius, placement,
                stock.measure(text, stockStyle, softWrap = false, maxLines = 1,
                  constraints = Constraints(maxWidth = 200)),
                adapted.measure(text, style, softWrap = false, maxLines = 1,
                  constraints = Constraints(maxWidth = 200)))
            }
          }
          SideEffect { measurements = measured; observedDensity = density }
        }
      }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      val density = assertNotNull(observedDensity)
      assertEquals(2.625f, density.density)
      assertEquals(1f, density.fontScale)
      val rendered = assertNotNull(measurements).map { layout ->
        assertEquals(layout.stock.size, layout.adapted.size, "Blur conversion must not change layout")
        val prefix = "radius-${layout.radius.toInt()}-${layout.placement}"
        ShadowPixels(layout,
          render(assets, "$prefix-stock", layout.stock, density),
          render(assets, "$prefix-adapted", layout.adapted, density))
      }
      val report = buildJsonObject {
        put("schemaVersion", 1); put("text", "H")
        put("widthPx", WIDTH); put("heightPx", HEIGHT)
        put("density", density.density); put("fontScale", density.fontScale)
        put("fontSizeSp", 64); put("fontSizePx", 168); put("fontWeight", 700)
        put("textColor", "opaque black"); put("shadowColor", "opaque white")
        put("background", "opaque black"); put("topLeftPx", JsonArray(listOf(64, 64).map(::JsonPrimitive)))
        put("shadowOffsetPx", JsonArray(listOf(320, 0).map(::JsonPrimitive)))
        put("scope", "Actual Compose and production-adapted paragraph shadows; original Android comparison pending")
        put("appearanceParityVerified", false)
        put("samples", JsonArray(rendered.map { sample -> buildJsonObject {
          put("authoredRadiusPx", sample.layout.radius); put("placement", sample.layout.placement)
          put("layoutWidthPx", sample.layout.stock.size.width); put("layoutHeightPx", sample.layout.stock.size.height)
          put("firstBaselinePx", sample.layout.stock.firstBaseline)
          put("stock", pixelReport(sample.stock)); put("adapted", pixelReport(sample.adapted))
        } }))
      }
      assets.write(assets.outputRoot / "native-shadow-radius" / "report.json", report.toString().encodeToByteArray())
      for (radius in RADII) {
        val samples = rendered.filter { it.layout.radius == radius }
        val base = samples.single { it.layout.placement == "base" }
        val span = samples.single { it.layout.placement == "span" }
        val typography = samples.single { it.layout.placement == "base-after-typography" }
        assertContentEquals(base.adapted.pixels, span.adapted.pixels, "Base/span shadow must share the same paint semantics")
        assertContentEquals(base.adapted.pixels, typography.adapted.pixels, "Typography must not cause a second conversion")
        for (sample in samples) {
          assertTrue(sample.adapted.pixels.all { it ushr 24 == 255 })
          assertTrue(sample.adapted.pixels.any { (it and 255) != 0 }, "Explicit shadow must stay visible, including radius zero")
        }
        if (radius >= 4f) {
          // Inspect actual nonzero raster support, not a unit test of the formula.
          // Stock SkParagraph treats radius as sigma and produces a wider halo.
          assertTrue(base.stock.pixels.count { (it and 255) > 0 } > base.adapted.pixels.count { (it and 255) > 0 },
            "The corrected shadow should have narrower raster support at radius $radius")
        }
      }
    }

  @Test fun nestedShadowConversionPreservesAnnotationOrderTagsAndExplicitNone() {
    val outer = SpanStyle(shadow = Shadow(Color.White, Offset(3f, 4f), 15f), fontWeight = FontWeight.Bold)
    val inner = SpanStyle(shadow = Shadow(Color.Red, Offset(5f, 6f), 4f), color = Color.Yellow)
    val text = buildAnnotatedString {
      append("Helloworld")
      addStringAnnotation("provider", "retained", 1, 8)
      addStyle(ParagraphStyle(textAlign = TextAlign.End), 0, 10)
      addStyle(outer, 0, 10)
      addStyle(inner, 2, 7)
      addStyle(SpanStyle(shadow = Shadow.None), 3, 5)
      addStyle(SpanStyle(fontWeight = FontWeight.Light), 1, 9)
    }
    val before = annotations(text)
    val converted = androidSpanShadowsForLayout(text)
    val after = annotations(converted)
    assertEquals(text.text, converted.text)
    assertEquals(before.size, after.size)
    before.zip(after).forEach { (original, actual) ->
      assertEquals(listOf(original.start, original.end, original.tag), listOf(actual.start, actual.end, actual.tag))
      val style = original.item as? SpanStyle
      val originalShadow = style?.shadow
      if (originalShadow != null && originalShadow != Shadow.None) {
        val changed = actual.item as SpanStyle
        assertNotEquals(style.shadow, changed.shadow)
        assertEquals(style, changed.copy(shadow = style.shadow), "Only blur is adapted")
        assertEquals(originalShadow.color, changed.shadow?.color)
        assertEquals(originalShadow.offset, changed.shadow?.offset)
      } else assertEquals(original, actual)
    }
    assertEquals(before, annotations(text), "Authored text must remain unchanged")
    val noShadow = buildAnnotatedString { append("H"); addStringAnnotation("provider", "retained", 0, 1) }
    assertSame(noShadow, androidSpanShadowsForLayout(noShadow))
  }

  private fun annotations(text: AnnotatedString): List<AnnotatedString.Range<out AnnotatedString.Annotation>> {
    val result = mutableListOf<AnnotatedString.Range<out AnnotatedString.Annotation>>()
    text.mapAnnotations { result += it; it }
    return result
  }

  private fun render(assets: DeterministicFixtureAssets, id: String, layout: TextLayoutResult, density: Density): Raster {
    val bitmap = ImageBitmap(WIDTH, HEIGHT)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
      drawRect(Color.Black)
      drawText(layout, topLeft = Offset(64f, 64f))
    }
    val pixels = IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    val png = try {
      val encoded = assertNotNull(image.encodeToData(EncodedImageFormat.PNG))
      try { encoded.bytes } finally { encoded.close() }
    } finally { image.close() }
    assets.write(assets.outputRoot / "native-shadow-radius" / "$id.png", png)
    return Raster(pixels, png.toByteString().sha256().hex())
  }

  private fun pixelReport(raster: Raster) = buildJsonObject {
    put("pngSha256", raster.sha256)
    put("nonzeroPixelCount", raster.pixels.count { (it and 255) != 0 })
    put("maximumGray", raster.pixels.maxOf { it and 255 })
    put("columnBlueSums", JsonArray((0 until WIDTH).map { x ->
      JsonPrimitive((0 until HEIGHT).sumOf { y -> raster.pixels[y * WIDTH + x] and 255 })
    }))
  }

  private data class ShadowLayouts(val radius: Float, val placement: String, val stock: TextLayoutResult, val adapted: TextLayoutResult)
  private data class ShadowPixels(val layout: ShadowLayouts, val stock: Raster, val adapted: Raster)
  private data class Raster(val pixels: IntArray, val sha256: String)
  private companion object {
    const val WIDTH = 768; const val HEIGHT = 384
    val RADII = listOf(0f, 4f, 15f, 30f)
  }
}
