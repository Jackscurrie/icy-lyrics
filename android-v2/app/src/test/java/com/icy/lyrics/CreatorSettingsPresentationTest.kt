package com.icy.lyrics

import android.view.Display
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatorSettingsPresentationTest {
  @Test
  fun lyricCreatorSettingsStayOnThePhoneDisplay() {
    assertTrue(shouldShowLyricCreatorSettings(Display.DEFAULT_DISPLAY))
    assertFalse(shouldShowLyricCreatorSettings(Display.DEFAULT_DISPLAY + 1))
  }
}
