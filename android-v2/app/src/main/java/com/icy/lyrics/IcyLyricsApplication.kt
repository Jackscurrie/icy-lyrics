package com.icy.lyrics

import android.app.Application
import android.content.Context
import com.icy.lyrics.core.platform.AppServices
import com.icy.lyrics.core.platform.provider.IcyLyricsDatabaseConfig
import com.icy.lyrics.core.platform.provider.SpotifyCatalogConfig
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
  // The phone activity and its opt-in floating window share one resolver/clock.
  // Opening or collapsing the overlay must not create another lyrics request.
  private val phoneSession = PhoneSession(context.applicationContext as Application)
  val phoneViewModel get() = phoneSession.viewModel
  fun acquirePhoneSession() = phoneSession.acquire()
  private val icyDatabaseConfig = IcyLyricsDatabaseConfig(
    userAgent = "IcyLyricsAndroid/${BuildConfig.VERSION_NAME} (+https://jackscurrie.com/icy-lyrics)",
  )
  val services: AppServices = AppServices.create(
    context = context,
    spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID,
    spotifyScopes = setOf("user-read-currently-playing"),
    icyDatabaseConfig = icyDatabaseConfig,
    spotifyCatalogConfig = SpotifyCatalogConfig(
      icyCatalogEndpoint = if (BuildConfig.FLAVOR == "tv") {
        icyDatabaseConfig.endpoint
      } else {
        null
      },
      icyCatalogUserAgent =
        "IcyLyricsAndroidTV/${BuildConfig.VERSION_NAME} (+https://jackscurrie.com/icy-lyrics)",
    ),
  )
  val mediaTracker = SpotifyMediaSessionTracker(context)
}
