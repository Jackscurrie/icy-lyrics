package com.icy.lyrics.androidauto

import android.os.SystemClock
import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricsSceneEngine
import com.icy.lyrics.core.lyrics.animation.LyricsSceneOptions
import com.icy.lyrics.core.lyrics.animation.TimedElementStatus
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.timing.PlaybackClock
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.IcyLyricsUiState
import com.icy.lyrics.ui.LyricsUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One real, currently audible lyric line for Android Auto's driving surface. */
internal data class DrivingLyricLine(
  val trackKey: String,
  val sceneKey: String,
  val text: String,
)

/**
 * Uses the same normalized scene engine as the phone UI, but deliberately
 * disables generated interludes. Android Auto receives one vocal line or no
 * lyric at all; it never receives previous/next rows, dots, or note markers.
 */
internal class DrivingLyricSelector {
  private val sceneEngine = LyricsSceneEngine()

  fun reset() = sceneEngine.reset()

  fun select(
    trackKey: String,
    document: LyricsDocument?,
    lyricsPositionMs: Long,
    rawPositionMs: Long,
  ): DrivingLyricLine? {
    if (document == null || document is StaticLyrics) return null
    val scene = sceneEngine.frame(
      document = document,
      lyricsPositionMs = lyricsPositionMs.coerceAtLeast(0L),
      rawPositionMs = rawPositionMs.coerceAtLeast(0L),
      options = LyricsSceneOptions(synthesizeInterludes = false),
    )
    // Focus anchors intentionally resolve background vocals to their group's
    // lead for the phone layout. Driving mode needs the actually audible line:
    // prefer an active lead, then use an active background when no lead is
    // currently playing.
    val line = scene.lines.firstOrNull {
      it.kind == LyricSceneLineKind.VOCAL && it.status == TimedElementStatus.ACTIVE
    } ?: scene.lines.firstOrNull {
      it.kind == LyricSceneLineKind.BACKGROUND && it.status == TimedElementStatus.ACTIVE
    } ?: return null
    val text = line.text
      .trim()
      .replace(WHITESPACE, " ")
      .take(MAX_DRIVING_LYRIC_CHARS)
      .takeIf(String::isNotEmpty)
      ?: return null
    return DrivingLyricLine(trackKey, line.key, text)
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")
    const val MAX_DRIVING_LYRIC_CHARS = 500
  }
}

/**
 * Observes the same phone session used by the Activity and floating overlay.
 * This surface never creates a resolver or issues another lyrics request.
 * Metadata consumers are notified only when the active line changes, so
 * Android Auto is not refreshed per word.
 */
internal class DrivingLyricsCoordinator(
  private val state: StateFlow<IcyLyricsUiState>,
  private val scope: CoroutineScope,
  private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
  private val playbackClock = PlaybackClock()
  private val selector = DrivingLyricSelector()
  private val mutableActiveLine = MutableStateFlow<DrivingLyricLine?>(null)
  private var currentTrackKey: String? = null
  private var acceptedDocument: LyricsDocument? = null
  private var canAcceptReadyDocument = false
  private var latestSnapshot: NowPlayingSnapshot? = null
  private var latestSample: com.icy.lyrics.core.lyrics.timing.PlaybackSample? = null
  private var timingOffsetMs = 0

  val activeLine: StateFlow<DrivingLyricLine?> = mutableActiveLine.asStateFlow()

  init {
    scope.launch {
      state.collect(::onState)
    }
    scope.launch {
      while (isActive) {
        refreshActiveLine()
        delay(if (latestSnapshot?.isPlaying == true) PLAYING_TICK_MS else IDLE_TICK_MS)
      }
    }
  }

  private fun onState(state: IcyLyricsUiState) {
    val snapshot = state.nowPlaying
    val trackKey = snapshot?.identity?.exactStorageKey
    if (trackKey != currentTrackKey) {
      currentTrackKey = trackKey
      acceptedDocument = null
      canAcceptReadyDocument = false
      latestSample = null
      playbackClock.reset()
      selector.reset()
      mutableActiveLine.value = null
    }

    latestSnapshot = snapshot
    snapshot?.asPlaybackSample()?.let { sample ->
      if (sample != latestSample) {
        playbackClock.update(sample)
        latestSample = sample
      }
    }
    timingOffsetMs = state.settings.effectiveTimingOffsetMs

    when (val lyrics = state.lyrics) {
      is LyricsUiStatus.Loading -> {
        canAcceptReadyDocument = true
        if (!lyrics.retainingPrevious) acceptedDocument = null
      }
      is LyricsUiStatus.Ready -> {
        val documentTrackKey = lyrics.document.metadata.trackUri
        // A car may connect after the phone has already loaded lyrics. Accept
        // that bound document immediately, but never a stale previous-track
        // Ready emission while the controller catches up with a new snapshot.
        acceptedDocument = lyrics.document.takeIf {
          trackKey != null && (
            documentTrackKey == trackKey ||
              (documentTrackKey == null && canAcceptReadyDocument)
            )
        }
      }
      LyricsUiStatus.Idle,
      is LyricsUiStatus.Empty,
      is LyricsUiStatus.Failed,
      -> acceptedDocument = null
    }
    refreshActiveLine()
  }

  private fun refreshActiveLine() {
    val trackKey = currentTrackKey
    val snapshot = latestSnapshot
    val frame = playbackClock.frameAt(elapsedRealtime(), timingOffsetMs)
    mutableActiveLine.value = if (
      trackKey == null ||
      snapshot == null ||
      frame == null ||
      frame.trackUri != trackKey
    ) {
      null
    } else {
      selector.select(
        trackKey = trackKey,
        document = acceptedDocument,
        lyricsPositionMs = frame.lyricsPositionMs,
        rawPositionMs = frame.rawPositionMs,
      )
    }
  }

  private companion object {
    const val PLAYING_TICK_MS = 100L
    const val IDLE_TICK_MS = 500L
  }
}
