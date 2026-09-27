package com.icy.lyrics

import android.content.Context
import android.content.SharedPreferences
import com.icy.lyrics.ui.TvPerformanceBackground
import com.icy.lyrics.ui.TvPerformanceMode

/** Preferences that exist only in the dedicated TV source set. */
internal class TvPerformancePreferences private constructor(
  private val preferences: SharedPreferences,
) {
  constructor(context: Context) : this(
    context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE),
  )

  fun mode(): TvPerformanceMode = decodeTvPerformanceMode(preferences.getString(MODE_KEY, null))

  fun background(): TvPerformanceBackground =
    decodeTvPerformanceBackground(preferences.getString(BACKGROUND_KEY, null))

  fun setMode(value: TvPerformanceMode) {
    preferences.edit().putString(MODE_KEY, value.name).apply()
  }

  fun setBackground(value: TvPerformanceBackground) {
    preferences.edit().putString(BACKGROUND_KEY, value.name).apply()
  }

  private companion object {
    const val STORE_NAME = "tv_performance_v1"
    const val MODE_KEY = "mode"
    const val BACKGROUND_KEY = "background"
  }
}

internal fun decodeTvPerformanceMode(value: String?): TvPerformanceMode =
  TvPerformanceMode.entries.firstOrNull { it.name == value } ?: TvPerformanceMode.OFF

internal fun decodeTvPerformanceBackground(value: String?): TvPerformanceBackground =
  TvPerformanceBackground.entries.firstOrNull { it.name == value }
    ?: TvPerformanceBackground.STATIC_BLURRED
