package com.icy.lyrics.core.platform.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val SETTINGS_STORE_NAME = "settings_v2"

private val Context.icyLyricsSettingsStore: DataStore<Preferences> by preferencesDataStore(
  name = SETTINGS_STORE_NAME,
)

class SettingsRepository private constructor(
  private val store: DataStore<Preferences>,
) {
  constructor(context: Context) : this(context.applicationContext.icyLyricsSettingsStore)

  val settings: Flow<AppSettings> = store.data.map(::decode)

  suspend fun current(): AppSettings = settings.first()

  suspend fun isLegacyCleanupComplete(): Boolean =
    store.data.first()[Keys.legacyCleanupComplete] ?: false

  suspend fun markLegacyCleanupComplete() {
    set(Keys.legacyCleanupComplete, true)
  }

  suspend fun setUseLocalTtml(value: Boolean) = set(Keys.useLocalTtml, value)
  suspend fun setRememberLocalTtml(value: Boolean) = set(Keys.rememberLocalTtml, value)
  suspend fun setBackgroundEnabled(value: Boolean) = set(Keys.backgroundEnabled, value)
  suspend fun setBackgroundStyle(value: BackgroundStyle) = set(Keys.backgroundStyle, value.name)
  suspend fun setRevealEnabled(value: Boolean) = set(Keys.revealEnabled, value)
  suspend fun setMixedSide(value: MixedSide) = set(Keys.mixedSide, value.name)
  suspend fun setKeepScreenAwake(value: Boolean) = set(Keys.keepScreenAwake, value)
  suspend fun setDebugEnabled(value: Boolean) = set(Keys.debugEnabled, value)
  suspend fun setRememberBluetoothTiming(value: Boolean) = set(Keys.rememberBluetoothTiming, value)
  suspend fun setSpicyEnabled(value: Boolean) = set(Keys.spicyEnabled, value)
  suspend fun setSpicyTokenSharingConsent(value: Boolean) = set(Keys.spicyTokenConsent, value)
  suspend fun setLrclibEnabled(value: Boolean) = set(Keys.lrclibEnabled, value)

  suspend fun setGlobalTimingOffsetMs(value: Int) {
    set(
      Keys.globalTimingOffsetMs,
      value.coerceIn(
        SettingsDefaults.MIN_TIMING_OFFSET_MS,
        SettingsDefaults.MAX_TIMING_OFFSET_MS,
      ),
    )
  }

  suspend fun setSourceSelectionMode(value: SourceSelectionMode) {
    set(Keys.sourceSelectionMode, value.name)
  }

  suspend fun replace(value: AppSettings) {
    store.edit { preferences ->
      preferences[Keys.useLocalTtml] = value.useLocalTtml
      preferences[Keys.rememberLocalTtml] = value.rememberLocalTtml
      preferences[Keys.backgroundEnabled] = value.backgroundEnabled
      preferences[Keys.backgroundStyle] = value.backgroundStyle.name
      preferences[Keys.revealEnabled] = value.revealEnabled
      preferences[Keys.mixedSide] = value.mixedSide.name
      preferences[Keys.keepScreenAwake] = value.keepScreenAwake
      preferences[Keys.debugEnabled] = value.debugEnabled
      preferences[Keys.globalTimingOffsetMs] = value.globalTimingOffsetMs.coerceIn(
        SettingsDefaults.MIN_TIMING_OFFSET_MS,
        SettingsDefaults.MAX_TIMING_OFFSET_MS,
      )
      preferences[Keys.rememberBluetoothTiming] = value.rememberBluetoothTiming
      preferences[Keys.sourceSelectionMode] = value.sourceSelectionMode.name
      preferences[Keys.spicyEnabled] = value.spicyEnabled
      preferences[Keys.spicyTokenConsent] = value.spicyTokenSharingConsent
      preferences[Keys.lrclibEnabled] = value.lrclibEnabled
    }
  }

  private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
    store.edit { it[key] = value }
  }

  private fun decode(preferences: Preferences): AppSettings {
    val defaults = SettingsDefaults.value
    return AppSettings(
      useLocalTtml = preferences[Keys.useLocalTtml] ?: defaults.useLocalTtml,
      rememberLocalTtml = preferences[Keys.rememberLocalTtml] ?: defaults.rememberLocalTtml,
      backgroundEnabled = preferences[Keys.backgroundEnabled] ?: defaults.backgroundEnabled,
      backgroundStyle = preferences[Keys.backgroundStyle]
        ?.let { runCatching { BackgroundStyle.valueOf(it) }.getOrNull() }
        ?: defaults.backgroundStyle,
      revealEnabled = preferences[Keys.revealEnabled] ?: defaults.revealEnabled,
      mixedSide = preferences[Keys.mixedSide]
        ?.let { runCatching { MixedSide.valueOf(it) }.getOrNull() }
        ?: defaults.mixedSide,
      keepScreenAwake = preferences[Keys.keepScreenAwake] ?: defaults.keepScreenAwake,
      debugEnabled = preferences[Keys.debugEnabled] ?: defaults.debugEnabled,
      globalTimingOffsetMs = (preferences[Keys.globalTimingOffsetMs] ?: defaults.globalTimingOffsetMs)
        .coerceIn(SettingsDefaults.MIN_TIMING_OFFSET_MS, SettingsDefaults.MAX_TIMING_OFFSET_MS),
      rememberBluetoothTiming = preferences[Keys.rememberBluetoothTiming]
        ?: defaults.rememberBluetoothTiming,
      sourceSelectionMode = preferences[Keys.sourceSelectionMode]
        ?.let { runCatching { SourceSelectionMode.valueOf(it) }.getOrNull() }
        ?: defaults.sourceSelectionMode,
      spicyEnabled = preferences[Keys.spicyEnabled] ?: defaults.spicyEnabled,
      spicyTokenSharingConsent = preferences[Keys.spicyTokenConsent]
        ?: defaults.spicyTokenSharingConsent,
      lrclibEnabled = preferences[Keys.lrclibEnabled] ?: defaults.lrclibEnabled,
    )
  }

  private object Keys {
    val useLocalTtml = booleanPreferencesKey("use_local_ttml")
    val rememberLocalTtml = booleanPreferencesKey("remember_ttml")
    val backgroundEnabled = booleanPreferencesKey("background_enabled")
    val backgroundStyle = stringPreferencesKey("background_style")
    val revealEnabled = booleanPreferencesKey("reveal_enabled")
    val mixedSide = stringPreferencesKey("mixed_side")
    val keepScreenAwake = booleanPreferencesKey("keep_screen_awake")
    val debugEnabled = booleanPreferencesKey("debug_enabled")
    val globalTimingOffsetMs = intPreferencesKey("lyric_delay_ms")
    val rememberBluetoothTiming = booleanPreferencesKey("remember_bluetooth_timing")
    val sourceSelectionMode = stringPreferencesKey("source_selection_mode")
    val spicyEnabled = booleanPreferencesKey("spicy_enabled")
    val spicyTokenConsent = booleanPreferencesKey("spicy_token_sharing_consent")
    val lrclibEnabled = booleanPreferencesKey("lrclib_enabled")
    val legacyCleanupComplete = booleanPreferencesKey("legacy_cleanup_complete")
  }
}
