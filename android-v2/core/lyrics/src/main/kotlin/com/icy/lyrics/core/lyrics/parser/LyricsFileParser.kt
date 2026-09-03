package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource

/** Format-detecting facade for file/document providers. */
object LyricsFileParser {
  @JvmStatic
  fun parse(
    raw: String,
    trackUri: String,
    source: LyricsSource = LyricsSource.LOCAL_TTML,
    durationMs: Long? = null,
  ): LyricsDocument {
    val content = raw.removePrefix("\uFEFF").trimStart()
    if (content.isBlank()) throw IllegalArgumentException("Lyrics file is empty")
    return when {
      content.startsWith("<") -> TtmlParser.parse(content, trackUri, source)
      content.startsWith("{") -> IcyLyricsJsonParser.parse(content, trackUri, source)
      LyricsfileYamlParser.looksLikeLyricsfile(content) -> LyricsfileYamlParser.parse(content, trackUri, source)
      LRC_MARKER.containsMatchIn(content) -> LrcParser.parse(content, trackUri, source, durationMs)
        ?: throw IllegalArgumentException("LRC contains no displayable timed lines")
      else -> LrcParser.parsePlain(content, trackUri, source)
        ?: throw IllegalArgumentException("Lyrics file contains no displayable lines")
    }
  }

  private val LRC_MARKER = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}")
}
