package com.icy.lyrics.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.timing.PlaybackSample
import com.icy.lyrics.core.platform.storage.TrackKeys
import kotlin.math.roundToLong

object TrackIdentityExtractor {
  private val spotifyTrack = Regex("""^spotify:track:([A-Za-z0-9]{22})$""")
  private val spotifyUrl = Regex("""open\.spotify\.com/track/([A-Za-z0-9]{22})""")
  private val bareSpotifyId = Regex("""^[A-Za-z0-9]{22}$""")

  fun from(snapshot: NowPlayingSnapshot): TrackIdentity {
    val candidates = buildList {
      snapshot.rawMediaId?.let(::add)
      snapshot.rawUri?.let(::add)
      snapshot.extras.values.forEach(::add)
    }.map(String::trim).filter(String::isNotEmpty)

    val localUri = candidates.firstOrNull { it.startsWith("spotify:local:", ignoreCase = true) }
    val explicitSpotifyId = candidates.firstNotNullOfOrNull(::extractExplicitSpotifyId)
    val rawMediaId = snapshot.rawMediaId?.trim()?.takeIf(bareSpotifyId::matches)
    val keyedExtrasId = snapshot.extras.entries.firstNotNullOfOrNull { (key, value) ->
      val specificKey = key.contains("spotify", ignoreCase = true) ||
        key.contains("track", ignoreCase = true)
      value.trim().takeIf { specificKey && bareSpotifyId.matches(it) }
    }
    val exact = localUri ?: (explicitSpotifyId ?: rawMediaId ?: keyedExtrasId)
      ?.let { "spotify:track:$it" }

    val metadataIdentity = TrackIdentity(
      uri = "metadata:pending",
      title = snapshot.title.orEmpty(),
      artists = snapshot.artist?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
      album = snapshot.album.orEmpty(),
      durationMs = snapshot.durationMs,
    )
    return metadataIdentity.copy(
      uri = exact ?: "metadata:${TrackKeys.metadata(metadataIdentity)}",
    )
  }

  private fun extractExplicitSpotifyId(value: String): String? =
    spotifyTrack.matchEntire(value)?.groupValues?.getOrNull(1)
      ?: spotifyUrl.find(value)?.groupValues?.getOrNull(1)
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
  val artwork: Bitmap?,
  val capturedAtElapsedMs: Long,
  val rawMediaId: String?,
  val rawUri: String?,
  val extras: Map<String, String>,
  val availableActions: Long,
) {
  val identity: TrackIdentity by lazy(LazyThreadSafetyMode.NONE) { TrackIdentityExtractor.from(this) }
  val isPlaying: Boolean get() = playbackState == PlaybackState.STATE_PLAYING
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

  fun currentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
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

object NowPlayingMapper {
  fun fromController(controller: MediaController): NowPlayingSnapshot? {
    val metadata = controller.metadata ?: return null
    val state = controller.playbackState
    val description = metadata.description
    val extras = buildMap {
      metadata.keySet().forEach { key -> metadata.textOrNull(key)?.let { put(key, it) } }
      description.extras?.keySet()?.forEach { key ->
        @Suppress("DEPRECATION")
        description.extras?.get(key)?.toString()?.takeIf(String::isNotBlank)?.let { put(key, it) }
      }
    }
    val rawUri = description.mediaUri?.toString()
      ?: metadata.textOrNull(MediaMetadata.METADATA_KEY_MEDIA_URI)

    return NowPlayingSnapshot(
      packageName = controller.packageName,
      title = metadata.textOrNull(MediaMetadata.METADATA_KEY_TITLE) ?: description.title?.toString(),
      artist = metadata.textOrNull(MediaMetadata.METADATA_KEY_ARTIST)
        ?: metadata.textOrNull(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: description.subtitle?.toString(),
      album = metadata.textOrNull(MediaMetadata.METADATA_KEY_ALBUM),
      durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L },
      positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
      playbackSpeed = state?.playbackSpeed ?: 0f,
      playbackState = state?.state ?: PlaybackState.STATE_NONE,
      artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: description.iconBitmap,
      capturedAtElapsedMs = state?.lastPositionUpdateTime?.takeIf { it > 0L }
        ?: SystemClock.elapsedRealtime(),
      rawMediaId = description.mediaId ?: metadata.textOrNull(MediaMetadata.METADATA_KEY_MEDIA_ID),
      rawUri = rawUri,
      extras = extras,
      availableActions = state?.actions ?: 0L,
    )
  }

  private fun MediaMetadata.textOrNull(key: String): String? = runCatching { getText(key) }
    .getOrNull()?.toString()?.takeIf(String::isNotBlank)
}
