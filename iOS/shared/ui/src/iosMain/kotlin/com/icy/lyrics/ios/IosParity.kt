package com.icy.lyrics.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.icy.lyrics.ui.IcyParityFixtureScreen
import com.icy.lyrics.ui.IcyParityFixtures
import com.icy.lyrics.ui.IosIcyUiPlatform
import com.icy.lyrics.ui.LocalIcyUiPlatform
import platform.UIKit.UIViewController

/** Offline fixture entry point. The Swift Release shell never exposes fixture launch arguments. */
fun createIcyParityViewController(scenarioId: String): UIViewController = ComposeUIViewController {
  CompositionLocalProvider(LocalIcyUiPlatform provides IosIcyUiPlatform("fixture", IcyParityFixtures.FRAME_TIME_NANOS)) {
    IcyParityFixtureScreen(scenarioId)
  }
}
