package com.icy.lyrics.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpotifyMediaSessionTracker(context: Context) {
  private val appContext = context.applicationContext
  private val manager = appContext.getSystemService(MediaSessionManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private val mutableSnapshot = MutableStateFlow<NowPlayingSnapshot?>(null)
  private var activeController: MediaController? = null
  private var started = false

  val snapshots: StateFlow<NowPlayingSnapshot?> = mutableSnapshot.asStateFlow()

  private val controllerCallback = object : MediaController.Callback() {
    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publish()
    override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = publish()
    override fun onSessionDestroyed() {
      setController(null)
      // Spotify can replace its MediaSession token during route or process
      // changes. Re-scan so an already-active replacement is adopted.
      handler.post(::refresh)
    }
  }

  private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
    chooseController(controllers.orEmpty())
  }

  fun hasNotificationAccess(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

  fun notificationAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

  fun start() {
    if (!hasNotificationAccess()) {
      clearSessionRegistration()
      return
    }
    if (!started) {
      started = runCatching {
        manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), handler)
        true
      }.getOrDefault(false)
      if (!started) {
        setController(null)
        return
      }
    }
    refresh()
  }

  fun stop() {
    clearSessionRegistration()
  }

  fun refresh() {
    if (!hasNotificationAccess()) {
      setController(null)
      return
    }
    runCatching { manager.getActiveSessions(listenerComponent()) }
      .onSuccess(::chooseController)
      .onFailure {
        // Permission can be revoked between the package-level access check and
        // this framework call. Never retain a controller after that race.
        if (it is SecurityException) clearSessionRegistration() else setController(null)
      }
  }

  fun playPause() {
    val controller = activeController ?: return
    if (mutableSnapshot.value?.isPlaying == true) controller.transportControls.pause()
    else controller.transportControls.play()
  }

  fun previous() = activeController?.transportControls?.skipToPrevious() ?: Unit
  fun next() = activeController?.transportControls?.skipToNext() ?: Unit
  fun seekTo(positionMs: Long) =
    activeController?.transportControls?.seekTo(positionMs.coerceAtLeast(0L)) ?: Unit

  private fun chooseController(controllers: List<MediaController>) {
    val spotify = controllers.filter { it.packageName == SPOTIFY_PACKAGE }
      .sortedWith(
        compareByDescending<MediaController> {
          it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        }.thenByDescending { it.metadata != null },
      )
      .firstOrNull()
    setController(spotify)
  }

  private fun setController(controller: MediaController?) {
    if (activeController?.sessionToken == controller?.sessionToken) {
      publish()
      return
    }
    activeController?.unregisterCallback(controllerCallback)
    activeController = controller
    controller?.registerCallback(controllerCallback, handler)
    publish()
  }

  private fun publish() {
    mutableSnapshot.value = activeController?.let(NowPlayingMapper::fromController)
  }

  private fun clearSessionRegistration() {
    if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
    started = false
    setController(null)
  }

  private fun listenerComponent() =
    ComponentName(appContext, IcyNotificationListenerService::class.java)

  private companion object {
    const val SPOTIFY_PACKAGE = "com.spotify.music"
  }
}
