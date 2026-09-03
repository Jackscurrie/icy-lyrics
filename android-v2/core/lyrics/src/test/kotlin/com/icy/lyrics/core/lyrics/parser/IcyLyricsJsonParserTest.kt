package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcyLyricsJsonParserTest {
  @Test
  fun `recognizes uppercase Provider alias from the desktop schema`() {
    val document = IcyLyricsJsonParser.parse(
      """{"Type":"Static","Provider":"aml","Lines":[{"Text":"Hello"}]}""",
      "spotify:track:wire",
    )

    assertEquals(LyricsSource.APPLE_MUSIC, document.metadata.source)
  }

  @Test
  fun `normalizes wrapped desktop payload seconds aliases background and duet`() {
    val document = IcyLyricsJsonParser.parse(
      """
      {"Result": {
        "Type": "Syllable",
        "source": "spl",
        "SongWriters": ["Writer A", "Writer B"],
        "Content": [{
          "Type": "Vocal",
          "OppositeAligned": true,
          "Lead": {
            "StartTime": 1.25,
            "EndTime": 3.0,
            "Syllables": [
              {"Text":"Hel", "StartTime":1.25, "EndTime":1.5, "IsPartOfWord":true, "RomanizedText":"Her"},
              {"Text":"lo", "StartTime":1.5, "EndTime":2.0, "TransliteratedText":"ro"}
            ]
          },
          "Background": [{
            "StartTime": 2.0,
            "EndTime": 2.8,
            "Syllables": [{"Text":"BG", "StartTime":2.0, "EndTime":2.8}]
          }]
        }]
      }}
      """.trimIndent(),
      "spotify:track:wire",
    ) as SyllableLyrics

    assertEquals(LyricsSource.SPICY, document.metadata.source)
    assertEquals(listOf("Writer A", "Writer B"), document.metadata.songwriters)
    assertTrue(document.lines.single().oppositeAligned)
    assertEquals(1_250L, document.lines.single().lead.startMs)
    assertEquals("Her", document.lines.single().lead.tokens[0].transliteratedText)
    assertEquals("ro", document.lines.single().lead.tokens[1].transliteratedText)
    assertFalse(document.lines.single().background.single().oppositeAligned)
  }
}
