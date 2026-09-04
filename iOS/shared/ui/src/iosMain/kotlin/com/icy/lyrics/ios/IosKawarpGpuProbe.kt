@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.ComposeUIViewController
import com.icy.lyrics.ui.ArtworkTexture
import com.icy.lyrics.ui.DITHERING
import com.icy.lyrics.ui.KAWARP_SHADER
import com.icy.lyrics.ui.SATURATION
import com.icy.lyrics.ui.WARP_INTENSITY
import com.icy.lyrics.ui.drawKawarpFrame
import com.icy.lyrics.ui.preprocessArtwork
import com.icy.lyrics.ui.shaderUniformBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import platform.UIKit.UIViewController

/** Only the Swift DEBUG route calls this. Rendering uses Compose's actual UIKit canvas. */
fun createIcyKawarpGpuProbeViewController(
  inputPath: String, outputDirectory: String, caseId: String, onFrameDrawn: (String) -> Unit,
): UIViewController {
  val match = requireNotNull(Regex("(256x512|512x256)-phase-(0|1|3|12)").matchEntire(caseId))
  val (width, height) = match.groupValues[1].split('x').map(String::toInt)
  val time = match.groupValues[2].toFloat()
  return ComposeUIViewController {
    val prepared = remember(inputPath, outputDirectory) { prepareKawarpProbe(inputPath.toPath(), outputDirectory.toPath()) }
    val density = LocalDensity.current
    val effect = remember { RuntimeEffect.makeForShader(KAWARP_SHADER) }
    val paint = remember { Paint().apply { isAntiAlias = true } }
    DisposableEffect(prepared, effect, paint) {
      onDispose { paint.close(); effect.close(); prepared.texture.shader.close(); prepared.texture.image.close() }
    }
    Canvas(Modifier.fillMaxSize()) {
      drawKawarpFrame(effect, paint, prepared.texture, prepared.texture, time, 1f)
      // Observing a DrawScope alone does not prove Metal. Swift separately requires
      // a visible CAMetalLayer/device and XCTest captures that real window surface.
      val uniforms = listOf(size.width, size.height, time, 1f, WARP_INTENSITY, SATURATION, DITHERING)
      val config = buildJsonObject {
        put("id", caseId); put("widthPx", size.width); put("heightPx", size.height)
        put("shaderSha256", hash(KAWARP_SHADER.encodeToByteArray()))
        put("processedRgbaSha256", prepared.record.getValue("processedRgbaSha256"))
        put("uniformFloat32Values", JsonArray(uniforms.map(::JsonPrimitive)))
        put("uniformFloat32BitsHex", JsonArray(uniforms.map { JsonPrimitive(it.toRawBits().toUInt().toString(16).padStart(8, '0')) }))
        put("uniformLittleEndianBytesHex", shaderUniformBytes(size.width, size.height, time, 1f, WARP_INTENSITY, SATURATION, DITHERING).toByteString().hex())
        put("childSampling", "SamplingMode.DEFAULT = NEAREST/NONE")
        put("childTileModes", "CLAMP/CLAMP"); put("childLocalMatrix", "identity")
        put("fromAndToTextureIdentity", "same processed artwork")
        put("paintAntiAlias", paint.isAntiAlias); put("paintDither", paint.isDither)
      }
      onFrameDrawn(buildJsonObject {
        put("schemaVersion", 1); put("catalog", "kawarp-gpu-uniform-phases-v1")
        put("id", caseId); put("drawWidthPx", size.width); put("drawHeightPx", size.height)
        put("matchesRequestedSize", size.width == width.toFloat() && size.height == height.toFloat())
        put("density", density.density); put("fontScale", density.fontScale)
        put("configuration", config); put("configurationCanonicalJson", config.toString())
        put("configurationSha256", hash(config.toString().encodeToByteArray()))
        put("preparation", prepared.record)
        put("appearanceParityVerified", false)
        put("scope", "Eight selected uniform phases only; no elapsed clock, crossfade, interruption, lifecycle or full animation proof")
      }.toString())
    }
  }
}

private data class PreparedKawarpProbe(val texture: ArtworkTexture, val record: JsonObject)

private fun prepareKawarpProbe(input: Path, output: Path): PreparedKawarpProbe {
  val bytes = FileSystem.SYSTEM.read(input) { readByteArray() }
  require(hash(bytes) == "637ea5fc5b72e14361d2e801b64451bd333df8a52ec676c8872463f2b0ed4c18") { "Kawarp probe input PNG differs from original Android" }
  require(hash(KAWARP_SHADER.encodeToByteArray()) == "755f24a2ef9d9f873877f44f9a7cda0f5945a7be5ffe6351cc79dcca7b382d3b") { "Kawarp shader differs from original Android" }
  FileSystem.SYSTEM.createDirectories(output)
  val decoded = Image.makeFromEncoded(bytes)
  val bitmap = decoded.toComposeImageBitmap()
  require(bitmap.width == 256 && bitmap.height == 256)
  val inputRgba = rgba(bitmap)
  require(hash(inputRgba) == "9afe42cd3ba4e0e1e7cd3b7c576794a5a924fa8bcaa53ffb377cfbf682f209d2") { "Native decoded input pixels differ from original Android" }
  val processed = try { preprocessArtwork(bitmap) } finally { decoded.close() }
  val processedRgba = rgba(processed.toComposeImageBitmap())
  val encoded = requireNotNull(processed.encodeToData(EncodedImageFormat.PNG))
  val processedPng = try { encoded.bytes } finally { encoded.close() }
  FileSystem.SYSTEM.write(output / "input-artwork.png") { write(bytes) }
  FileSystem.SYSTEM.write(output / "input-artwork.rgba") { write(inputRgba) }
  FileSystem.SYSTEM.write(output / "processed-artwork.png") { write(processedPng) }
  FileSystem.SYSTEM.write(output / "processed-artwork.rgba") { write(processedRgba) }
  FileSystem.SYSTEM.write(output / "native-kawarp.sksl") { writeUtf8(KAWARP_SHADER) }
  val record = buildJsonObject {
    put("inputPngSha256", hash(bytes)); put("inputRgbaSha256", hash(inputRgba))
    put("inputWidthPx", 256); put("inputHeightPx", 256); put("allDecodedPixelsOpaque", true)
    put("processedWidthPx", processed.width); put("processedHeightPx", processed.height)
    put("processedPngSha256", hash(processedPng)); put("processedRgbaSha256", hash(processedRgba))
    put("androidProcessedRgbaSha256", "72c5d9307a973d0d26c891f07f899705a0d26f493753863d5cc5257c6433eef1")
    put("processedPixelsMatchAndroid", hash(processedRgba) == "72c5d9307a973d0d26c891f07f899705a0d26f493753863d5cc5257c6433eef1")
    put("processedColorRepresentation", "BGRA_8888/sRGB/UNPREMUL, all decoded pixels opaque")
    put("preprocessing", "Production preprocessArtwork and ArtworkTexture; platform linear128 resize then shared eight-pass blur")
  }
  FileSystem.SYSTEM.write(output / "preparation.json") { writeUtf8(record.toString()) }
  return PreparedKawarpProbe(ArtworkTexture(processed), record)
}

private fun rgba(bitmap: ImageBitmap): ByteArray {
  val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
  require(pixels.all { it ushr 24 == 255 }) { "This probe requires opaque artwork pixels" }
  return ByteArray(pixels.size * 4).also { out -> pixels.forEachIndexed { i, pixel ->
    out[i * 4] = (pixel ushr 16).toByte(); out[i * 4 + 1] = (pixel ushr 8).toByte()
    out[i * 4 + 2] = pixel.toByte(); out[i * 4 + 3] = (pixel ushr 24).toByte()
  } }
}

private fun hash(bytes: ByteArray) = bytes.toByteString().sha256().hex()
