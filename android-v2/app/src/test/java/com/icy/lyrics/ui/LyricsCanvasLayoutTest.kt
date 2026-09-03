package com.icy.lyrics.ui

import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricsSceneEngine
import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCanvasLayoutTest {
  @Test
  fun `letter ranges stay anchored to the fixed UTF-16 token layout`() {
    assertEquals(
      listOf(4..4, 5..6, 7..7),
      letterCharacterRanges(tokenStart = 4, letters = listOf("A", "🙂", "B")),
    )
  }

  @Test
  fun `three interlude dots each map to a drawable layout range`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(TimedLyricLine("before", 0, 2_000), TimedLyricLine("after", 6_000, 7_000)),
    )
    val interlude = LyricsSceneEngine().frame(lyrics, 3_000).lines
      .single { it.kind == LyricSceneLineKind.INTERLUDE }

    val ranges = interlude.tokenCharacterRanges()

    assertEquals("• • •", interlude.text)
    assertEquals(listOf(0..0, 2..2, 4..4), ranges)
    assertTrue(ranges.all { it.last in interlude.text.indices })
  }

  @Test
  fun `wavy seek track follows a stable sine wave`() {
    assertEquals(0f, wavyTrackOffset(0f, 20f, 4f, 0f), 0.0001f)
    assertEquals(4f, wavyTrackOffset(5f, 20f, 4f, 0f), 0.0001f)
    assertEquals(0f, wavyTrackOffset(10f, 20f, 4f, 0f), 0.0001f)
    assertTrue(wavyTrackOffset(15f, 20f, 4f, 0f) < 0f)
    assertEquals(0f, wavyTrackOffset(5f, 0f, 4f, 0f), 0f)
  }

  @Test
  fun `scrub popup uses a stable tabular time label`() {
    assertEquals("0:00", formatTime(0L))
    assertEquals("2:07", formatTime(127_999L))
    assertEquals("61:01", formatTime(3_661_000L))
  }
}
