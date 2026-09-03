package com.icy.lyrics.core.platform.migration

import android.content.Context
import com.icy.lyrics.core.platform.settings.SettingsRepository

data class LegacyCleanupReport(
  val alreadyComplete: Boolean = false,
  val lyricsEntriesRemoved: Int = 0,
  val authEntriesRemoved: Int = 0,
)

/** Fresh-start boundary: v1 preferences are erased and are never imported. */
class LegacyPreferenceCleaner(
  context: Context,
  private val settings: SettingsRepository,
) {
  private val appContext = context.applicationContext

  suspend fun cleanupOnce(): LegacyCleanupReport {
    if (settings.isLegacyCleanupComplete()) return LegacyCleanupReport(alreadyComplete = true)
    val lyrics = appContext.getSharedPreferences(LEGACY_LYRICS_STORE, Context.MODE_PRIVATE)
    val auth = appContext.getSharedPreferences(LEGACY_AUTH_STORE, Context.MODE_PRIVATE)
    val lyricsCount = lyrics.all.size
    val authCount = auth.all.size
    check(lyrics.edit().clear().commit()) { "Could not clear the legacy lyrics store" }
    check(auth.edit().clear().commit()) { "Could not clear the legacy Spotify credential store" }
    settings.markLegacyCleanupComplete()
    return LegacyCleanupReport(
      lyricsEntriesRemoved = lyricsCount,
      authEntriesRemoved = authCount,
    )
  }

  companion object {
    const val LEGACY_LYRICS_STORE = "lyrics_store"
    const val LEGACY_AUTH_STORE = "spotify_auth"
  }
}
