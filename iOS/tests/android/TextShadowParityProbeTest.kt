package com.icy.lyrics.parity

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Standalone stock Android paragraph diagnostic; no app fixture, layout, or baseline changes. */
@RunWith(AndroidJUnit4::class)
class TextShadowParityProbeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun captureBaseAndSpanShadowRadii() {
    val runId = InstrumentationRegistry.getArguments().getString("shadowProbeRunId")
    assumeTrue("Shadow rasterization is an explicit emulator probe", !runId.isNullOrBlank())
    require(runId!!.matches(Regex("[A-Za-z0-9_-]+")))
    val measured = AtomicReference<List<Measured>>()
    val actualDensity = AtomicReference<Density>()
    compose.setContent {
      val density = LocalDensity.current
      val measurer = rememberTextMeasurer(cacheSize = 16)
      val layouts = listOf(0f, 4f, 15f, 30f).flatMap { radius ->
        listOf("base", "span").map { placement ->
          val shadow = Shadow(Color.White, Offset(320f, 0f), radius)
          val text = if (placement == "span") buildAnnotatedString {
            withStyle(SpanStyle(shadow = shadow)) { append("H") }
          } else AnnotatedString("H")
          val style = TextStyle(color = Color.Black, fontSize = 64.sp,
            fontWeight = FontWeight.Bold, shadow = if (placement == "span") null else shadow)
          Measured(radius, placement, measurer.measure(text, style, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = 200)))
        }
      }
      SideEffect { measured.set(layouts); actualDensity.set(density) }
    }
    compose.waitForIdle()
    val density = requireNotNull(actualDensity.get())
    assertEquals(2.625f, density.density, 0f)
    assertEquals(1f, density.fontScale, 0f)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "shadow-radius/$runId").apply { mkdirs() }
    val samples = JSONArray()
    requireNotNull(measured.get()).forEach { row ->
      val bitmap = ImageBitmap(WIDTH, HEIGHT)
      CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
        drawRect(Color.Black)
        drawText(row.layout, topLeft = Offset(64f, 64f))
      }
      val pixels = IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
      check(pixels.all { it ushr 24 == 255 }) { "Probe must stay opaque" }
      val name = "radius-${row.radius.toInt()}-${row.placement}.png"
      val output = File(directory, name)
      output.outputStream().use { stream ->
        check(bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
      }
      samples.put(JSONObject().put("authoredRadiusPx", row.radius).put("placement", row.placement)
        .put("layoutWidthPx", row.layout.size.width).put("layoutHeightPx", row.layout.size.height)
        .put("firstBaselinePx", row.layout.firstBaseline).put("lastBaselinePx", row.layout.lastBaseline)
        .put("png", name).put("pngSha256", sha256(output))
        .put("nonzeroPixelCount", pixels.count { (it and 255) != 0 })
        .put("maximumGray", pixels.maxOf { it and 255 })
        .put("columnBlueSums", JSONArray((0 until WIDTH).map { x ->
          (0 until HEIGHT).sumOf { y -> pixels[y * WIDTH + x] and 255 }
        })))
      bitmap.asAndroidBitmap().recycle()
    }
    val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
      .put("sdk", Build.VERSION.SDK_INT).put("buildFingerprint", Build.FINGERPRINT)
      .put("captureBackend", "android-compose-software-bitmap")
      .put("text", "H").put("widthPx", WIDTH).put("heightPx", HEIGHT)
      .put("density", density.density).put("fontScale", density.fontScale)
      .put("fontSizeSp", 64).put("fontSizePx", with(density) { 64.sp.toPx() }).put("fontWeight", 700)
      .put("fontFamily", "original Android default sans-serif")
      .put("textColor", "opaque black").put("shadowColor", "opaque white").put("background", "opaque black")
      .put("topLeftPx", JSONArray(listOf(64, 64))).put("shadowOffsetPx", JSONArray(listOf(320, 0)))
      .put("scope", "Stock original Android Compose TextMeasurer and Canvas shadow semantics; no app fixture or production adapter")
      .put("appearanceParityVerified", false).put("samples", samples)
    File(directory, "report.json").writeText(report.toString(2))
  }

  private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
  private data class Measured(val radius: Float, val placement: String, val layout: TextLayoutResult)
  private companion object { const val WIDTH = 768; const val HEIGHT = 384 }
}
