package com.icy.lyrics.core.platform.ios

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsOrchestrator
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.database.IcyLyricsDatabase
import com.icy.lyrics.core.platform.database.openIosDatabase
import com.icy.lyrics.core.platform.diagnostics.DiagnosticEvent
import com.icy.lyrics.core.platform.diagnostics.DiagnosticRepository
import com.icy.lyrics.core.platform.network.DarwinLyricsHttpClient
import com.icy.lyrics.core.platform.provider.LocalTtmlProvider
import com.icy.lyrics.core.platform.provider.LrclibConfig
import com.icy.lyrics.core.platform.provider.LrclibProvider
import com.icy.lyrics.core.platform.provider.PlatformLyricsResolver
import com.icy.lyrics.core.platform.provider.SpicyHostCircuitBreaker
import com.icy.lyrics.core.platform.provider.SpicyLyricsProvider
import com.icy.lyrics.core.platform.provider.SpotifyAccessTokenSource
import com.icy.lyrics.core.platform.provider.SpotifyTrackResolver
import com.icy.lyrics.core.platform.settings.AppSettings
import com.icy.lyrics.core.platform.settings.IosSettingsBackend
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import com.icy.lyrics.core.platform.storage.StoredLocalTtml
import com.icy.lyrics.core.platform.storage.TrackAliasRepository
import com.icy.lyrics.core.platform.timing.BluetoothRoute
import com.icy.lyrics.core.platform.timing.BluetoothTimingResolver
import com.icy.lyrics.core.platform.timing.DeviceTiming
import com.icy.lyrics.core.platform.timing.DeviceTimingRepository
import com.icy.lyrics.core.platform.timing.EffectiveTimingOffset
import kotlinx.coroutines.flow.Flow

/** Native composition root. All lyric selection, parsing and stored document semantics are shared. */
class IosServices private constructor(
  private val database: IcyLyricsDatabase,
  private val http: DarwinLyricsHttpClient,
  private val settingsRepository: SettingsRepository,
  private val local: LocalTtmlRepository,
  private val cache: LyricsCacheRepository,
  private val diagnostics: DiagnosticRepository,
  localProvider: LocalTtmlProvider,
  private val catalog: SpotifyTrackResolver,
  private val resolver: PlatformLyricsResolver,
  private val timings: DeviceTimingRepository,
) {
  private val imports = IosLocalTtmlImports(database.localTtmlDao(), provider = localProvider)
  val settings: Flow<AppSettings> get() = settingsRepository.settings
  val library: Flow<List<StoredLocalTtml>> get() = local.observeLibrary()
  val diagnosticEvents: Flow<List<DiagnosticEvent>> get() = diagnostics.observeRecent()
  val deviceTimings: Flow<List<DeviceTiming>> get() = timings.observeAll()

  suspend fun currentSettings(): AppSettings = settingsRepository.current()
  suspend fun updateSettings(value: AppSettings) = settingsRepository.replace(value)
  suspend fun initialize() { diagnostics.prune(); cache.pruneExpired() }

  suspend fun resolve(track: TrackIdentity, allowCached: Boolean = true, requestId: Long = 0L): LyricsResolution {
    // An exact user import must win before even attempting a metadata alias lookup.
    if (settingsRepository.current().useLocalTtml) {
      local.get(track)?.let {
        return LyricsResolution.Found(it.document, LyricsProviderId.LOCAL_TTML, listOf(
          ProviderAttempt(LyricsProviderId.LOCAL_TTML, ProviderAttemptOutcome.FOUND,
            source = it.document.metadata.source, syncKind = it.document.syncKind.name, message = "Saved local TTML"),
        ))
      }
    }
    val resolved = catalog.resolve(track)?.let { track.copy(uri = it) } ?: track
    return resolver.resolve(LyricsRequest(resolved, allowCached = allowCached, requestId = requestId))
  }

  suspend fun importTtml(track: TrackIdentity, rawTtml: String, sourceUri: String? = null) {
    imports.import(track, rawTtml, sourceUri)
  }
  suspend fun deleteSavedLyrics(trackKey: String): Boolean = imports.delete(trackKey)
  suspend fun clearDiagnostics() { diagnostics.clear() }
  fun effectiveTiming(route: Flow<BluetoothRoute?>): Flow<EffectiveTimingOffset> =
    BluetoothTimingResolver(settingsRepository, timings, route).effectiveOffset
  suspend fun rememberTiming(route: BluetoothRoute, offsetMs: Int) = timings.save(route, offsetMs)
  suspend fun deleteTiming(deviceKey: String): Boolean = timings.delete(deviceKey)
  fun close() { http.close(); database.close() }

  companion object {
    fun create(tokenSource: SpotifyAccessTokenSource): IosServices {
      val database = openIosDatabase()
      val http = DarwinLyricsHttpClient()
      val settings = SettingsRepository(IosSettingsBackend())
      val local = LocalTtmlRepository(database.localTtmlDao())
      val cache = LyricsCacheRepository(database.lyricsCacheDao())
      val diagnostics = DiagnosticRepository(database.diagnosticEventDao())
      val aliases = TrackAliasRepository(database.trackAliasDao())
      val localProvider = LocalTtmlProvider(local) { settings.current().useLocalTtml }
      val circuit = SpicyHostCircuitBreaker()
      fun spicy(id: LyricsProviderId) = SpicyLyricsProvider(id, http, tokenSource, cache,
        enabled = { settings.current().spicyEnabled },
        tokenSharingConsent = { settings.current().spicyTokenSharingConsent },
        diagnostics = diagnostics, hostCircuitBreaker = circuit)
      val lrclib = LrclibProvider(http, cache, LrclibConfig(userAgent = "IcyLyricsIOS/1.0"),
        enabled = { settings.current().lrclibEnabled }, diagnostics = diagnostics)
      val resolver = PlatformLyricsResolver(LyricsOrchestrator(listOf(localProvider,
        spicy(LyricsProviderId.SPICY), lrclib, spicy(LyricsProviderId.APPLE_MUSIC), spicy(LyricsProviderId.SPOTIFY))), settings, diagnostics)
      return IosServices(database, http, settings, local, cache, diagnostics, localProvider,
        SpotifyTrackResolver(http, tokenSource, aliases, diagnostics = diagnostics), resolver,
        DeviceTimingRepository(database.deviceTimingDao()))
    }
  }
}
