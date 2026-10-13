package com.icy.lyrics.androidauto

import android.media.session.PlaybackState
import android.support.v4.media.session.PlaybackStateCompat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcyLyricsCarMediaServiceTest {
  @Test
  fun onlyVerifiedTrustedControllersMayReadTheCarMediaBrowser() {
    assertTrue(isTrustedCarController(20, 10, true, true))
    assertTrue(isTrustedCarController(10, 10, true, false))
    assertFalse(isTrustedCarController(20, 10, true, false))
    assertFalse(isTrustedCarController(20, 10, false, true))
    assertFalse(isTrustedCarController(10, 10, false, true))
    assertFalse(isTrustedCarController(-1, 10, true, true))
  }

  @Test
  fun mapsSpotifyFrameworkStateToTheAndroidAutoSession() {
    assertEquals(
      PlaybackStateCompat.STATE_PLAYING,
      carPlaybackState(PlaybackState.STATE_PLAYING),
    )
    assertEquals(
      PlaybackStateCompat.STATE_PAUSED,
      carPlaybackState(PlaybackState.STATE_PAUSED),
    )
    assertEquals(PlaybackStateCompat.STATE_NONE, carPlaybackState(null))
  }

  @Test
  fun exposesStandardDrivingControlsAndOnlySeeksTimedTracks() {
    val untimed = carPlaybackActions(hasDuration = false)
    val timed = carPlaybackActions(hasDuration = true)

    assertTrue(untimed has PlaybackStateCompat.ACTION_PLAY)
    assertTrue(untimed has PlaybackStateCompat.ACTION_PAUSE)
    assertTrue(untimed has PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
    assertTrue(untimed has PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
    assertFalse(untimed has PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
    assertFalse(untimed has PlaybackStateCompat.ACTION_SEEK_TO)
    assertTrue(timed has PlaybackStateCompat.ACTION_SEEK_TO)
  }

  private infix fun Long.has(action: Long): Boolean = this and action != 0L
}
