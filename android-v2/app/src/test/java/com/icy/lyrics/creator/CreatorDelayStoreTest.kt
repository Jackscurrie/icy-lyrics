package com.icy.lyrics.creator

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreatorDelayStoreTest {
  @Test
  fun defaultAndPersistedDelayAreGlobal() = runTest {
    val preferences = FakeCreatorDelayPreferences()
    val first = store(preferences)
    val second = store(preferences)

    assertEquals(0, first.getDelayMs())
    assertEquals(240, first.setDelayMs(240))
    assertEquals(240, second.getDelayMs())
  }

  @Test
  fun delayIsAlwaysClampedToTheSupportedPositiveRange() = runTest {
    val preferences = FakeCreatorDelayPreferences()
    val store = store(preferences)

    assertEquals(0, store.setDelayMs(-200))
    assertEquals(1_500, store.setDelayMs(9_000))

    preferences.values[CreatorDelayStore.KEY_AUDIO_DELAY_MS] = Int.MIN_VALUE
    assertEquals(0, store.getDelayMs())
    preferences.values[CreatorDelayStore.KEY_AUDIO_DELAY_MS] = Int.MAX_VALUE
    assertEquals(1_500, store.getDelayMs())
  }

  @Test
  fun positiveHeadphoneDelayMovesCapturedTimingEarlier() {
    assertEquals(9_750L, CreatorDelayStore.correctedTimingPositionMs(10_000L, 250))
    assertEquals(0L, CreatorDelayStore.correctedTimingPositionMs(100L, 250))
    assertEquals(8_500L, CreatorDelayStore.correctedTimingPositionMs(10_000L, 2_000))
  }

  @Test
  fun failedPreferenceCommitIsNotReportedAsSaved() = runTest {
    val preferences = FakeCreatorDelayPreferences(writeSucceeds = false)

    assertFailsWith<IOException> { store(preferences).setDelayMs(180) }
  }

  private fun store(preferences: CreatorDelayPreferences) = CreatorDelayStore(
    preferences = preferences,
    ioDispatcher = Dispatchers.Unconfined,
  )
}

private class FakeCreatorDelayPreferences(
  private val writeSucceeds: Boolean = true,
) : CreatorDelayPreferences {
  val values = mutableMapOf<String, Int>()

  override fun readInt(key: String, defaultValue: Int): Int = values[key] ?: defaultValue

  override fun writeInt(key: String, value: Int): Boolean {
    if (writeSucceeds) values[key] = value
    return writeSucceeds
  }
}
