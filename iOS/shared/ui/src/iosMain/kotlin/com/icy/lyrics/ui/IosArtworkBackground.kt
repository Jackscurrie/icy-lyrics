package com.icy.lyrics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import kotlin.math.max

@Composable internal fun IosArtworkBackground(
  artwork: ImageBitmap?, enabled: Boolean, style: BackgroundStyle, isPlaying: Boolean,
  modifier: Modifier, content: @Composable () -> Unit,
) {
  val reducedMotion = rememberReducedMotionEnabled()
  val animated = enabled && style == BackgroundStyle.ANIMATED && !reducedMotion
  Box(modifier.fillMaxSize().background(Color.Black)) {
    if (animated && artwork != null) IosKawarpBackground(artwork, isPlaying)
    else if (enabled) IosStaticArtworkBackground(artwork)
    AndroidDitheredGradient(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.62f))))
    content()
  }
}

@Composable private fun IosStaticArtworkBackground(artwork: ImageBitmap?) {
  val colors = remember(artwork) { artworkPalette(artwork) }
  if (artwork != null) Image(
    bitmap = artwork, contentDescription = null, contentScale = ContentScale.Crop,
    modifier = Modifier.fillMaxSize().blur(54.dp), alpha = 0.52f,
  )
  AndroidDitheredGradient(Brush.verticalGradient(listOf(colors.first.copy(alpha = 0.34f), colors.second.copy(alpha = 0.18f), Color.Black)))
}

/** Android Compose enables dithering on its Canvas paint; Skia-backed Compose does not. */
internal fun androidBackgroundGradientPaint(): ComposePaint = ComposePaint().apply {
  isAntiAlias = true
  skiaPaint.isDither = true
}

@Composable private fun AndroidDitheredGradient(brush: Brush) {
  val paint = remember { androidBackgroundGradientPaint() }
  DisposableEffect(paint) { onDispose { paint.skiaPaint.close() } }
  Canvas(Modifier.fillMaxSize()) {
    brush.applyTo(size, paint, 1f)
    drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, paint) }
  }
}

private class ArtworkTexture(val image: SkImage) {
  val shader: Shader = image.makeShader(sampling = SamplingMode.LINEAR)
}

@Composable private fun IosKawarpBackground(artwork: ImageBitmap, isPlaying: Boolean) {
  val platform = LocalIcyUiPlatform.current
  val fixedFrame = platform.fixedFrameTimeNanos
  val processed by produceState<ArtworkTexture?>(null, artwork) {
    value = withContext(Dispatchers.Default) { ArtworkTexture(preprocessArtwork(artwork)) }
  }
  val black = remember { ArtworkTexture(imageFromArgb(IntArray(BLUR_SIZE * BLUR_SIZE) { 0xff000000.toInt() })) }
  var from by remember { mutableStateOf(black) }
  var to by remember { mutableStateOf(black) }
  var transitionStartedAt by remember { mutableLongStateOf(0L) }
  var transitionDurationNanos by remember { mutableLongStateOf(FIRST_CROSSFADE_NANOS) }
  var hasShownArtwork by remember { mutableStateOf(false) }
  var frameNanos by remember { mutableLongStateOf(platform.monotonicTimeNanos()) }
  var lastAnimationFrameNanos by remember { mutableLongStateOf(0L) }
  var animationTimeSeconds by remember { mutableFloatStateOf(0f) }
  val playing = rememberUpdatedState(isPlaying)
  val effect = remember { runCatching { RuntimeEffect.makeForShader(KAWARP_SHADER) }.getOrNull() }
  val paint = remember { Paint().apply { isAntiAlias = true } }
  DisposableEffect(effect, paint) {
    onDispose { paint.close(); effect?.close() }
  }
  LaunchedEffect(processed) {
    val next = processed ?: return@LaunchedEffect
    from = if (hasShownArtwork) to else black
    to = next
    transitionDurationNanos = if (hasShownArtwork) SUBSEQUENT_CROSSFADE_NANOS else FIRST_CROSSFADE_NANOS
    transitionStartedAt = platform.monotonicTimeNanos()
    hasShownArtwork = true
  }
  LaunchedEffect(effect, processed, fixedFrame) {
    if (effect == null || processed == null || fixedFrame != null) return@LaunchedEffect
    lastAnimationFrameNanos = 0L
    while (isActive) withFrameNanos { next ->
      if (lastAnimationFrameNanos != 0L) {
        animationTimeSeconds = advanceKawarpPhase(animationTimeSeconds, next - lastAnimationFrameNanos, playing.value)
      }
      lastAnimationFrameNanos = next
      frameNanos = next
    }
  }
  if (effect == null) {
    IosStaticArtworkBackground(artwork)
    return
  }
  val blend = if (fixedFrame != null && processed != null) 1f else if (!hasShownArtwork || transitionStartedAt == 0L) 0f
    else ((frameNanos - transitionStartedAt).toDouble() / transitionDurationNanos).coerceIn(0.0, 1.0).toFloat()
  val time = fixedFrame?.let { it / 1_000_000_000f } ?: animationTimeSeconds
  Canvas(Modifier.fillMaxSize()) {
    val uniforms = Data.makeFromBytes(shaderUniformBytes(size.width, size.height, time, blend, WARP_INTENSITY, SATURATION, DITHERING))
    val shader = effect.makeShader(uniforms, arrayOf(from.shader, to.shader), null)
    try {
      paint.shader = shader
      drawIntoCanvas { it.skiaCanvas.drawRect(Rect.makeWH(size.width, size.height), paint) }
    } finally {
      paint.shader = null
      shader.close()
      uniforms.close()
    }
  }
}

/** Skia linear image resize matches Bitmap.createScaledBitmap's filtering policy. */
private fun preprocessArtwork(source: ImageBitmap): SkImage {
  val scaled = ImageBitmap(BLUR_SIZE, BLUR_SIZE)
  val resizePaint = androidx.compose.ui.graphics.Paint().apply { filterQuality = FilterQuality.Low }
  androidx.compose.ui.graphics.Canvas(scaled).drawImageRect(
    source, IntOffset.Zero, IntSize(source.width, source.height),
    IntOffset.Zero, IntSize(BLUR_SIZE, BLUR_SIZE), resizePaint,
  )
  val pixels = IntArray(BLUR_SIZE * BLUR_SIZE)
  scaled.readPixels(pixels)
  return imageFromArgb(kawaseBlurPixels(pixels, BLUR_SIZE, BLUR_SIZE))
}

private fun imageFromArgb(pixels: IntArray): SkImage {
  val bytes = ByteArray(pixels.size * 4)
  pixels.forEachIndexed { i, pixel -> repeat(4) { shift -> bytes[i * 4 + shift] = (pixel ushr (shift * 8)).toByte() } }
  return SkImage.makeRaster(
    ImageInfo(BLUR_SIZE, BLUR_SIZE, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
    bytes, BLUR_SIZE * 4,
  )
}

private fun artworkPalette(artwork: ImageBitmap?): Pair<Color, Color> {
  if (artwork == null || artwork.width <= 0 || artwork.height <= 0) return Color(0xff23658a) to Color(0xff553c78)
  val pixels = IntArray(artwork.width * artwork.height)
  artwork.readPixels(pixels)
  val samples = ArrayList<Color>(144)
  val stepX = (artwork.width / 12).coerceAtLeast(1); val stepY = (artwork.height / 12).coerceAtLeast(1)
  var y = stepY / 2
  while (y < artwork.height) {
    var x = stepX / 2
    while (x < artwork.width) {
      val pixel = pixels[y * artwork.width + x]
      samples += Color((pixel ushr 16 and 255) / 255f, (pixel ushr 8 and 255) / 255f, (pixel and 255) / 255f)
      x += stepX
    }
    y += stepY
  }
  if (samples.isEmpty()) return Color(0xff23658a) to Color(0xff553c78)
  val vivid = samples.sortedByDescending { max(it.red, max(it.green, it.blue)) - minOf(it.red, it.green, it.blue) }
  fun lift(color: Color) = Color(
    (color.red * 0.78f + 0.16f).coerceIn(0f, 1f), (color.green * 0.78f + 0.16f).coerceIn(0f, 1f),
    (color.blue * 0.78f + 0.16f).coerceIn(0f, 1f),
  )
  return lift(vivid.first()) to lift(vivid.getOrElse(vivid.size / 3) { vivid.first() })
}
