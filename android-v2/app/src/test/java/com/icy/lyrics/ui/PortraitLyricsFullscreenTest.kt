package com.icy.lyrics.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitLyricsFullscreenTest {
  @Test
  fun expandedPortraitLyricsHidePlayerChromeButKeepTheRegularPresentation() {
    val regular = portraitLyricsPresentation(expanded = false)
    val expanded = portraitLyricsPresentation(expanded = true)

    assertTrue(regular.showPlayerChrome)
    assertFalse(expanded.showPlayerChrome)
    assertFalse(regular.focusPresentation)
    assertFalse(expanded.focusPresentation)
  }

  @Test
  fun portraitLyricsToggleHasAnAccessibleDescriptionInBothStates() {
    assertEquals("Expand lyrics", portraitLyricsPresentation(expanded = false).toggleContentDescription)
    assertEquals("Collapse lyrics", portraitLyricsPresentation(expanded = true).toggleContentDescription)
  }
}
