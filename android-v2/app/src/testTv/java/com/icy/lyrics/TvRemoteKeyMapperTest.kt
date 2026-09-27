package com.icy.lyrics

import android.view.KeyEvent
import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.ui.AppDestination
import com.icy.lyrics.ui.LandscapeMode
import com.icy.lyrics.ui.LandscapeRemoteCommand
import com.icy.lyrics.ui.LyricsUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteKeyMapperTest {
  @Test
  fun cleanPlayerMapsLyricsPlayPauseAndDeferredHorizontalTaps() {
    assertEquals(
      TvRemoteAction.Lyrics(LandscapeRemoteCommand.PREVIOUS_LYRIC),
      tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 0),
    )
    assertEquals(
      TvRemoteAction.Lyrics(LandscapeRemoteCommand.NEXT_LYRIC),
      tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 0),
    )
    assertEquals(TvRemoteAction.PlayPause, tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER, 0))
    assertEquals(TvRemoteAction.HorizontalTap(-1), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, 0))
    assertEquals(TvRemoteAction.HorizontalTap(1), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
  }

  @Test
  fun cleanPlayerOnlyRepeatsLyricBrowsing() {
    assertEquals(
      TvRemoteAction.Lyrics(LandscapeRemoteCommand.NEXT_LYRIC),
      tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 4),
    )
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 1))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 4))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeatCount = 1))
    assertTrue(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_UP))
    assertTrue(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN))
    assertFalse(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT))
    assertFalse(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER))
  }

  @Test
  fun overlayControlRowUsesHorizontalFocusAndUpSelectsScrubber() {
    val controls = TvRemoteNavigationState().show()

    assertEquals(TvRemoteAction.MoveControlFocus(-1), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, 0, controls))
    assertEquals(TvRemoteAction.MoveControlFocus(1), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, controls))
    assertEquals(TvRemoteAction.FocusScrubber, tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP, 0, controls))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN, 0, controls))
    assertEquals(TvRemoteAction.ActivateFocusedControl, tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER, 0, controls))
    assertEquals(TvRemoteAction.DismissControls, tvRemoteActionForKey(KeyEvent.KEYCODE_BACK, 0, controls))
    assertFalse(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT, controls))
  }

  @Test
  fun focusedScrubberOnlySeeksHorizontallyAndDownReturnsToControls() {
    val scrubber = TvRemoteNavigationState().show().focusScrubber()

    assertEquals(TvRemoteAction.SeekBy(-5_000L), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, 0, scrubber))
    assertEquals(TvRemoteAction.SeekBy(5_000L), tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, 3, scrubber))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP, 0, scrubber))
    assertEquals(TvRemoteAction.FocusControls, tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN, 0, scrubber))
    assertNull(tvRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER, 0, scrubber))
    assertTrue(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT, scrubber))
    assertTrue(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT, scrubber))
    assertFalse(isRepeatableTvRemoteKey(KeyEvent.KEYCODE_DPAD_UP, scrubber))
  }

  @Test
  fun controlStateUsesIconOrderAndOverlayPersistsWhenBadgeTimesOut() {
    assertEquals(
      listOf(
        TvRemoteControl.PREVIOUS_TRACK,
        TvRemoteControl.PLAY_PAUSE,
        TvRemoteControl.NEXT_TRACK,
        TvRemoteControl.SETTINGS,
      ),
      TvRemoteControl.entries,
    )

    val opened = TvRemoteNavigationState().show()
    assertTrue(opened.overlayVisible)
    assertTrue(opened.sourceBadgeVisible)
    assertEquals(TvRemoteControl.PLAY_PAUSE, opened.focusedControl)

    val previous = opened.moveControlFocus(-1)
    val clampedPrevious = previous.moveControlFocus(-1)
    val next = opened.moveControlFocus(1)
    val settings = next.moveControlFocus(1)
    assertEquals(TvRemoteControl.PREVIOUS_TRACK, clampedPrevious.focusedControl)
    assertEquals(TvRemoteControl.NEXT_TRACK, next.focusedControl)
    assertEquals(TvRemoteControl.SETTINGS, settings.focusedControl)

    val focusedScrubber = settings.focusScrubber()
    assertEquals(TvRemoteFocusTarget.SCRUBBER, focusedScrubber.focusTarget)
    assertNull(focusedScrubber.scrubberPositionMs)
    assertEquals(22_500L, focusedScrubber.updateScrubberPosition(22_500L).scrubberPositionMs)
    assertNull(focusedScrubber.updateScrubberPosition(22_500L).focusControls().scrubberPositionMs)

    val badgeHidden = focusedScrubber.hideSourceBadge()
    assertTrue(badgeHidden.overlayVisible)
    assertFalse(badgeHidden.sourceBadgeVisible)
    assertTrue(badgeHidden.registerInteraction().sourceBadgeVisible)
    assertFalse(badgeHidden.hide().overlayVisible)
  }

  @Test
  fun sameDirectionDoubleTapSkipsTrackWithoutModeStep() {
    val left = TvPendingHorizontalTap(direction = -1, eventTimeMs = 1_000L)
    val right = TvPendingHorizontalTap(direction = 1, eventTimeMs = 2_000L)

    val previous = resolveTvHorizontalSecondDown(left, -1, 1_250L)
    assertTrue(previous.consumeAsDoubleTap)
    assertEquals(TvRemoteAction.PreviousTrack, previous.immediateAction)

    val next = resolveTvHorizontalSecondDown(right, 1, 2_300L)
    assertTrue(next.consumeAsDoubleTap)
    assertEquals(TvRemoteAction.NextTrack, next.immediateAction)
  }

  @Test
  fun expiredOrOppositeSecondTapFlushesOnlyTheOlderModeStep() {
    val left = TvPendingHorizontalTap(direction = -1, eventTimeMs = 1_000L)

    val expired = resolveTvHorizontalSecondDown(left, -1, 1_301L)
    assertFalse(expired.consumeAsDoubleTap)
    assertEquals(TvRemoteAction.StepLandscape(-1), expired.immediateAction)

    val opposite = resolveTvHorizontalSecondDown(left, 1, 1_150L)
    assertFalse(opposite.consumeAsDoubleTap)
    assertEquals(TvRemoteAction.StepLandscape(-1), opposite.immediateAction)
  }

  @Test
  fun menuAndSettingsKeysOpenOverlayFocusedOnSettings() {
    val expected = TvRemoteAction.ShowControls(TvRemoteControl.SETTINGS)
    assertEquals(expected, tvRemoteActionForKey(KeyEvent.KEYCODE_MENU, 0))
    assertEquals(expected, tvRemoteActionForKey(KeyEvent.KEYCODE_SETTINGS, 0))
    assertTrue(isTvPlayerRemoteKey(KeyEvent.KEYCODE_MENU))
    assertTrue(isTvPlayerRemoteKey(KeyEvent.KEYCODE_SETTINGS))
  }

  @Test
  fun backIsCapturedOnlyForOverlayAndRoutesSettingsSubpages() {
    assertFalse(isTvPlayerRemoteKey(KeyEvent.KEYCODE_BACK))
    assertTrue(isTvPlayerRemoteKey(KeyEvent.KEYCODE_BACK, controlsVisible = true))
    assertEquals(AppDestination.PLAYER, tvBackDestination(AppDestination.SETTINGS))
    assertEquals(AppDestination.SETTINGS, tvBackDestination(AppDestination.LIBRARY))
    assertEquals(AppDestination.SETTINGS, tvBackDestination(AppDestination.DEBUG))
    assertEquals(AppDestination.SETTINGS, tvBackDestination(AppDestination.ABOUT_LEGAL))
    assertNull(tvBackDestination(AppDestination.PLAYER))
  }

  @Test
  fun transportAndConfirmKeysHaveStableMappings() {
    assertEquals(TvRemoteAction.PlayPause, tvRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    assertEquals(TvRemoteAction.PreviousTrack, tvRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0))
    assertEquals(TvRemoteAction.NextTrack, tvRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_NEXT, 0))
    assertTrue(isTvConfirmKey(KeyEvent.KEYCODE_DPAD_CENTER))
    assertTrue(isTvConfirmKey(KeyEvent.KEYCODE_ENTER))
    assertFalse(isTvConfirmKey(KeyEvent.KEYCODE_DPAD_LEFT))
    assertEquals(300L, TV_REMOTE_DOUBLE_TAP_WINDOW_MS)
    assertEquals(5_000L, TV_REMOTE_SEEK_STEP_MS)
    assertEquals(3_000L, TV_REMOTE_SOURCE_BADGE_TIMEOUT_MS)
    assertEquals(700L, TV_REMOTE_SCRUBBER_PREVIEW_TIMEOUT_MS)
  }

  @Test
  fun onlyTimedLyricsInALyricsModeCaptureLyricNavigation() {
    val staticLyrics = LyricsUiStatus.Ready(
      StaticLyrics(lines = listOf(StaticLyricLine("untimed"))),
    )
    val timedLyrics = LyricsUiStatus.Ready(
      LineLyrics(lines = listOf(TimedLyricLine("timed", startMs = 0L, endMs = 1_000L))),
    )

    assertFalse(isTimedLyricsRemoteReady(LandscapeMode.LYRICS, staticLyrics))
    assertFalse(
      isTimedLyricsRemoteReady(
        LandscapeMode.LYRICS,
        LyricsUiStatus.Ready(LineLyrics(lines = emptyList())),
      ),
    )
    assertFalse(isTimedLyricsRemoteReady(LandscapeMode.ARTWORK_ONLY, timedLyrics))
    assertFalse(isTimedLyricsRemoteReady(LandscapeMode.LYRICS, LyricsUiStatus.Loading()))
    assertTrue(isTimedLyricsRemoteReady(LandscapeMode.MIXED, timedLyrics))
    assertTrue(isTimedLyricsRemoteReady(LandscapeMode.LYRICS, timedLyrics))
  }
}
