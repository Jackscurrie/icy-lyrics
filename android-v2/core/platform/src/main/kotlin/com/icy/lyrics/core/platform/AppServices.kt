package com.icy.lyrics.core.platform

import android.content.Context
import com.icy.lyrics.core.lyrics.provider.LyricsOrchestrator
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.platform.auth.KeystoreSpotifyCredentialStore
import com.icy.lyrics.core.platform.auth.SpotifyPkceClient
import com.icy.lyrics.core.platform.auth.SpotifyPkceConfig
import com.icy.lyrics.core.platform.database.IcyLyricsDatabase
import com.icy.lyrics.core.platform.diagnostics.DiagnosticRepository
import com.icy.lyrics.core.platform.migration.LegacyPreferenceCleaner
import com.icy.lyrics.core.platform.provider.LocalTtmlProvider
import com.icy.lyrics.core.platform.provider.LrclibConfig
import com.icy.lyrics.core.platform.provider.LrclibProvider
import com.icy.lyrics.core.platform.provider.PlatformLyricsResolver
import com.icy.lyrics.core.platform.provider.SpicyLyricsConfig
import com.icy.lyrics.core.platform.provider.SpicyHostCircuitBreaker
import com.icy.lyrics.core.platform.provider.SpicyLyricsProvider
import com.icy.lyrics.core.platform.provider.SpotifyAccessTokenSource
import com.icy.lyrics.core.platform.provider.SpotifyCatalogConfig
import com.icy.lyrics.core.platform.provider.SpotifyTrackResolver
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import com.icy.lyrics.core.platform.storage.TrackAliasRepository
import com.icy.lyrics.core.platform.timing.BluetoothRouteMonitor
import com.icy.lyrics.core.platform.timing.BluetoothTimingResolver
import com.icy.lyrics.core.platform.timing.DeviceTimingRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppServices private constructor(
  val database: IcyLyricsDatabase,
  val settings: SettingsRepository,
  val localTtmlRepository: LocalTtmlRepository,
  val lyricsCacheRepository: LyricsCacheRepository,
  val aliasRepository: TrackAliasRepository,
  val diagnostics: DiagnosticRepository,
  val deviceTimingRepository: DeviceTimingRepository,
  val bluetoothRouteMonitor: BluetoothRouteMonitor,
  val bluetoothTimingResolver: BluetoothTimingResolver,
  val legacyPreferenceCleaner: LegacyPreferenceCleaner,
  val localTtmlProvider: LocalTtmlProvider,
  val spotifyPkceClient: SpotifyPkceClient?,
  val spotifyTrackResolver: SpotifyTrackResolver,
  val spicyProvider: SpicyLyricsProvider,
  val spotifyProvider: SpicyLyricsProvider,
  val appleMusicProvider: SpicyLyricsProvider,
  val lrclibProvider: LrclibProvider,
  val lyricsResolver: PlatformLyricsResolver,
) {
  companion object {
    fun create(
      context: Context,
      spotifyClientId: String = "",
      spotifyScopes: Set<String> = emptySet(),
      spotifyAccessTokenSource: SpotifyAccessTokenSource? = null,
      httpClient: OkHttpClient = defaultHttpClient(),
      spicyConfig: SpicyLyricsConfig = SpicyLyricsConfig(),
      lrclibConfig: LrclibConfig = LrclibConfig(),
      spotifyCatalogConfig: SpotifyCatalogConfig = SpotifyCatalogConfig(),
    ): AppServices {
      val appContext = context.applicationContext
      val database = IcyLyricsDatabase.open(appContext)
      val settings = SettingsRepository(appContext)
      val local = LocalTtmlRepository(database.localTtmlDao())
      val cache = LyricsCacheRepository(database.lyricsCacheDao())
      val aliases = TrackAliasRepository(database.trackAliasDao())
      val diagnostics = DiagnosticRepository(database.diagnosticEventDao())
      val pkce = spotifyClientId.takeIf(String::isNotBlank)?.let { clientId ->
        SpotifyPkceClient(
          config = SpotifyPkceConfig(clientId = clientId, scopes = spotifyScopes),
          client = httpClient,
          credentials = KeystoreSpotifyCredentialStore(appContext),
          diagnostics = diagnostics,
        )
      }
      val effectiveTokenSource = spotifyAccessTokenSource ?: pkce ?: SpotifyAccessTokenSource { null }
      val spotifyTrackResolver = SpotifyTrackResolver(
        client = httpClient,
        tokenSource = effectiveTokenSource,
        aliases = aliases,
        config = spotifyCatalogConfig,
        diagnostics = diagnostics,
      )
      val deviceTimings = DeviceTimingRepository(database.deviceTimingDao())
      val routeMonitor = BluetoothRouteMonitor(appContext)
      val timingResolver = BluetoothTimingResolver(settings, deviceTimings, routeMonitor)
      val legacyCleaner = LegacyPreferenceCleaner(appContext, settings)
      val localProvider = LocalTtmlProvider(local) { settings.current().useLocalTtml }
      val spicyHostCircuitBreaker = SpicyHostCircuitBreaker()
      val spicyProvider = SpicyLyricsProvider(
        id = LyricsProviderId.SPICY,
        client = httpClient,
        tokenSource = effectiveTokenSource,
        cache = cache,
        config = spicyConfig,
        enabled = { settings.current().spicyEnabled },
        tokenSharingConsent = { settings.current().spicyTokenSharingConsent },
        diagnostics = diagnostics,
        hostCircuitBreaker = spicyHostCircuitBreaker,
      )
      val spotify = SpicyLyricsProvider(
        id = LyricsProviderId.SPOTIFY,
        client = httpClient,
        tokenSource = effectiveTokenSource,
        cache = cache,
        config = spicyConfig,
        enabled = { settings.current().spicyEnabled },
        tokenSharingConsent = { settings.current().spicyTokenSharingConsent },
        diagnostics = diagnostics,
        hostCircuitBreaker = spicyHostCircuitBreaker,
      )
      val apple = SpicyLyricsProvider(
        id = LyricsProviderId.APPLE_MUSIC,
        client = httpClient,
        tokenSource = effectiveTokenSource,
        cache = cache,
        config = spicyConfig,
        enabled = { settings.current().spicyEnabled },
        tokenSharingConsent = { settings.current().spicyTokenSharingConsent },
        diagnostics = diagnostics,
        hostCircuitBreaker = spicyHostCircuitBreaker,
      )
      val lrclib = LrclibProvider(
        client = httpClient,
        cache = cache,
        config = lrclibConfig,
        enabled = { settings.current().lrclibEnabled },
        diagnostics = diagnostics,
      )
      val orchestrator = LyricsOrchestrator(
        listOf(localProvider, spicyProvider, lrclib, apple, spotify),
      )
      val resolver = PlatformLyricsResolver(orchestrator, settings, diagnostics)
      return AppServices(
        database,
        settings,
        local,
        cache,
        aliases,
        diagnostics,
        deviceTimings,
        routeMonitor,
        timingResolver,
        legacyCleaner,
        localProvider,
        pkce,
        spotifyTrackResolver,
        spicyProvider,
        spotify,
        apple,
        lrclib,
        resolver,
      )
    }

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .connectTimeout(10L, TimeUnit.SECONDS)
      .readTimeout(20L, TimeUnit.SECONDS)
      .writeTimeout(20L, TimeUnit.SECONDS)
      .callTimeout(30L, TimeUnit.SECONDS)
      .build()
  }
}
