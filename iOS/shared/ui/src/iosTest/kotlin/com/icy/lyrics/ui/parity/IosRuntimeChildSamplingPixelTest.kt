package com.icy.lyrics.ui.parity

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.skiaCanvas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual RuntimeEffect pixels; this raster contract probe does not claim Metal/Kawarp parity. */
class IosRuntimeChildSamplingPixelTest {
  @Test fun runtimeChildrenKeepNearestTexelsWhereLinearWouldBlendTheirColors() {
    val assets = DeterministicFixtureAssets()
    val sourcePixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xff000000.toInt())
    val sourceBytes = argbBytes(sourcePixels)
    val image = Image.makeRaster(ImageInfo(2, 2, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
      sourceBytes, 8)
    val effect = RuntimeEffect.makeForShader(SHADER)
    val modes = listOf("default-nearest" to SamplingMode.DEFAULT, "linear-before" to SamplingMode.LINEAR)
    val samples = try { modes.map { (id, sampling) ->
      val bitmap = ImageBitmap(128, 32)
      val child = image.makeShader(sampling = sampling)
      val shader = effect.makeShader(null, arrayOf(child), null)
      val paint = Paint().apply { isAntiAlias = true; this.shader = shader }
      try {
        Canvas(bitmap).skiaCanvas.drawRect(Rect.makeWH(128f, 32f), paint)
        val pixels = IntArray(128 * 32).also { bitmap.readPixels(it) }
        val outputImage = Image.makeFromBitmap(bitmap.asSkiaBitmap())
        val png = try {
          val encoded = assertNotNull(outputImage.encodeToData(EncodedImageFormat.PNG))
          try { encoded.bytes } finally { encoded.close() }
        } finally { outputImage.close() }
        assets.write(assets.outputRoot / "native-runtime-child-sampling" / "$id.png", png)
        Sample(id, pixels, png.toByteString().sha256().hex())
      } finally {
        paint.shader = null; paint.close(); shader.close(); child.close(); bitmap.asSkiaBitmap().close()
      }
    } } finally { effect.close(); image.close() }
    val report = buildJsonObject {
      put("schemaVersion", 1); put("widthPx", 128); put("heightPx", 32)
      put("backend", "Skia raster Canvas on native iOS test runtime")
      put("scope", "RuntimeEffect child sampling contract only; no Metal, full Kawarp, or lifecycle acceptance")
      put("appearanceParityVerified", false)
      put("shader", SHADER); put("shaderSha256", SHADER.encodeToByteArray().toByteString().sha256().hex())
      put("sourceArgb", JsonArray(sourcePixels.map { JsonPrimitive(it.toUInt().toString(16)) }))
      put("sourceBgraSha256", sourceBytes.toByteString().sha256().hex())
      put("sourceColorSpace", "sRGB"); put("sourceAlphaType", "UNPREMUL (all pixels opaque)")
      put("samplePoints", JsonArray(listOf(listOf(0.5, 0.5), listOf(0.75, 0.5), listOf(1.25, 0.5), listOf(1.0, 1.0))
        .map { JsonArray(it.map(::JsonPrimitive)) }))
      put("samples", JsonArray(samples.map { sample -> buildJsonObject {
        put("id", sample.id); put("pngSha256", sample.pngSha256)
        put("argbLittleEndianSha256", argbBytes(sample.pixels).toByteString().sha256().hex())
        put("centersRgba", JsonArray(CENTERS.map { x -> JsonArray(rgba(sample.pixels[16 * 128 + x]).map(::JsonPrimitive)) }))
      } }))
    }
    assets.write(assets.outputRoot / "native-runtime-child-sampling" / "report.json", report.toString().encodeToByteArray())
    val nearest = samples.single { it.id == "default-nearest" }.pixels
    val linear = samples.single { it.id == "linear-before" }.pixels
    val expected = intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt())
    assertContentEquals(expected, CENTERS.map { nearest[16 * 128 + it] }.toIntArray())
    for (y in 0 until 32) for (x in 0 until 128) assertEquals(expected[x / 32], nearest[y * 128 + x])
    assertEquals(nearest[16 * 128 + 16], linear[16 * 128 + 16], "Exact texel centers agree")
    for (x in CENTERS.drop(1)) {
      val pixel = linear[16 * 128 + x]
      val channels = rgba(pixel)
      assertTrue(channels[0] in 1..254, "Fractional linear samples must mix black and white")
      assertEquals(channels[0], channels[1]); assertEquals(channels[1], channels[2]); assertEquals(255, channels[3])
    }
  }

  private fun argbBytes(pixels: IntArray) = ByteArray(pixels.size * 4).also { bytes ->
    pixels.forEachIndexed { index, pixel -> repeat(4) { shift -> bytes[index * 4 + shift] = (pixel ushr (shift * 8)).toByte() } }
  }
  private fun rgba(pixel: Int) = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255, pixel ushr 24)
  private data class Sample(val id: String, val pixels: IntArray, val pngSha256: String)
  private companion object {
    val CENTERS = listOf(16, 48, 80, 112)
    const val SHADER = "uniform shader image; half4 main(float2 p) { float2 q = p.x < 32.0 ? float2(0.5,0.5) : p.x < 64.0 ? float2(0.75,0.5) : p.x < 96.0 ? float2(1.25,0.5) : float2(1.0,1.0); return image.eval(q); }"
  }
}
