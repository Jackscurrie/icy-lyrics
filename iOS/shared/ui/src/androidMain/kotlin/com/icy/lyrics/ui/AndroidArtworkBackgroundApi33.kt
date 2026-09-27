package com.icy.lyrics.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Android 13 implementation kept in its own class so API 30-32 never load RuntimeShader references. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun KawarpBackgroundApi33(artwork: Bitmap, isPlaying: Boolean) {
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
