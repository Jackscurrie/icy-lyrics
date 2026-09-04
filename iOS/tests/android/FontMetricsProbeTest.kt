package com.icy.lyrics.parity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.text.TextPaint
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Instrumentation initializes the real system font stack through the app zygote. */
@RunWith(AndroidJUnit4::class)
class FontMetricsProbeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun measureDefaultHeaderAndBody() {
    val measured = AtomicReference<JSONObject>()
    compose.setContent {
      MaterialTheme {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val rows = JSONArray()
        for (weight in listOf(FontWeight.Normal, FontWeight.Bold)) {
          val style = MaterialTheme.typography.headlineMedium.copy(fontWeight = weight)
          val result = measurer.measure(AnnotatedString(HEADER), style,
            softWrap = false, constraints = Constraints(maxWidth = 20_000))
          rows.put(layoutJson("headlineMedium-${weight.weight}", HEADER, result))
        }
        val bodyStyle = MaterialTheme.typography.bodyLarge
        rows.put(layoutJson("bodyLarge-400", BODY, measurer.measure(AnnotatedString(BODY), bodyStyle,
          softWrap = false, constraints = Constraints(maxWidth = 20_000))))
        val record = JSONObject().put("density", density.density).put("fontScale", density.fontScale)
          .put("compose", rows)
        SideEffect { measured.set(record) }
      }
    }
    compose.waitForIdle()
    val record = requireNotNull(measured.get())
    assertEquals(2.625, record.getDouble("density"), 0.00001)
    assertEquals(1.0, record.getDouble("fontScale"), 0.00001)
    val paints = JSONArray()
    for (family in listOf("default", "sans-serif")) for (weight in listOf(400, 700)) {
      for (flags in listOf(Paint.ANTI_ALIAS_FLAG, Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)) {
        val paint = TextPaint(flags).apply {
          textSize = 73.5f
          typeface = Typeface.create(if (family == "default") Typeface.DEFAULT else Typeface.create(family, Typeface.NORMAL), weight, false)
        }
        paints.put(paintJson(HEADER, paint).put("familyRequest", family).put("weightRequest", weight))
      }
    }
    record.put("nativePaint", paints).put("header", HEADER).put("body", BODY)
      .put("sdk", android.os.Build.VERSION.SDK_INT).put("buildFingerprint", android.os.Build.FINGERPRINT)
      .put("note", "Compose character boxes are layout cells; shaped glyph bounds and raster ink are separate measurements. No renderer style changes.")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.getExternalFilesDir(null), "font-metrics").apply { mkdirs() }
    File(folder, "android-font-metrics.json").writeText(record.toString(2))
  }

  private fun layoutJson(name: String, text: String, result: TextLayoutResult): JSONObject {
    val lines = JSONArray()
    for (line in 0 until result.lineCount) lines.put(JSONObject()
      .put("left", result.getLineLeft(line)).put("right", result.getLineRight(line))
      .put("width", result.getLineRight(line) - result.getLineLeft(line))
      .put("baseline", result.multiParagraph.getLineBaseline(line))
      .put("top", result.getLineTop(line)).put("bottom", result.getLineBottom(line)))
    val boxes = JSONArray()
    for (offset in text.indices) {
      val box = result.getBoundingBox(offset)
      boxes.put(JSONObject().put("utf16Offset", offset).put("character", text[offset].toString())
        .put("layoutBox", JSONArray(listOf(box.left, box.top, box.right, box.bottom))))
    }
    // Read the paint actually owned by Compose, rather than reconstructing its flags.
    val info = (field(result.multiParagraph, "paragraphInfoList") as List<*>).first()!!
    val paragraph = field(info, "paragraph")
    val intrinsics = field(paragraph, "paragraphIntrinsics")
    val paint = field(intrinsics, "textPaint") as Paint
    val style = result.layoutInput.style
    return JSONObject().put("name", name).put("text", text).put("widthPx", result.size.width)
      .put("heightPx", result.size.height).put("firstBaseline", result.firstBaseline)
      .put("lastBaseline", result.lastBaseline).put("lines", lines).put("characterLayoutBoxes", boxes)
      .put("fontSizeSp", style.fontSize.value).put("lineHeightSp", style.lineHeight.value)
      .put("fontWeight", style.fontWeight?.weight).put("fontFamily", style.fontFamily.toString())
      .put("letterSpacing", style.letterSpacing.toString()).put("actualComposePaint", paintJson(text, paint))
  }

  private fun field(instance: Any, name: String): Any =
    instance.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(instance)!!

  private fun paintJson(text: String, paint: Paint): JSONObject {
    val textBounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
    val widths = FloatArray(text.length).also { paint.getTextWidths(text, it) }
    val metrics = paint.fontMetrics
    val shaped = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
    val glyphs = JSONArray()
    val fonts = linkedMapOf<String, JSONObject>()
    for (index in 0 until shaped.glyphCount()) {
      val font = shaped.getFont(index)
      val fontKey = "${font.file?.path}/${font.ttcIndex}/${font.style.weight}"
      if (fontKey !in fonts) fonts[fontKey] = JSONObject().put("file", font.file?.path)
        .put("ttcIndex", font.ttcIndex).put("weight", font.style.weight).put("slant", font.style.slant)
        .put("axes", JSONArray(font.axes.orEmpty().map { JSONObject().put("tag", it.tag).put("value", it.styleValue) }))
        .put("sha256", font.file?.let { file -> MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } })
      val bounds = RectF()
      val advance = font.getGlyphBounds(shaped.getGlyphId(index), paint, bounds)
      glyphs.put(JSONObject().put("id", shaped.getGlyphId(index)).put("x", shaped.getGlyphX(index))
        .put("y", shaped.getGlyphY(index)).put("advance", advance).put("font", fontKey)
        .put("inkBoundsRelativeToGlyphOrigin", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
    }
    return JSONObject().put("flags", paint.flags).put("antiAlias", paint.isAntiAlias)
      .put("subpixelText", paint.isSubpixelText).put("linearText", paint.isLinearText).put("hinting", paint.hinting)
      .put("textSizePx", paint.textSize).put("textScaleX", paint.textScaleX).put("textSkewX", paint.textSkewX)
      .put("letterSpacingEm", paint.letterSpacing).put("fontFeatures", paint.fontFeatureSettings)
      .put("fontVariationSettings", paint.fontVariationSettings).put("typefaceWeight", paint.typeface.weight)
      .put("textLocale", paint.textLocale.toLanguageTag()).put("measureText", paint.measureText(text))
      .put("textBounds", JSONArray(listOf(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom)))
      .put("utf16Advances", JSONArray(widths.toList())).put("sumUtf16Advances", widths.sum())
      .put("fontMetrics", JSONObject().put("top", metrics.top).put("ascent", metrics.ascent)
        .put("descent", metrics.descent).put("bottom", metrics.bottom).put("leading", metrics.leading))
      .put("shapedAdvance", shaped.advance).put("shapedAscent", shaped.ascent).put("shapedDescent", shaped.descent)
      .put("fonts", JSONArray(fonts.values.toList())).put("glyphs", glyphs).put("rasterInkRgbAbove150", rasterBounds(text, paint))
  }

  private fun rasterBounds(text: String, source: Paint): JSONArray {
    val paint = Paint(source).apply { color = android.graphics.Color.WHITE }
    val bitmap = Bitmap.createBitmap(ceil(source.measureText(text)).toInt() + 80, 240, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply { drawColor(android.graphics.Color.BLACK); drawText(text, 20f, 120f, paint) }
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      if (android.graphics.Color.red(bitmap.getPixel(x, y)) > 150) {
        left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
      }
    }
    bitmap.recycle()
    // Origin is the drawText baseline; right/bottom are inclusive thresholded pixels.
    return JSONArray(listOf(left - 20, top - 120, right - 20, bottom - 120))
  }

  companion object {
    const val HEADER = "Play something in Spotify"
    const val BODY = "The player appears as soon as Spotify publishes its media session."
  }
}
