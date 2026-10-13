package com.icy.lyrics.overlay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** These options intentionally do not touch the main player or its background preferences. */
data class OverlayOptions(
  val enabled: Boolean = false,
  val backgroundEnabled: Boolean = true,
  val showArtwork: Boolean = true,
  val showTitles: Boolean = true,
  val bannerSeen: Boolean = false,
  val spinArtwork: Boolean = true,
)

class OverlayPreferences(context: Context) : AutoCloseable {
  private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val mutableOptions = MutableStateFlow(read())
  val options: StateFlow<OverlayOptions> = mutableOptions.asStateFlow()
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
    mutableOptions.value = read()
  }

  init { preferences.registerOnSharedPreferenceChangeListener(listener) }

  fun setEnabled(value: Boolean) = put("enabled", value)
  fun setBackgroundEnabled(value: Boolean) = put("background", value)
  fun setShowArtwork(value: Boolean) = put("artwork", value)
  fun setShowTitles(value: Boolean) = put("titles", value)
  fun setSpinArtwork(value: Boolean) = put("spin_artwork", value)
  fun markBannerSeen() = put("intro_130_seen", true)

  private fun put(key: String, value: Boolean) {
    preferences.edit().putBoolean(key, value).apply()
    // Also update immediately if a caller is not on Android's main listener thread.
    mutableOptions.value = read()
  }

  private fun read() = OverlayOptions(
    enabled = preferences.getBoolean("enabled", false),
    backgroundEnabled = preferences.getBoolean("background", true),
    showArtwork = preferences.getBoolean("artwork", true),
    showTitles = preferences.getBoolean("titles", true),
    bannerSeen = preferences.getBoolean("intro_130_seen", false),
    spinArtwork = preferences.getBoolean("spin_artwork", true),
  )

  override fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }

  companion object { internal const val FILE = "lyrics_overlay" }
}
