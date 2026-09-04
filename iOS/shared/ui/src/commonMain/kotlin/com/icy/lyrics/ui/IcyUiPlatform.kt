package com.icy.lyrics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer

enum class IcyLegalDocument { AGPL, THIRD_PARTY }

/** Native integration only. Layout, text, vectors and interactions stay in common code. */
interface IcyUiPlatform {
  val versionName: String
  val onboardingInstructions: String
  val emptyPlayerInstructions: String
  val aboutDescription: String
  /** Null retains Android's existing default font and its existing metrics. */
  val fontFamily: FontFamily? get() = null
  /** Screenshot fixtures can freeze frame time without replacing the player UI. */
  val fixedFrameTimeNanos: Long? get() = null
  fun fontFallback(text: androidx.compose.ui.text.AnnotatedString, weight: androidx.compose.ui.text.font.FontWeight?): androidx.compose.ui.text.AnnotatedString = text
  fun monotonicTimeMs(): Long
  fun monotonicTimeNanos(): Long
  fun formatDateTime(epochMs: Long): String
  fun copyDiagnostics(text: String)
  fun legalDocument(document: IcyLegalDocument): String
  @Composable fun BackHandler(enabled: Boolean, onBack: () -> Unit)
  @Composable fun ReducedMotionEnabled(): Boolean
  @Composable fun Background(
    artwork: ImageBitmap?, enabled: Boolean, style: BackgroundStyle, isPlaying: Boolean,
    modifier: Modifier, content: @Composable () -> Unit,
  )
}

val LocalIcyUiPlatform = staticCompositionLocalOf<IcyUiPlatform> {
  error("Provide IcyUiPlatform at the application entry point")
}

@Composable internal fun IcyBackHandler(enabled: Boolean, onBack: () -> Unit) =
  LocalIcyUiPlatform.current.BackHandler(enabled, onBack)

@Composable internal fun rememberReducedMotionEnabled(): Boolean =
  LocalIcyUiPlatform.current.ReducedMotionEnabled()

@Composable fun ArtworkBackground(
  artwork: ImageBitmap?, enabled: Boolean, style: BackgroundStyle, isPlaying: Boolean,
  modifier: Modifier = Modifier, content: @Composable () -> Unit,
) = LocalIcyUiPlatform.current.Background(artwork, enabled, style, isPlaying, modifier, content)

@Composable internal fun icyTypography(): Typography {
  val original = MaterialTheme.typography
  val family = LocalIcyUiPlatform.current.fontFamily ?: return original
  return remember(original, family) {
    original.copy(
      displayLarge = original.displayLarge.copy(fontFamily = family),
      displayMedium = original.displayMedium.copy(fontFamily = family),
      displaySmall = original.displaySmall.copy(fontFamily = family),
      headlineLarge = original.headlineLarge.copy(fontFamily = family),
      headlineMedium = original.headlineMedium.copy(fontFamily = family),
      headlineSmall = original.headlineSmall.copy(fontFamily = family),
      titleLarge = original.titleLarge.copy(fontFamily = family),
      titleMedium = original.titleMedium.copy(fontFamily = family),
      titleSmall = original.titleSmall.copy(fontFamily = family),
      bodyLarge = original.bodyLarge.copy(fontFamily = family),
      bodyMedium = original.bodyMedium.copy(fontFamily = family),
      bodySmall = original.bodySmall.copy(fontFamily = family),
      labelLarge = original.labelLarge.copy(fontFamily = family),
      labelMedium = original.labelMedium.copy(fontFamily = family),
      labelSmall = original.labelSmall.copy(fontFamily = family),
    )
  }
}

/** Canvas TextStyle does not inherit Material typography; set the family before measurement. */
@Composable internal fun rememberIcyTextMeasurer(cacheSize: Int): IcyTextMeasurer {
  val platform = LocalIcyUiPlatform.current
  val family = platform.fontFamily
  val delegate = rememberTextMeasurer(cacheSize)
  return remember(delegate, family, platform) { IcyTextMeasurer(delegate, family, platform) }
}

internal class IcyTextMeasurer(private val delegate: TextMeasurer, private val family: FontFamily?, private val platform: IcyUiPlatform) {
  fun measure(
    text: androidx.compose.ui.text.AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    constraints: androidx.compose.ui.unit.Constraints = androidx.compose.ui.unit.Constraints(),
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
  ): androidx.compose.ui.text.TextLayoutResult = delegate.measure(
    text = platform.fontFallback(text, style.fontWeight),
    style = if (family != null && (style.fontFamily == null || style.fontFamily == FontFamily.Default)) {
      style.copy(fontFamily = family)
    } else style,
    constraints = constraints,
    softWrap = softWrap, overflow = overflow, maxLines = maxLines,
  )

  fun measure(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    constraints: androidx.compose.ui.unit.Constraints = androidx.compose.ui.unit.Constraints(),
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
  ): androidx.compose.ui.text.TextLayoutResult = measure(
    androidx.compose.ui.text.AnnotatedString(text), style, constraints, softWrap, overflow, maxLines,
  )
}
