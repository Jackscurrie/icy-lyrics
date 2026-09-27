package com.icy.lyrics.creator

import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreatorTtmlCodecTest {
  @Test
  fun generatedTtmlReparsesWithWordBackgroundSpeakerAndMetadataTiming() {
    val lead = CreatorLine(
      tokens = listOf(
        CreatorToken(
          fragments = listOf(
            CreatorFragment(text = "sing", startTimeMs = 0L, endTimeMs = 400L),
            CreatorFragment(text = "ing", startTimeMs = 400L, endTimeMs = 800L),
          ),
          boundaryAfter = " ",
        ),
        CreatorToken(
          fragments = listOf(
            CreatorFragment(text = "<&shine>", startTimeMs = 800L, endTimeMs = 1_200L),
          ),
        ),
      ),
      isSecondSpeaker = true,
    ).synchronizeTiming()
    val background = CreatorLine(
      tokens = listOf(
        CreatorToken(
          fragments = listOf(
            CreatorFragment(text = "echo", startTimeMs = 200L, endTimeMs = 700L),
          ),
        ),
      ),
      isBackground = true,
      attachedToLineId = lead.id,
    ).synchronizeTiming()
    val project = CreatorProject(
      uri = TRACK_URI,
      metadata = CreatorMetadata(
        name = "A & B",
        artists = listOf("Artist"),
        songwriters = listOf("Writer & One"),
        albums = listOf("Album"),
        spotifyTrackId = TRACK_ID,
        appleMusicTrackId = "12345",
        isrc = "USAAA0000001",
        language = "en",
      ),
      lines = listOf(lead, background),
    )

    val raw = CreatorTtmlCodec.encode(project)
    val parsed = TtmlParser.parse(raw, TRACK_URI)

    assertTrue(parsed is SyllableLyrics)
    assertEquals("en", parsed.metadata.language)
    assertEquals(listOf("Writer & One"), parsed.metadata.songwriters)
    assertEquals(1, parsed.lines.size)
    val parsedLine = parsed.lines.single()
    assertTrue(parsedLine.oppositeAligned)
    assertTrue(parsedLine.lead.oppositeAligned)
    assertEquals(listOf("sing", "ing", "<&shine>"), parsedLine.lead.tokens.map { it.text })
    assertEquals(listOf(0L, 400L, 800L), parsedLine.lead.tokens.map { it.startMs })
    assertEquals(listOf(400L, 800L, 1_200L), parsedLine.lead.tokens.map { it.endMs })
    assertEquals(listOf(true, false, true), parsedLine.lead.tokens.map { it.isPartOfWord })
    assertEquals("singing <&shine>", parsedLine.lead.text)
    assertEquals(1, parsedLine.background.size)
    assertEquals("echo", parsedLine.background.single().text)
    assertEquals(200L, parsedLine.background.single().startMs)
    assertEquals(700L, parsedLine.background.single().endMs)
    assertTrue(raw.contains("ttm:role=\"x-bg\""))
    assertTrue(raw.contains("itunes:timing=\"Word\""))
    assertTrue(raw.contains("A &amp; B"))
    assertTrue(raw.contains("xmlns:amll=\"http://www.example.com/ns/amll\""))
    assertTrue(raw.contains("<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">"))
    assertTrue(raw.contains("<amll:meta key=\"musicName\" value=\"A &amp; B\"/>"))
    assertTrue(raw.contains("<amll:meta key=\"artists\" value=\"Artist\"/>"))
    assertTrue(raw.contains("itunes:key=\"L1\""))
    assertTrue(raw.contains("begin=\"0.000s\" end=\"1.200s\""))
    assertTrue(!raw.contains("ms\""))
    assertEquals(TRACK_ID, inspectCreatorTtmlIdentity(raw).spotifyTrackId)
  }

  @Test
  fun codecDecodeRestoresEditableFragmentsAndBackgroundAttachment() {
    val raw = CreatorTtmlCodec.encode(completeProject())
    val decoded = CreatorTtmlCodec.decode(
      raw,
      TRACK_URI,
      metadataHint = CreatorMetadata(name = "Hinted title", artists = listOf("Artist")),
    )

    assertEquals("Hinted title", decoded.metadata.name)
    assertEquals(TRACK_ID, decoded.metadata.spotifyTrackId)
    assertEquals("en", decoded.metadata.language)
    assertEquals(2, decoded.lines.size)
    val lead = decoded.lines[0]
    val background = decoded.lines[1]
    assertEquals(listOf("hel", "lo"), lead.tokens[0].fragments.map { it.text })
    assertEquals(" world", lead.tokens[0].boundaryAfter + lead.tokens[1].text())
    assertTrue(background.isBackground)
    assertEquals(lead.id, background.attachedToLineId)
    assertTrue(background.isSecondSpeaker)
  }

  @Test
  fun codecDecodePreservesDesktopGeneratorSiblingWordSpaces() {
    val raw = """
      <tt xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
          itunes:timing="Word" xmlns="http://www.w3.org/ns/ttml">
        <body><p begin="0s" end="1.2s">
          <span begin="0s" end="0.4s">sing</span><span begin="0.4s" end="0.8s">ing</span> <span begin="0.8s" end="1.2s">shine</span>
        </p></body>
      </tt>
    """.trimIndent()

    val decoded = CreatorTtmlCodec.decode(raw, TRACK_URI)

    assertEquals(listOf(listOf("sing", "ing"), listOf("shine")), decoded.lines.single().tokens.map { token ->
      token.fragments.map(CreatorFragment::text)
    })
    assertEquals("sing\\ing shine", decoded.editorText())
  }

  @Test
  fun desktopStyleMetadataRoundTripsAndOverridesFallbackHints() {
    val project = completeProject().copy(
      metadata = CreatorMetadata(
        name = "File title",
        artists = listOf("File artist one", "File artist two"),
        songwriters = listOf("File writer"),
        albums = listOf("File album"),
        spotifyTrackId = TRACK_ID,
        appleMusicTrackId = "54321",
        isrc = "USAAA0000002",
        language = "en",
        raw = mapOf("customField" to listOf("custom value")),
      ),
    )

    val decoded = CreatorTtmlCodec.decode(
      CreatorTtmlCodec.encode(project),
      TRACK_URI,
      CreatorMetadata(name = "Fallback", artists = listOf("Fallback artist")),
    )

    assertEquals("File title", decoded.metadata.name)
    assertEquals(listOf("File artist one", "File artist two"), decoded.metadata.artists)
    assertEquals(listOf("File writer"), decoded.metadata.songwriters)
    assertEquals(listOf("File album"), decoded.metadata.albums)
    assertEquals(TRACK_ID, decoded.metadata.spotifyTrackId)
    assertEquals("54321", decoded.metadata.appleMusicTrackId)
    assertEquals("USAAA0000002", decoded.metadata.isrc)
    assertEquals(mapOf("customField" to listOf("custom value")), decoded.metadata.raw)
  }

  @Test
  fun identityInspectorAcceptsDesktopAndLegacyMetadataNames() {
    val desktop = """<tt><head><metadata><amll:meta key='spotifyId' value='$TRACK_ID'/></metadata></head></tt>"""
    val legacy = """<tt><head><metadata><meta value='spotify:track:$TRACK_ID' key='spotifyTrackId'/></metadata></head></tt>"""

    assertEquals(TRACK_ID, inspectCreatorTtmlIdentity(desktop).spotifyTrackId)
    assertEquals(TRACK_ID, inspectCreatorTtmlIdentity(legacy).spotifyTrackId)
    assertEquals(null, inspectCreatorTtmlIdentity("<tt/>").spotifyTrackId)
  }

  @Test
  fun arbitraryLyricsDocumentUsesSourceAndMetadataHints() {
    val document = StaticLyrics(
      metadata = LyricsMetadata(
        trackUri = TRACK_URI,
        source = LyricsSource.LRCLIB,
        sourceLabel = "LRCLIB exact match",
        songwriters = listOf("Document writer"),
        language = "fr",
      ),
      lines = listOf(StaticLyricLine("bonjour monde")),
    )

    val project = creatorProjectFromLyricsDocument(
      document = document,
      trackUri = "",
      metadataHint = CreatorMetadata(name = "Song", artists = listOf("Artist")),
    )

    assertEquals(TRACK_URI, project.uri)
    assertEquals(CreatorSource.LRCLIB, project.source.source)
    assertEquals("LRCLIB exact match", project.source.label)
    assertEquals("Song", project.metadata.name)
    assertEquals(listOf("Document writer"), project.metadata.songwriters)
    assertEquals("fr", project.metadata.language)
    assertEquals("bonjour monde", project.lines.single().text())
  }

  @Test
  fun strictValidationRejectsUntimedAndInvalidFragments() {
    val untimed = CreatorProject(
      lines = listOf(
        CreatorLine(tokens = listOf(CreatorToken(fragments = listOf(CreatorFragment(text = "word"))))),
      ),
    )
    val invalid = CreatorProject(
      lines = listOf(
        CreatorLine(
          tokens = listOf(
            CreatorToken(
              fragments = listOf(
                CreatorFragment(text = "word", startTimeMs = 500L, endTimeMs = 500L),
              ),
            ),
          ),
        ),
      ),
    )

    assertTrue(CreatorTtmlCodec.validate(untimed).single().message.contains("fully timed"))
    assertTrue(CreatorTtmlCodec.validate(invalid).single().message.contains("end after"))
    assertFailsWith<CreatorValidationException> { CreatorTtmlCodec.encode(untimed) }
    assertFailsWith<CreatorValidationException> { CreatorTtmlCodec.encode(invalid) }
  }

  @Test
  fun unfinishedDraftExportsAsStaticTtmlWithoutInventingTiming() {
    val cases = listOf(
      CreatorFragment(text = "missing both"),
      CreatorFragment(text = "missing start", endTimeMs = 700L),
      CreatorFragment(text = "missing end", startTimeMs = 300L),
      CreatorFragment(text = "backwards", startTimeMs = 900L, endTimeMs = 400L),
    )

    cases.forEach { fragment ->
      val project = CreatorProject(
        uri = TRACK_URI,
        metadata = CreatorMetadata(name = "Draft", spotifyTrackId = TRACK_ID),
        lines = listOf(
          CreatorLine(tokens = listOf(CreatorToken(fragments = listOf(fragment)))),
        ),
      )

      val exported = CreatorTtmlCodec.encodeForExport(project)

      assertTrue(exported.exportedAsStaticDraft)
      assertTrue(exported.omittedTimingIssueCount > 0)
      assertTrue(exported.rawTtml.contains("itunes:timing=\"None\""))
      assertTrue(exported.rawTtml.contains("icyLyricsCreatorDraftTiming"))
      assertTrue(!exported.rawTtml.contains(" begin=\""))
      assertTrue(!exported.rawTtml.contains(" end=\""))
      val reparsed = TtmlParser.parse(exported.rawTtml, TRACK_URI, LyricsSource.LOCAL_TTML)
      assertEquals(fragment.text, (reparsed as StaticLyrics).lines.single().text)
    }
  }

  @Test
  fun completeExportRemainsDesktopCompatibleWordTimedTtml() {
    val exported = CreatorTtmlCodec.encodeForExport(completeProject())

    assertTrue(!exported.exportedAsStaticDraft)
    assertEquals(0, exported.omittedTimingIssueCount)
    assertTrue(exported.rawTtml.contains("itunes:timing=\"Word\""))
    assertTrue(TtmlParser.parse(exported.rawTtml, TRACK_URI) is SyllableLyrics)
  }

  @Test
  fun structurallyUnsafeDraftStillCannotExport() {
    val project = CreatorProject(
      uri = TRACK_URI,
      lines = listOf(
        CreatorLine(tokens = listOf(CreatorToken(fragments = listOf(CreatorFragment(text = ""))))),
      ),
    )

    assertFailsWith<CreatorValidationException> { CreatorTtmlCodec.encodeForExport(project) }
  }

  @Test
  fun sameLineOverlapBlocksStrictSavePayloadAndUsesStaticDraftExport() {
    val project = CreatorProject(
      uri = TRACK_URI,
      metadata = CreatorMetadata(name = "Overlap", spotifyTrackId = TRACK_ID),
      lines = listOf(
        CreatorLine(
          tokens = listOf(
            CreatorToken(
              fragments = listOf(CreatorFragment(text = "one", startTimeMs = 100L, endTimeMs = 700L)),
              boundaryAfter = " ",
            ),
            CreatorToken(
              fragments = listOf(CreatorFragment(text = "two", startTimeMs = 600L, endTimeMs = 1_000L)),
            ),
          ),
        ).synchronizeTiming(),
      ),
    )

    val issues = CreatorTtmlCodec.validate(project)
    assertTrue(
      issues.any {
        it.kind == CreatorValidationKind.TIMING &&
          it.lineIndex == 0 && it.tokenIndex == 1 &&
          it.message.contains("overlaps the previous timed part")
      },
    )

    // saveToLocalLyrics uses this strict encoder, so an overlap cannot be
    // installed as finished word-synchronised Local lyrics.
    assertFailsWith<CreatorValidationException> { CreatorTtmlCodec.encode(project) }

    val export = CreatorTtmlCodec.encodeForExport(project)
    assertTrue(export.exportedAsStaticDraft)
    assertEquals(1, export.omittedTimingIssueCount)
    assertTrue(export.rawTtml.contains("itunes:timing=\"None\""))
    assertTrue(!export.rawTtml.contains(" begin=\""))
    assertTrue(!export.rawTtml.contains(" end=\""))
    val reparsed = TtmlParser.parse(export.rawTtml, TRACK_URI)
    assertEquals("one two", (reparsed as StaticLyrics).lines.single().text)
  }

  @Test
  fun strictValidationRejectsForwardAndDuplicateBackgroundRows() {
    val firstLead = timedLine("first", 0L, 1_000L)
    val nextLead = timedLine("next", 1_000L, 2_000L)
    val forwardBackground = timedLine("bad", 100L, 500L).copy(
      isBackground = true,
      attachedToLineId = nextLead.id,
    )
    val forwardIssues = CreatorTtmlCodec.validate(
      CreatorProject(lines = listOf(firstLead, forwardBackground, nextLead)),
    )
    assertTrue(forwardIssues.any { it.message.contains("earlier lead") })

    val firstBackground = timedLine("one", 100L, 400L).copy(
      isBackground = true,
      attachedToLineId = firstLead.id,
    )
    val secondBackground = timedLine("two", 500L, 900L).copy(
      isBackground = true,
      attachedToLineId = firstLead.id,
    )
    val duplicateIssues = CreatorTtmlCodec.validate(
      CreatorProject(lines = listOf(firstLead, firstBackground, secondBackground)),
    )
    assertTrue(duplicateIssues.any { it.message.contains("second background row") })
  }

  @Test
  fun invalidXmlControlsAreRejectedBeforeGeneration() {
    val project = CreatorProject(lines = listOf(timedLine("bad\u0000text", 0L, 1_000L)))
    val issues = CreatorTtmlCodec.validate(project)

    assertTrue(issues.any { it.message.contains("invalid XML text") })
    assertFailsWith<CreatorValidationException> { CreatorTtmlCodec.encode(project) }
  }

  private fun completeProject(): CreatorProject {
    val lead = CreatorLine(
      tokens = listOf(
        CreatorToken(
          fragments = listOf(
            CreatorFragment(text = "hel", startTimeMs = 0L, endTimeMs = 250L),
            CreatorFragment(text = "lo", startTimeMs = 250L, endTimeMs = 500L),
          ),
          boundaryAfter = " ",
        ),
        CreatorToken(
          fragments = listOf(
            CreatorFragment(text = "world", startTimeMs = 500L, endTimeMs = 1_000L),
          ),
        ),
      ),
    ).synchronizeTiming()
    val background = timedLine("echo", 200L, 700L).copy(
      isBackground = true,
      isSecondSpeaker = true,
      attachedToLineId = lead.id,
    )
    return CreatorProject(
      uri = TRACK_URI,
      metadata = CreatorMetadata(
        songwriters = listOf("Writer"),
        language = "en",
      ),
      lines = listOf(lead, background),
    )
  }

  private fun timedLine(text: String, startMs: Long, endMs: Long): CreatorLine = CreatorLine(
    tokens = listOf(
      CreatorToken(
        fragments = listOf(
          CreatorFragment(text = text, startTimeMs = startMs, endTimeMs = endMs),
        ),
      ),
    ),
  ).synchronizeTiming()

  private companion object {
    const val TRACK_ID = "0123456789ABCDEFGHIJKL"
    const val TRACK_URI = "spotify:track:$TRACK_ID"
  }
}
