package com.icy.lyrics.core.platform.storage

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import okio.ByteString.Companion.encodeUtf8
import com.icy.lyrics.core.platform.runtime.normalizeNfd
import kotlin.math.roundToLong

object TrackKeys {
  fun exact(track: TrackIdentity): String = track.exactStorageKey

  /** Metadata matching is a fallback only when the media session supplied no stable URI. */
  fun mayUseMetadataFallback(track: TrackIdentity): Boolean =
    track.uri.startsWith("metadata:", ignoreCase = true)

  fun metadata(track: TrackIdentity): String {
    val durationBucket = track.durationMs
      ?.takeIf { it > 0L }
      ?.let { (it / 5_000.0).roundToLong() * 5_000L }
      ?: 0L
    return listOf(
      normalize(track.title),
      track.artists.joinToString(" ") { normalize(it) },
      normalize(track.album),
      durationBucket.toString(),
    ).joinToString("|")
  }

  fun privacyHash(value: String): String {
    return value.encodeUtf8().sha256().hex().take(16)
  }

  private fun normalize(value: String): String {
    return normalizeNfd(value)
      .replace(Regex("""\p{Mn}+"""), "")
      .lowercase()
      .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
      .trim()
      .replace(Regex("""\s+"""), " ")
  }
}
