@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.test.InternalTestApi::class,
  androidx.compose.ui.test.ExperimentalTestApi::class,
  kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.icy.lyrics.ui.parity

import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.icy.lyrics.ui.androidFontScalingDensity
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Duration.Companion.minutes

/**
 * Only this helper uses the pinned internal Compose 1.11.1 constructor, for measured safe insets.
 * Its normal public test clock drives the original withFrameNanos animations at 16ms intervals.
 * This creates a Skia raster surface; it does not replace the UIKit/Metal screenshot gate.
 *
 * Source: https://github.com/JetBrains/compose-multiplatform-core/blob/v1.11.1/compose/ui/ui-test/src/skikoMain/kotlin/androidx/compose/ui/test/ComposeUiTest.skiko.kt#L141
 */
internal fun runAndroidReferenceUiTest(
  profile: AndroidReferenceProfile,
  block: suspend SkikoComposeUiTest.() -> Unit,
): TestResult = SkikoComposeUiTest(
  width = profile.widthPx,
  height = profile.heightPx,
  density = androidFontScalingDensity(Density(profile.density, profile.fontScale)),
  // The preserved Android createAndroidComposeRule harness uses the original unconfined dispatcher.
  // Retain both this dispatcher and its original clock mode. New v2 defaults must not change it.
  effectContext = UnconfinedTestDispatcher(),
  testTimeout = 10.minutes,
  semanticsOwnerListener = null,
  windowInsets = AndroidReferenceWindowInsets(profile),
  useStandardTestDispatcherForComposition = false,
).runTest(block)

private class AndroidReferenceWindowInsets(profile: AndroidReferenceProfile) : PlatformWindowInsets {
  private val measured = profile.safeDrawingInsetsPx
  override val statusBars = PlatformInsets(top = measured[1])
  override val navigationBars = PlatformInsets(bottom = measured[3])
  override val systemBars = PlatformInsets(measured[0], measured[1], measured[2], measured[3])
  override fun excluding(safeInsets: Boolean, ime: Boolean): PlatformWindowInsets =
    if (safeInsets) object : PlatformWindowInsets {} else this
}
