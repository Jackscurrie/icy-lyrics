package com.icy.lyrics.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidFontScalingTest {
  @Test fun capturedApi36LargeTextConversionsMatchAllElevenFloatValues() {
    // Original Android capture: density3/fontScale1.8, portrait-long,
    // iOS/tests/fixtures/android36-large-text-font-conversions.json.
    val sizes = intArrayOf(12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64)
    val pixels = floatArrayOf(64.8f, 73.2f, 78f, 82.8f, 92.399994f, 98.399994f,
      102.399994f, 109.98857f, 121.16572f, 154.69714f, 199.4057f)
    val density = androidFontScalingDensity(Density(3f, 1.8f))
    assertEquals(3f, density.density)
    assertEquals(1.8f, density.fontScale)
    with(density) {
      sizes.indices.forEach { index ->
        assertEquals(pixels[index].toBits(), sizes[index].sp.toPx().toBits(), "${sizes[index]}sp")
      }
      assertEquals(36f, 12.dp.toPx()) // Layout dimensions do not use the font curve.
    }
  }

  @Test fun originalNormalAndSmallDensityObjectsAreUnchangedAndWrappingIsIdempotent() {
    for (scale in listOf(0.8f, 1f, 1.02f)) {
      val native = Density(3f, scale)
      assertSame(native, androidFontScalingDensity(native))
    }
    val normal = androidFontScalingDensity(Density(3f))
    with(normal) {
      for (size in listOf(12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64))
        assertEquals((size * 3f).toBits(), size.sp.toPx().toBits())
    }
    val large = androidFontScalingDensity(Density(3f, 1.8f))
    assertSame(large, androidFontScalingDensity(large))
  }

  @Test fun intermediateSizesAndScalesUseCurvesAndBoundaryRules() {
    assertEquals(26f, AndroidFontScaleCurve.forScale(1.8f).spToDp(16f))
    assertEquals(23.2f, AndroidFontScaleCurve.forScale(1.65f).spToDp(14f))
    assertEquals(15.6f, AndroidFontScaleCurve.forScale(1.1f).spToDp(14f))
    assertEquals(24.4f, AndroidFontScaleCurve.forScale(1.809f).spToDp(14f))
    assertEquals(100f, AndroidFontScaleCurve.forScale(2f).spToDp(100f))
    assertEquals(125f, AndroidFontScaleCurve.forScale(2f).spToDp(125f))
    assertEquals(30f, AndroidFontScaleCurve.forScale(2.5f).spToDp(12f))
  }

  @Test fun forwardAndInversePreserveSignedValuesAcrossEveryInterval() {
    for (scale in listOf(1.03f, 1.1f, 1.15f, 1.3f, 1.5f, 1.65f, 1.8f, 2f, 2.5f)) {
      val density = androidFontScalingDensity(Density(3f, scale))
      with(density) {
        for (size in listOf(-150f, -64f, -14f, -0.125f, 0f, 0.125f, 7f, 8f, 10f,
            12f, 13.125f, 14f, 16f, 18f, 20f, 24f, 28f, 30f, 32f, 64f, 99f, 100f, 150f)) {
          val back = size.sp.toPx().toSp().value
          assertTrue(abs(back - size) <= 0.00003f, "$size at $scale returned $back")
          assertTrue(abs(size.dp.toSp().toDp().value - size) <= 0.00003f)
        }
      }
    }
  }

  @Test fun relativeEmUnitsCannotBeConvertedWithoutTheirFontSize() {
    with(androidFontScalingDensity(Density(3f, 1.8f))) {
      assertFailsWith<IllegalStateException> { 1.em.toDp() }
    }
  }
}
