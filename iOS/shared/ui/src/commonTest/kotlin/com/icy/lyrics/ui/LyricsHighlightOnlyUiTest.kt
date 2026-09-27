package com.icy.lyrics.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsHighlightOnlyUiTest {
  @Test
  fun highlightOnlySnapsOrdinaryLineMovementAndAutoFollow() {
    assertTrue(
      shouldSnapLyricsLineMovement(
        remoteResnapPending = false,
        focusPresentation = false,
        reducedMotion = false,
        highlightOnly = true,
        drasticPositionChange = false,
      ),
    )
    assertTrue(shouldSnapLyricsAutoFollow(reducedMotion = false, highlightOnly = true))
  }

  @Test
  fun defaultMotionStillUsesTheExistingAnimationPath() {
    assertFalse(
      shouldSnapLyricsLineMovement(
        remoteResnapPending = false,
        focusPresentation = false,
        reducedMotion = false,
        highlightOnly = false,
        drasticPositionChange = false,
      ),
    )
    assertFalse(shouldSnapLyricsAutoFollow(reducedMotion = false, highlightOnly = false))
  }
}
