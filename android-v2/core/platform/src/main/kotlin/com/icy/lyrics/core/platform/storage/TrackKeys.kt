package com.icy.lyrics.core.platform.storage

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
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
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun normalize(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
      .lowercase(Locale.ROOT)
      .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
      .trim()
      .replace(Regex("""\s+"""), " ")
  }
}
