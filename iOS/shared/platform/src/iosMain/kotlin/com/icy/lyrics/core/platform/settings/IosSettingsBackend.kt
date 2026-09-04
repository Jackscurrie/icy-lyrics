package com.icy.lyrics.core.platform.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Local app preferences; tokens and imported files are never stored here. */
internal class IosSettingsBackend(
  private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SettingsBackend {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val mutex = Mutex()
  private val mutable = MutableStateFlow(
    defaults.stringForKey(SETTINGS_KEY)?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
      ?: SettingsDefaults.value,
  )
  override val settings: StateFlow<AppSettings> = mutable.asStateFlow()

  override suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
    val next = transform(mutable.value).let {
      it.copy(globalTimingOffsetMs = it.globalTimingOffsetMs.coerceIn(SettingsDefaults.MIN_TIMING_OFFSET_MS, SettingsDefaults.MAX_TIMING_OFFSET_MS))
    }
    defaults.setObject(json.encodeToString(next), forKey = SETTINGS_KEY)
    check(defaults.synchronize()) { "Settings could not be saved." }
    mutable.value = next
  }

  override suspend fun isLegacyCleanupComplete(): Boolean = true
  override suspend fun markLegacyCleanupComplete() = Unit

  private companion object { const val SETTINGS_KEY = "icy_lyrics_settings_v2" }
}
