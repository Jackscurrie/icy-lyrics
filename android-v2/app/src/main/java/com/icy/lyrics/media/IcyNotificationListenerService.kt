package com.icy.lyrics.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.icy.lyrics.IcyLyricsApplication

class IcyNotificationListenerService : NotificationListenerService() {
  private val tracker: SpotifyMediaSessionTracker
    get() = (application as IcyLyricsApplication).container.mediaTracker

  override fun onListenerConnected() {
    tracker.start()
    tracker.refresh()
  }

  override fun onListenerDisconnected() = tracker.stop()

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn?.packageName == SPOTIFY_PACKAGE) tracker.refresh()
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    if (sbn?.packageName == SPOTIFY_PACKAGE) tracker.refresh()
  }

  private companion object {
    const val SPOTIFY_PACKAGE = "com.spotify.music"
  }
}
