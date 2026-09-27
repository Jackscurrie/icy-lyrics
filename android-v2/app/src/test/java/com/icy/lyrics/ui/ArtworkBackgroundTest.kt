package com.icy.lyrics.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkBackgroundTest {
  @Test
  fun `pause and resume change phase rate without jumping`() {
    val afterPlaying = advanceKawarpPhase(12f, 100_000_000L, isPlaying = true)
    val afterPaused = advanceKawarpPhase(afterPlaying, 100_000_000L, isPlaying = false)
    val afterResumed = advanceKawarpPhase(afterPaused, 100_000_000L, isPlaying = true)

    assertEquals(12.1f, afterPlaying, 0.0001f)
    assertEquals(12.11f, afterPaused, 0.0001f)
    assertEquals(12.21f, afterResumed, 0.0001f)
  }

  @Test
  fun `performance backgrounds always select a static treatment`() {
    assertEquals(
      ArtworkBackgroundTreatment.PREBLURRED_ARTWORK,
      artworkBackgroundTreatment(
        enabled = true,
        style = BackgroundStyle.ANIMATED,
        reducedMotion = false,
        performanceBackground = TvPerformanceBackground.STATIC_BLURRED,
      ),
    )
    assertEquals(
      ArtworkBackgroundTreatment.SOLID_ALBUM_COLOR,
      artworkBackgroundTreatment(false, BackgroundStyle.ANIMATED, false, TvPerformanceBackground.SOLID_ALBUM_COLOR),
    )
    assertEquals(
      ArtworkBackgroundTreatment.DIMMED_ARTWORK,
      artworkBackgroundTreatment(true, BackgroundStyle.ANIMATED, false, TvPerformanceBackground.DIMMED_ARTWORK),
    )
    assertEquals(
      ArtworkBackgroundTreatment.BLACK,
      artworkBackgroundTreatment(true, BackgroundStyle.ANIMATED, false, TvPerformanceBackground.BLACK),
    )
  }

  @Test
  fun `ordinary background policy is unchanged without a performance override`() {
    assertEquals(
      ArtworkBackgroundTreatment.ANIMATED,
      artworkBackgroundTreatment(true, BackgroundStyle.ANIMATED, false, null),
    )
    assertEquals(
      ArtworkBackgroundTreatment.STATIC_BLURRED,
      artworkBackgroundTreatment(true, BackgroundStyle.ANIMATED, true, null),
    )
    assertEquals(
      ArtworkBackgroundTreatment.STATIC_BLURRED,
      artworkBackgroundTreatment(true, BackgroundStyle.STATIC_BLURRED, false, null),
    )
    assertEquals(
      ArtworkBackgroundTreatment.BLACK,
      artworkBackgroundTreatment(false, BackgroundStyle.ANIMATED, false, null),
    )
  }
}
