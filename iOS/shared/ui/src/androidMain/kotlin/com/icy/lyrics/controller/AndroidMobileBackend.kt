package com.icy.lyrics.controller

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.AppServices
import com.icy.lyrics.core.platform.settings.AppSettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class AndroidMobileBackend(private val services: AppServices) : MobileBackend {
  override val settings get() = services.settings.settings
  override val library get() = services.localTtmlRepository.observeLibrary()
  override val diagnosticEvents get() = services.diagnostics.observeRecent(limit = 200)
  override val deviceTiming get() = services.bluetoothTimingResolver.effectiveOffset.map {
    MobileDeviceTiming(it.route?.deviceKey, it.route?.displayName,
      it.offsetMs.takeIf { _ -> it.route != null && it.usingRememberedDeviceOffset })
  }
  override suspend fun currentSettings() = services.settings.current()
  override suspend fun updateSettings(value: AppSettings) = services.settings.replace(value)
  override suspend fun resolve(track: TrackIdentity, allowCached: Boolean, requestId: Long): LyricsResolution {
    if (services.settings.current().useLocalTtml) services.localTtmlRepository.get(track)?.let { local ->
      return LyricsResolution.Found(local.document, LyricsProviderId.LOCAL_TTML, listOf(
        ProviderAttempt(LyricsProviderId.LOCAL_TTML, ProviderAttemptOutcome.FOUND,
          source = local.document.metadata.source, syncKind = local.document.syncKind.name, message = "Saved local TTML"),
      ))
    }
    val alias = services.spotifyTrackResolver.resolve(track)
    val resolved = alias?.takeIf(String::isNotBlank)?.let { track.copy(uri = it) } ?: track
    return services.lyricsResolver.resolve(LyricsRequest(resolved, allowCached = allowCached, requestId = requestId))
  }
  override suspend fun importTtml(track: TrackIdentity, text: String, sourceUri: String?) {
    services.localTtmlProvider.import(track, text, sourceUri = sourceUri)
  }
  override suspend fun deleteSavedLyrics(trackKey: String) = services.localTtmlRepository.deleteByTrackKey(trackKey)
  override suspend fun clearDiagnostics() { services.diagnostics.clear() }
  override suspend fun setDeviceTiming(value: Int?) {
    if (value == null) {
      val key = services.bluetoothTimingResolver.effectiveOffset.first().route?.deviceKey
        ?: error("No Bluetooth media device is active.")
      services.deviceTimingRepository.delete(key)
    } else check(services.bluetoothTimingResolver.rememberForCurrentRoute(value)) { "No Bluetooth media device is active." }
  }
}
