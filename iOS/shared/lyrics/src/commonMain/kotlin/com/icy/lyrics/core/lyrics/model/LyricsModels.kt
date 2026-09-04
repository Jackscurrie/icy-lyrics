package com.icy.lyrics.core.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The normalized timing resolution understood by every Icy Lyrics renderer. */
@Serializable
enum class LyricsSyncKind {
  STATIC,
  LINE,
  SYLLABLE,
}

/** Stable source identity. The short codes match the desktop payload contract. */
@Serializable
enum class LyricsSource(val code: String) {
  LOCAL_TTML("ldb"),
  SPICY("spl"),
  SPOTIFY("spt"),
  APPLE_MUSIC("aml"),
  LRCLIB("lrc"),
  UNKNOWN("unknown");

  companion object {
    fun fromCode(value: String?): LyricsSource {
      val normalized = value
        ?.trim()
        ?.lowercase()
        ?.replace("_", "")
        ?.replace("-", "")
        ?.replace(" ", "")
        ?: return UNKNOWN

      return when (normalized) {
        "ldb", "local", "localdb", "localttml" -> LOCAL_TTML
        "spl", "spicy", "spicylyrics", "lyricsdatabase" -> SPICY
        "spt", "spotify" -> SPOTIFY
        "aml", "apple", "applemusic" -> APPLE_MUSIC
        "lrc", "lrclib" -> LRCLIB
        else -> UNKNOWN
      }
    }
  }
}

/**
 * Exact playback identity plus the metadata needed by provider fallback matching.
 *
 * [uri] is deliberately never split for persistence. In particular,
 * `spotify:local:Artist:Album:Title:duration` must remain whole.
 */
@Serializable
data class TrackIdentity(
  val uri: String,
  val title: String = "",
  val artists: List<String> = emptyList(),
  val album: String = "",
  val durationMs: Long? = null,
  val isrc: String? = null,
  val artworkUri: String? = null,
) {
  init {
    require(uri.isNotBlank()) { "Track URI must not be blank" }
    require(durationMs == null || durationMs >= 0L) { "Track duration must not be negative" }
  }

  val isSpotifyLocal: Boolean
    get() = uri.startsWith(SPOTIFY_LOCAL_PREFIX, ignoreCase = true)

  val spotifyTrackId: String?
    get() {
      if (!uri.startsWith(SPOTIFY_TRACK_PREFIX, ignoreCase = true)) return null
      return uri.substring(SPOTIFY_TRACK_PREFIX.length).takeIf { it.isNotBlank() && ':' !in it }
    }

  /** The only valid key for local lyric persistence. */
  val exactStorageKey: String
    get() = uri

  companion object {
    const val SPOTIFY_LOCAL_PREFIX = "spotify:local:"
    const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
  }
}

@Serializable
data class LyricsMetadata(
  val trackUri: String? = null,
  val source: LyricsSource = LyricsSource.UNKNOWN,
  val sourceLabel: String? = null,
  val songwriters: List<String> = emptyList(),
  val language: String? = null,
  val hasTransliterations: Boolean = false,
  val instrumental: Boolean = false,
)

/** Serializable, renderer-ready lyric document. Every timestamp is milliseconds. */
@Serializable
sealed interface LyricsDocument {
  val metadata: LyricsMetadata
  val syncKind: LyricsSyncKind
}

@Serializable
@SerialName("Static")
data class StaticLyrics(
  override val metadata: LyricsMetadata = LyricsMetadata(),
  val lines: List<StaticLyricLine>,
) : LyricsDocument {
  override val syncKind: LyricsSyncKind = LyricsSyncKind.STATIC
}

@Serializable
data class StaticLyricLine(
  val text: String,
  val transliteratedText: String? = null,
)

@Serializable
@SerialName("Line")
data class LineLyrics(
  override val metadata: LyricsMetadata = LyricsMetadata(),
  val lines: List<TimedLyricLine>,
) : LyricsDocument {
  override val syncKind: LyricsSyncKind = LyricsSyncKind.LINE
}

@Serializable
data class TimedLyricLine(
  val text: String,
  val startMs: Long,
  val endMs: Long,
  val transliteratedText: String? = null,
  val oppositeAligned: Boolean = false,
) {
  init {
    require(startMs >= 0L) { "Line start must not be negative" }
    require(endMs >= startMs) { "Line end must not precede its start" }
  }
}

@Serializable
@SerialName("Syllable")
data class SyllableLyrics(
  override val metadata: LyricsMetadata = LyricsMetadata(),
  val lines: List<SyllableLyricLine>,
) : LyricsDocument {
  override val syncKind: LyricsSyncKind = LyricsSyncKind.SYLLABLE
}

@Serializable
data class SyllableLyricLine(
  val lead: VocalLine,
  val background: List<VocalLine> = emptyList(),
  val oppositeAligned: Boolean = false,
)

@Serializable
data class VocalLine(
  val startMs: Long,
  val endMs: Long,
  val tokens: List<LyricToken>,
  val oppositeAligned: Boolean = false,
  val transliteratedText: String? = null,
) {
  init {
    require(startMs >= 0L) { "Vocal start must not be negative" }
    require(endMs >= startMs) { "Vocal end must not precede its start" }
  }

  val text: String
    get() = joinTokens(tokens) { it.text }

  val transliteration: String?
    get() {
      transliteratedText?.takeIf(String::isNotBlank)?.let { return it }
      if (tokens.none { !it.transliteratedText.isNullOrBlank() }) return null
      return joinTokens(tokens) { it.transliteratedText ?: it.text }
    }
}

@Serializable
data class LyricToken(
  val text: String,
  val startMs: Long,
  val endMs: Long,
  val isPartOfWord: Boolean = false,
  val transliteratedText: String? = null,
) {
  init {
    require(startMs >= 0L) { "Token start must not be negative" }
    require(endMs >= startMs) { "Token end must not precede its start" }
  }
}

fun LyricsDocument.withMetadata(metadata: LyricsMetadata): LyricsDocument = when (this) {
  is StaticLyrics -> copy(metadata = metadata)
  is LineLyrics -> copy(metadata = metadata)
  is SyllableLyrics -> copy(metadata = metadata)
}

fun LyricsDocument.withSource(source: LyricsSource, sourceLabel: String? = null): LyricsDocument =
  withMetadata(metadata.copy(source = source, sourceLabel = sourceLabel ?: metadata.sourceLabel))

fun LyricsDocument.withTrackUri(uri: String): LyricsDocument =
  withMetadata(metadata.copy(trackUri = uri))

private fun joinTokens(tokens: List<LyricToken>, text: (LyricToken) -> String): String = buildString {
  tokens.forEachIndexed { index, token ->
    if (index > 0 && !tokens[index - 1].isPartOfWord) append(' ')
    append(text(token))
  }
}.trim()
