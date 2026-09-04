package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.core.lyrics.model.VocalLine
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.constructor.ConstructScalar
import it.krzeminski.snakeyaml.engine.kmp.nodes.Node
import it.krzeminski.snakeyaml.engine.kmp.nodes.Tag
import it.krzeminski.snakeyaml.engine.kmp.schema.CoreSchema

class LyricsfileParseException(message: String, cause: Throwable? = null) :
  IllegalArgumentException(message, cause)

/** Strict safe reader for the public Lyricsfile 1.0 draft used by LRCLIB. */
object LyricsfileYamlParser {
  private const val MAX_INPUT_CHARS = 2_000_000
  private const val DEFAULT_LINE_DURATION_MS = 3_000L
  private const val MIN_DURATION_MS = 1L
  private val topLevelKeys = setOf("version", "metadata", "lines", "plain")
  private val metadataKeys = setOf(
    "title", "artist", "album", "duration_ms", "offset_ms", "language", "instrumental",
  )
  private val lineKeys = setOf("text", "start_ms", "end_ms", "words")
  private val wordKeys = setOf("text", "start_ms", "end_ms")

  fun parse(
    rawYaml: String,
    trackUri: String,
    source: LyricsSource = LyricsSource.LRCLIB,
  ): LyricsDocument {
    require(trackUri.isNotBlank()) { "An exact track URI is required" }
    if (rawYaml.isBlank()) throw LyricsfileParseException("Lyricsfile is empty")
    if (rawYaml.length > MAX_INPUT_CHARS) throw LyricsfileParseException("Lyricsfile exceeds the size limit")
    rejectForbiddenYamlFeatures(rawYaml)

    val root = try {
      val settings = LoadSettings(
        schema = CoreSchema(),
        tagConstructors = mapOf(Tag.INT to BoundedIntegerConstructor),
        allowDuplicateKeys = false,
        allowRecursiveKeys = false,
        maxAliasesForCollections = 0,
        codePointLimit = MAX_INPUT_CHARS,
      )
      val documents = Load(settings).loadAll(rawYaml).take(2).toList()
      if (documents.size != 1) throw LyricsfileParseException("Lyricsfile must contain exactly one YAML document")
      documents.single().asStringMap("Lyricsfile root")
    } catch (error: LyricsfileParseException) {
      throw error
    } catch (error: Exception) {
      throw LyricsfileParseException("Invalid Lyricsfile YAML", error)
    }

    root.rejectUnknownKeys(topLevelKeys, "Lyricsfile root")
    val version = root["version"] as? String
      ?: throw LyricsfileParseException("Lyricsfile version must be the string '1.0'")
    if (version != "1.0") throw LyricsfileParseException("Unsupported Lyricsfile version: $version")

    val metadataMap = root["metadata"].asStringMap("metadata")
    metadataMap.rejectUnknownKeys(metadataKeys, "metadata")
    requiredString(metadataMap, "title", "metadata")
    requiredString(metadataMap, "artist", "metadata")
    metadataMap["album"]?.requireString("metadata.album")
    metadataMap["language"]?.requireString("metadata.language")
    metadataMap["duration_ms"]?.asLong("metadata.duration_ms")?.also {
      if (it < 0) throw LyricsfileParseException("metadata.duration_ms must not be negative")
    }
    metadataMap["offset_ms"]?.asLong("metadata.offset_ms")
    val instrumental = metadataMap["instrumental"]?.let {
      it as? Boolean ?: throw LyricsfileParseException("metadata.instrumental must be a boolean")
    } ?: false
    val metadata = LyricsMetadata(
      trackUri = trackUri,
      source = source,
      sourceLabel = sourceLabel(source),
      language = (metadataMap["language"] as? String)?.takeIf(String::isNotBlank),
      instrumental = instrumental,
    )

    val rawLineValues: List<*> = when (val value = root["lines"]) {
      null -> emptyList<Any?>()
      is List<*> -> value
      else -> throw LyricsfileParseException("lines must be a sequence")
    }
    val rawLines = rawLineValues.mapIndexed { index, value -> parseLine(value, index) }
    val plain = root["plain"]?.let { it.requireString("plain") }

    if (instrumental) {
      if (rawLines.isNotEmpty() || !plain.isNullOrEmpty()) {
        throw LyricsfileParseException("Instrumental Lyricsfile must not contain lyrics")
      }
      return StaticLyrics(metadata, emptyList())
    }
    if (rawLines.isEmpty()) {
      if (plain == null) throw LyricsfileParseException("Lyricsfile contains neither lines nor plain lyrics")
      return StaticLyrics(
        metadata = metadata,
        lines = plain.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::StaticLyricLine),
      )
    }

    return if (rawLines.any { it.words != null && it.words.isNotEmpty() }) {
      buildSyllable(rawLines, metadata)
    } else {
      buildLine(rawLines, metadata)
    }
  }

  fun looksLikeLyricsfile(raw: String): Boolean =
    VERSION_HEADER.containsMatchIn(raw) && METADATA_HEADER.containsMatchIn(raw)

  private fun parseLine(value: Any?, index: Int): RawLine {
    val map = value.asStringMap("lines[$index]")
    map.rejectUnknownKeys(lineKeys, "lines[$index]")
    val text = requiredString(map, "text", "lines[$index]")
    val start = map["start_ms"]?.asLong("lines[$index].start_ms")
      ?: throw LyricsfileParseException("lines[$index].start_ms is required")
    if (start < 0) throw LyricsfileParseException("lines[$index].start_ms must not be negative")
    val end = map["end_ms"]?.asLong("lines[$index].end_ms")
    if (end != null && end < start) throw LyricsfileParseException("lines[$index].end_ms precedes start_ms")
    val words = when (val rawWords = map["words"]) {
      null -> null
      is List<*> -> rawWords.mapIndexed { wordIndex, word -> parseWord(word, index, wordIndex) }
      else -> throw LyricsfileParseException("lines[$index].words must be a sequence")
    }
    return RawLine(text, start, end, words)
  }

  private fun parseWord(value: Any?, lineIndex: Int, wordIndex: Int): RawWord {
    val path = "lines[$lineIndex].words[$wordIndex]"
    val map = value.asStringMap(path)
    map.rejectUnknownKeys(wordKeys, path)
    val text = requiredString(map, "text", path)
    val start = map["start_ms"]?.asLong("$path.start_ms")
      ?: throw LyricsfileParseException("$path.start_ms is required")
    if (start < 0) throw LyricsfileParseException("$path.start_ms must not be negative")
    val end = map["end_ms"]?.asLong("$path.end_ms")
    if (end != null && end < start) throw LyricsfileParseException("$path.end_ms precedes start_ms")
    return RawWord(text, start, end)
  }

  private fun buildLine(lines: List<RawLine>, metadata: LyricsMetadata): LineLyrics = LineLyrics(
    metadata = metadata,
    lines = lines.map { line ->
      TimedLyricLine(
        text = line.text,
        startMs = line.startMs,
        // Do not use the next line: Lyricsfile explicitly permits overlap.
        endMs = (line.endMs ?: line.startMs + DEFAULT_LINE_DURATION_MS).coerceAtLeast(line.startMs + MIN_DURATION_MS),
      )
    },
  )

  private fun buildSyllable(lines: List<RawLine>, metadata: LyricsMetadata): SyllableLyrics = SyllableLyrics(
    metadata = metadata,
    lines = lines.map { line ->
      val rawWords = line.words.orEmpty()
      val lineEnd = line.endMs ?: rawWords.mapNotNull(RawWord::endMs).maxOrNull()
        ?: line.startMs + DEFAULT_LINE_DURATION_MS
      val tokens = if (rawWords.isEmpty()) {
        listOf(LyricToken(line.text, line.startMs, lineEnd.coerceAtLeast(line.startMs + MIN_DURATION_MS)))
      } else rawWords.mapIndexed { index, word ->
        val nextStart = rawWords.getOrNull(index + 1)?.startMs
        val end = word.endMs ?: nextStart?.takeIf { it >= word.startMs } ?: lineEnd
        LyricToken(
          text = word.text.trim(),
          startMs = word.startMs,
          endMs = end.coerceAtLeast(word.startMs + MIN_DURATION_MS),
          isPartOfWord = !word.text.lastOrNull().isWhitespaceOrNull() &&
            rawWords.getOrNull(index + 1)?.text?.firstOrNull().isNotWhitespaceOrNull(),
        )
      }
      val start = minOf(line.startMs, tokens.minOf(LyricToken::startMs))
      val end = maxOf(lineEnd, tokens.maxOf(LyricToken::endMs)).coerceAtLeast(start + MIN_DURATION_MS)
      SyllableLyricLine(VocalLine(start, end, tokens))
    },
  )

  private fun rejectForbiddenYamlFeatures(raw: String) {
    raw.lineSequence().forEachIndexed { index, rawLine ->
      var singleQuoted = false
      var doubleQuoted = false
      var escaped = false
      for (position in rawLine.indices) {
        val char = rawLine[position]
        if (escaped) {
          escaped = false
          continue
        }
        if (doubleQuoted && char == '\\') {
          escaped = true
          continue
        }
        if (!doubleQuoted && char == '\'') singleQuoted = !singleQuoted
        if (!singleQuoted && char == '"') doubleQuoted = !doubleQuoted
        if (singleQuoted || doubleQuoted || char == '#') {
          if (char == '#' && !singleQuoted && !doubleQuoted) break
          continue
        }
        if (char in charArrayOf('&', '*', '!')) {
          val beginsToken = position == 0 || rawLine[position - 1].isWhitespace() || rawLine[position - 1] in "[{,:?-"
          val hasName = rawLine.getOrNull(position + 1)?.let { !it.isWhitespace() } == true
          if (beginsToken && hasName) {
            throw LyricsfileParseException("YAML tags, anchors and aliases are not allowed (line ${index + 1})")
          }
        }
      }
    }
  }

  private fun Any?.asStringMap(path: String): Map<String, Any?> {
    val map = this as? Map<*, *> ?: throw LyricsfileParseException("$path must be a mapping")
    return map.entries.associate { (key, value) ->
      val stringKey = key as? String ?: throw LyricsfileParseException("$path keys must be strings")
      stringKey to value
    }
  }

  private fun Map<String, Any?>.rejectUnknownKeys(allowed: Set<String>, path: String) {
    (keys - allowed).firstOrNull()?.let { throw LyricsfileParseException("Unknown $path field: $it") }
  }

  private fun requiredString(map: Map<String, Any?>, key: String, path: String): String =
    map[key]?.requireString("$path.$key")
      ?: throw LyricsfileParseException("$path.$key is required")

  private fun Any.requireString(path: String): String =
    this as? String ?: throw LyricsfileParseException("$path must be a string")

  private fun Any.asLong(path: String): Long = when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    else -> throw LyricsfileParseException("$path must be an integer")
  }

  // Bound CoreSchema integers before SnakeYAML's unsupported Native BigInteger path.
  // Floating scalars retain their distinct type and are rejected by asLong on every platform.
  private object BoundedIntegerConstructor : ConstructScalar() {
    override fun construct(node: Node?): Long {
      val value = constructScalar(node)
      val (digits, radix) = when {
        value.startsWith("0x") -> value.substring(2) to 16
        value.startsWith("0o") -> value.substring(2) to 8
        else -> value to 10
      }
      return digits.toLongOrNull(radix)
        ?: throw LyricsfileParseException("Lyricsfile integer is outside the supported integer range")
    }
  }

  private fun Char?.isWhitespaceOrNull(): Boolean = this == null || isWhitespace()
  private fun Char?.isNotWhitespaceOrNull(): Boolean = this == null || !isWhitespace()

  private fun sourceLabel(source: LyricsSource): String = when (source) {
    LyricsSource.LOCAL_TTML -> "Local TTML"
    LyricsSource.SPICY -> "Spicy Lyrics"
    LyricsSource.SPOTIFY -> "Spotify"
    LyricsSource.APPLE_MUSIC -> "Apple Music"
    LyricsSource.LRCLIB -> "LRCLIB"
    LyricsSource.UNKNOWN -> "Unknown"
  }

  private data class RawLine(
    val text: String,
    val startMs: Long,
    val endMs: Long?,
    val words: List<RawWord>?,
  )

  private data class RawWord(val text: String, val startMs: Long, val endMs: Long?)

  private val VERSION_HEADER = Regex("(?m)^[ \\t\\n\\x0B\\f\\r]*version[ \\t\\n\\x0B\\f\\r]*:")
  private val METADATA_HEADER = Regex("(?m)^[ \\t\\n\\x0B\\f\\r]*metadata[ \\t\\n\\x0B\\f\\r]*:")
}
