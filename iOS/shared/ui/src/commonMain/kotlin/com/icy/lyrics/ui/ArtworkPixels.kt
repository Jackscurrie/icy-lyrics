package com.icy.lyrics.ui

import kotlin.math.floor

/** The Android Kawase kernel, including its integer channel rounding and clamp edges. */
internal fun kawaseBlurPixels(input: IntArray, width: Int, height: Int, passes: Int = BLUR_PASSES): IntArray {
  require(width > 0 && height > 0 && input.size == width * height)
  var read = input.copyOf()
  var write = IntArray(read.size)
  repeat(passes) { pass ->
    val offset = pass + 0.5f
    for (y in 0 until height) for (x in 0 until width) {
      val a = bilinearArgb(read, width, height, x - offset, y - offset)
      val b = bilinearArgb(read, width, height, x + offset, y - offset)
      val c = bilinearArgb(read, width, height, x - offset, y + offset)
      val d = bilinearArgb(read, width, height, x + offset, y + offset)
      fun channel(shift: Int): Int = ((a ushr shift and 0xff) + (b ushr shift and 0xff) +
        (c ushr shift and 0xff) + (d ushr shift and 0xff)) / 4
      write[y * width + x] = (channel(24) shl 24) or (channel(16) shl 16) or
        (channel(8) shl 8) or channel(0)
    }
    val swap = read; read = write; write = swap
  }
  return read
}

internal fun bilinearArgb(pixels: IntArray, width: Int, height: Int, rawX: Float, rawY: Float): Int {
  val x = rawX.coerceIn(0f, (width - 1).toFloat())
  val y = rawY.coerceIn(0f, (height - 1).toFloat())
  val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
  val x1 = (x0 + 1).coerceAtMost(width - 1); val y1 = (y0 + 1).coerceAtMost(height - 1)
  fun lerp(a: Int, b: Int, t: Float): Int {
    fun channel(shift: Int): Int {
      val left = a ushr shift and 0xff; val right = b ushr shift and 0xff
      return (left + (right - left) * t).toInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }
  return lerp(
    lerp(pixels[y0 * width + x0], pixels[y0 * width + x1], x - x0),
    lerp(pixels[y1 * width + x0], pixels[y1 * width + x1], x - x0), y - y0,
  )
}

/** Native runtime-effect uniforms use little-endian IEEE-754 float32 bytes. */
internal fun shaderUniformBytes(vararg values: Float): ByteArray = ByteArray(values.size * 4).also { bytes ->
  values.forEachIndexed { index, value ->
    val bits = value.toBits()
    repeat(4) { channel -> bytes[index * 4 + channel] = (bits ushr (channel * 8)).toByte() }
  }
}
