package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {
  @Test
  fun `ordinary TTML unitless time remains seconds`() {
    val lyrics = TtmlParser.parse(
      """<tt><body><div><p begin="1" end="2.5">One line</p></div></body></tt>""",
      "spotify:track:ordinary",
    ) as LineLyrics

    assertEquals(1_000L, lyrics.lines.single().startMs)
    assertEquals(2_500L, lyrics.lines.single().endMs)
  }

  @Test
  fun `AMLL Apple Word dialect treats unitless time as milliseconds`() {
    val lyrics = TtmlParser.parse(fixture("apple-word.ttml"), LOCAL_URI) as SyllableLyrics
    val group = lyrics.lines.single()

    assertEquals(680L, group.lead.startMs)
    assertEquals(1_200L, group.lead.tokens[1].startMs)
    assertEquals(2_400L, group.lead.endMs)
    assertEquals(LOCAL_URI, lyrics.metadata.trackUri)
    assertTrue(group.oppositeAligned)
  }

  @Test
  fun `word trailing space drives IsPartOfWord and text joining`() {
    val lyrics = TtmlParser.parse(fixture("apple-word.ttml"), LOCAL_URI) as SyllableLyrics
    val tokens = lyrics.lines.single().lead.tokens

    assertFalse(tokens[0].isPartOfWord)
    assertTrue(tokens[1].isPartOfWord)
    assertEquals("こんにちは 世界", lyrics.lines.single().lead.text)
  }

  @Test
  fun `v2 background romanization and songwriters survive normalization`() {
    val lyrics = TtmlParser.parse(fixture("apple-word.ttml"), LOCAL_URI) as SyllableLyrics
    val line = lyrics.lines.single()

    assertEquals(listOf("Ada Lovelace", "Grace Hopper"), lyrics.metadata.songwriters)
    assertEquals("ja", lyrics.metadata.language)
    assertTrue(lyrics.metadata.hasTransliterations)
    assertEquals("Konnichiwa sekai", line.lead.transliteratedText)
    assertEquals("Konnichiwa", line.lead.tokens[0].transliteratedText)
    assertEquals("hello", line.background.single().transliteratedText)
    assertFalse(line.background.single().oppositeAligned)
    assertEquals(1_350L, line.background.single().startMs)
    assertEquals(2_200L, line.background.single().endMs)
  }

  @Test
  fun `iTunes key joins word-aligned transliteration sidecar`() {
    val raw = """
      <tt xmlns="http://www.w3.org/ns/ttml"
          xmlns:itunes="http://music.apple.com/lyric"
          itunes:timing="Word">
        <head><metadata>
          <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="en">
              <text for="row-1"><span begin="100" end="200">Kon </span><span begin="200" end="300">nichi</span></text>
            </transliteration></transliterations>
          </iTunesMetadata>
        </metadata></head>
        <body><p itunes:key="row-1" begin="100" end="300">
          <span begin="100" end="200">こん </span><span begin="200" end="300">にち</span>
        </p></body>
      </tt>
    """.trimIndent()
    val lyrics = TtmlParser.parse(raw, "spotify:track:sidecar") as SyllableLyrics
    assertEquals("Kon nichi", lyrics.lines.single().lead.transliteration)
    assertEquals(listOf("Kon", "nichi"), lyrics.lines.single().lead.tokens.map { it.transliteratedText })
  }

  @Test
  fun `parses AMLL Apple Word TTML without a default namespace`() {
    val raw = """
      <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
          xmlns:amll="http://www.example.com/ns/amll"
          xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
          itunes:timing="Word">
        <head><metadata>
          <ttm:agent type="person" xml:id="v1"/>
          <iTunesMetadata><songwriters><songwriter>Example Writer</songwriter></songwriters></iTunesMetadata>
          <amll:meta key="musicName" value="Example Song"/>
        </metadata></head>
        <body dur="00:03.000"><div begin="00:00.250" end="00:03.000">
          <p begin="00:00.250" end="00:02.750" ttm:agent="v1" itunes:key="L1">
            <span begin="00:00.250" end="00:01.250">First </span>
            <span begin="00:01.250" end="00:02.750">line</span>
          </p>
        </div></body>
      </tt>
    """.trimIndent()

    val lyrics = TtmlParser.parse(raw, "spotify:track:namespace-free") as SyllableLyrics

    assertEquals("First line", lyrics.lines.single().lead.text)
    assertEquals(250L, lyrics.lines.single().lead.startMs)
    assertEquals(2_750L, lyrics.lines.single().lead.endMs)
    assertEquals(listOf("Example Writer"), lyrics.metadata.songwriters)
  }

  @Test
  fun `parses static and paragraph timed line TTML`() {
    val static = TtmlParser.parse(
      "<tt><body><p>First</p><p>Second</p></body></tt>",
      "spotify:track:static",
    ) as StaticLyrics
    assertEquals(listOf("First", "Second"), static.lines.map { it.text })

    val line = TtmlParser.parse(
      "<tt><body><p begin=\"00:01.250\" end=\"00:03.000\">Timed</p></body></tt>",
      "spotify:track:line",
    ) as LineLyrics
    assertEquals(1_250L, line.lines.single().startMs)
    assertEquals(3_000L, line.lines.single().endMs)
  }

  @Test
  fun `rejects hostile declarations and malformed entity replacement`() {
    val externalEntity = """
      <!DOCTYPE tt [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <tt><body><p>&xxe;</p></body></tt>
    """.trimIndent()
    assertThrows(UnsafeTtmlException::class.java) {
      TtmlParser.parse(externalEntity, "spotify:track:unsafe")
    }
    assertThrows(TtmlParseException::class.java) {
      TtmlParser.parse("<tt><body><p>bad &#x0; replacement</p></body></tt>", "spotify:track:bad")
    }
  }

  @Test
  fun `rejects invalid clock values and invalid structural replacements`() {
    listOf("-1s", "NaN", "00:60.000", "1::2", "Infinity").forEach { value ->
      assertThrows(TtmlParseException::class.java) { TtmlParser.parseTimeToMilliseconds(value) }
    }
    assertThrows(TtmlParseException::class.java) {
      TtmlParser.parse("<tt><body><p begin=\"oops\">bad</p></body></tt>", "spotify:track:bad-time")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TtmlParser.parse("<tt><body><p>bad id</p></body></tt>", "")
    }
  }

  @Test
  fun `supports frames ticks durations and offset units`() {
    assertEquals(500L, TtmlParser.parseTimeToMilliseconds("15f", frameRate = 30.0))
    assertEquals(2_000L, TtmlParser.parseTimeToMilliseconds("20t", tickRate = 10.0))
    assertEquals(90_000L, TtmlParser.parseTimeToMilliseconds("1.5m"))
    assertEquals(3_723_450L, TtmlParser.parseTimeToMilliseconds("01:02:03.450"))
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture $name" }.readText()

  companion object {
    private const val LOCAL_URI = "spotify:local:Artist:Album:Title:197000"
  }
}
