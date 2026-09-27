package com.icy.lyrics.creator

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken as ParsedLyricToken
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource as ParsedLyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.VocalLine
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import java.util.Locale

data class CreatorValidationIssue(
  val lineIndex: Int,
  val tokenIndex: Int? = null,
  val fragmentIndex: Int? = null,
  val message: String,
  val kind: CreatorValidationKind = CreatorValidationKind.STRUCTURE,
)

enum class CreatorValidationKind {
  STRUCTURE,
  TIMING,
}

data class CreatorTtmlExportResult(
  val rawTtml: String,
  /** True when unfinished timing was deliberately omitted rather than fabricated. */
  val exportedAsStaticDraft: Boolean,
  val omittedTimingIssueCount: Int,
)

data class CreatorEmbeddedTrackIdentity(
  val spotifyTrackId: String? = null,
  val appleMusicTrackId: String? = null,
)

class CreatorValidationException(
  val issues: List<CreatorValidationIssue>,
) : IllegalArgumentException(issues.firstOrNull()?.message ?: "The lyric project is not ready to export.")

object CreatorTtmlCodec {
  fun validate(project: CreatorProject): List<CreatorValidationIssue> {
    val issues = mutableListOf<CreatorValidationIssue>()
    if (project.version != 1) {
      issues += CreatorValidationIssue(-1, message = "Unsupported lyric project version ${project.version}.")
    }
    if (project.lines.isEmpty()) {
      issues += CreatorValidationIssue(-1, message = "The lyric project has no lines.")
    }

    val earlierLeadIds = linkedSetOf<String>()
    val backgroundsByLead = mutableMapOf<String, Int>()
    val allLineIds = mutableSetOf<String>()
    var lastLeadId: String? = null

    project.lines.forEachIndexed { lineIndex, line ->
      if (!allLineIds.add(line.id)) {
        issues += CreatorValidationIssue(lineIndex, message = "Line ${lineIndex + 1} has a duplicate ID.")
      }

      if (line.isBackground) {
        val targetId = line.attachedToLineId ?: lastLeadId
        when {
          targetId == null -> issues += CreatorValidationIssue(
            lineIndex,
            message = "Background line ${lineIndex + 1} is not attached to a lead line.",
          )

          targetId !in earlierLeadIds -> issues += CreatorValidationIssue(
            lineIndex,
            message = "Background line ${lineIndex + 1} must reference an earlier lead line.",
          )

          else -> {
            val count = backgroundsByLead.getOrDefault(targetId, 0) + 1
            backgroundsByLead[targetId] = count
            if (count > 1) {
              issues += CreatorValidationIssue(
                lineIndex,
                message = "Line ${lineIndex + 1} is a second background row for the same lead.",
              )
            }
          }
        }
      } else {
        earlierLeadIds += line.id
        lastLeadId = line.id
      }

      if (line.tokens.isEmpty()) {
        issues += CreatorValidationIssue(lineIndex, message = "Line ${lineIndex + 1} has no words.")
      }
      var previousFragment: CreatorFragment? = null
      line.tokens.forEachIndexed { tokenIndex, token ->
        if (token.fragments.isEmpty()) {
          issues += CreatorValidationIssue(
            lineIndex,
            tokenIndex,
            message = "Line ${lineIndex + 1}, word ${tokenIndex + 1} has no fragments.",
          )
        }
        if (containsInvalidXmlCharacter(token.boundaryAfter)) {
          issues += CreatorValidationIssue(
            lineIndex,
            tokenIndex,
            message = "Line ${lineIndex + 1}, word ${tokenIndex + 1} contains invalid XML text.",
          )
        }
        token.fragments.forEachIndexed { fragmentIndex, fragment ->
          val location = "Line ${lineIndex + 1}, word ${tokenIndex + 1}"
          when {
            fragment.text.isEmpty() -> issues += CreatorValidationIssue(
              lineIndex,
              tokenIndex,
              fragmentIndex,
              "$location contains an empty fragment.",
            )

            containsInvalidXmlCharacter(fragment.text) -> issues += CreatorValidationIssue(
              lineIndex,
              tokenIndex,
              fragmentIndex,
              "$location contains invalid XML text.",
            )

            fragment.startTimeMs == null || fragment.endTimeMs == null ->
              issues += CreatorValidationIssue(
                lineIndex,
                tokenIndex,
                fragmentIndex,
                "$location has not been fully timed.",
                CreatorValidationKind.TIMING,
              )

            fragment.startTimeMs < 0L -> issues += CreatorValidationIssue(
              lineIndex,
              tokenIndex,
              fragmentIndex,
              "$location cannot start before zero.",
              CreatorValidationKind.TIMING,
            )

            fragment.endTimeMs <= fragment.startTimeMs -> issues += CreatorValidationIssue(
              lineIndex,
              tokenIndex,
              fragmentIndex,
              "$location must end after it starts.",
              CreatorValidationKind.TIMING,
            )
          }
          val start = fragment.startTimeMs
          val end = fragment.endTimeMs
          val previousStart = previousFragment?.startTimeMs
          val previousEnd = previousFragment?.endTimeMs
          if (
            start != null && end != null && start >= 0L && end > start &&
            previousStart != null && previousEnd != null &&
            previousStart >= 0L && previousEnd > previousStart &&
            start < previousEnd
          ) {
            issues += CreatorValidationIssue(
              lineIndex,
              tokenIndex,
              fragmentIndex,
              "$location overlaps the previous timed part on this line.",
              CreatorValidationKind.TIMING,
            )
          }
          previousFragment = fragment
        }
      }
    }

    val metadataText = buildList {
      add(project.metadata.name)
      addAll(project.metadata.artists)
      addAll(project.metadata.songwriters)
      addAll(project.metadata.albums)
      add(project.metadata.spotifyTrackId)
      add(project.metadata.appleMusicTrackId)
      add(project.metadata.isrc)
      add(project.metadata.language)
      project.metadata.raw.forEach { (key, values) -> add(key); addAll(values) }
    }
    if (metadataText.any(::containsInvalidXmlCharacter)) {
      issues += CreatorValidationIssue(-1, message = "Track metadata contains invalid XML text.")
    }
    return issues
  }

  fun encode(project: CreatorProject): String {
    val issues = validate(project)
    if (issues.isNotEmpty()) throw CreatorValidationException(issues)

    val backgroundByLead = mutableMapOf<String, CreatorLine>()
    var lastLeadId: String? = null
    project.lines.forEach { line ->
      if (line.isBackground) {
        (line.attachedToLineId ?: lastLeadId)?.let { targetId ->
          backgroundByLead[targetId] = line
        }
      } else {
        lastLeadId = line.id
      }
    }

    val leadEntries = project.lines.mapIndexedNotNull { lineIndex, line ->
      if (line.isBackground) null else lineIndex to line
    }
    val documentStart = leadEntries.minOf { (_, line) -> line.timedRange().first }
    val documentEnd = leadEntries.maxOf { (_, line) -> line.timedRange().last }

    return buildString {
      append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
      append("<tt xmlns:amll=\"http://www.example.com/ns/amll\"")
      append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
      append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
      append(" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\"")
      if (project.metadata.language.isNotBlank()) {
        append(" xml:lang=\"").append(xmlEscape(project.metadata.language, attribute = true)).append('"')
      }
      append(" itunes:timing=\"Word\" xmlns=\"http://www.w3.org/ns/ttml\">\n")
      append("  <head>\n")
      append("    <metadata>\n")
      append("      <ttm:agent type=\"person\" xml:id=\"v1\"/>\n")
      append("      <ttm:agent type=\"person\" xml:id=\"v2\"/>\n")
      append("      <iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">\n")
      append("        <songwriters>\n")
      project.metadata.songwriters.forEach { songwriter ->
        if (songwriter.isNotBlank()) {
          append("          <songwriter>").append(xmlEscape(songwriter)).append("</songwriter>\n")
        }
      }
      append("        </songwriters>\n")
      append("      </iTunesMetadata>\n")
      appendOptionalAmllMeta("musicName", project.metadata.name)
      project.metadata.artists.forEach { appendOptionalAmllMeta("artists", it) }
      project.metadata.albums.forEach { appendOptionalAmllMeta("album", it) }
      appendOptionalAmllMeta("spotifyId", project.metadata.spotifyTrackId)
      appendOptionalAmllMeta("appleMusicId", project.metadata.appleMusicTrackId)
      appendOptionalAmllMeta("isrc", project.metadata.isrc)
      project.metadata.raw.toSortedMap().forEach { (key, values) ->
        values.forEach { appendOptionalAmllMeta(key, it) }
      }
      append("    </metadata>\n")
      append("  </head>\n")
      append("  <body dur=\"").append(formatTtmlSeconds(documentEnd)).append("\">\n")
      append("    <div begin=\"").append(formatTtmlSeconds(documentStart))
        .append("\" end=\"").append(formatTtmlSeconds(documentEnd)).append("\">\n")
      leadEntries.forEach { (lineIndex, lead) ->
        appendVocalParagraph(lead, backgroundByLead[lead.id], lineIndex + 1)
      }
      append("    </div>\n")
      append("  </body>\n")
      append("</tt>\n")
    }
  }

  /**
   * Exports a standards-safe TTML file at every stage of authoring.
   *
   * A complete project stays word-synchronised. If timing alone is incomplete
   * or invalid, the export becomes an explicitly static TTML draft. This keeps
   * every authored lyric visible without inventing timestamps that could later
   * be mistaken for final synchronization. Structural/XML errors still block
   * export because they cannot be represented safely.
   */
  fun encodeForExport(project: CreatorProject): CreatorTtmlExportResult {
    val issues = validate(project)
    val structuralIssues = issues.filter { it.kind == CreatorValidationKind.STRUCTURE }
    if (structuralIssues.isNotEmpty()) throw CreatorValidationException(structuralIssues)
    val timingIssues = issues.filter { it.kind == CreatorValidationKind.TIMING }
    if (timingIssues.isEmpty()) {
      return CreatorTtmlExportResult(
        rawTtml = encode(project),
        exportedAsStaticDraft = false,
        omittedTimingIssueCount = 0,
      )
    }
    return CreatorTtmlExportResult(
      rawTtml = encodeStaticDraft(project),
      exportedAsStaticDraft = true,
      omittedTimingIssueCount = timingIssues.size,
    )
  }

  fun decode(
    rawTtml: String,
    trackUri: String,
    metadataHint: CreatorMetadata = CreatorMetadata(),
  ): CreatorProject {
    val parsed = TtmlParser.parse(rawTtml, trackUri, ParsedLyricsSource.LOCAL_TTML)
    val embedded = embeddedMetadata(rawTtml)
    val mergedMetadata = metadataHint.copy(
      name = embedded.name.ifBlank { metadataHint.name },
      artists = embedded.artists.ifEmpty { metadataHint.artists },
      albums = embedded.albums.ifEmpty { metadataHint.albums },
      spotifyTrackId = embedded.spotifyTrackId.ifBlank { metadataHint.spotifyTrackId },
      appleMusicTrackId = embedded.appleMusicTrackId.ifBlank { metadataHint.appleMusicTrackId },
      isrc = embedded.isrc.ifBlank { metadataHint.isrc },
      raw = if (embedded.raw.isEmpty()) metadataHint.raw else metadataHint.raw + embedded.raw,
    )
    return creatorProjectFromLyricsDocument(
      document = parsed,
      trackUri = trackUri,
      sourceHint = CreatorSourceProvenance(CreatorSource.LOCAL_TTML),
      metadataHint = mergedMetadata,
    )
  }

  private fun StringBuilder.appendAmllMeta(key: String, value: String) {
    append("      <amll:meta key=\"").append(xmlEscape(key, attribute = true))
      .append("\" value=\"").append(xmlEscape(value, attribute = true)).append("\"/>\n")
  }

  private fun StringBuilder.appendOptionalAmllMeta(key: String, value: String) {
    if (value.isNotBlank()) appendAmllMeta(key, value)
  }

  private fun encodeStaticDraft(project: CreatorProject): String {
    val backgroundByLead = mutableMapOf<String, CreatorLine>()
    var lastLeadId: String? = null
    project.lines.forEach { line ->
      if (line.isBackground) {
        (line.attachedToLineId ?: lastLeadId)?.let { backgroundByLead[it] = line }
      } else {
        lastLeadId = line.id
      }
    }
    val leadEntries = project.lines.mapIndexedNotNull { index, line ->
      if (line.isBackground) null else index to line
    }

    return buildString {
      append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
      append("<tt xmlns:amll=\"http://www.example.com/ns/amll\"")
      append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
      append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
      append(" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\"")
      if (project.metadata.language.isNotBlank()) {
        append(" xml:lang=\"").append(xmlEscape(project.metadata.language, attribute = true)).append('"')
      }
      append(" itunes:timing=\"None\" xmlns=\"http://www.w3.org/ns/ttml\">\n")
      append("  <head>\n")
      append("    <metadata>\n")
      append("      <ttm:agent type=\"person\" xml:id=\"v1\"/>\n")
      append("      <ttm:agent type=\"person\" xml:id=\"v2\"/>\n")
      append("      <iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">\n")
      append("        <songwriters>\n")
      project.metadata.songwriters.forEach { songwriter ->
        if (songwriter.isNotBlank()) {
          append("          <songwriter>").append(xmlEscape(songwriter)).append("</songwriter>\n")
        }
      }
      append("        </songwriters>\n")
      append("      </iTunesMetadata>\n")
      appendOptionalAmllMeta("musicName", project.metadata.name)
      project.metadata.artists.forEach { appendOptionalAmllMeta("artists", it) }
      project.metadata.albums.forEach { appendOptionalAmllMeta("album", it) }
      appendOptionalAmllMeta("spotifyId", project.metadata.spotifyTrackId)
      appendOptionalAmllMeta("appleMusicId", project.metadata.appleMusicTrackId)
      appendOptionalAmllMeta("isrc", project.metadata.isrc)
      project.metadata.raw.toSortedMap().forEach { (key, values) ->
        values.forEach { appendOptionalAmllMeta(key, it) }
      }
      appendAmllMeta("icyLyricsCreatorDraftTiming", "incomplete")
      append("    </metadata>\n")
      append("  </head>\n")
      append("  <body>\n")
      append("    <div>\n")
      leadEntries.forEach { (lineIndex, lead) ->
        append("      <p itunes:key=\"L").append(lineIndex + 1).append('"')
          .append(" ttm:agent=\"").append(if (lead.isSecondSpeaker) "v2" else "v1").append("\">")
        append(xmlEscape(lead.text()))
        backgroundByLead[lead.id]?.let { background ->
          append("<span ttm:role=\"x-bg\" ttm:agent=\"")
            .append(if (background.isSecondSpeaker) "v2" else "v1")
            .append("\">").append(xmlEscape(background.text())).append("</span>")
        }
        append("</p>\n")
      }
      append("    </div>\n")
      append("  </body>\n")
      append("</tt>\n")
    }
  }

  private fun StringBuilder.appendVocalParagraph(
    lead: CreatorLine,
    background: CreatorLine?,
    number: Int,
  ) {
    val range = lead.timedRange()
    append("      <p begin=\"").append(formatTtmlSeconds(range.first))
      .append("\" end=\"").append(formatTtmlSeconds(range.last))
      .append("\" itunes:key=\"L").append(number).append('"')
      .append(" ttm:agent=\"").append(if (lead.isSecondSpeaker) "v2" else "v1").append("\">")
    appendTimedFragments(lead)
    if (background != null) {
      val backgroundRange = background.timedRange()
      append("<span ttm:role=\"x-bg\" ttm:agent=\"")
        .append(if (background.isSecondSpeaker) "v2" else "v1")
        .append("\" begin=\"").append(formatTtmlSeconds(backgroundRange.first))
        .append("\" end=\"").append(formatTtmlSeconds(backgroundRange.last)).append("\">")
      appendTimedFragments(background)
      append("</span>")
    }
    append("</p>\n")
  }

  private fun StringBuilder.appendTimedFragments(line: CreatorLine) {
    line.tokens.forEachIndexed { tokenIndex, token ->
      token.fragments.forEachIndexed { fragmentIndex, fragment ->
        append("<span begin=\"").append(formatTtmlSeconds(requireNotNull(fragment.startTimeMs)))
          .append("\" end=\"").append(formatTtmlSeconds(requireNotNull(fragment.endTimeMs))).append("\">")
        append(xmlEscape(fragment.text))
        if (
          fragmentIndex == token.fragments.lastIndex &&
          tokenIndex < line.tokens.lastIndex &&
          token.boundaryAfter.isNotEmpty()
        ) {
          append(' ')
        }
        append("</span>")
      }
    }
  }

  private fun CreatorLine.timedRange(): LongRange {
    val fragments = tokens.flatMap(CreatorToken::fragments)
    return fragments.minOf { requireNotNull(it.startTimeMs) }..fragments.maxOf {
      requireNotNull(it.endTimeMs)
    }
  }
}

fun serializeCreatorTtml(project: CreatorProject): String = CreatorTtmlCodec.encode(project)

fun parseCreatorTtml(
  rawTtml: String,
  trackUri: String,
  metadataHint: CreatorMetadata = CreatorMetadata(),
): CreatorProject = CreatorTtmlCodec.decode(rawTtml, trackUri, metadataHint)

fun inspectCreatorTtmlIdentity(rawTtml: String): CreatorEmbeddedTrackIdentity {
  val metadata = embeddedMetadata(rawTtml)
  return CreatorEmbeddedTrackIdentity(
    spotifyTrackId = metadata.spotifyTrackId.takeIf(String::isNotBlank),
    appleMusicTrackId = metadata.appleMusicTrackId.takeIf(String::isNotBlank),
  )
}

fun creatorProjectFromLyricsDocument(
  document: LyricsDocument,
  trackUri: String,
  sourceHint: CreatorSourceProvenance? = null,
  metadataHint: CreatorMetadata = CreatorMetadata(),
): CreatorProject {
  val uri = trackUri.ifBlank { document.metadata.trackUri.orEmpty() }
  val spotifyTrackId = uri.removePrefix("spotify:track:").takeIf {
    uri.startsWith("spotify:track:") && ':' !in it
  }.orEmpty()
  val metadata = metadataHint.copy(
    spotifyTrackId = metadataHint.spotifyTrackId.ifBlank { spotifyTrackId },
    songwriters = document.metadata.songwriters.ifEmpty { metadataHint.songwriters },
    language = document.metadata.language ?: metadataHint.language,
  )
  val lines = when (document) {
    is StaticLyrics -> document.lines.map { line ->
      CreatorLine(tokens = creatorTokensFromText(line.text))
    }

    is LineLyrics -> document.lines.map { line ->
      CreatorLine(
        tokens = creatorTokensFromText(line.text),
        isSecondSpeaker = line.oppositeAligned,
        startTimeMs = line.startMs,
        endTimeMs = line.endMs,
      )
    }

    is SyllableLyrics -> buildList {
      document.lines.forEach { line ->
        val lead = creatorLineFromVocal(
          line.lead,
          secondSpeaker = line.oppositeAligned || line.lead.oppositeAligned,
        )
        add(lead)
        line.background.forEach { vocal ->
          add(
            creatorLineFromVocal(vocal, background = true, secondSpeaker = vocal.oppositeAligned)
              .copy(attachedToLineId = lead.id),
          )
        }
      }
    }
  }

  val inferredSource = document.metadata.source.toCreatorSource()
  return CreatorProject(
    uri = uri,
    source = sourceHint ?: CreatorSourceProvenance(
      source = inferredSource,
      label = document.metadata.sourceLabel ?: inferredSource.displayName,
    ),
    metadata = metadata,
    lines = lines.ifEmpty { listOf(CreatorLine()) },
  )
}

private fun creatorTokensFromText(text: String): List<CreatorToken> {
  val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
  if (words.isEmpty()) return listOf(CreatorToken())
  return words.mapIndexed { index, word ->
    CreatorToken(
      fragments = listOf(CreatorFragment(text = word)),
      boundaryAfter = if (index < words.lastIndex) " " else "",
    )
  }
}

private fun creatorLineFromVocal(
  vocal: VocalLine,
  background: Boolean = false,
  secondSpeaker: Boolean = false,
): CreatorLine = CreatorLine(
  tokens = creatorTokensFromParsed(vocal.tokens),
  isBackground = background,
  isSecondSpeaker = secondSpeaker,
  startTimeMs = vocal.startMs,
  endTimeMs = vocal.endMs,
)

private fun creatorTokensFromParsed(tokens: List<ParsedLyricToken>): List<CreatorToken> {
  val result = mutableListOf<CreatorToken>()
  var fragments = mutableListOf<CreatorFragment>()
  tokens.forEach { token ->
    fragments += CreatorFragment(
      text = token.text,
      startTimeMs = token.startMs,
      endTimeMs = token.endMs,
    )
    if (!token.isPartOfWord) {
      result += CreatorToken(fragments = fragments, boundaryAfter = " ")
      fragments = mutableListOf()
    }
  }
  if (fragments.isNotEmpty()) result += CreatorToken(fragments = fragments)
  if (result.isEmpty()) return listOf(CreatorToken())
  result[result.lastIndex] = result.last().copy(boundaryAfter = "")
  return result
}

private fun ParsedLyricsSource.toCreatorSource(): CreatorSource = when (this) {
  ParsedLyricsSource.LOCAL_TTML -> CreatorSource.LOCAL_TTML
  ParsedLyricsSource.ICY_DATABASE -> CreatorSource.ICY_DATABASE
  ParsedLyricsSource.LRCLIB -> CreatorSource.LRCLIB
  ParsedLyricsSource.APPLE_MUSIC -> CreatorSource.APPLE_MUSIC
  else -> CreatorSource.DRAFT
}

private data class EmbeddedCreatorMetadata(
  val name: String = "",
  val artists: List<String> = emptyList(),
  val albums: List<String> = emptyList(),
  val spotifyTrackId: String = "",
  val appleMusicTrackId: String = "",
  val isrc: String = "",
  val raw: Map<String, List<String>> = emptyMap(),
)

private fun embeddedMetadata(rawTtml: String): EmbeddedCreatorMetadata {
  val entries = buildList {
    CREATOR_META_TAG.findAll(rawTtml).forEach { match ->
      val attributes = CREATOR_XML_ATTRIBUTE.findAll(match.value).associate { attribute ->
        attribute.groupValues[1].lowercase() to xmlUnescape(
          attribute.groupValues[2].ifEmpty { attribute.groupValues[3] },
        )
      }
      val key = attributes["key"] ?: attributes["name"] ?: return@forEach
      val value = attributes["value"] ?: attributes["content"] ?: return@forEach
      add(key to value)
    }
  }
  fun values(vararg keys: String): List<String> {
    val accepted = keys.map(String::lowercase).toSet()
    return entries.filter { it.first.lowercase() in accepted }.map(Pair<String, String>::second)
  }
  fun first(vararg keys: String): String = values(*keys).firstOrNull().orEmpty()

  val knownKeys = setOf(
    "musicname",
    "title",
    "artists",
    "artist",
    "album",
    "spotifyid",
    "spotifytrackid",
    "applemusicid",
    "applemusictrackid",
    "isrc",
    "timingmode",
  )
  val raw = entries
    .filterNot { it.first.lowercase() in knownKeys }
    .groupBy(keySelector = Pair<String, String>::first, valueTransform = Pair<String, String>::second)
  return EmbeddedCreatorMetadata(
    name = first("musicName", "title"),
    artists = values("artists", "artist"),
    albums = values("album"),
    spotifyTrackId = first("spotifyId", "spotifyTrackId").normalizeSpotifyTrackId(),
    appleMusicTrackId = first("appleMusicId", "appleMusicTrackId"),
    isrc = first("isrc"),
    raw = raw,
  )
}

private fun String.normalizeSpotifyTrackId(): String = trim()
  .removePrefix("spotify:track:")
  .takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
  .orEmpty()

private fun formatTtmlSeconds(milliseconds: Long): String =
  String.format(Locale.ROOT, "%.3fs", milliseconds.coerceAtLeast(0L) / 1_000.0)

private fun xmlUnescape(value: String): String {
  val numeric = DECIMAL_XML_ENTITY.replace(value) { match ->
    match.groupValues[1].toIntOrNull()?.takeIf(Character::isValidCodePoint)
      ?.let(Character::toChars)?.concatToString() ?: match.value
  }
  val decodedNumeric = HEX_XML_ENTITY.replace(numeric) { match ->
    match.groupValues[1].toIntOrNull(16)?.takeIf(Character::isValidCodePoint)
      ?.let(Character::toChars)?.concatToString() ?: match.value
  }
  return decodedNumeric
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
}

private val CREATOR_META_TAG = Regex(
  pattern = "<\\s*(?:[A-Za-z_][A-Za-z0-9_.-]*:)?meta\\b[^>]*>",
  option = RegexOption.IGNORE_CASE,
)
private val CREATOR_XML_ATTRIBUTE = Regex(
  pattern = "(?:^|\\s)(?:[A-Za-z_][A-Za-z0-9_.-]*:)?([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
)
private val DECIMAL_XML_ENTITY = Regex("&#([0-9]+);")
private val HEX_XML_ENTITY = Regex("&#[xX]([0-9A-Fa-f]+);")

private fun containsInvalidXmlCharacter(value: String): Boolean {
  var offset = 0
  while (offset < value.length) {
    val codePoint = Character.codePointAt(value, offset)
    val valid = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
      codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD ||
      codePoint in 0x10000..0x10FFFF
    if (!valid) return true
    offset += Character.charCount(codePoint)
  }
  return false
}

private fun xmlEscape(value: String, attribute: Boolean = false): String = buildString {
  value.forEach { character ->
    when (character) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> if (attribute) append("&quot;") else append(character)
      '\'' -> if (attribute) append("&apos;") else append(character)
      else -> append(character)
    }
  }
}
