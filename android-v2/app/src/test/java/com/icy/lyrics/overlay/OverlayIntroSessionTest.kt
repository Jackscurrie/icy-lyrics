package com.icy.lyrics.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayIntroSessionTest {
  @Test fun firstOpeningShowsTheIntroWithoutEnablingAnything() {
    assertTrue(OverlayIntroSession(alreadySeen = false).visible)
  }

  @Test fun laterOpeningDoesNotRepeatTheIntro() {
    assertFalse(OverlayIntroSession(alreadySeen = true).visible)
  }

  @Test fun closeKeepsTheIntroDismissedInTheCurrentActivitySession() {
    val session = OverlayIntroSession(alreadySeen = false)
    session.dismiss()
    assertFalse(session.visible)
    session.dismiss()
    assertFalse(session.visible)
  }

  @Test fun guideEndsWithExplicitEnableInsteadOfEnablingWhenPermissionIsGranted() {
    assertTrue(overlayPermissionSteps.last().contains("Enable lyrics overlay"))
  }
}
