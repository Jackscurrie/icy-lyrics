package com.icy.lyrics.core.platform.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface SettingsBackend {
  val settings: Flow<AppSettings>
  suspend fun update(transform: (AppSettings) -> AppSettings)
  suspend fun isLegacyCleanupComplete(): Boolean
  suspend fun markLegacyCleanupComplete()
}

/** Shared setting semantics; durable transactions belong to the platform backend. */
class SettingsRepository(private val backend: SettingsBackend) {
  val settings: Flow<AppSettings> get() = backend.settings
  suspend fun current(): AppSettings = settings.first()
  suspend fun update(transform: (AppSettings) -> AppSettings) = backend.update(transform)
  suspend fun isLegacyCleanupComplete(): Boolean = backend.isLegacyCleanupComplete()
  suspend fun markLegacyCleanupComplete() = backend.markLegacyCleanupComplete()
  suspend fun setUseLocalTtml(value: Boolean) = update { it.copy(useLocalTtml = value) }
  suspend fun setRememberLocalTtml(value: Boolean) = update { it.copy(rememberLocalTtml = value) }
  suspend fun setBackgroundEnabled(value: Boolean) = update { it.copy(backgroundEnabled = value) }
  suspend fun setBackgroundStyle(value: BackgroundStyle) = update { it.copy(backgroundStyle = value) }
  suspend fun setRevealEnabled(value: Boolean) = update { it.copy(revealEnabled = value) }
  suspend fun setMixedSide(value: MixedSide) = update { it.copy(mixedSide = value) }
  suspend fun setKeepScreenAwake(value: Boolean) = update { it.copy(keepScreenAwake = value) }
  suspend fun setDebugEnabled(value: Boolean) = update { it.copy(debugEnabled = value) }
  suspend fun setRememberBluetoothTiming(value: Boolean) = update { it.copy(rememberBluetoothTiming = value) }
  suspend fun setSpicyEnabled(value: Boolean) = update { it.copy(spicyEnabled = value) }
  suspend fun setSpicyTokenSharingConsent(value: Boolean) = update { it.copy(spicyTokenSharingConsent = value) }
  suspend fun setLrclibEnabled(value: Boolean) = update { it.copy(lrclibEnabled = value) }

  suspend fun setGlobalTimingOffsetMs(value: Int) = update {
    it.copy(globalTimingOffsetMs = value.coerceIn(SettingsDefaults.MIN_TIMING_OFFSET_MS, SettingsDefaults.MAX_TIMING_OFFSET_MS))
  }
  suspend fun setSourceSelectionMode(value: SourceSelectionMode) = update { it.copy(sourceSelectionMode = value) }

  suspend fun replace(value: AppSettings) = update { value.copy(
    globalTimingOffsetMs = value.globalTimingOffsetMs.coerceIn(SettingsDefaults.MIN_TIMING_OFFSET_MS, SettingsDefaults.MAX_TIMING_OFFSET_MS),
  ) }
}
