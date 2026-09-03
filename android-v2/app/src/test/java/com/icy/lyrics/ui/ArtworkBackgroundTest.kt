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
}
