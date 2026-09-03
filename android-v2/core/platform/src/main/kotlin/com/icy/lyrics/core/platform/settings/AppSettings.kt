package com.icy.lyrics.core.platform.settings

import kotlinx.serialization.Serializable

@Serializable
enum class SourceSelectionMode {
  STRICT_PRIORITY,
  BETTER_SYNC,
}

@Serializable
enum class BackgroundStyle {
  ANIMATED,
  STATIC_BLURRED,
}

@Serializable
enum class MixedSide {
  LEFT,
  RIGHT,
}

@Serializable
data class AppSettings(
  val useLocalTtml: Boolean = true,
  val rememberLocalTtml: Boolean = true,
  val backgroundEnabled: Boolean = true,
  val backgroundStyle: BackgroundStyle = BackgroundStyle.ANIMATED,
  val revealEnabled: Boolean = false,
  val mixedSide: MixedSide = MixedSide.LEFT,
  val keepScreenAwake: Boolean = true,
  val debugEnabled: Boolean = false,
  val globalTimingOffsetMs: Int = 0,
  val rememberBluetoothTiming: Boolean = true,
  val sourceSelectionMode: SourceSelectionMode = SourceSelectionMode.STRICT_PRIORITY,
  val spicyEnabled: Boolean = false,
  val spicyTokenSharingConsent: Boolean = false,
  val lrclibEnabled: Boolean = true,
)

object SettingsDefaults {
  const val MIN_TIMING_OFFSET_MS = -5_000
  const val MAX_TIMING_OFFSET_MS = 5_000
  val value = AppSettings()
}
