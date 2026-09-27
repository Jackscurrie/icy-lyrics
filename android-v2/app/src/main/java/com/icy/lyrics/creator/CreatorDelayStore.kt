package com.icy.lyrics.creator

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persists the Lyric Creator's global headphone-output delay. */
class CreatorDelayStore internal constructor(
  private val preferences: CreatorDelayPreferences,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(context: Context) : this(
    preferences = SharedPreferencesCreatorDelayPreferences(
      context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    ),
    ioDispatcher = Dispatchers.IO,
  )

  private val mutex = Mutex()

  suspend fun getDelayMs(): Int = withContext(ioDispatcher) {
    mutex.withLock { normalize(preferences.readInt(KEY_AUDIO_DELAY_MS, DEFAULT_AUDIO_DELAY_MS)) }
  }

  /** Returns and persists the bounded value actually used by timing capture. */
  suspend fun setDelayMs(value: Int): Int = withContext(ioDispatcher) {
    mutex.withLock {
      val normalized = normalize(value)
      if (!preferences.writeInt(KEY_AUDIO_DELAY_MS, normalized)) {
        throw IOException("The Lyric Creator headphone delay could not be saved.")
      }
      normalized
    }
  }

  suspend fun reset(): Int = setDelayMs(DEFAULT_AUDIO_DELAY_MS)

  companion object {
    const val MIN_AUDIO_DELAY_MS = 0
    const val MAX_AUDIO_DELAY_MS = 1_500
    const val DEFAULT_AUDIO_DELAY_MS = 0
    // Keep this legacy name so existing Personal installs retain their setting.
    internal const val PREFERENCES_NAME = "personal_lyric_creator"
    internal const val KEY_AUDIO_DELAY_MS = "headphone_audio_delay_ms"

    fun normalize(value: Int): Int = value.coerceIn(MIN_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS)

    /** Positive output delay is subtracted so the authored timestamp matches the heard audio. */
    fun correctedTimingPositionMs(playbackPositionMs: Long, delayMs: Int): Long =
      (playbackPositionMs - normalize(delayMs).toLong()).coerceAtLeast(0L)
  }
}

internal interface CreatorDelayPreferences {
  fun readInt(key: String, defaultValue: Int): Int
  fun writeInt(key: String, value: Int): Boolean
}

private class SharedPreferencesCreatorDelayPreferences(
  private val preferences: SharedPreferences,
) : CreatorDelayPreferences {
  override fun readInt(key: String, defaultValue: Int): Int =
    preferences.getInt(key, defaultValue)

  override fun writeInt(key: String, value: Int): Boolean =
    preferences.edit().putInt(key, value).commit()
}
