package com.icy.lyrics.ui

import android.graphics.Bitmap
import android.database.ContentObserver
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
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
  performanceBackground: TvPerformanceBackground? = null,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val reducedMotion = rememberReducedMotionEnabled()
  val treatment = artworkBackgroundTreatment(enabled, style, reducedMotion, performanceBackground)
  Box(modifier.fillMaxSize().background(Color.Black)) {
    when (treatment) {
      ArtworkBackgroundTreatment.ANIMATED -> if (artwork != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          KawarpBackgroundApi33(artwork, isPlaying)
        } else {
          KawarpBackgroundApi30(artwork, isPlaying)
        }
      }
      ArtworkBackgroundTreatment.STATIC_BLURRED -> StaticArtworkBackground(artwork)
      ArtworkBackgroundTreatment.PREBLURRED_ARTWORK -> PreblurredArtworkBackground(artwork)
      ArtworkBackgroundTreatment.SOLID_ALBUM_COLOR -> SolidAlbumColorBackground(artwork)
      ArtworkBackgroundTreatment.DIMMED_ARTWORK -> DimmedArtworkBackground(artwork)
      ArtworkBackgroundTreatment.BLACK -> Unit
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
internal fun StaticArtworkBackground(artwork: Bitmap?) {
  val colors = remember(artwork) { artwork.palette() }
  if (artwork != null) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Image(
        bitmap = artwork.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().blur(54.dp),
        alpha = 0.52f,
      )
    } else {
      CpuPreblurredArtwork(artwork)
    }
  }
  Canvas(Modifier.fillMaxSize()) {
    drawRect(
      Brush.verticalGradient(
        listOf(colors.primary.copy(alpha = 0.34f), colors.secondary.copy(alpha = 0.18f), Color.Black),
      ),
    )
  }
}

/** One CPU pass per artwork; subsequent lyric frames only composite this small texture. */
@Composable
private fun PreblurredArtworkBackground(artwork: Bitmap?) {
  if (artwork == null) return
  CpuPreblurredArtwork(artwork)
}

@Composable
private fun SolidAlbumColorBackground(artwork: Bitmap?) {
  val fallback = Color(0xFF102E3F)
  val color by produceState(initialValue = fallback, key1 = artwork) {
    value = withContext(Dispatchers.Default) {
      artwork.palette().primary.performanceSolidColor()
    }
  }
  Canvas(Modifier.fillMaxSize()) { drawRect(color) }
}

@Composable
private fun DimmedArtworkBackground(artwork: Bitmap?) {
  if (artwork == null) return
  Image(
    bitmap = artwork.asImageBitmap(),
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier.fillMaxSize(),
    alpha = 0.34f,
  )
}

@Composable
private fun CpuPreblurredArtwork(artwork: Bitmap) {
  val processed by produceState<Bitmap?>(initialValue = null, key1 = artwork) {
    value = withContext(Dispatchers.Default) { preprocessArtwork(artwork) }
  }
  processed?.let { blurred ->
    Image(
      bitmap = blurred.asImageBitmap(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
      alpha = 0.52f,
    )
  }
}

/** CPU Kawase preprocessing with exactly eight four-corner passes. */
internal fun preprocessArtwork(source: Bitmap): Bitmap {
  // MediaSession artwork may be backed by an immutable GPU-only HARDWARE
  // bitmap. Bitmap.getPixels() rejects that config, so make the smallest
  // possible readable copy before the one-time 128 px preprocessing pass.
  val readableSource = if (source.config == Bitmap.Config.HARDWARE) {
    requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) {
      "Artwork could not be copied into a readable bitmap."
    }
  } else {
    source
  }
  val scaledCandidate = Bitmap.createScaledBitmap(readableSource, BLUR_SIZE, BLUR_SIZE, true)
  val scaled = if (scaledCandidate.config == Bitmap.Config.HARDWARE) {
    requireNotNull(scaledCandidate.copy(Bitmap.Config.ARGB_8888, false)) {
      "Scaled artwork could not be copied into a readable bitmap."
    }
  } else {
    scaledCandidate
  }
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
  if (scaledCandidate !== scaled && scaledCandidate !== source) scaledCandidate.recycle()
  if (
    readableSource !== scaled &&
    readableSource !== scaledCandidate &&
    readableSource !== source
  ) {
    readableSource.recycle()
  }
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
  val readable = if (config == Bitmap.Config.HARDWARE) {
    copy(Bitmap.Config.ARGB_8888, false)
  } else {
    this
  } ?: return ArtworkPalette(Color(0xFF23658A), Color(0xFF553C78))
  val samples = ArrayList<Color>(144)
  val stepX = (readable.width / 12).coerceAtLeast(1)
  val stepY = (readable.height / 12).coerceAtLeast(1)
  var y = stepY / 2
  while (y < readable.height) {
    var x = stepX / 2
    while (x < readable.width) {
      val pixel = readable.getPixel(x, y)
      samples += Color(
        android.graphics.Color.red(pixel) / 255f,
        android.graphics.Color.green(pixel) / 255f,
        android.graphics.Color.blue(pixel) / 255f,
      )
      x += stepX
    }
    y += stepY
  }
  if (readable !== this) readable.recycle()
  if (samples.isEmpty()) return ArtworkPalette(Color(0xFF23658A), Color(0xFF553C78))
  val vivid = samples.sortedByDescending { color ->
    max(color.red, max(color.green, color.blue)) - minOf(color.red, color.green, color.blue)
  }
  return ArtworkPalette(vivid.first().lift(), vivid.getOrElse(vivid.size / 3) { vivid.first() }.lift())
}

/** Keeps white lyrics readable even when the sampled album colour is bright. */
private fun Color.performanceSolidColor(): Color = Color(
  red = red * 0.42f,
  green = green * 0.42f,
  blue = blue * 0.42f,
)

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

