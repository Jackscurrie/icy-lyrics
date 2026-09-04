package com.icy.lyrics.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class IosBackgroundGradientTest {
  @Test fun androidGradientPaintDistributesQuantizationErrorDeterministically() {
    val undithered = renderGradient(Paint())
    val first = renderGradient(androidBackgroundGradientPaint())
    val second = renderGradient(androidBackgroundGradientPaint())

    // A vertical gradient is uniform along each row unless the rasterizer
    // distributes quantization error. Exercise the actual Skia raster behavior,
    // rather than merely checking a paint flag or tolerating screenshot errors.
    assertTrue(undithered.indices.all { undithered[it] == undithered[it / WIDTH * WIDTH] })
    assertTrue(first.indices.any { first[it] != first[it / WIDTH * WIDTH] })
    assertTrue(first.all { it ushr 24 == 255 })
    assertContentEquals(first, second)
  }

  private fun renderGradient(paint: Paint): IntArray {
    val image = ImageBitmap(WIDTH, HEIGHT)
    try {
      Brush.verticalGradient(listOf(Color(0xff404040), Color(0xff424242)))
        .applyTo(Size(WIDTH.toFloat(), HEIGHT.toFloat()), paint, 1f)
      Canvas(image).drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
      return IntArray(WIDTH * HEIGHT).also { image.readPixels(it) }
    } finally {
      paint.skiaPaint.close()
    }
  }

  private companion object {
    const val WIDTH = 32
    const val HEIGHT = 256
  }
}
