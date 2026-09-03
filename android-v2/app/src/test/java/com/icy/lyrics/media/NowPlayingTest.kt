package com.icy.lyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
  @Test
  fun interpolationUsesPlaybackSpeed() {
    val snapshot = snapshot(positionMs = 1_000L, speed = 1.5f, capturedAt = 2_000L)
    assertEquals(4_000L, snapshot.currentPositionMs(nowElapsedMs = 4_000L))
  }

  @Test
  fun pausedPositionDoesNotAdvanceAndPlayingPositionClampsToDuration() {
    val paused = snapshot(
      positionMs = 2_000L,
      speed = 1f,
      capturedAt = 1_000L,
      playbackState = PlaybackState.STATE_PAUSED,
    )
    assertEquals(2_000L, paused.currentPositionMs(nowElapsedMs = 9_000L))

    val playing = snapshot(positionMs = 9_500L, capturedAt = 1_000L)
    assertEquals(10_000L, playing.currentPositionMs(nowElapsedMs = 9_000L))
  }

  @Test
  fun invalidFrameworkSpeedFallsBackSafelyForPlaybackSample() {
    val playing = snapshot(speed = Float.NaN)
    val omittedPlayingSpeed = snapshot(speed = 0f)
    val paused = snapshot(speed = Float.POSITIVE_INFINITY, playbackState = PlaybackState.STATE_PAUSED)

    assertEquals(1f, playing.effectivePlaybackSpeed)
    assertEquals(1f, playing.asPlaybackSample().playbackSpeed)
    assertEquals(1f, omittedPlayingSpeed.effectivePlaybackSpeed)
    assertEquals(0f, paused.effectivePlaybackSpeed)
    assertEquals(0f, paused.asPlaybackSample().playbackSpeed)
  }

  @Test
  fun fullSpotifyLocalUriIsTheStorageIdentity() {
    val uri = "spotify:local:Artist:Album:Song:213"
    val identity = snapshot(rawUri = uri).identity
    assertTrue(identity.isSpotifyLocal)
    assertEquals(uri, identity.exactStorageKey)
  }

  @Test
  fun bareSpotifyIdIsAcceptedFromRawMediaId() {
    val id = "5K1m4aaPCxwnm9SKlWW1vh"
    val identity = snapshot(rawUri = null, rawMediaId = id).identity
    assertEquals("spotify:track:$id", identity.exactStorageKey)
  }

  @Test
  fun bareSpotifyIdRequiresTrackSpecificExtrasKey() {
    val id = "5K1m4aaPCxwnm9SKlWW1vh"
    val accepted = snapshot(
      rawUri = null,
      extras = mapOf("com.spotify.track.id" to id),
    ).identity
    val rejected = snapshot(
      rawUri = null,
      extras = mapOf("android.media.random" to id),
    ).identity

    assertEquals("spotify:track:$id", accepted.exactStorageKey)
    assertTrue(rejected.exactStorageKey.startsWith("metadata:"))
  }

  @Test
  fun metadataFallbackUsesStableFiveSecondDurationBucket() {
    val first = snapshot(rawUri = null, durationMs = 418_240L).identity
    val second = snapshot(rawUri = null, durationMs = 418_900L).identity
    assertEquals("metadata:song|artist|album|420000", first.exactStorageKey)
    assertEquals(first.exactStorageKey, second.exactStorageKey)
  }

  private fun snapshot(
    positionMs: Long = 0L,
    speed: Float = 1f,
    capturedAt: Long = 0L,
    rawUri: String? = "spotify:track:2YbNZLoiREBYZo4HeKB8Np",
    rawMediaId: String? = null,
    durationMs: Long = 10_000L,
    extras: Map<String, String> = emptyMap(),
    playbackState: Int = PlaybackState.STATE_PLAYING,
  ) = NowPlayingSnapshot(
    packageName = "com.spotify.music",
    title = "Song",
    artist = "Artist",
    album = "Album",
    durationMs = durationMs,
    positionMs = positionMs,
    playbackSpeed = speed,
    playbackState = playbackState,
    artwork = null,
    capturedAtElapsedMs = capturedAt,
    rawMediaId = rawMediaId,
    rawUri = rawUri,
    extras = extras,
    availableActions = 0L,
  )
}
