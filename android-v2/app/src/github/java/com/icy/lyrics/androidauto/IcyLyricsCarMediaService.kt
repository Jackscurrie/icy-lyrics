package com.icy.lyrics.androidauto

import android.graphics.Bitmap
import android.media.session.PlaybackState as FrameworkPlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaSessionManager
import androidx.media.MediaSessionManager.RemoteUserInfo
import com.icy.lyrics.IcyLyricsApplication
import com.icy.lyrics.PhoneSessionLease
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.media.SpotifyMediaSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Host-rendered Android Auto surface for the GitHub release.
 *
 * Android Auto owns this service's visual presentation. The service mirrors
 * Spotify's active MediaSession and forwards transport commands; it does not
 * render the parked four-mode lyrics activity or move Spotify audio through a
 * second player.
 */
internal class IcyLyricsCarMediaService : MediaBrowserServiceCompat() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var tracker: SpotifyMediaSessionTracker
  private lateinit var mediaSession: MediaSessionCompat
  private lateinit var lyricsCoordinator: DrivingLyricsCoordinator
  private lateinit var phoneSession: PhoneSessionLease
  private var artworkSource: ImageBitmap? = null
  private var scaledArtwork: Bitmap? = null
  private var lastBrowseFingerprint: String? = null
  private var activeLyric: DrivingLyricLine? = null

  override fun onCreate() {
    super.onCreate()
    val icyApplication = application as IcyLyricsApplication
    tracker = icyApplication.container.mediaTracker
    tracker.start()
    phoneSession = icyApplication.container.acquirePhoneSession()
    lyricsCoordinator = DrivingLyricsCoordinator(phoneSession.viewModel.state, serviceScope)

    mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
      setFlags(
        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
          MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
      )
      setCallback(TransportCallback())
      isActive = true
    }
    sessionToken = mediaSession.sessionToken

    val initialSnapshot = tracker.snapshots.value
    lastBrowseFingerprint = browseFingerprint(initialSnapshot)
    publish(initialSnapshot, lyricFor(initialSnapshot))
    serviceScope.launch {
      tracker.snapshots.collectLatest { snapshot ->
        publish(snapshot, lyricFor(snapshot))
        val fingerprint = browseFingerprint(snapshot)
        if (fingerprint != lastBrowseFingerprint) {
          lastBrowseFingerprint = fingerprint
          notifyChildrenChanged(ROOT_ID)
        }
      }
    }
    serviceScope.launch {
      lyricsCoordinator.activeLine.collectLatest { line ->
        activeLyric = line
        val snapshot = tracker.snapshots.value
        publish(snapshot, lyricFor(snapshot))
      }
    }
  }

  override fun onGetRoot(
    clientPackageName: String,
    clientUid: Int,
    rootHints: Bundle?,
  ): BrowserRoot? {
    // This exported entry point must not expose the current song/lyric to any
    // arbitrary app. Android Auto is trusted by the platform through its media
    // control privileges or enabled notification listener. Verify package/UID
    // ownership as well, rather than trusting a package-name allowlist.
    val ownsPackage = runCatching {
      packageManager.getPackagesForUid(clientUid)?.contains(clientPackageName) == true
    }.getOrDefault(false)
    val trusted = ownsPackage && runCatching {
      MediaSessionManager.getSessionManager(this).isTrustedForMediaControl(
        RemoteUserInfo(clientPackageName, -1, clientUid),
      )
    }.getOrDefault(false)
    return if (isTrustedCarController(clientUid, applicationInfo.uid, ownsPackage, trusted)) {
      BrowserRoot(ROOT_ID, null)
    } else {
      null
    }
  }

  override fun onLoadChildren(
    parentId: String,
    result: Result<List<MediaBrowserCompat.MediaItem>>,
  ) {
    if (parentId != ROOT_ID) {
      result.sendResult(emptyList())
      return
    }
    result.sendResult(listOf(browseItem(tracker.snapshots.value)))
  }

  override fun onDestroy() {
    serviceScope.cancel()
    phoneSession.close()
    mediaSession.release()
    scaledArtwork = null
    artworkSource = null
    super.onDestroy()
  }

  private fun publish(snapshot: NowPlayingSnapshot?, activeLyricText: String?) {
    mediaSession.setMetadata(metadata(snapshot, activeLyricText))
    mediaSession.setPlaybackState(playbackState(snapshot))
  }

  private fun metadata(
    snapshot: NowPlayingSnapshot?,
    activeLyricText: String?,
  ): MediaMetadataCompat {
    val title = snapshot?.displayTitle ?: "Start a song in Spotify"
    val artist = snapshot?.displayArtist ?: "Icy Lyrics"
    val builder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CURRENT_MEDIA_ID)
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot?.album.orEmpty())
      // DISPLAY_* fields are the supported way to offer a driver-facing text
      // hierarchy. The host chooses their actual position and truncation.
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
      .putString(
        MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
        activeLyricText ?: artist,
      )
      .putString(
        MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
        if (activeLyricText == null) snapshot?.album.orEmpty() else artist,
      )

    snapshot?.durationMs?.takeIf { it > 0L }?.let {
      builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it)
    }
    carArtwork(snapshot?.artwork)?.let {
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }
    return builder.build()
  }

  private fun playbackState(snapshot: NowPlayingSnapshot?): PlaybackStateCompat {
    if (snapshot == null) {
      val message = if (tracker.hasNotificationAccess()) {
        "Start a song in Spotify on your phone"
      } else {
        "Grant Icy Lyrics notification access on your phone"
      }
      return PlaybackStateCompat.Builder()
        .setActions(carPlaybackActions(hasDuration = false))
        .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message)
        .setState(PlaybackStateCompat.STATE_ERROR, 0L, 0f)
        .build()
    }
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val hasDuration = snapshot.durationMs?.let { it > 0L } == true
    return PlaybackStateCompat.Builder()
      .setActions(carPlaybackActions(hasDuration))
      .setState(
        carPlaybackState(snapshot.playbackState),
        snapshot.currentPositionMs(nowElapsedMs),
        snapshot.effectivePlaybackSpeed,
        nowElapsedMs,
      )
      .build()
  }

  private fun browseFingerprint(snapshot: NowPlayingSnapshot?): String = listOf(
    snapshot?.rawMediaId,
    snapshot?.rawUri,
    snapshot?.displayTitle,
    snapshot?.displayArtist,
    snapshot?.album,
    snapshot?.durationMs,
  ).joinToString(separator = "\u0000")

  private fun browseItem(snapshot: NowPlayingSnapshot?): MediaBrowserCompat.MediaItem {
    val activeLyricText = lyricFor(snapshot)
    val description = MediaDescriptionCompat.Builder()
      .setMediaId(CURRENT_MEDIA_ID)
      .setTitle(snapshot?.displayTitle ?: "Start a song in Spotify")
      .setSubtitle(activeLyricText ?: snapshot?.displayArtist ?: "Icy Lyrics will mirror it here")
      .setDescription(if (activeLyricText == null) snapshot?.album else snapshot?.displayArtist)
      .build()
    return MediaBrowserCompat.MediaItem(
      description,
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )
  }

  private fun carArtwork(source: ImageBitmap?): Bitmap? {
    if (artworkSource === source) return scaledArtwork
    artworkSource = source
    val bitmap = source?.let { runCatching { it.asAndroidBitmap() }.getOrNull() }
    scaledArtwork = bitmap?.scaledToFit(MAX_ARTWORK_EDGE_PX)
    return scaledArtwork
  }

  private inner class TransportCallback : MediaSessionCompat.Callback() {
    override fun onPrepare() {
      val snapshot = tracker.snapshots.value
      publish(snapshot, lyricFor(snapshot))
    }

    override fun onPlay() {
      if (tracker.snapshots.value?.isPlaying == false) tracker.playPause()
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = onPlay()

    override fun onPause() {
      if (tracker.snapshots.value?.isPlaying == true) tracker.playPause()
    }

    override fun onStop() = onPause()

    override fun onSkipToPrevious() = tracker.previous()

    override fun onSkipToNext() = tracker.next()

    override fun onSeekTo(pos: Long) = tracker.seekTo(pos)
  }

  private fun lyricFor(snapshot: NowPlayingSnapshot?): String? {
    val trackKey = snapshot?.identity?.exactStorageKey ?: return null
    return drivingLyricTextForTrack(activeLyric, trackKey)
  }

  private fun Bitmap.scaledToFit(maxEdgePx: Int): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxEdgePx || longestEdge <= 0) return this
    val scale = maxEdgePx.toFloat() / longestEdge
    return Bitmap.createScaledBitmap(
      this,
      (width * scale).roundToInt().coerceAtLeast(1),
      (height * scale).roundToInt().coerceAtLeast(1),
      true,
    )
  }

  private companion object {
    const val SESSION_TAG = "IcyLyricsAndroidAuto"
    const val ROOT_ID = "icy-lyrics-root"
    const val CURRENT_MEDIA_ID = "icy-lyrics-current-spotify-track"
    const val MAX_ARTWORK_EDGE_PX = 320
  }
}

internal fun isTrustedCarController(
  callerUid: Int,
  applicationUid: Int,
  packageBelongsToUid: Boolean,
  trustedForMediaControl: Boolean,
): Boolean = callerUid >= 0 && packageBelongsToUid &&
  (callerUid == applicationUid || trustedForMediaControl)

internal fun drivingLyricTextForTrack(
  activeLyric: DrivingLyricLine?,
  trackKey: String?,
): String? = activeLyric?.takeIf { trackKey != null && it.trackKey == trackKey }?.text

internal fun carPlaybackState(frameworkState: Int?): Int = when (frameworkState) {
  FrameworkPlaybackState.STATE_STOPPED -> PlaybackStateCompat.STATE_STOPPED
  FrameworkPlaybackState.STATE_PAUSED -> PlaybackStateCompat.STATE_PAUSED
  FrameworkPlaybackState.STATE_PLAYING -> PlaybackStateCompat.STATE_PLAYING
  FrameworkPlaybackState.STATE_FAST_FORWARDING -> PlaybackStateCompat.STATE_FAST_FORWARDING
  FrameworkPlaybackState.STATE_REWINDING -> PlaybackStateCompat.STATE_REWINDING
  FrameworkPlaybackState.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
  FrameworkPlaybackState.STATE_ERROR -> PlaybackStateCompat.STATE_ERROR
  FrameworkPlaybackState.STATE_CONNECTING -> PlaybackStateCompat.STATE_CONNECTING
  FrameworkPlaybackState.STATE_SKIPPING_TO_PREVIOUS -> PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
  FrameworkPlaybackState.STATE_SKIPPING_TO_NEXT -> PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
  FrameworkPlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM
  else -> PlaybackStateCompat.STATE_NONE
}

internal fun carPlaybackActions(hasDuration: Boolean): Long {
  var actions = PlaybackStateCompat.ACTION_PLAY or
    PlaybackStateCompat.ACTION_PAUSE or
    PlaybackStateCompat.ACTION_PLAY_PAUSE or
    PlaybackStateCompat.ACTION_STOP or
    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
  if (hasDuration) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
  return actions
}
