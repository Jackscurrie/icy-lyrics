package com.icy.lyrics.core.platform.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.icy.lyrics.core.platform.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyPreferenceCleanerTest {
  @Test
  fun cleanupClearsBothLegacyStoresAndIsIdempotent() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val lyrics = context.getSharedPreferences(LegacyPreferenceCleaner.LEGACY_LYRICS_STORE, Context.MODE_PRIVATE)
    val auth = context.getSharedPreferences(LegacyPreferenceCleaner.LEGACY_AUTH_STORE, Context.MODE_PRIVATE)
    lyrics.edit().putString("ttml-raw:track", "private lyrics").putInt("lyric_delay_ms", 500).commit()
    auth.edit().putString("access_token", "plaintext-token").commit()
    val cleaner = LegacyPreferenceCleaner(context, SettingsRepository(context))

    val first = cleaner.cleanupOnce()
    val second = cleaner.cleanupOnce()

    assertEquals(2, first.lyricsEntriesRemoved)
    assertEquals(1, first.authEntriesRemoved)
    assertTrue(lyrics.all.isEmpty())
    assertTrue(auth.all.isEmpty())
    assertTrue(second.alreadyComplete)
  }
}
