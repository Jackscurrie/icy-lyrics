package com.icy.lyrics.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import com.icy.lyrics.ui.IcyParityFixtureScreen
import com.icy.lyrics.ui.IcyParityFixtures
import com.icy.lyrics.ui.IosIcyUiPlatform
import com.icy.lyrics.ui.LocalIcyUiPlatform
import platform.UIKit.UIViewController

/** Offline fixture entry point. The Swift Release shell never exposes fixture launch arguments. */
fun createIcyParityViewController(scenarioId: String, onFrameDrawn: (String) -> Unit): UIViewController = ComposeUIViewController {
  CompositionLocalProvider(LocalIcyUiPlatform provides IosIcyUiPlatform("fixture", IcyParityFixtures.FRAME_TIME_NANOS)) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    // Observe the same conversions as the Android fixture host, using the real
    // platform Density. No font-scaling algorithm or typography is overridden.
    val fontSamples = with(density) {
      listOf(12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64).joinToString(",") { fontSize ->
        "\"$fontSize\":${fontSize.sp.toPx()}"
      }
    }
    Box(Modifier.fillMaxSize().drawWithContent {
      drawContent()
      // The native debug host publishes this only after this draw returns and
      // its UIKit geometry matches these actual Compose pixels. This is a draw
      // acknowledgement, not a claim that display-clock springs have settled.
      onFrameDrawn("""{"contentWidthPx":${size.width.toInt()},"contentHeightPx":${size.height.toInt()},"composeDensity":${density.density},"fontScale":${density.fontScale},"safeDrawingInsetsPx":[${insets.getLeft(density, direction)},${insets.getTop(density)},${insets.getRight(density, direction)},${insets.getBottom(density)}],"spToPx":{$fontSamples}}""")
    }) {
      IcyParityFixtureScreen(scenarioId)
    }
  }
}
