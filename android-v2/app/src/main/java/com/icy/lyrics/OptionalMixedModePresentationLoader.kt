package com.icy.lyrics

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import com.icy.lyrics.ui.OptionalMixedModePresentation

private const val OPTIONAL_MIXED_MODE_PRESENTATION =
  "com.icy.lyrics.OPTIONAL_MIXED_MODE_PRESENTATION"

/** Loads a distribution-owned renderer only when that distribution declares one. */
internal fun loadOptionalMixedModePresentation(
  activity: ComponentActivity,
): OptionalMixedModePresentation? {
  if (!BuildConfig.PRIVATE_FEATURE_INCLUDED) return null
  val className = runCatching {
    activity.packageManager
      .getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
      .metaData
      ?.getString(OPTIONAL_MIXED_MODE_PRESENTATION)
  }.getOrNull()?.takeIf(String::isNotBlank) ?: return null

  return runCatching {
    val constructor = Class.forName(className)
      .getDeclaredConstructor(ComponentActivity::class.java)
      .apply { isAccessible = true }
    constructor.newInstance(activity) as OptionalMixedModePresentation
  }.getOrNull()
}
