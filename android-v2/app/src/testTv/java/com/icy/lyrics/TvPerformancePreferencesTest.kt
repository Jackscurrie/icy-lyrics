package com.icy.lyrics

import com.icy.lyrics.ui.TvPerformanceBackground
import com.icy.lyrics.ui.TvPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPerformancePreferencesTest {
  @Test
  fun missingOrUnknownModeFallsBackToOff() {
    assertEquals(TvPerformanceMode.OFF, decodeTvPerformanceMode(null))
    assertEquals(TvPerformanceMode.OFF, decodeTvPerformanceMode("FUTURE_MODE"))
  }

  @Test
  fun everyKnownModeRoundTripsByStableName() {
    TvPerformanceMode.entries.forEach { mode ->
      assertEquals(mode, decodeTvPerformanceMode(mode.name))
    }
  }

  @Test
  fun missingOrUnknownBackgroundFallsBackToStaticBlurredArtwork() {
    assertEquals(
      TvPerformanceBackground.STATIC_BLURRED,
      decodeTvPerformanceBackground(null),
    )
    assertEquals(
      TvPerformanceBackground.STATIC_BLURRED,
      decodeTvPerformanceBackground("FUTURE_BACKGROUND"),
    )
  }

  @Test
  fun everyKnownBackgroundRoundTripsByStableName() {
    TvPerformanceBackground.entries.forEach { background ->
      assertEquals(background, decodeTvPerformanceBackground(background.name))
    }
  }
}
