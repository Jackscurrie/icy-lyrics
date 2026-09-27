package com.icy.lyrics.media

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.timing.PlaybackSample
import com.icy.lyrics.core.platform.storage.TrackKeys
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToLong

object TrackIdentityExtractor {
  private val spotifyTrack = Regex(
    """spotify:track:([A-Za-z0-9]{22})(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
  )
  private val encodedSpotifyTrack = Regex(
    """spotify%3Atrack%3A([A-Za-z0-9]{22})(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
  )
  private val spotifyUrl = Regex(
    """open\.spotify\.com/track/([A-Za-z0-9]{22})(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
  )
  private val bareSpotifyId = Regex("""^[A-Za-z0-9]{22}$""")
  private val spotifyQualityBadge = Regex(
    """\s*[•·]\s*Lossless\s*$""",
    RegexOption.IGNORE_CASE,
  )

  fun from(snapshot: NowPlayingSnapshot): TrackIdentity {
    val candidates = buildList {
      snapshot.rawMediaId?.let(::add)
      snapshot.rawUri?.let(::add)
      snapshot.extras.values.forEach(::add)
    }.map(String::trim).filter(String::isNotEmpty)

    val localUri = candidates.firstOrNull { it.startsWith("spotify:local:", ignoreCase = true) }
    val explicitSpotifyId = candidates.firstNotNullOfOrNull(::extractExplicitSpotifyId)
    val keyedExtrasId = snapshot.extras.entries.firstNotNullOfOrNull { (key, value) ->
      val specificKey = key.contains("spotify", ignoreCase = true) ||
        key.contains("track", ignoreCase = true) ||
        key.equals(MEDIA3_COMPAT_MEDIA_ID_KEY, ignoreCase = true)
      value.trim().takeIf { specificKey && bareSpotifyId.matches(it) }
    }
    // The Media3 state extra is explicitly track-specific, so prefer it over a
    // generic description id. Retain the legacy bare-id fallback because some
    // Spotify sessions expose the track only through MediaDescription.mediaId.
    val rawMediaId = snapshot.rawMediaId?.trim()?.takeIf(bareSpotifyId::matches)
    val exact = localUri ?: (explicitSpotifyId ?: keyedExtrasId ?: rawMediaId)
      ?.let { "spotify:track:$it" }

    val metadataIdentity = TrackIdentity(
      uri = "metadata:pending",
      title = snapshot.title.orEmpty(),
      artists = snapshot.artist
        ?.takeIf(String::isNotBlank)
        ?.let(::withoutSpotifyQualityBadge)
        ?.let(::listOf)
        .orEmpty(),
      album = snapshot.album.orEmpty(),
      durationMs = snapshot.durationMs,
    )
    return metadataIdentity.copy(
      uri = exact ?: "metadata:${TrackKeys.metadata(metadataIdentity)}",
    )
  }

  private fun extractExplicitSpotifyId(value: String): String? =
    spotifyTrack.find(value)?.groupValues?.getOrNull(1)
      ?: encodedSpotifyTrack.find(value)?.groupValues?.getOrNull(1)
      ?: spotifyUrl.find(value)?.groupValues?.getOrNull(1)

  private fun withoutSpotifyQualityBadge(value: String): String {
    val original = value.trim()
    return spotifyQualityBadge.replace(original, "").trim().ifBlank { original }
  }

  private const val MEDIA3_COMPAT_MEDIA_ID_KEY =
    "androidx.media.PlaybackStateCompat.Extras.KEY_MEDIA_ID"
}

data class NowPlayingSnapshot(
  val packageName: String,
  val title: String?,
  val artist: String?,
  val album: String?,
  val durationMs: Long?,
  val positionMs: Long,
  val playbackSpeed: Float,
  val playbackState: Int,
  val artwork: ImageBitmap?,
  val capturedAtElapsedMs: Long,
  val rawMediaId: String?,
  val rawUri: String?,
  val extras: Map<String, String>,
  val availableActions: Long,
) {
  val identity: TrackIdentity by lazy(LazyThreadSafetyMode.NONE) { TrackIdentityExtractor.from(this) }
  val isPlaying: Boolean get() = playbackState == 3
  val displayTitle: String get() = title?.takeIf(String::isNotBlank) ?: "Unknown track"
  val displayArtist: String get() = artist?.takeIf(String::isNotBlank) ?: "Unknown artist"

  /**
   * MediaSession implementations occasionally publish a non-finite speed while
   * replacing their playback state. Keep the UI clock alive for a playing
   * session without allowing an invalid framework value into PlaybackSample.
   */
  val effectivePlaybackSpeed: Float
    get() = if (isPlaying) {
      // A few MediaSession publishers leave Builder's default zero speed in a
      // STATE_PLAYING update. Playing at zero is internally contradictory, so
      // use the platform's normal-rate convention instead of freezing time.
      playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
    } else {
      playbackSpeed.takeIf { it.isFinite() && it >= 0f } ?: 0f
    }

  fun currentPositionMs(nowElapsedMs: Long): Long {
    val elapsed = if (isPlaying) (nowElapsedMs - capturedAtElapsedMs).coerceAtLeast(0L) else 0L
    val interpolated = positionMs.coerceAtLeast(0L) +
      (elapsed * effectivePlaybackSpeed).roundToLong()
    return durationMs?.takeIf { it > 0L }?.let { interpolated.coerceIn(0L, it) }
      ?: interpolated.coerceAtLeast(0L)
  }

  fun asPlaybackSample(): PlaybackSample = PlaybackSample(
    trackUri = identity.exactStorageKey,
    positionMs = positionMs.coerceAtLeast(0L),
    sampledAtMs = capturedAtElapsedMs.coerceAtLeast(0L),
    isPlaying = isPlaying,
    playbackSpeed = effectivePlaybackSpeed,
    durationMs = durationMs?.coerceAtLeast(0L),
  )
}

