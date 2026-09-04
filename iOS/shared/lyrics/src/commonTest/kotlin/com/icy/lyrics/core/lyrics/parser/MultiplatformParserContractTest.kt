package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MultiplatformParserContractTest {
  @Test
  fun xmlPreservesMixedTextCdataEntitiesAndNamespaceRoles() {
    val xml = """
      <lyric:tt xmlns:lyric="http://www.w3.org/ns/ttml"
        xmlns:meta="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
        <lyric:body><lyric:p>Hello <lyric:span>wide</lyric:span> <![CDATA[world & ]]><lyric:span>friends</lyric:span> &amp; &#x1F3B5;<lyric:span meta:role="x-translation">hidden</lyric:span></lyric:p></lyric:body>
      </lyric:tt>
    """.trimIndent()
    val document = TtmlParser.parse(xml, TRACK) as StaticLyrics
    assertEquals("Hello wide world & friends & \uD83C\uDFB5", document.lines.single().text)
    assertEquals("en", document.metadata.language)
  }

  @Test
  fun xmlRejectsMalformedOrUnresolvedInputBeforeModelConstruction() {
    listOf(
      "<tt><p>&notDeclared;</p></tt>",
      "<tt><p>&#xD800;</p></tt>",
      "<tt><p>\u0001</p></tt>",
      "<tt><p>x</tt>",
      "<tt><p begin='1s' begin='2s'>x</p></tt>",
      "<tt><p a:x='one' b:x='two' xmlns:a='urn:same' xmlns:b='urn:same'>x</p></tt>",
      "<tt><missing:p>x</missing:p></tt>",
      "<tt><p>one</p></tt><tt><p>two</p></tt>",
    ).forEach { xml -> assertFailsWith<TtmlParseException>(xml) { TtmlParser.parse(xml, TRACK) } }
  }

  @Test
  fun xmlEnforcesTreeLimitsWhileReading() {
    assertFailsWith<UnsafeTtmlException> {
      TtmlParser.parse("<tt>" + "<span>".repeat(70) + "<p>x</p>" + "</span>".repeat(70) + "</tt>", TRACK)
    }
    assertFailsWith<UnsafeTtmlException> {
      TtmlParser.parse("<tt><p>x</p>" + "<span/>".repeat(50_000) + "</tt>", TRACK)
    }
    assertFailsWith<UnsafeTtmlException> { TtmlParser.parse(" ".repeat(2_000_000) + "<tt/>", TRACK) }
  }

  @Test
  fun yamlKeepsCoreSchemaIntegersExactAndRejectsOverflowAndFloatingTiming() {
    val document = LyricsfileYamlParser.parse(yaml("0x400", "0o4000"), TRACK) as LineLyrics
    assertEquals(1024L, document.lines.single().startMs)
    assertEquals(2048L, document.lines.single().endMs)
    listOf(
      "9223372036854775808", "-9223372036854775809", "0x8000000000000000", "0o1000000000000000000000",
      "9999999999999999999999999999999999999999", "1.5", "1.0", "'1000'",
    ).forEach { value ->
      assertFailsWith<LyricsfileParseException>(value) { LyricsfileYamlParser.parse(yaml(value, "9000"), TRACK) }
    }
  }

  @Test
  fun yamlPreservesLongBoundsAndIntegersBeyondFloatingPointPrecision() {
    val document = LyricsfileYamlParser.parse(
      yaml("9007199254740993", "9007199254740994")
        .replace("artist: 'Artist'", "artist: 'Artist', duration_ms: 9223372036854775807, offset_ms: -9223372036854775808"),
      TRACK,
    ) as LineLyrics
    assertEquals(9_007_199_254_740_993L, document.lines.single().startMs)
    assertEquals(9_007_199_254_740_994L, document.lines.single().endMs)
    listOf("9223372036854775807", "0x7fffffffffffffff", "0o777777777777777777777").forEach { end ->
      val boundary = LyricsfileYamlParser.parse(yaml("9223372036854775806", end), TRACK) as LineLyrics
      assertEquals(Long.MAX_VALUE - 1, boundary.lines.single().startMs)
      assertEquals(Long.MAX_VALUE, boundary.lines.single().endMs)
    }
  }

  @Test
  fun yamlRetainsCoreSchemaIntegerSpellings() {
    listOf("+1024", "001024", "0x400", "0o2000").forEach { start ->
      val document = LyricsfileYamlParser.parse(yaml(start, "+2048"), TRACK) as LineLyrics
      assertEquals(1024L, document.lines.single().startMs)
      assertEquals(2048L, document.lines.single().endMs)
    }
    val zero = LyricsfileYamlParser.parse(yaml("-0", "1"), TRACK) as LineLyrics
    assertEquals(0L, zero.lines.single().startMs)
    listOf(
      "+0x400", "-0x400", "+0o2000", "-0o2000", "0b10000000000", "1_024", "0x4_00", "0o2_000",
    ).forEach { value ->
      assertFailsWith<LyricsfileParseException>(value) { LyricsfileYamlParser.parse(yaml(value, "9000"), TRACK) }
    }
  }

  @Test
  fun yamlRejectsFloatingScalarsInEveryTimingField() {
    listOf("1.0", "0.0", "-0.0", "1e3", ".nan", ".inf", "-.inf").forEach { value ->
      val documents = mapOf(
        "metadata.duration_ms" to yaml("0", "9000").replace("artist: 'Artist'", "artist: 'Artist', duration_ms: $value"),
        "metadata.offset_ms" to yaml("0", "9000").replace("artist: 'Artist'", "artist: 'Artist', offset_ms: $value"),
        "line.start_ms" to yaml(value, "9000"),
        "line.end_ms" to yaml("0", value),
        "word.start_ms" to yaml("0", "9000").replace("end_ms: 9000}", "end_ms: 9000, words: [{text: 'word', start_ms: $value, end_ms: 9000}]}"),
        "word.end_ms" to yaml("0", "9000").replace("end_ms: 9000}", "end_ms: 9000, words: [{text: 'word', start_ms: 0, end_ms: $value}]}"),
      )
      documents.forEach { (field, raw) ->
        assertFailsWith<LyricsfileParseException>("$field: $value") { LyricsfileYamlParser.parse(raw, TRACK) }
      }
    }
  }

  @Test
  fun yamlRequiresExactlyOneDocumentAndPreservesFlowMappingsAndFoldedScalars() {
    val document = LyricsfileYamlParser.parse("""
      version: '1.0'
      metadata: {title: 'Title', artist: 'Artist'}
      plain: >-
        Hello
        world
    """.trimIndent(), TRACK) as StaticLyrics
    assertEquals("Hello world", document.lines.single().text)
    assertFailsWith<LyricsfileParseException> {
      LyricsfileYamlParser.parse(yaml("0", "1000") + "\n---\n" + yaml("0", "1000"), TRACK)
    }
  }

  private fun yaml(start: String, end: String) = """
    version: '1.0'
    metadata: {title: 'Title', artist: 'Artist'}
    lines:
      - {text: 'word', start_ms: $start, end_ms: $end}
  """.trimIndent()

  companion object { private const val TRACK = "spotify:track:multiplatform" }
}
