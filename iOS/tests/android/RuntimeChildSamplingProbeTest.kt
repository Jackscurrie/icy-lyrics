package com.icy.lyrics.parity

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** Actual hardware Android RuntimeShader child sampling; no production Kawarp or fixture changes. */
@RunWith(AndroidJUnit4::class)
class RuntimeChildSamplingProbeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun captureDefaultAndExplicitLinearRuntimeChildren() {
    val runId = InstrumentationRegistry.getArguments().getString("samplingProbeRunId")
    assumeTrue("Runtime sampling is an explicit hardware emulator probe", !runId.isNullOrBlank())
    require(runId!!.matches(Regex("[A-Za-z0-9_-]+")))
    val sourcePixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xff000000.toInt())
    val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    source.setPixels(sourcePixels, 0, 2, 0, 0, 2, 2)
    val observed = AtomicReference<JSONObject>()
    var mode by mutableStateOf("default-nearest")
    compose.setContent {
      val density = LocalDensity.current
      val child = remember(mode) {
        BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
          // The reference branch deliberately leaves FILTER_MODE_DEFAULT untouched.
          if (mode == "linear-control") it.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
      }
      val runtime = remember(child) { RuntimeShader(SHADER).also { it.setInputShader("image", child) } }
      val paint = remember(runtime) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = runtime }
      }
      Canvas(Modifier.requiredSize(with(density) { 128f.toDp() }, with(density) { 32f.toDp() }).testTag("sampling")) {
        drawIntoCanvas { wrapper ->
          val canvas = wrapper.nativeCanvas
          canvas.drawRect(0f, 0f, 128f, 32f, Paint().apply { color = android.graphics.Color.BLACK })
          canvas.drawRect(0f, 0f, 128f, 32f, paint)
          observed.set(JSONObject().put("id", mode).put("canvasHardwareAccelerated", canvas.isHardwareAccelerated)
            .put("drawWidthPx", size.width).put("drawHeightPx", size.height)
            .put("paintFlags", paint.flags).put("paintFilterBitmap", paint.isFilterBitmap)
            .put("childFilterMode", child.filterMode).put("density", density.density).put("fontScale", density.fontScale))
        }
      }
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "runtime-child-sampling/$runId").apply { mkdirs() }
    val samples = JSONArray()
    for (id in listOf("default-nearest", "linear-control")) {
      compose.runOnUiThread { mode = id }
      compose.waitForIdle()
      val bitmap = compose.onNodeWithTag("sampling").captureToImage().asAndroidBitmap()
      val record = requireNotNull(observed.get())
      assertEquals(id, record.getString("id"))
      check(record.getBoolean("canvasHardwareAccelerated")) { "RuntimeShader probe requires real hardware Canvas" }
      assertEquals(128, bitmap.width); assertEquals(32, bitmap.height)
      assertEquals(128.0, record.getDouble("drawWidthPx"), 0.0)
      assertEquals(32.0, record.getDouble("drawHeightPx"), 0.0)
      val pixels = IntArray(128 * 32).also { bitmap.getPixels(it, 0, 128, 0, 0, 128, 32) }
      check(pixels.all { it ushr 24 == 255 }) { "Sampling output must remain opaque" }
      val file = File(directory, "$id.png")
      file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      record.put("png", file.name).put("pngSha256", sha256(file.readBytes()))
        .put("argbLittleEndianSha256", sha256(argbBytes(pixels)))
        .put("centersRgba", JSONArray(listOf(16, 48, 80, 112).map { x -> JSONArray(rgba(pixels[16 * 128 + x])) }))
        .put("captureColorSpace", bitmap.colorSpace?.name)
      samples.put(record)
    }
    val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
      .put("widthPx", 128).put("heightPx", 32).put("sdk", Build.VERSION.SDK_INT)
      .put("buildFingerprint", Build.FINGERPRINT)
      .put("backend", "Android hardware Canvas / RuntimeShader, captured by Compose PixelCopy")
      .put("scope", "RuntimeShader child sampling contract only; no full Kawarp or lifecycle acceptance")
      .put("appearanceParityVerified", false).put("shader", SHADER).put("shaderSha256", sha256(SHADER.toByteArray()))
      .put("sourceArgb", JSONArray(sourcePixels.map { it.toUInt().toString(16) }))
      .put("sourceBgraSha256", sha256(argbBytes(sourcePixels))).put("sourceColorSpace", source.colorSpace?.name)
      .put("sourceAlphaType", "all pixels opaque")
      .put("samplePoints", JSONArray(listOf(listOf(0.5, 0.5), listOf(0.75, 0.5), listOf(1.25, 0.5), listOf(1.0, 1.0))
        .map { JSONArray(it) })).put("samples", samples)
    File(directory, "report.json").writeText(report.toString(2))
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun argbBytes(pixels: IntArray) = ByteArray(pixels.size * 4).also { bytes ->
    pixels.forEachIndexed { index, pixel -> repeat(4) { shift -> bytes[index * 4 + shift] = (pixel ushr (shift * 8)).toByte() } }
  }
  private fun rgba(pixel: Int) = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255, pixel ushr 24)
  private companion object {
    const val SHADER = "uniform shader image; half4 main(float2 p) { float2 q = p.x < 32.0 ? float2(0.5,0.5) : p.x < 64.0 ? float2(0.75,0.5) : p.x < 96.0 ? float2(1.25,0.5) : float2(1.0,1.0); return image.eval(q); }"
  }
}
