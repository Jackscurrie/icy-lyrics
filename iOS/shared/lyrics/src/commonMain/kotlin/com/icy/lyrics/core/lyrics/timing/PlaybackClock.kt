package com.icy.lyrics.core.lyrics.timing

import kotlinx.serialization.Serializable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

@Serializable
data class PlaybackSample(
  val trackUri: String,
  val positionMs: Long,
  /** Monotonic elapsed-realtime timestamp associated with [positionMs]. */
  val sampledAtMs: Long,
  val isPlaying: Boolean,
  val playbackSpeed: Float = 1f,
  val durationMs: Long? = null,
) {
  init {
    require(trackUri.isNotBlank()) { "Track URI must not be blank" }
    require(positionMs >= 0L) { "Playback position must not be negative" }
    require(sampledAtMs >= 0L) { "Playback sample time must not be negative" }
    require(playbackSpeed.isFinite() && playbackSpeed >= 0f) {
      "Playback speed must be finite and non-negative"
    }
    require(durationMs == null || durationMs >= 0L) { "Duration must not be negative" }
  }
}

@Serializable
data class PlaybackTimingSettings(
  val globalOffsetMs: Int = 0,
  val deviceOffsetsMs: Map<String, Int> = emptyMap(),
) {
  fun resolve(outputDeviceId: String?): ResolvedTimingOffset {
    val deviceValue = outputDeviceId?.let(deviceOffsetsMs::get)
    return ResolvedTimingOffset(
      globalOffsetMs = globalOffsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS),
      outputDeviceId = outputDeviceId,
      deviceOffsetMs = deviceValue?.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS),
    )
  }

  companion object {
    const val MIN_OFFSET_MS = -5_000
    const val MAX_OFFSET_MS = 5_000
  }
}

@Serializable
data class ResolvedTimingOffset(
  val globalOffsetMs: Int,
  val outputDeviceId: String?,
  /** A device value replaces, rather than adds to, the global setting. */
  val deviceOffsetMs: Int?,
) {
  val effectiveOffsetMs: Int
    get() = deviceOffsetMs ?: globalOffsetMs
}

data class PlaybackClockFrame(
  val trackUri: String,
  val rawPositionMs: Long,
  val lyricsPositionMs: Long,
  val durationMs: Long?,
  val isPlaying: Boolean,
  val perceptualLeadMs: Long,
  val playbackOffsetMs: Int,
)

/**
 * Stable lyric clock ported from desktop Icy Lyrics.
 *
 * The class accepts externally timestamped MediaSession samples, extrapolates stale-but-playing
 * samples, snaps real discontinuities, and gently removes ordinary position jitter. Callers must
 * pass the same monotonic clock domain to [update] and [frameAt].
 */
class PlaybackClock(
  private val perceptualLeadMs: Long = DEFAULT_PERCEPTUAL_LEAD_MS,
  private val jitterSnapThresholdMs: Long = JITTER_SNAP_THRESHOLD_MS,
  private val jitterTimeConstantMs: Double = JITTER_TIME_CONSTANT_MS,
  private val sampleDiscontinuityThresholdMs: Long = SAMPLE_DISCONTINUITY_THRESHOLD_MS,
) {
  private val lock = SynchronizedObject()
  private var anchor: PlaybackSample? = null
  private var latestSample: PlaybackSample? = null
  private var predicted: Prediction? = null

  fun update(sample: PlaybackSample): Unit = synchronized(lock) {
    val previous = latestSample

    // Controller callbacks can be queued around a seek or route change. An
    // older sample from the same running state must not replace a newer anchor
    // and momentarily rewind the UI. State/rate changes are still accepted so
    // pause and resume can never be hidden by this guard.
    if (
      previous != null &&
      previous.trackUri == sample.trackUri &&
      previous.isPlaying == sample.isPlaying &&
      previous.playbackSpeed == sample.playbackSpeed &&
      sample.sampledAtMs < previous.sampledAtMs
    ) {
      return
    }
    latestSample = sample

    val currentAnchor = anchor
    val trackChanged = currentAnchor?.trackUri != sample.trackUri
    val playbackStateChanged = currentAnchor?.isPlaying != sample.isPlaying
    val playbackRateChanged = currentAnchor?.playbackSpeed != sample.playbackSpeed
    val stateDiscontinuity = previous?.let {
      if (it.trackUri != sample.trackUri || !it.isPlaying || !sample.isPlaying) false
      else {
        val expected = it.positionMs +
          ((sample.sampledAtMs - it.sampledAtMs).coerceAtLeast(0L) * it.playbackSpeed).roundToLong()
        abs(sample.positionMs - expected) > sampleDiscontinuityThresholdMs
      }
    } ?: false

    // Repeated identical samples from some players are stale. Retain the first timestamp so the
    // lyric clock continues to advance instead of being pinned to zero on every notification.
    val isDistinct = currentAnchor?.positionMs != sample.positionMs
    if (currentAnchor == null || trackChanged || playbackStateChanged || playbackRateChanged || stateDiscontinuity ||
      isDistinct || !sample.isPlaying
    ) {
      anchor = sample
    }

    if (
      trackChanged || playbackStateChanged || playbackRateChanged ||
      stateDiscontinuity || !sample.isPlaying
    ) {
      predicted = null
    }
  }

  fun reset(): Unit = synchronized(lock) {
    anchor = null
    latestSample = null
    predicted = null
  }

  fun frameAt(nowMs: Long, playbackOffsetMs: Int = 0): PlaybackClockFrame? = synchronized(lock) {
    require(nowMs >= 0L) { "Clock time must not be negative" }
    val current = anchor ?: return null
    val rawMeasured = measuredPosition(current, nowMs)
    val rawStable = normalize(rawMeasured, current, nowMs)
    val safeOffset = playbackOffsetMs.coerceIn(
      PlaybackTimingSettings.MIN_OFFSET_MS,
      PlaybackTimingSettings.MAX_OFFSET_MS,
    )
    val lyricsPosition = (rawStable + perceptualLeadMs - safeOffset)
      .coerceAtLeast(0L)
      .coerceAtMost(current.durationMs ?: Long.MAX_VALUE)

    return PlaybackClockFrame(
      trackUri = current.trackUri,
      rawPositionMs = rawStable,
      lyricsPositionMs = lyricsPosition,
      durationMs = current.durationMs,
      isPlaying = current.isPlaying,
      perceptualLeadMs = perceptualLeadMs,
      playbackOffsetMs = safeOffset,
    )
  }

  private fun measuredPosition(sample: PlaybackSample, nowMs: Long): Long {
    val elapsed = if (sample.isPlaying) {
      ((nowMs - sample.sampledAtMs).coerceAtLeast(0L) * sample.playbackSpeed).roundToLong()
    } else 0L
    return clampToTrack(sample.positionMs + elapsed, sample.durationMs)
  }

  private fun normalize(measured: Long, sample: PlaybackSample, nowMs: Long): Long {
    val previous = predicted
    if (previous == null || previous.trackUri != sample.trackUri || !sample.isPlaying) {
      predicted = Prediction(sample.trackUri, measured.toDouble(), nowMs)
      return measured
    }

    val elapsed = (nowMs - previous.updatedAtMs).coerceAtLeast(0L)
    var value = previous.positionMs + elapsed * sample.playbackSpeed
    val error = measured - value
    value = if (abs(error) > jitterSnapThresholdMs) {
      measured.toDouble()
    } else {
      val alpha = 1.0 - exp(-elapsed / jitterTimeConstantMs.coerceAtLeast(1.0))
      value + error * alpha
    }
    val stable = clampToTrack(value.roundToLong(), sample.durationMs)
    predicted = Prediction(sample.trackUri, stable.toDouble(), nowMs)
    return stable
  }

  private fun clampToTrack(positionMs: Long, durationMs: Long?): Long =
    positionMs.coerceAtLeast(0L).let { if (durationMs != null && durationMs > 0L) it.coerceAtMost(durationMs) else it }

  private data class Prediction(val trackUri: String, val positionMs: Double, val updatedAtMs: Long)

  companion object {
    const val DEFAULT_PERCEPTUAL_LEAD_MS = 100L
    const val JITTER_SNAP_THRESHOLD_MS = 500L
    const val JITTER_TIME_CONSTANT_MS = 300.0
    const val SAMPLE_DISCONTINUITY_THRESHOLD_MS = 1_000L
  }
}
