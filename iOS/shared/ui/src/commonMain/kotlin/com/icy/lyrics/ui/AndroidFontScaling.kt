/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.icy.lyrics.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS entry points alone opt into the frozen Android UI's font conversions.
 * Screen density and the user's reported fontScale remain unchanged. Returning
 * the original object below Android's threshold preserves default-size behavior.
 *
 * Tables/Float operation order: AndroidX ui-unit-android 1.11.0,
 * FontScaleConverterFactory, FontScaleConverterTable and MathUtils.
 * See iOS/docs/ANDROID-FONT-SCALING.md for source hashes and captured evidence.
 */
internal fun androidFontScalingDensity(original: Density): Density =
  if (original.fontScale < 1.03f || original is AndroidFontScalingDensity) original
  else AndroidFontScalingDensity(original.density, original.fontScale)

@Immutable
private data class AndroidFontScalingDensity(
  override val density: Float,
  override val fontScale: Float,
) : Density {
  private val converter = AndroidFontScaleCurve.forScale(fontScale)

  override fun TextUnit.toDp(): Dp {
    check(type == TextUnitType.Sp) { "Only Sp can convert to Px" }
    return Dp(converter.spToDp(value))
  }

  override fun Dp.toSp(): TextUnit = converter.dpToSp(value).sp
}

/** Immutable per-scale curve; no process-history-dependent interpolation cache. */
internal class AndroidFontScaleCurve private constructor(
  private val fromSp: FloatArray,
  private val toDp: FloatArray,
) {
  fun spToDp(value: Float): Float = lookup(value, fromSp, toDp)
  fun dpToSp(value: Float): Float = lookup(value, toDp, fromSp)

  companion object {
    private val sizes = floatArrayOf(8f, 10f, 12f, 14f, 18f, 20f, 24f, 30f, 100f)
    private val scaleKeys = intArrayOf(115, 130, 150, 180, 200)
    private val tables = arrayOf(
      floatArrayOf(9.2f, 11.5f, 13.8f, 16.4f, 19.8f, 21.8f, 25.2f, 30f, 100f),
      floatArrayOf(10.4f, 13f, 15.6f, 18.8f, 21.6f, 23.6f, 26.4f, 30f, 100f),
      floatArrayOf(12f, 15f, 18f, 22f, 24f, 26f, 28f, 30f, 100f),
      floatArrayOf(14.4f, 18f, 21.6f, 24.4f, 27.6f, 30.8f, 32.8f, 34.8f, 100f),
      floatArrayOf(16f, 20f, 24f, 26f, 30f, 34f, 36f, 38f, 100f),
    )

    fun forScale(scale: Float): AndroidFontScaleCurve {
      require(scale.isFinite() && scale > 0f) { "Font scale must be finite and positive" }
      if (scale < 1.03f) return AndroidFontScaleCurve(floatArrayOf(1f), floatArrayOf(scale))
      // The pinned Android lookup keys truncate scale*100; do not round them.
      val scaleKey = (scale * 100f).toInt()
      val index = search(scaleKeys.size) { scaleKeys[it].compareTo(scaleKey) }
      if (index >= 0) return AndroidFontScaleCurve(sizes, tables[index])
      val higher = -(index + 1)
      if (higher >= scaleKeys.size) return AndroidFontScaleCurve(floatArrayOf(1f), floatArrayOf(scale))
      val lower = higher - 1
      val startScale = if (lower < 0) 1f else scaleKeys[lower].toFloat() / 100f
      val endScale = scaleKeys[higher].toFloat() / 100f
      val fraction = constrainedMap(0f, 1f, startScale, endScale, scale)
      val start = if (lower < 0) sizes else tables[lower]
      return AndroidFontScaleCurve(sizes, FloatArray(sizes.size) { index ->
        lerp(start[index], tables[higher][index], fraction)
      })
    }

    private fun lookup(value: Float, source: FloatArray, target: FloatArray): Float {
      val positive = abs(value)
      val sign = sign(value)
      val index = search(source.size) { source[it].compareTo(positive) }
      if (index >= 0) return sign * target[index]
      val lower = -(index + 1) - 1
      if (lower >= source.lastIndex) {
        val start = source.last()
        if (start == 0f) return 0f
        return value * (target.last() / start)
      }
      val startSp = if (lower == -1) 0f else source[lower]
      val startDp = if (lower == -1) 0f else target[lower]
      return sign * constrainedMap(startDp, target[lower + 1], startSp, source[lower + 1], positive)
    }

    private fun lerp(start: Float, stop: Float, amount: Float): Float = start + (stop - start) * amount

    // Primitive-array binarySearch is JVM-only in the pinned stdlib. Preserve
    // its exact match/insertion-point contract without depending on java.util.
    private inline fun search(size: Int, compareAt: (Int) -> Int): Int {
      var low = 0
      var high = size - 1
      while (low <= high) {
        val middle = (low + high).ushr(1)
        val comparison = compareAt(middle)
        if (comparison < 0) low = middle + 1
        else if (comparison > 0) high = middle - 1
        else return middle
      }
      return -(low + 1)
    }

    private fun constrainedMap(rangeMin: Float, rangeMax: Float, valueMin: Float, valueMax: Float, value: Float): Float {
      val fraction = if (valueMin != valueMax) (value - valueMin) / (valueMax - valueMin) else 0f
      return lerp(rangeMin, rangeMax, fraction.coerceIn(0f, 1f))
    }
  }
}
