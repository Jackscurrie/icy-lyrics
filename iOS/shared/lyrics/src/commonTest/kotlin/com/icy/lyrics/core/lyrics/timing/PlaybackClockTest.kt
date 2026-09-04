package com.icy.lyrics.core.lyrics.timing

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class PlaybackClockTest {
  @Test
  fun `extrapolates half and one-and-a-half playback rates`() {
    val slow = PlaybackClock(perceptualLeadMs = 0)
    slow.update(sample(position = 2_000, sampledAt = 1_000, speed = 0.5f))
    assertEquals(3_000L, slow.frameAt(3_000)?.rawPositionMs)

    val fast = PlaybackClock(perceptualLeadMs = 0)
    fast.update(sample(position = 2_000, sampledAt = 1_000, speed = 1.5f))
    assertEquals(5_000L, fast.frameAt(3_000)?.rawPositionMs)
  }

  @Test
  fun `paused samples never extrapolate even with a nonzero speed`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 2_000, sampledAt = 1_000, speed = 1.5f, playing = false))
    assertEquals(2_000L, clock.frameAt(20_000)?.rawPositionMs)
  }

  @Test
  fun `perceptual lead and user delay use desktop sign convention`() {
    val clock = PlaybackClock(perceptualLeadMs = 100)
    clock.update(sample(position = 1_000, sampledAt = 10, playing = false))

    assertEquals(1_100L, clock.frameAt(10, playbackOffsetMs = 0)?.lyricsPositionMs)
    assertEquals(850L, clock.frameAt(10, playbackOffsetMs = 250)?.lyricsPositionMs)
    assertEquals(1_350L, clock.frameAt(10, playbackOffsetMs = -250)?.lyricsPositionMs)
  }

  @Test
  fun `device timing replaces global timing and clamps safely`() {
    val settings = PlaybackTimingSettings(
      globalOffsetMs = 200,
      deviceOffsetsMs = mapOf("buds" to -120, "wild" to 99_000),
    )
    assertEquals(200, settings.resolve(null).effectiveOffsetMs)
    assertEquals(-120, settings.resolve("buds").effectiveOffsetMs)
    assertEquals(5_000, settings.resolve("wild").effectiveOffsetMs)
    assertEquals(200, settings.resolve("unknown").effectiveOffsetMs)
  }

  @Test
  fun `repeated stale playing notifications retain original anchor`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 1_000, sampledAt = 0))
    assertEquals(2_000L, clock.frameAt(1_000)?.rawPositionMs)
    clock.update(sample(position = 1_000, sampledAt = 1_000))
    val later = clock.frameAt(2_000)?.rawPositionMs
    assertTrue(checkNotNull(later) >= 2_900L)
  }

  @Test
  fun `large discontinuity snaps while ordinary jitter is smoothed`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 1_000, sampledAt = 0))
    clock.frameAt(1_000)
    clock.update(sample(position = 8_000, sampledAt = 1_000))
    assertEquals(8_000L, clock.frameAt(1_000)?.rawPositionMs)
  }

  @Test
  fun `seek snaps immediately and stale pre-seek callback is ignored`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 4_000, sampledAt = 4_000))
    clock.frameAt(5_000)

    clock.update(sample(position = 18_000, sampledAt = 5_100))
    assertEquals(18_000L, clock.frameAt(5_100)?.rawPositionMs)

    // This was queued before the seek but delivered afterwards.
    clock.update(sample(position = 4_500, sampledAt = 4_500))
    assertEquals(18_400L, clock.frameAt(5_500)?.rawPositionMs)
  }

  @Test
  fun `playback rate change uses the new rate without smoothing the anchor`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 1_000, sampledAt = 1_000, speed = 1f))
    assertEquals(2_000L, clock.frameAt(2_000)?.rawPositionMs)

    clock.update(sample(position = 2_000, sampledAt = 2_000, speed = 1.5f))
    assertEquals(3_500L, clock.frameAt(3_000)?.rawPositionMs)
  }

  @Test
  fun `pause and resume establish exact new anchors`() {
    val clock = PlaybackClock(perceptualLeadMs = 0)
    clock.update(sample(position = 1_000, sampledAt = 1_000))
    assertEquals(2_000L, clock.frameAt(2_000)?.rawPositionMs)

    clock.update(sample(position = 2_050, sampledAt = 2_100, playing = false))
    assertEquals(2_050L, clock.frameAt(20_000)?.rawPositionMs)

    clock.update(sample(position = 2_050, sampledAt = 20_000, speed = 0.5f))
    assertEquals(2_550L, clock.frameAt(21_000)?.rawPositionMs)
  }

  @Test
  fun `reset clears clock`() {
    val clock = PlaybackClock()
    clock.update(sample(position = 1_000, sampledAt = 0))
    clock.reset()
    assertNull(clock.frameAt(10))
  }

  private fun sample(
    position: Long,
    sampledAt: Long,
    speed: Float = 1f,
    playing: Boolean = true,
  ) = PlaybackSample(
    trackUri = "spotify:track:clock",
    positionMs = position,
    sampledAtMs = sampledAt,
    isPlaying = playing,
    playbackSpeed = speed,
    durationMs = 30_000,
  )
}
