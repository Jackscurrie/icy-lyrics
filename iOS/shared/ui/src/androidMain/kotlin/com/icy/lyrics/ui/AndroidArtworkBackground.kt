package com.icy.lyrics.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Artwork backdrop adapted from @kawarp/core. Album preprocessing happens at
 * 128 px only when the bitmap changes; animated frames only blend, warp and
 * grade those prepared textures. Turning animation off schedules no frames.
 */
@Composable
fun AndroidArtworkBackground(
  artwork: Bitmap?,
  enabled: Boolean,
  style: BackgroundStyle,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val reducedMotion = rememberReducedMotionEnabled()
  val animated = enabled && style == BackgroundStyle.ANIMATED && !reducedMotion
  Box(modifier.fillMaxSize().background(Color.Black)) {
    if (animated && artwork != null) {
      KawarpBackground(artwork, isPlaying)
    } else if (enabled) {
      StaticArtworkBackground(artwork)
    }
    Canvas(Modifier.fillMaxSize()) {
      drawRect(
        Brush.verticalGradient(
          listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.62f)),
        ),
      )
    }
    content()
  }
}

@Composable
private fun StaticArtworkBackground(artwork: Bitmap?) {
  val colors = remember(artwork) { artwork.palette() }
  if (artwork != null) {
    Image(
      bitmap = artwork.asImageBitmap(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize().blur(54.dp),
      alpha = 0.52f,
    )
  }
  Canvas(Modifier.fillMaxSize()) {
    drawRect(
      Brush.verticalGradient(
        listOf(colors.primary.copy(alpha = 0.34f), colors.secondary.copy(alpha = 0.18f), Color.Black),
      ),
    )
  }
}

@Composable
private fun KawarpBackground(artwork: Bitmap, isPlaying: Boolean) {
  val platform = LocalIcyUiPlatform.current
  val fixedFrame = platform.fixedFrameTimeNanos
  val processed by produceState<Bitmap?>(initialValue = null, key1 = artwork) {
    value = withContext(Dispatchers.Default) { preprocessArtwork(artwork) }
  }
  val black = remember {
    Bitmap.createBitmap(BLUR_SIZE, BLUR_SIZE, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.BLACK)
    }
  }
  var from by remember { mutableStateOf(black) }
  var to by remember { mutableStateOf(black) }
  var transitionStartedAt by remember { mutableLongStateOf(0L) }
  var transitionDurationNanos by remember { mutableLongStateOf(FIRST_CROSSFADE_NANOS) }
  var hasShownArtwork by remember { mutableStateOf(false) }
  var frameNanos by remember { mutableLongStateOf(platform.monotonicTimeNanos()) }
  var lastAnimationFrameNanos by remember { mutableLongStateOf(0L) }
  var animationTimeSeconds by remember { mutableFloatStateOf(0f) }
  val currentIsPlaying = rememberUpdatedState(isPlaying)
  val runtimeShader = remember { runCatching { RuntimeShader(KAWARP_SHADER) }.getOrNull() }
  val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
  val fromShader = remember(from) { BitmapShader(from, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
  val toShader = remember(to) { BitmapShader(to, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }

  LaunchedEffect(processed) {
    val next = processed ?: return@LaunchedEffect
    from = if (hasShownArtwork) to else black
    to = next
    transitionDurationNanos = if (hasShownArtwork) {
      SUBSEQUENT_CROSSFADE_NANOS
    } else {
      FIRST_CROSSFADE_NANOS
    }
    transitionStartedAt = platform.monotonicTimeNanos()
    hasShownArtwork = true
  }
  LaunchedEffect(runtimeShader, processed) {
    if (runtimeShader == null || processed == null || fixedFrame != null) return@LaunchedEffect
    lastAnimationFrameNanos = 0L
    while (isActive) {
      withFrameNanos { nextFrameNanos ->
        if (lastAnimationFrameNanos != 0L) {
          animationTimeSeconds = advanceKawarpPhase(
            animationTimeSeconds,
            nextFrameNanos - lastAnimationFrameNanos,
            currentIsPlaying.value,
          )
        }
        lastAnimationFrameNanos = nextFrameNanos
        frameNanos = nextFrameNanos
      }
    }
  }

  if (runtimeShader == null) {
    StaticArtworkBackground(artwork)
    return
  }
  val blend = if (fixedFrame != null && processed != null) 1f else if (!hasShownArtwork || transitionStartedAt == 0L) {
    0f
  } else {
    ((frameNanos - transitionStartedAt).toDouble() / transitionDurationNanos)
      .coerceIn(0.0, 1.0)
      .toFloat()
  }
  val time = fixedFrame?.let { it / 1_000_000_000f } ?: animationTimeSeconds

  Canvas(Modifier.fillMaxSize()) {
    runtimeShader.setInputShader("fromImage", fromShader)
    runtimeShader.setInputShader("toImage", toShader)
    runtimeShader.setFloatUniform("resolution", size.width, size.height)
    runtimeShader.setFloatUniform("time", time)
    runtimeShader.setFloatUniform("blend", blend)
    runtimeShader.setFloatUniform("intensity", WARP_INTENSITY)
    runtimeShader.setFloatUniform("saturation", SATURATION)
    runtimeShader.setFloatUniform("dithering", DITHERING)
    paint.shader = runtimeShader
    drawIntoCanvas { canvas ->
      canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
  }
}

/** CPU Kawase preprocessing with exactly eight four-corner passes. */
private fun preprocessArtwork(source: Bitmap): Bitmap {
  val scaled = Bitmap.createScaledBitmap(source, BLUR_SIZE, BLUR_SIZE, true)
  var read = IntArray(BLUR_SIZE * BLUR_SIZE)
  var write = IntArray(read.size)
  scaled.getPixels(read, 0, BLUR_SIZE, 0, 0, BLUR_SIZE, BLUR_SIZE)
  repeat(BLUR_PASSES) { pass ->
    val offset = pass + 0.5f
    for (y in 0 until BLUR_SIZE) {
      for (x in 0 until BLUR_SIZE) {
        val c1 = sampleBilinear(read, x - offset, y - offset)
        val c2 = sampleBilinear(read, x + offset, y - offset)
        val c3 = sampleBilinear(read, x - offset, y + offset)
        val c4 = sampleBilinear(read, x + offset, y + offset)
        write[y * BLUR_SIZE + x] = average(c1, c2, c3, c4)
      }
    }
    val swap = read
    read = write
    write = swap
  }
  val processed = Bitmap.createBitmap(read, BLUR_SIZE, BLUR_SIZE, Bitmap.Config.ARGB_8888)
  if (scaled !== source) scaled.recycle()
  return processed
}

private fun sampleBilinear(pixels: IntArray, rawX: Float, rawY: Float): Int {
  val x = rawX.coerceIn(0f, (BLUR_SIZE - 1).toFloat())
  val y = rawY.coerceIn(0f, (BLUR_SIZE - 1).toFloat())
  val x0 = floor(x).toInt()
  val y0 = floor(y).toInt()
  val x1 = (x0 + 1).coerceAtMost(BLUR_SIZE - 1)
  val y1 = (y0 + 1).coerceAtMost(BLUR_SIZE - 1)
  val fx = x - x0
  val fy = y - y0
  return lerpColor(
    lerpColor(pixels[y0 * BLUR_SIZE + x0], pixels[y0 * BLUR_SIZE + x1], fx),
    lerpColor(pixels[y1 * BLUR_SIZE + x0], pixels[y1 * BLUR_SIZE + x1], fx),
    fy,
  )
}

private fun lerpColor(left: Int, right: Int, amount: Float): Int {
  fun channel(shift: Int): Int {
    val a = left ushr shift and 0xff
    val b = right ushr shift and 0xff
    return (a + (b - a) * amount).toInt().coerceIn(0, 255)
  }
  return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun average(a: Int, b: Int, c: Int, d: Int): Int {
  fun channel(shift: Int): Int = (
    (a ushr shift and 0xff) + (b ushr shift and 0xff) +
      (c ushr shift and 0xff) + (d ushr shift and 0xff)
    ) / 4
  return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private data class ArtworkPalette(val primary: Color, val secondary: Color)

private fun Bitmap?.palette(): ArtworkPalette {
  if (this == null || width <= 0 || height <= 0) {
    return ArtworkPalette(Color(0xFF23658A), Color(0xFF553C78))
  }
  val samples = ArrayList<Color>(144)
  val stepX = (width / 12).coerceAtLeast(1)
  val stepY = (height / 12).coerceAtLeast(1)
  var y = stepY / 2
  while (y < height) {
    var x = stepX / 2
    while (x < width) {
      val pixel = getPixel(x, y)
      samples += Color(
        android.graphics.Color.red(pixel) / 255f,
        android.graphics.Color.green(pixel) / 255f,
        android.graphics.Color.blue(pixel) / 255f,
      )
      x += stepX
    }
    y += stepY
  }
  if (samples.isEmpty()) return ArtworkPalette(Color(0xFF23658A), Color(0xFF553C78))
  val vivid = samples.sortedByDescending { color ->
    max(color.red, max(color.green, color.blue)) - minOf(color.red, color.green, color.blue)
  }
  return ArtworkPalette(vivid.first().lift(), vivid.getOrElse(vivid.size / 3) { vivid.first() }.lift())
}

private fun Color.lift(): Color = Color(
  red = (red * 0.78f + 0.16f).coerceIn(0f, 1f),
  green = (green * 0.78f + 0.16f).coerceIn(0f, 1f),
  blue = (blue * 0.78f + 0.16f).coerceIn(0f, 1f),
)

@Composable
internal fun rememberAndroidReducedMotionEnabled(): Boolean {
  val context = LocalContext.current
  fun readValue(): Boolean = Settings.Global.getFloat(
    context.contentResolver,
    Settings.Global.ANIMATOR_DURATION_SCALE,
    1f,
  ) == 0f
  var reducedMotion by remember(context) { mutableStateOf(readValue()) }
  DisposableEffect(context) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        reducedMotion = readValue()
      }
    }
    context.contentResolver.registerContentObserver(
      Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
      false,
      observer,
    )
    onDispose { context.contentResolver.unregisterContentObserver(observer) }
  }
  return reducedMotion
}

