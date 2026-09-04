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
import kotlin.math.roundToLong

/** Parser for ordinary and Enhanced LRC. */
object LrcParser {
  private val lineTimestamp = Regex("\\[([0-9]{1,3}):([0-9]{2})(?:[.:]([0-9]{1,3}))?]" )
  private val inlineTimestamp = Regex("<([0-9]{1,3}):([0-9]{2})(?:[.:]([0-9]{1,3}))?>")
  private val offsetTag = Regex("^[ \\t\\n\\x0B\\f\\r]*\\[offset:([+-]?[0-9]+)][ \\t\\n\\x0B\\f\\r]*$", RegexOption.IGNORE_CASE)
  private const val MIN_LINE_DURATION_MS = 250L
  private const val DEFAULT_LAST_LINE_DURATION_MS = 3_000L

  fun parse(
    rawLrc: String,
    trackUri: String,
    source: LyricsSource = LyricsSource.LRCLIB,
    durationMs: Long? = null,
  ): LyricsDocument? {
    require(trackUri.isNotBlank()) { "An exact track URI is required" }
    val offsetMs = rawLrc.lineSequence()
      .mapNotNull { offsetTag.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
      .lastOrNull() ?: 0L

    val lines = rawLrc.lineSequence().flatMap { rawLine ->
      val timestamps = lineTimestamp.findAll(rawLine).toList()
      if (timestamps.isEmpty()) return@flatMap emptySequence<RawLine>()
      val contentStart = timestamps.last().range.last + 1
      val content = rawLine.substring(contentStart)
      if (content.isBlank()) return@flatMap emptySequence<RawLine>()
      timestamps.asSequence().mapNotNull { match ->
        match.timestampMsOrNull()?.let { RawLine((it + offsetMs).coerceAtLeast(0L), content) }
      }
    }.distinct().sortedBy(RawLine::startMs).toList()

    if (lines.isEmpty()) return null
    val metadata = LyricsMetadata(
      trackUri = trackUri,
      source = source,
      sourceLabel = sourceLabel(source),
    )
    return if (lines.any { inlineTimestamp.containsMatchIn(it.rawText) }) {
      enhanced(lines, metadata, durationMs, offsetMs)
    } else {
      lineSynced(lines, metadata, durationMs)
    }
  }

  fun parsePlain(
    rawLyrics: String,
    trackUri: String,
    source: LyricsSource = LyricsSource.UNKNOWN,
  ): StaticLyrics? {
    require(trackUri.isNotBlank()) { "An exact track URI is required" }
    val lines = rawLyrics.lineSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .map(::StaticLyricLine)
      .toList()
    if (lines.isEmpty()) return null
    return StaticLyrics(
      metadata = LyricsMetadata(trackUri, source, sourceLabel(source)),
      lines = lines,
    )
  }

  private fun lineSynced(
    raw: List<RawLine>,
    metadata: LyricsMetadata,
    durationMs: Long?,
  ): LineLyrics {
    val lines = raw.mapIndexed { index, line ->
      val next = raw.getOrNull(index + 1)?.startMs
      val fallback = durationMs?.takeIf { it > line.startMs } ?: line.startMs + DEFAULT_LAST_LINE_DURATION_MS
      val end = (next ?: fallback).coerceAtLeast(line.startMs + MIN_LINE_DURATION_MS)
      TimedLyricLine(line.rawText.trim(), line.startMs, end)
    }
    return LineLyrics(metadata, lines)
  }

  private fun enhanced(
    raw: List<RawLine>,
    metadata: LyricsMetadata,
    durationMs: Long?,
    offsetMs: Long,
  ): LyricsDocument? {
    val lines = raw.mapIndexedNotNull { index, line ->
      val matches = inlineTimestamp.findAll(line.rawText).toList()
      val lineEnd = raw.getOrNull(index + 1)?.startMs
        ?: durationMs?.takeIf { it > line.startMs }
        ?: line.startMs + DEFAULT_LAST_LINE_DURATION_MS

      val tokens = if (matches.isEmpty()) {
        listOf(LyricToken(line.rawText.trim(), line.startMs, lineEnd))
      } else {
        val pieces = matches.mapIndexedNotNull { tokenIndex, match ->
          val start = match.timestampMsOrNull()?.plus(offsetMs)?.coerceAtLeast(0L)
            ?: return@mapIndexedNotNull null
          val nextMatch = matches.getOrNull(tokenIndex + 1)
          val textStart = match.range.last + 1
          val textEnd = nextMatch?.range?.first ?: line.rawText.length
          val rawText = line.rawText.substring(textStart, textEnd)
          val text = rawText.trim()
          if (text.isBlank()) return@mapIndexedNotNull null
          RawToken(start, nextMatch?.timestampMsOrNull()?.plus(offsetMs) ?: lineEnd, rawText, text)
        }
        pieces.mapIndexed { tokenIndex, token ->
          val next = pieces.getOrNull(tokenIndex + 1)
          LyricToken(
            text = token.text,
            startMs = token.startMs,
            endMs = token.endMs.coerceAtLeast(token.startMs + 1L),
            isPartOfWord = next != null &&
              token.rawText.lastOrNull()?.isWhitespace() == false &&
              next.rawText.firstOrNull()?.isWhitespace() == false,
          )
        }
      }
      if (tokens.isEmpty()) return@mapIndexedNotNull null
      SyllableLyricLine(
        lead = VocalLine(
          startMs = tokens.minOf(LyricToken::startMs),
          endMs = tokens.maxOf(LyricToken::endMs),
          tokens = tokens,
        )
      )
    }
    if (lines.isEmpty()) return null
    return SyllableLyrics(metadata, lines)
  }

  private fun MatchResult.timestampMsOrNull(): Long? {
    val minutes = groupValues.getOrNull(1)?.toLongOrNull() ?: return null
    val seconds = groupValues.getOrNull(2)?.toLongOrNull() ?: return null
    if (seconds !in 0L..59L) return null
    val fraction = groupValues.getOrNull(3).orEmpty()
    val millis = when (fraction.length) {
      0 -> 0L
      1 -> fraction.toLongOrNull()?.times(100L)
      2 -> fraction.toLongOrNull()?.times(10L)
      else -> fraction.take(3).toLongOrNull()
    } ?: return null
    return minutes * 60_000L + seconds * 1_000L + millis
  }

  private fun sourceLabel(source: LyricsSource): String = when (source) {
    LyricsSource.LOCAL_TTML -> "Local TTML"
    LyricsSource.SPICY -> "Spicy Lyrics"
    LyricsSource.SPOTIFY -> "Spotify"
    LyricsSource.APPLE_MUSIC -> "Apple Music"
    LyricsSource.LRCLIB -> "LRCLIB"
    LyricsSource.UNKNOWN -> "Unknown"
  }

  private data class RawLine(val startMs: Long, val rawText: String)
  private data class RawToken(val startMs: Long, val endMs: Long, val rawText: String, val text: String)
}
