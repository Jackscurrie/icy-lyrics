package com.icy.lyrics.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * API 30-32 equivalent of Kawarp's animated artwork backdrop.
 *
 * RuntimeShader is unavailable before API 33, so this path warps the same
 * 128 px preblurred artwork through a hardware-accelerated bitmap mesh. The
 * small mesh keeps CPU work fixed while its overscan hides moving edges on TV.
 */
@Composable
internal fun KawarpBackgroundApi30(artwork: Bitmap, isPlaying: Boolean) {
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
  var animationTimeSeconds by remember { mutableFloatStateOf(0f) }
  val latestFrameNanos = remember { longArrayOf(platform.monotonicTimeNanos()) }
  val currentIsPlaying = rememberUpdatedState(isPlaying)
  val vertices = remember { FloatArray(LEGACY_KAWARP_VERTEX_FLOATS) }
  val artworkPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
    }
  }
  val vignettePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
  val vignetteSize = remember { FloatArray(2) }

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
  LaunchedEffect(processed, fixedFrame) {
    if (processed == null || fixedFrame != null) return@LaunchedEffect
    var lastRenderedFrameNanos = 0L
    while (isActive) {
      withFrameNanos { nextFrameNanos ->
        if (lastRenderedFrameNanos == 0L) {
          lastRenderedFrameNanos = nextFrameNanos
          latestFrameNanos[0] = nextFrameNanos
        } else {
          val elapsedNanos = nextFrameNanos - lastRenderedFrameNanos
          if (shouldAdvanceLegacyKawarpFrame(elapsedNanos)) {
            latestFrameNanos[0] = nextFrameNanos
            animationTimeSeconds = advanceKawarpPhase(
              animationTimeSeconds,
              elapsedNanos,
              currentIsPlaying.value,
            )
            lastRenderedFrameNanos = nextFrameNanos
          }
        }
      }
    }
  }

  Canvas(Modifier.fillMaxSize()) {
    if (size.width <= 0f || size.height <= 0f) return@Canvas
    val drawFrameNanos = fixedFrame ?: latestFrameNanos[0]
    val blend = if (fixedFrame != null && processed != null) {
      1f
    } else if (!hasShownArtwork || transitionStartedAt == 0L) {
      0f
    } else {
      ((drawFrameNanos - transitionStartedAt).toDouble() / transitionDurationNanos)
        .coerceIn(0.0, 1.0)
        .toFloat()
    }
    val time = fixedFrame?.let { it / 1_000_000_000f } ?: animationTimeSeconds
    populateLegacyKawarpMesh(size.width, size.height, time, vertices)

    drawIntoCanvas { canvas ->
      val nativeCanvas = canvas.nativeCanvas
      if (blend < 1f) {
        artworkPaint.alpha = ((1f - blend) * 255f).toInt().coerceIn(0, 255)
        nativeCanvas.drawBitmapMesh(
          from,
          LEGACY_KAWARP_COLUMNS,
          LEGACY_KAWARP_ROWS,
          vertices,
          0,
          null,
          0,
          artworkPaint,
        )
      }
      if (blend > 0f) {
        artworkPaint.alpha = (blend * 255f).toInt().coerceIn(0, 255)
        nativeCanvas.drawBitmapMesh(
          to,
          LEGACY_KAWARP_COLUMNS,
          LEGACY_KAWARP_ROWS,
          vertices,
          0,
          null,
          0,
          artworkPaint,
        )
      }

      if (vignetteSize[0] != size.width || vignetteSize[1] != size.height) {
        vignetteSize[0] = size.width
        vignetteSize[1] = size.height
        vignettePaint.shader = RadialGradient(
          size.width / 2f,
          size.height / 2f,
          max(size.width, size.height) * 0.72f,
          intArrayOf(0x00000000, 0x33000000),
          floatArrayOf(0.42f, 1f),
          Shader.TileMode.CLAMP,
        )
      }
      nativeCanvas.drawRect(0f, 0f, size.width, size.height, vignettePaint)
    }
  }
}

internal fun shouldAdvanceLegacyKawarpFrame(elapsedNanos: Long): Boolean =
  elapsedNanos >= LEGACY_KAWARP_FRAME_INTERVAL_NANOS

/** Populates a stable, overscanned mesh without allocating per frame. */
internal fun populateLegacyKawarpMesh(
  width: Float,
  height: Float,
  timeSeconds: Float,
  vertices: FloatArray,
) {
  require(vertices.size >= LEGACY_KAWARP_VERTEX_FLOATS) {
    "Kawarp mesh needs at least $LEGACY_KAWARP_VERTEX_FLOATS floats"
  }
  val overscanX = width * LEGACY_KAWARP_OVERSCAN
  val overscanY = height * LEGACY_KAWARP_OVERSCAN
  val spanX = width + overscanX * 2f
  val spanY = height + overscanY * 2f
  val phase = timeSeconds * 0.24f
  var index = 0
  for (row in 0..LEGACY_KAWARP_ROWS) {
    val v = row.toFloat() / LEGACY_KAWARP_ROWS
    for (column in 0..LEGACY_KAWARP_COLUMNS) {
      val u = column.toFloat() / LEGACY_KAWARP_COLUMNS
      val edgeEnvelope = sin(PI.toFloat() * u) * sin(PI.toFloat() * v)
      val centerWeight = (1f - hypot(u - 0.5f, v - 0.5f) / 0.72f).coerceIn(0f, 1f)
      val weight = edgeEnvelope * (0.42f + centerWeight * 0.58f)
      val horizontalWave =
        sin(v * PI.toFloat() * 2.6f + phase) * 0.68f +
          sin(u * PI.toFloat() * 3.1f - phase * 0.73f + 1.7f) * 0.32f
      val verticalWave =
        sin(u * PI.toFloat() * 2.3f - phase * 0.91f + 0.8f) * 0.66f +
          sin(v * PI.toFloat() * 3.4f + phase * 0.61f + 2.1f) * 0.34f
      vertices[index++] = -overscanX + u * spanX + horizontalWave * width * 0.052f * weight
      vertices[index++] = -overscanY + v * spanY + verticalWave * height * 0.052f * weight
    }
  }
}

internal const val LEGACY_KAWARP_COLUMNS = 12
internal const val LEGACY_KAWARP_ROWS = 8
internal const val LEGACY_KAWARP_VERTEX_FLOATS =
  (LEGACY_KAWARP_COLUMNS + 1) * (LEGACY_KAWARP_ROWS + 1) * 2
internal const val LEGACY_KAWARP_FRAME_INTERVAL_NANOS = 33_333_333L
private const val LEGACY_KAWARP_OVERSCAN = 0.065f
