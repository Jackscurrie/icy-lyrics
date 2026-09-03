package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsfileYamlParserTest {
  @Test
  fun `parses official word timed v1 example`() {
    val document = LyricsfileYamlParser.parse(fixture("example.lyricsfile.yaml"), TRACK) as SyllableLyrics
    val lead = document.lines.single().lead

    assertEquals(4_200L, lead.startMs)
    assertEquals(6_800L, lead.endMs)
    assertEquals("Stay until the morning", lead.text)
    assertEquals(listOf(4_200L, 4_800L, 5_400L, 5_750L), lead.tokens.map { it.startMs })
    assertFalse(lead.tokens.first().isPartOfWord)
    assertTrue(lead.tokens.last().isPartOfWord)
    assertEquals("en", document.metadata.language)
    assertEquals(TRACK, document.metadata.trackUri)
  }

  @Test
  fun `format facade recognizes lyricsfile before plain text`() {
    val document = LyricsFileParser.parse(fixture("example.lyricsfile.yaml"), TRACK)
    assertTrue(document is SyllableLyrics)
  }

  @Test
  fun `preserves overlapping line bounds rather than assuming next start`() {
    val yaml = base(
      """
      lines:
        - text: 'first'
          start_ms: 1000
          end_ms: 5000
        - text: 'second'
          start_ms: 2000
          end_ms: 3000
      """,
    )
    val document = LyricsfileYamlParser.parse(yaml, TRACK) as LineLyrics
    assertEquals(listOf(5_000L, 3_000L), document.lines.map { it.endMs })
  }

  @Test
  fun `plain literal keeps blank lines and instrumental is represented explicitly`() {
    val plain = LyricsfileYamlParser.parse(
      base("plain: |\n  Verse one\n\n  Verse two"),
      TRACK,
    ) as StaticLyrics
    assertEquals(listOf("Verse one", "", "Verse two"), plain.lines.map { it.text })

    val instrumental = LyricsfileYamlParser.parse(
      """
      version: '1.0'
      metadata:
        title: 'No vocals'
        artist: 'Example'
        instrumental: true
      """.trimIndent(),
      TRACK,
    ) as StaticLyrics
    assertTrue(instrumental.metadata.instrumental)
    assertTrue(instrumental.lines.isEmpty())
  }

  @Test
  fun `rejects unknown versions duplicate keys tags anchors and aliases`() {
    val invalid = listOf(
      base("plain: hi").replace("'1.0'", "'2.0'"),
      base("plain: one\nplain: two"),
      base("plain: !evil value"),
      base("plain: &shared value"),
      base("plain: *shared"),
    )
    invalid.forEach { yaml ->
      assertThrows(LyricsfileParseException::class.java) {
        LyricsfileYamlParser.parse(yaml, TRACK)
      }
    }
  }

  @Test
  fun `allows indicator characters inside text and rejects type or timing violations`() {
    val punctuation = LyricsfileYamlParser.parse(base("plain: 'Rock & Roll! *really*'"), TRACK) as StaticLyrics
    assertEquals("Rock & Roll! *really*", punctuation.lines.single().text)

    listOf(
      base("lines: nope"),
      base("lines:\n  - text: bad\n    start_ms: -1"),
      base("lines:\n  - text: bad\n    start_ms: 100\n    end_ms: 99"),
      base("plain: ok\nunknown: field"),
      base("plain: ok").replace("title: 'Title'", "title: 42"),
    ).forEach { yaml ->
      assertThrows(LyricsfileParseException::class.java) {
        LyricsfileYamlParser.parse(yaml, TRACK)
      }
    }
  }

  private fun base(body: String): String =
    """
    version: '1.0'
    metadata:
      title: 'Title'
      artist: 'Artist'
    ${body.prependIndent("    ").trimStart()}
    """.trimIndent()

  private fun fixture(name: String): String =
    checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture $name" }.readText()

  companion object {
    private const val TRACK = "spotify:track:yaml"
  }
}
