package com.icy.lyrics.creator

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Editable provenance kept with a Lyric Creator draft. */
@Serializable
enum class CreatorSource(val code: String, val displayName: String) {
  LOCAL_TTML("ldb", "Local TTML"),
  ICY_DATABASE("icy", "Icy Lyrics Database"),
  LRCLIB("lrc", "LRCLIB"),
  APPLE_MUSIC("aml", "Apple Music"),
  DRAFT("draft", "Draft"),
}

@Serializable
data class CreatorSourceProvenance(
  val source: CreatorSource = CreatorSource.DRAFT,
  val label: String = source.displayName,
  val maker: String? = null,
  val uploader: String? = null,
)

@Serializable
data class CreatorMetadata(
  val name: String = "",
  val artists: List<String> = emptyList(),
  val songwriters: List<String> = emptyList(),
  val albums: List<String> = emptyList(),
  val spotifyTrackId: String = "",
  val appleMusicTrackId: String = "",
  val isrc: String = "",
  val language: String = "",
  val raw: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class CreatorFragment(
  val id: String = createCreatorId("fragment"),
  val text: String = "",
  val startTimeMs: Long? = null,
  val endTimeMs: Long? = null,
)

@Serializable
data class CreatorToken(
  val id: String = createCreatorId("token"),
  val fragments: List<CreatorFragment> = listOf(CreatorFragment()),
  /** Exact text written between this word and the next word. */
  val boundaryAfter: String = "",
)

@Serializable
data class CreatorLine(
  val id: String = createCreatorId("line"),
  val tokens: List<CreatorToken> = listOf(CreatorToken()),
  val isBackground: Boolean = false,
  val isSecondSpeaker: Boolean = false,
  val attachedToLineId: String? = null,
  val startTimeMs: Long? = null,
  val endTimeMs: Long? = null,
)

@Serializable
data class CreatorProject(
  val version: Int = 1,
  val uri: String = "",
  val source: CreatorSourceProvenance = CreatorSourceProvenance(),
  val metadata: CreatorMetadata = CreatorMetadata(),
  val lines: List<CreatorLine> = listOf(CreatorLine()),
)

private val CREATOR_PROJECT_JSON = Json {
  encodeDefaults = true
  explicitNulls = true
  ignoreUnknownKeys = true
}

fun serializeCreatorProjectJson(project: CreatorProject): String =
  CREATOR_PROJECT_JSON.encodeToString(CreatorProject.serializer(), project)

fun deserializeCreatorProjectJson(raw: String): CreatorProject {
  val project = CREATOR_PROJECT_JSON.decodeFromString(CreatorProject.serializer(), raw)
  require(project.version == 1) { "Unsupported lyric project version ${project.version}." }
  return project
}

fun createCreatorId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

fun createCreatorFragment(text: String = ""): CreatorFragment = CreatorFragment(text = text)

fun createCreatorToken(text: String = ""): CreatorToken =
  CreatorToken(fragments = listOf(createCreatorFragment(text)))

fun createCreatorLine(tokens: List<CreatorToken> = listOf(createCreatorToken())): CreatorLine =
  CreatorLine(tokens = tokens)

fun createEmptyCreatorProject(uri: String = ""): CreatorProject = CreatorProject(uri = uri)

fun CreatorToken.text(): String = fragments.joinToString(separator = "") { it.text }

fun CreatorLine.text(): String = buildString {
  tokens.forEach { token ->
    append(token.text())
    append(token.boundaryAfter)
  }
}

fun CreatorProject.plainText(): String = lines.joinToString(separator = "\n") { it.text() }

/**
 * Text shown in the mobile editor. Fragment boundaries must remain explicit or
 * editing an unrelated row would collapse a loaded syllable-timed word into a
 * new untimed fragment.
 */
fun CreatorProject.editorText(): String = lines.joinToString(separator = "\n") { line ->
  buildString {
    line.tokens.forEach { token ->
      append(token.fragments.joinToString(separator = "\\") { it.text })
      append(token.boundaryAfter)
    }
  }
}

/**
 * Rebinds an imported project to a different song without carrying over
 * identity metadata that belongs to the source file's original track.
 */
fun CreatorProject.rebindToTrack(
  trackUri: String,
  currentTrackMetadata: CreatorMetadata,
): CreatorProject = copy(
  uri = trackUri,
  metadata = metadata.copy(
    name = currentTrackMetadata.name,
    artists = currentTrackMetadata.artists,
    albums = currentTrackMetadata.albums,
    spotifyTrackId = currentTrackMetadata.spotifyTrackId,
    appleMusicTrackId = currentTrackMetadata.appleMusicTrackId,
    isrc = currentTrackMetadata.isrc,
  ),
)

/**
 * Plain-text import follows the desktop contract exactly: each source line is
 * a lyric row and only a backslash splits written words. Trailing whitespace
 * is retained as the exact boundary after that word.
 */
fun importCreatorPlainText(raw: String): List<CreatorLine> = raw
  .replace("\r\n", "\n")
  .replace('\r', '\n')
  .split('\n')
  .mapNotNull(::tokensFromPlainTextLine)

/**
 * Touch-first plain-text import. Normal whitespace separates timing words and
 * is retained exactly in [CreatorToken.boundaryAfter]. A backslash inside one
 * non-whitespace word separates independently timed fragments of that word.
 */
fun importCreatorMobilePlainText(raw: String): List<CreatorLine> = raw
  .replace("\r\n", "\n")
  .replace('\r', '\n')
  .split('\n')
  .mapNotNull(::tokensFromMobilePlainTextLine)

private fun tokensFromMobilePlainTextLine(line: String): CreatorLine? {
  val tokens = mutableListOf<CreatorToken>()
  var offset = 0
  var leadingWhitespace = ""
  while (offset < line.length && line[offset].isWhitespace()) offset += 1
  if (offset > 0) leadingWhitespace = line.substring(0, offset)

  while (offset < line.length) {
    val wordStart = offset
    while (offset < line.length && !line[offset].isWhitespace()) offset += 1
    val rawWord = line.substring(wordStart, offset)
    val boundaryStart = offset
    while (offset < line.length && line[offset].isWhitespace()) offset += 1
    val boundary = line.substring(boundaryStart, offset)
    val fragmentText = splitPreservingEmptyParts(rawWord, '\\').filter(String::isNotEmpty)
    if (fragmentText.isEmpty()) continue
    val fragments = fragmentText.mapIndexed { index, text ->
      CreatorFragment(text = if (tokens.isEmpty() && index == 0) leadingWhitespace + text else text)
    }
    leadingWhitespace = ""
    tokens += CreatorToken(fragments = fragments, boundaryAfter = boundary)
  }
  return tokens.takeIf(List<CreatorToken>::isNotEmpty)?.let { CreatorLine(tokens = it) }
}

private fun tokensFromPlainTextLine(line: String): CreatorLine? {
  val parts = splitPreservingEmptyParts(line, '\\')
  val tokens = parts.mapIndexedNotNull { index, part ->
    val boundary = part.takeLastWhile(Char::isWhitespace)
    val text = if (boundary.isEmpty()) part else part.dropLast(boundary.length)
    if (text.isEmpty() && boundary.isEmpty()) {
      null
    } else {
      CreatorToken(
        fragments = listOf(CreatorFragment(text = text)),
        boundaryAfter = boundary.ifEmpty { if (index < parts.lastIndex) " " else "" },
      )
    }
  }
  return tokens.takeIf(List<CreatorToken>::isNotEmpty)?.let { CreatorLine(tokens = it) }
}

private fun splitPreservingEmptyParts(value: String, delimiter: Char): List<String> {
  val result = mutableListOf<String>()
  var start = 0
  value.forEachIndexed { index, character ->
    if (character == delimiter) {
      result += value.substring(start, index)
      start = index + 1
    }
  }
  result += value.substring(start)
  return result
}

/** Reorders a complete written word while keeping separator slots stationary. */
fun moveCreatorTokenWithinLine(
  line: CreatorLine,
  fromIndex: Int,
  toIndex: Int,
): CreatorLine {
  if (fromIndex == toIndex || fromIndex !in line.tokens.indices || toIndex !in line.tokens.indices) {
    return line
  }
  val boundaries = line.tokens.map(CreatorToken::boundaryAfter)
  val tokens = line.tokens.toMutableList()
  val token = tokens.removeAt(fromIndex)
  tokens.add(toIndex, token)
  return line.copy(
    tokens = tokens.mapIndexed { index, next -> next.copy(boundaryAfter = boundaries[index]) },
  )
}

/** Derives line timing from every partially or completely timed fragment. */
fun CreatorLine.synchronizeTiming(): CreatorLine {
  val fragments = tokens.flatMap(CreatorToken::fragments)
  if (fragments.none { it.startTimeMs != null || it.endTimeMs != null }) return this
  return copy(
    startTimeMs = fragments.mapNotNull(CreatorFragment::startTimeMs).minOrNull(),
    endTimeMs = fragments.mapNotNull(CreatorFragment::endTimeMs).maxOrNull(),
  )
}

/**
 * Reconciles newly parsed text with an existing project. Exact unchanged words
 * keep IDs and timings, including after line insertion/reordering; edited words
 * remain untimed so stale timestamps are never assigned to different text.
 */
fun reconcileCreatorLinesPreservingTiming(
  existing: List<CreatorLine>,
  edited: List<CreatorLine>,
): List<CreatorLine> {
  val unusedLineIndexes = existing.indices.toMutableSet()
  return edited.mapIndexed { newLineIndex, newLine ->
    val exactIndex = unusedLineIndexes.minByOrNull { oldIndex ->
      if (existing[oldIndex].text() == newLine.text()) kotlin.math.abs(oldIndex - newLineIndex)
      else Int.MAX_VALUE
    }?.takeIf { existing[it].text() == newLine.text() }
    val newTokenTexts = newLine.tokens.map(CreatorToken::text)
    val similarIndex = unusedLineIndexes.maxWithOrNull(
      compareBy<Int> { candidate ->
        val remaining = existing[candidate].tokens.map(CreatorToken::text).toMutableList()
        newTokenTexts.count { text ->
          val match = remaining.indexOf(text)
          if (match >= 0) remaining.removeAt(match)
          match >= 0
        }
      }.thenByDescending { candidate -> kotlin.math.abs(candidate - newLineIndex) },
    )?.takeIf { candidate ->
      existing[candidate].tokens.any { old -> newTokenTexts.any { it == old.text() } }
    }
    val positionalIndex = newLineIndex.takeIf {
      existing.size == edited.size && unusedLineIndexes.contains(it)
    }
    val oldIndex = exactIndex ?: similarIndex ?: positionalIndex
    val oldLine = oldIndex?.let(existing::get)
    if (oldIndex != null) unusedLineIndexes.remove(oldIndex)
    if (oldLine == null) return@mapIndexed newLine

    val unusedTokenIndexes = oldLine.tokens.indices.toMutableSet()
    val reconciledTokens = newLine.tokens.mapIndexed { newTokenIndex, newToken ->
      val oldTokenIndex = unusedTokenIndexes.minByOrNull { candidate ->
        if (oldLine.tokens[candidate].text() == newToken.text()) {
          kotlin.math.abs(candidate - newTokenIndex)
        } else {
          Int.MAX_VALUE
        }
      }?.takeIf { oldLine.tokens[it].text() == newToken.text() }
      val oldToken = oldTokenIndex?.let(oldLine.tokens::get)
      if (oldTokenIndex != null) unusedTokenIndexes.remove(oldTokenIndex)
      if (
        oldToken != null &&
        oldToken.fragments.map(CreatorFragment::text) == newToken.fragments.map(CreatorFragment::text)
      ) {
        oldToken.copy(boundaryAfter = newToken.boundaryAfter)
      } else {
        newToken
      }
    }
    oldLine.copy(tokens = reconciledTokens).synchronizeTiming()
  }
}

fun CreatorProject.reconcileMobilePlainText(raw: String): CreatorProject = copy(
  lines = reconcileCreatorLinesPreservingTiming(lines, importCreatorMobilePlainText(raw))
    .ifEmpty { listOf(CreatorLine()) },
)
