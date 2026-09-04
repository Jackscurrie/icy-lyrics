package com.icy.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/** Apply the Android app's font-size curve to the existing iPhone Dynamic Type scale. */
@Composable
internal fun ProvideAndroidFontScaling(content: @Composable () -> Unit) {
  val native = LocalDensity.current
  val compatible = remember(native) { androidFontScalingDensity(native) }
  CompositionLocalProvider(LocalDensity provides compatible, content = content)
}
