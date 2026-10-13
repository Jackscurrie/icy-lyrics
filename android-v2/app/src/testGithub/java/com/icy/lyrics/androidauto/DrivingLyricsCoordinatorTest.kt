package com.icy.lyrics.androidauto

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.core.lyrics.model.VocalLine
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.IcyLyricsUiState
import com.icy.lyrics.ui.LyricsUiStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DrivingLyricsCoordinatorTest {
  @Test
  fun connectingAfterThePhoneHasLoadedLyricsUsesTheSharedReadyDocument() = runTest {
    val shared = MutableStateFlow(IcyLyricsUiState(
      nowPlaying = snapshot(TRACK),
      lyrics = LyricsUiStatus.Ready(document(TRACK, "Already loaded")),
    ))
    val coordinator = DrivingLyricsCoordinator(shared, backgroundScope) { 1_500L }
    runCurrent()

    assertEquals("Already loaded", coordinator.activeLine.value?.text)
  }

  @Test
  fun staleReadyDocumentCannotFollowANewTrackAndFreshReadyNeedsNoExtraReload() = runTest {
    val nextTrack = "spotify:track:abcdefghijklmnopqrstuv"
    val shared = MutableStateFlow(IcyLyricsUiState(
      nowPlaying = snapshot(TRACK),
      lyrics = LyricsUiStatus.Ready(document(TRACK, "First song")),
    ))
    val coordinator = DrivingLyricsCoordinator(shared, backgroundScope) { 1_500L }
    runCurrent()
    assertEquals("First song", coordinator.activeLine.value?.text)

    shared.value = shared.value.copy(nowPlaying = snapshot(nextTrack))
    runCurrent()
    assertNull(coordinator.activeLine.value)

    // StateFlow can conflate Loading with Ready; a document's exact track
    // identity is sufficient and does not require starting a second resolver.
    shared.value = shared.value.copy(lyrics = LyricsUiStatus.Ready(document(nextTrack, "New song")))
    runCurrent()
    assertEquals("New song", coordinator.activeLine.value?.text)
  }

  @Test
  fun lineSyncedLyricsExposeOnlyTheActuallyActiveLine() {
    val selector = DrivingLyricSelector()
    val lyrics = LineLyrics(
      lines = listOf(
        TimedLyricLine("First line", 1_000, 2_000),
        TimedLyricLine("Second line", 3_000, 4_000),
      ),
    )

    assertNull(selector.select(TRACK, lyrics, 500, 500))
    assertEquals("First line", selector.select(TRACK, lyrics, 1_500, 1_500)?.text)
    assertNull(selector.select(TRACK, lyrics, 2_500, 2_500))
    assertEquals("Second line", selector.select(TRACK, lyrics, 3_500, 3_500)?.text)
  }

  @Test
  fun noteAndBlankPlaceholdersNeverBecomeDrivingLyrics() {
    val selector = DrivingLyricSelector()
    val lyrics = LineLyrics(
      lines = listOf(
        TimedLyricLine("♪", 0, 1_000),
        TimedLyricLine("   ", 1_000, 2_000),
        TimedLyricLine("Actual lyric", 2_000, 3_000),
      ),
    )

    assertNull(selector.select(TRACK, lyrics, 500, 500))
    assertNull(selector.select(TRACK, lyrics, 1_500, 1_500))
    assertEquals("Actual lyric", selector.select(TRACK, lyrics, 2_500, 2_500)?.text)
  }

  @Test
  fun backgroundOnlyVocalIsUsableWithoutPresentationParentheses() {
    val selector = DrivingLyricSelector()
    val lyrics = SyllableLyrics(
      lines = listOf(
        SyllableLyricLine(
          lead = vocal("♪", 1_000, 2_000),
          background = listOf(vocal("(Louder)", 1_100, 1_900)),
        ),
      ),
    )

    assertEquals("Louder", selector.select(TRACK, lyrics, 1_500, 1_500)?.text)
  }

  @Test
  fun activeBackgroundReplacesItsFinishedLead() {
    val selector = DrivingLyricSelector()
    val lyrics = SyllableLyrics(
      lines = listOf(
        SyllableLyricLine(
          lead = vocal("Finished lead", 1_000, 2_000),
          background = listOf(vocal("(Still singing)", 1_500, 3_000)),
        ),
      ),
    )

    assertEquals("Finished lead", selector.select(TRACK, lyrics, 1_750, 1_750)?.text)
    assertEquals("Still singing", selector.select(TRACK, lyrics, 2_500, 2_500)?.text)
    assertNull(selector.select(TRACK, lyrics, 3_500, 3_500))
  }

  @Test
  fun staticLyricsHaveNoDrivingActiveLine() {
    val lyrics = StaticLyrics(lines = listOf(StaticLyricLine("Untimed lyric")))

    assertNull(DrivingLyricSelector().select(TRACK, lyrics, 1_000, 1_000))
  }

  @Test
  fun drivingTextIsFlattenedForTheHostMetadataRow() {
    val lyrics = LineLyrics(
      lines = listOf(TimedLyricLine("One\n   two\tthree", 0, 2_000)),
    )

    assertEquals(
      "One two three",
      DrivingLyricSelector().select(TRACK, lyrics, 1_000, 1_000)?.text,
    )
  }

  @Test
  fun aPreviousTracksLineCannotLeakIntoTheNextTrack() {
    val line = DrivingLyricLine(TRACK, "lead:0", "Old song lyric")

    assertEquals("Old song lyric", drivingLyricTextForTrack(line, TRACK))
    assertNull(drivingLyricTextForTrack(line, "spotify:track:abcdefghijklmnopqrstuv"))
    assertNull(drivingLyricTextForTrack(line, null))
  }

  private fun vocal(text: String, startMs: Long, endMs: Long) = VocalLine(
    startMs = startMs,
    endMs = endMs,
    tokens = listOf(LyricToken(text, startMs, endMs)),
  )

  private companion object {
    const val TRACK = "spotify:track:0123456789012345678901"

    fun document(trackKey: String, text: String) = LineLyrics(
      metadata = LyricsMetadata(trackUri = trackKey),
      lines = listOf(TimedLyricLine(text, 1_000, 2_000)),
    )

    fun snapshot(trackKey: String) = NowPlayingSnapshot(
      packageName = "com.spotify.music", title = "Song", artist = "Artist", album = "Album",
      durationMs = 10_000L, positionMs = 1_500L, playbackSpeed = 1f, playbackState = 3,
      artwork = null, capturedAtElapsedMs = 1_500L, rawMediaId = null,
      rawUri = trackKey, extras = emptyMap(), availableActions = 0L,
    )
  }
}
