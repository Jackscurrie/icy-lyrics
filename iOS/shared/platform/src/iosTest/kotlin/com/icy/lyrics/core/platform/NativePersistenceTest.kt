@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.core.platform

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.database.openIosDatabaseAtPath
import com.icy.lyrics.core.platform.provider.LocalTtmlProvider
import com.icy.lyrics.core.platform.settings.IosSettingsBackend
import com.icy.lyrics.core.platform.settings.SettingsDefaults
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.storage.LocalTtmlRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/** Runs on the iOS simulator: exercises the real SQLite driver and native preferences. */
class NativePersistenceTest {
  @Test
  fun localImportSurvivesDatabaseReopeningAndKeepsTheCompleteLocalUri() = runBlocking {
    val path = NSTemporaryDirectory() + "icy-lyrics-${NSUUID().UUIDString}.db"
    val track = TrackIdentity(
      uri = "spotify:local:Artist:Album:Title:123",
      title = "Title",
      artists = listOf("Artist"),
      album = "Album",
      durationMs = 123_000L,
    )
    val raw = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="2s">Saved line</p></div></body></tt>"""
    try {
      val first = openIosDatabaseAtPath(path)
      try {
        LocalTtmlProvider(LocalTtmlRepository(first.localTtmlDao())).import(track, raw, sourceUri = "picked.ttml")
      } finally { first.close() }

      val reopened = openIosDatabaseAtPath(path)
      try {
        val repository = LocalTtmlRepository(reopened.localTtmlDao())
        val stored = assertNotNull(repository.get(track))
        assertEquals(track.uri, stored.trackKey)
        assertEquals(raw, stored.rawTtml)
        assertEquals("picked.ttml", stored.sourceUri)
        assertNull(repository.get(track.copy(uri = "spotify:local:Artist:Album:Title:124")))
        assertTrue(repository.deleteByTrackKey(track.uri))
        assertNull(repository.get(track))
      } finally { reopened.close() }
    } finally {
      listOf(path, "$path-shm", "$path-wal").forEach {
        NSFileManager.defaultManager.removeItemAtPath(it, error = null)
      }
    }
  }

  @Test
  fun settingsReplacementSurvivesBackendRecreation() = runBlocking {
    val suite = "icy-lyrics-test-${NSUUID().UUIDString}"
    val defaults = NSUserDefaults(suiteName = suite)
    try {
      val settings = SettingsRepository(IosSettingsBackend(defaults))
      settings.replace(SettingsDefaults.value.copy(spicyEnabled = true, spicyTokenSharingConsent = true, globalTimingOffsetMs = 99_000))
      val reopened = SettingsRepository(IosSettingsBackend(NSUserDefaults(suiteName = suite))).current()
      assertTrue(reopened.spicyEnabled)
      assertTrue(reopened.spicyTokenSharingConsent)
      assertEquals(SettingsDefaults.MAX_TIMING_OFFSET_MS, reopened.globalTimingOffsetMs)
    } finally {
      defaults.removePersistentDomainForName(suite)
    }
  }
}
