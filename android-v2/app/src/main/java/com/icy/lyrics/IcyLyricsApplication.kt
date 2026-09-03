package com.icy.lyrics

import android.app.Application
import android.content.Context
import com.icy.lyrics.core.platform.AppServices
import com.icy.lyrics.media.SpotifyMediaSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IcyLyricsApplication : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

  override fun onCreate() {
    super.onCreate()
    container.mediaTracker.start()
    applicationScope.launch {
      container.services.legacyPreferenceCleaner.cleanupOnce()
      container.services.diagnostics.prune()
      container.services.lyricsCacheRepository.pruneExpired()
    }
  }
}

class AppContainer(context: Context) {
  val services: AppServices = AppServices.create(
    context = context,
    spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID,
    spotifyScopes = setOf("user-read-currently-playing"),
  )
  val mediaTracker = SpotifyMediaSessionTracker(context)
}
