package com.icy.lyrics.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ArtworkPixelsTest {
  @Test fun constantArtworkSurvivesEveryBlurPassWithoutColorOrAlphaDrift() {
    val pixels = IntArray(16) { 0x8071a3f5.toInt() }
    assertContentEquals(pixels, kawaseBlurPixels(pixels, 4, 4))
  }

  @Test fun bilinearSamplesClampToEdgesAndRoundEachInterpolationLikeAndroid() {
    val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffff0000.toInt(), 0xff0000ff.toInt())
    assertEquals(pixels[0], bilinearArgb(pixels, 2, 2, -5f, -5f))
    assertEquals(pixels[3], bilinearArgb(pixels, 2, 2, 8f, 8f))
    assertEquals(0xff7f3f7f.toInt(), bilinearArgb(pixels, 2, 2, 0.5f, 0.5f))
  }

  @Test fun runtimeUniformsKeepFloatOrderAndLittleEndianByteRepresentation() {
    assertContentEquals(
      byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64),
      shaderUniformBytes(1f, -2f),
    )
  }

  @Test fun pausedAnimationContinuesAtOneTenthRateAndClampsLargeFrameGaps() {
    assertEquals(2.025f, advanceKawarpPhase(2f, 1_000_000_000L, false))
    assertEquals(2.25f, advanceKawarpPhase(2f, 1_000_000_000L, true))
  }
}
