package com.icy.lyrics.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppEntryExperienceTest {
  @Test
  fun androidOnboardingCopyMatchesTheSetupInstructions() {
    assertEquals("Allow Notification Access", androidIcyOnboardingCopy.title)
    assertEquals(
      "Icy Lyrics reads Android's \"media player\" notification for current song data. " +
        "It does not collect or take control of any of your data.",
      androidIcyOnboardingCopy.description,
    )
    assertEquals("How do I do that?", androidIcyOnboardingCopy.stepsHeading)
    assertEquals(
      listOf(
        "1. Tap the blue button below to open the settings screen",
        "2. Tap on Icy Lyrics on the app list (you may need to scroll to find it)",
        "3. Turn on the switch next to \"Allow Notification Access\"",
        "4. Tap \"Allow\" on the confirmation window",
        "5. Use Android's back button feature to return here",
      ),
      androidIcyOnboardingCopy.steps,
    )
    assertEquals("Open Notification Access", androidIcyOnboardingCopy.actionLabel)
    assertTrue(androidIcyOnboardingCopy.footer.isEmpty())
  }

  @Test
  fun authorizedColdStartSkipsSuccessAndBeginsStartupExperience() {
    assertEquals(
      AppEntryExperiencePhase.STARTUP,
      initialAppEntryExperiencePhase(notificationAccess = true, launchExperienceEnabled = true),
    )
    assertFalse(
      shouldShowPermissionSuccess(
        previousNotificationAccess = true,
        notificationAccess = true,
        initialAccessObserved = false,
      ),
    )
  }

  @Test
  fun inSessionDeniedToGrantedTransitionShowsSuccess() {
    assertTrue(
      shouldShowPermissionSuccess(
        previousNotificationAccess = false,
        notificationAccess = true,
        initialAccessObserved = true,
      ),
    )
    assertFalse(
      shouldShowPermissionSuccess(
        previousNotificationAccess = false,
        notificationAccess = false,
        initialAccessObserved = true,
      ),
    )
  }

  @Test
  fun pendingSettingsRequestSurvivesRecreationAndShowsSuccess() {
    assertTrue(
      shouldShowPermissionSuccess(
        previousNotificationAccess = true,
        notificationAccess = true,
        initialAccessObserved = false,
        notificationSettingsRequestPending = true,
      ),
    )
  }

  @Test
  fun launchExperienceCanRemainAndroidOnly() {
    assertEquals(
      AppEntryExperiencePhase.CONTENT,
      initialAppEntryExperiencePhase(notificationAccess = true, launchExperienceEnabled = false),
    )
    assertEquals(
      AppEntryExperiencePhase.ONBOARDING,
      initialAppEntryExperiencePhase(notificationAccess = false, launchExperienceEnabled = true),
    )
  }

  @Test
  fun reducedMotionUsesShorterDurations() {
    assertEquals(
      1_180L,
      appEntryPhaseDurationMs(AppEntryExperiencePhase.STARTUP, reducedMotion = false),
    )
    assertTrue(
      appEntryPhaseDurationMs(AppEntryExperiencePhase.SUCCESS, reducedMotion = true) <
        appEntryPhaseDurationMs(AppEntryExperiencePhase.SUCCESS, reducedMotion = false),
    )
    assertTrue(
      appEntryPhaseDurationMs(AppEntryExperiencePhase.STARTUP, reducedMotion = true) <
        appEntryPhaseDurationMs(AppEntryExperiencePhase.STARTUP, reducedMotion = false),
    )
  }

  @Test
  fun startupWordmarkBeginsSmallThenGrowsOutWithAVisibleFade() {
    val beginning = startupWordmarkMotion(arrival = 0f, departure = 0f, reducedMotion = false)
    val centered = startupWordmarkMotion(arrival = 1f, departure = 0f, reducedMotion = false)
    val leaving = startupWordmarkMotion(arrival = 1f, departure = 1f, reducedMotion = false)

    assertTrue(beginning.scale < 0.6f)
    assertTrue(beginning.alpha < 0.2f)
    assertEquals(1f, centered.scale)
    assertEquals(1f, centered.alpha)
    assertTrue(leaving.scale > 1.5f)
    assertEquals(0f, leaving.alpha)
    assertTrue(leaving.outwardDp >= 20f)
  }

  @Test
  fun reducedMotionKeepsTheWordmarkCenteredWithoutZoomOrSplit() {
    val reduced = startupWordmarkMotion(arrival = 1f, departure = 0.5f, reducedMotion = true)

    assertEquals(1f, reduced.scale)
    assertEquals(0.5f, reduced.alpha)
    assertEquals(0f, reduced.outwardDp)
  }
}
