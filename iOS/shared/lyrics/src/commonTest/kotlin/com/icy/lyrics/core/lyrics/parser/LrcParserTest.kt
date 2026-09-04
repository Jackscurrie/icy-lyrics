package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class LrcParserTest {
  @Test
  fun `parses timestamps fractions duplicates and offset`() {
    val document = LrcParser.parse(
      """
      [offset:-100]
      [00:01.20][00:03.250]Echo
      [00:05]Last
      """.trimIndent(),
      TRACK,
      durationMs = 8_000,
    ) as LineLyrics

    assertEquals(listOf(1_100L, 3_150L, 4_900L), document.lines.map { it.startMs })
    assertEquals(listOf("Echo", "Echo", "Last"), document.lines.map { it.text })
    assertEquals(8_000L, document.lines.last().endMs)
  }

  @Test
  fun `parses enhanced LRC tokens and joins punctuation`() {
    val document = LrcParser.parse(
      "[00:01.00]<00:01.00>Hello<00:01.50>, <00:01.70>world",
      TRACK,
      durationMs = 3_000,
    ) as SyllableLyrics
    val lead = document.lines.single().lead

    assertEquals("Hello, world", lead.text)
    assertTrue(lead.tokens[0].isPartOfWord)
    assertFalse(lead.tokens[1].isPartOfWord)
    assertEquals(listOf(1_000L, 1_500L, 1_700L), lead.tokens.map { it.startMs })
  }

  @Test
  fun `returns null for metadata-only LRC and parses plain text`() {
    assertNull(LrcParser.parse("[ar:Artist]\n[ti:Title]", TRACK))
    val plain = LrcParser.parsePlain(" first \n\n second ", TRACK) as StaticLyrics
    assertEquals(listOf("first", "second"), plain.lines.map { it.text })
  }

  @Test
  fun `clamps a negative offset at track zero`() {
    val document = LrcParser.parse("[offset:-5000]\n[00:01]Starts", TRACK) as LineLyrics
    assertEquals(0L, document.lines.single().startMs)
    assertTrue(document.lines.single().endMs >= 250L)
  }

  companion object {
    private const val TRACK = "spotify:track:lrc"
  }
}
