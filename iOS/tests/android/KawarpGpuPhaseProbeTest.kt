package com.icy.lyrics.parity

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
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
import com.icy.lyrics.ui.IcyParityFixtures
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

/** Frozen original shader/preprocessing at selected uniform phases, not its live animation coroutine. */
@RunWith(AndroidJUnit4::class)
class KawarpGpuPhaseProbeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun captureOriginalPreprocessingAndEightHardwareShaderPhases() {
    val args = InstrumentationRegistry.getArguments()
    val runId = args.getString("kawarpProbeRunId")
    assumeTrue("Original Kawarp phases require an explicit owned-emulator run", !runId.isNullOrBlank())
    require(runId!!.matches(Regex("[A-Za-z0-9_-]+")))
    val sourceIdentity = requireNotNull(args.getString("referenceSourceManifestSha256"))
    require(sourceIdentity.matches(Regex("[0-9a-f]{64}")))
    val original = Class.forName("com.icy.lyrics.ui.ArtworkBackgroundKt")
    fun field(name: String): Any = original.getDeclaredField(name).also { it.isAccessible = true }.get(null)!!
    val shaderText = field("KAWARP_SHADER") as String
    val blurSize = field("BLUR_SIZE") as Int
    val intensity = field("WARP_INTENSITY") as Float
    val saturation = field("SATURATION") as Float
    val dithering = field("DITHERING") as Float
    val preprocess = original.getDeclaredMethod("preprocessArtwork", Bitmap::class.java).also { it.isAccessible = true }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "kawarp-gpu-phases/$runId").apply { mkdirs() }
    val input = IcyParityFixtures.artwork().asAndroidBitmap()
    assertEquals(256, input.width); assertEquals(256, input.height); assertEquals(128, blurSize)
    val sourceRecord = saveBitmap(directory, "input-artwork", input)
    // A separate diagnostic of the same Android resize API, not an intercepted/replaced production step.
    val resized = Bitmap.createScaledBitmap(input, blurSize, blurSize, true)
    val resizedRecord = saveBitmap(directory, "resized-intermediate", resized)
    val processed = preprocess.invoke(null, input) as Bitmap
    val processedRecord = saveBitmap(directory, "processed-artwork", processed)
    File(directory, "original-kawarp.agsl").writeText(shaderText)
    val phases = listOf(256 to 512, 512 to 256).flatMap { (width, height) ->
      listOf(0f, 1f, 3f, 12f).map { Phase(width, height, it) }
    }
    val observed = AtomicReference<JSONObject>()
    var phase by mutableStateOf(phases.first())
    compose.setContent {
      val density = LocalDensity.current
      val fromShader = remember { BitmapShader(processed, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
      val toShader = remember { BitmapShader(processed, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
      val runtime = remember { RuntimeShader(shaderText) }
      val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
      Canvas(Modifier.requiredSize(with(density) { phase.width.toFloat().toDp() },
        with(density) { phase.height.toFloat().toDp() }).testTag("kawarp-phase")) {
        runtime.setInputShader("fromImage", fromShader)
        runtime.setInputShader("toImage", toShader)
        runtime.setFloatUniform("resolution", size.width, size.height)
        runtime.setFloatUniform("time", phase.time)
        runtime.setFloatUniform("blend", 1f)
        runtime.setFloatUniform("intensity", intensity)
        runtime.setFloatUniform("saturation", saturation)
        runtime.setFloatUniform("dithering", dithering)
        paint.shader = runtime
        drawIntoCanvas { wrapper ->
          val canvas = wrapper.nativeCanvas
          canvas.drawRect(0f, 0f, size.width, size.height, Paint().apply { color = android.graphics.Color.BLACK })
          canvas.drawRect(0f, 0f, size.width, size.height, paint)
          val uniforms = listOf(size.width, size.height, phase.time, 1f, intensity, saturation, dithering)
          observed.set(JSONObject().put("id", phase.id).put("canvasHardwareAccelerated", canvas.isHardwareAccelerated)
            .put("drawWidthPx", size.width).put("drawHeightPx", size.height)
            .put("paintFlags", paint.flags).put("paintFilterBitmap", paint.isFilterBitmap).put("paintDither", paint.isDither)
            .put("fromShader", shaderRecord(fromShader)).put("toShader", shaderRecord(toShader))
            .put("density", density.density).put("fontScale", density.fontScale)
            .put("uniformFloat32Values", JSONArray(uniforms))
            .put("uniformFloat32BitsHex", JSONArray(uniforms.map { it.toRawBits().toUInt().toString(16).padStart(8, '0') }))
            .put("uniformLittleEndianBytesHex", littleEndian(uniforms.map(Float::toRawBits).toIntArray()).joinToString("") { "%02x".format(it) }))
        }
      }
    }
    val frames = JSONArray()
    for (selected in phases) {
      compose.runOnUiThread { phase = selected }
      compose.waitForIdle()
      val node = compose.onNodeWithTag("kawarp-phase")
      val bitmap = node.captureToImage().asAndroidBitmap()
      val record = requireNotNull(observed.get())
      assertEquals(selected.id, record.getString("id"))
      check(record.getBoolean("canvasHardwareAccelerated")) { "Kawarp phase probe requires actual hardware Canvas" }
      assertEquals(selected.width, bitmap.width); assertEquals(selected.height, bitmap.height)
      assertEquals(selected.width.toDouble(), record.getDouble("drawWidthPx"), 0.0)
      assertEquals(selected.height.toDouble(), record.getDouble("drawHeightPx"), 0.0)
      val bounds = node.fetchSemanticsNode().boundsInRoot
      record.put("nodeBoundsInRootPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        .put("captureSurface", "Exact tagged Compose node via hardware-window PixelCopy; no image resize or manual crop")
        .put("shaderPhaseSeconds", selected.time)
        .put("processedPixelSha256", processedRecord.getString("argbLittleEndianSha256"))
        .put("pixels", saveBitmap(directory, selected.id, bitmap))
      frames.put(record)
    }
    val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
      .put("sdk", Build.VERSION.SDK_INT).put("buildFingerprint", Build.FINGERPRINT)
      .put("referenceSourceManifestSha256", sourceIdentity).put("originalFacadeClass", original.name)
      .put("preprocessMethod", preprocess.toString()).put("shaderSha256", sha256(shaderText.toByteArray()))
      .put("shaderFile", "original-kawarp.agsl").put("sourceArtwork", sourceRecord)
      .put("resizedIntermediate", resizedRecord).put("resizedIntermediateScope", "Separate diagnostic call to Bitmap.createScaledBitmap(input,128,128,true)")
      .put("processedArtwork", processedRecord).put("fromAndToTextureIdentity", "Both are the same original processed artwork")
      .put("uniformOrder", JSONArray(listOf("width", "height", "time", "blend", "intensity", "saturation", "dithering")))
      .put("backend", "Android hardware Canvas / original RuntimeShader / Compose PixelCopy")
      .put("clockPolicy", "Selected uniform phases0,1,3,12seconds; no elapsed playback clock or live coroutine assertion")
      .put("scope", "Original preprocessing plus GPU uniform-phase diagnostic only; no crossfade, interruption, pause/resume, foreground, or full-app animation proof")
      .put("appearanceParityVerified", false).put("frames", frames)
    File(directory, "report.json").writeText(report.toString(2))
  }

  private fun saveBitmap(directory: File, id: String, bitmap: Bitmap): JSONObject {
    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    check(pixels.all { it ushr 24 == 255 }) { "This first artwork/phase probe requires actual opaque pixels" }
    val png = File(directory, "$id.png")
    png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    val argb = littleEndian(pixels)
    val rgba = ByteArray(pixels.size * 4).also { bytes -> pixels.forEachIndexed { index, pixel ->
      bytes[index * 4] = (pixel ushr 16).toByte(); bytes[index * 4 + 1] = (pixel ushr 8).toByte()
      bytes[index * 4 + 2] = pixel.toByte(); bytes[index * 4 + 3] = (pixel ushr 24).toByte()
    } }
    File(directory, "$id.argb-le").writeBytes(argb)
    File(directory, "$id.rgba").writeBytes(rgba)
    return JSONObject().put("png", png.name).put("pngSha256", sha256(png.readBytes()))
      .put("widthPx", bitmap.width).put("heightPx", bitmap.height).put("config", bitmap.config?.name)
      .put("colorSpace", bitmap.colorSpace?.name).put("hasAlphaFlag", bitmap.hasAlpha())
      .put("premultipliedFlag", bitmap.isPremultiplied).put("allDecodedPixelsOpaque", true)
      .put("argbLittleEndianFile", "$id.argb-le").put("argbLittleEndianSha256", sha256(argb))
      .put("rgbaFile", "$id.rgba").put("rgbaSha256", sha256(rgba))
  }

  private fun shaderRecord(shader: BitmapShader): JSONObject {
    val matrix = Matrix()
    val hasLocalMatrix = shader.getLocalMatrix(matrix)
    val values = FloatArray(9).also(matrix::getValues)
    return JSONObject().put("tileModeX", "CLAMP").put("tileModeY", "CLAMP").put("filterMode", shader.filterMode)
      .put("localMatrixWasSet", hasLocalMatrix).put("localMatrix", JSONArray(values.toList()))
  }
  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun littleEndian(pixels: IntArray) = ByteArray(pixels.size * 4).also { bytes ->
    pixels.forEachIndexed { index, pixel -> repeat(4) { shift -> bytes[index * 4 + shift] = (pixel ushr (shift * 8)).toByte() } }
  }
  private data class Phase(val width: Int, val height: Int, val time: Float) { val id get() = "${width}x${height}-phase-${time.toInt()}" }
}
