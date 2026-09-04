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
    listOf("9223372036854775808", "0x8000000000000000", "1.5", "1.0", "'1000'").forEach { value ->
      assertFailsWith<LyricsfileParseException>(value) { LyricsfileYamlParser.parse(yaml(value, "9000"), TRACK) }
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
