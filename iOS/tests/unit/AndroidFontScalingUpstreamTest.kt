package com.icy.lyrics.ui

import androidx.compose.ui.unit.fontscaling.FontScaleConverterFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the pinned Android dependency itself, not a second copy of its equations. */
class AndroidFontScalingUpstreamTest {
  @Test fun freshConversionsMatchPinnedAndroidXForEveryCurveAndInterpolatedScale() {
    val original = FontScaleConverterFactory.sLookupTables
    val anchors = original.clone().apply {
      for (index in size() - 1 downTo 0) {
        if (keyAt(index) !in listOf(115, 130, 150, 180, 200)) removeAt(index)
      }
    }
    try {
      for (scale in listOf(1.03f, 1.05f, 1.1f, 1.15f, 1.159f, 1.23f, 1.3f, 1.4f,
          1.5f, 1.65f, 1.75f, 1.8f, 1.809f, 1.9f, 2f, 2.009f, 2.5f)) {
        FontScaleConverterFactory.sLookupTables = anchors.clone()
        val upstream = requireNotNull(FontScaleConverterFactory.forScale(scale))
        val port = AndroidFontScaleCurve.forScale(scale)
        for (size in listOf(-150f, -64f, -0.125f, 0f, 0.125f, 7f, 8f, 10f, 12f, 13.125f,
            14f, 16f, 18f, 20f, 24f, 28f, 30f, 32f, 36f, 48f, 64f, 99f, 100f, 150f)) {
          assertEquals(upstream.convertSpToDp(size).toBits(), port.spToDp(size).toBits(), "SP $size at $scale")
          assertEquals(upstream.convertDpToSp(size).toBits(), port.dpToSp(size).toBits(), "DP $size at $scale")
        }
      }
    } finally {
      FontScaleConverterFactory.sLookupTables = original
    }
  }
}
