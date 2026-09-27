package com.icy.lyrics.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimingSliderRemoteNavigationTest {
  @Test
  fun verticalRemoteKeysLeaveTheTimingSliderInsteadOfAdjustingIt() {
    assertEquals(FocusDirection.Up, timingSliderVerticalFocusDirection(Key.DirectionUp))
    assertEquals(FocusDirection.Down, timingSliderVerticalFocusDirection(Key.DirectionDown))
  }

  @Test
  fun horizontalRemoteKeysRemainAvailableToAdjustTiming() {
    assertNull(timingSliderVerticalFocusDirection(Key.DirectionLeft))
    assertNull(timingSliderVerticalFocusDirection(Key.DirectionRight))
  }
}
