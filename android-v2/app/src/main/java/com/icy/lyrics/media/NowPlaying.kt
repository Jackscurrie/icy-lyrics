package com.icy.lyrics.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap

object NowPlayingMapper {
  fun fromController(controller: MediaController): NowPlayingSnapshot? {
    val metadata = controller.metadata ?: return null
    val state = controller.playbackState
    val description = metadata.description
    val activeQueueDescription = state?.activeQueueItemId
      ?.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }
      ?.let { activeId ->
        controller.queue?.firstOrNull { it.queueId == activeId }?.description
      }
    val extras = buildMap {
      metadata.keySet().forEach { key -> metadata.textOrNull(key)?.let { put(key, it) } }
      putBundleValues(description.extras)
      putBundleValues(activeQueueDescription?.extras)
      putBundleValues(controller.extras)
      // Media3 can expose the current media id to legacy/platform controllers
      // in PlaybackState extras. Keep these item-specific values last so a
      // same-named session extra cannot replace them.
      putBundleValues(state?.extras)
    }
    val rawUri = activeQueueDescription?.mediaUri?.toString()
      ?: description.mediaUri?.toString()
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
      artwork = (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: description.iconBitmap)?.asImageBitmap(),
      capturedAtElapsedMs = state?.lastPositionUpdateTime?.takeIf { it > 0L }
        ?: SystemClock.elapsedRealtime(),
      rawMediaId = activeQueueDescription?.mediaId
        ?: description.mediaId
        ?: metadata.textOrNull(MediaMetadata.METADATA_KEY_MEDIA_ID),
      rawUri = rawUri,
      extras = extras,
      availableActions = state?.actions ?: 0L,
    )
  }

  private fun MediaMetadata.textOrNull(key: String): String? = runCatching { getText(key) }
    .getOrNull()?.toString()?.takeIf(String::isNotBlank)

  private fun MutableMap<String, String>.putBundleValues(bundle: Bundle?) {
    bundle?.keySet()?.forEach { key ->
      @Suppress("DEPRECATION")
      runCatching { bundle.get(key) }
        .getOrNull()
        ?.toString()
        ?.takeIf(String::isNotBlank)
        ?.let { put(key, it) }
    }
  }
}
