package com.icy.lyrics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
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
  /** Match the platform's rasterization policy without changing layout/style values. */
  fun textStyle(style: TextStyle): TextStyle = style
  /** Canvas paragraph paint conversion runs once, after any typography adaptation. */
  fun styleForLayout(style: TextStyle): TextStyle = textStyle(style)
  /** Screenshot fixtures can freeze frame time without replacing the player UI. */
  val fixedFrameTimeNanos: Long? get() = null
  fun fontFallback(text: androidx.compose.ui.text.AnnotatedString, weight: androidx.compose.ui.text.font.FontWeight?): androidx.compose.ui.text.AnnotatedString = text
  fun textForLayout(text: AnnotatedString, style: TextStyle): AnnotatedString =
    fontFallback(text, style.fontWeight)
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
  val platform = LocalIcyUiPlatform.current
  val family = platform.fontFamily ?: return original
  return remember(original, family, platform) {
    fun TextStyle.adapt() = platform.textStyle(copy(fontFamily = family))
    original.copy(
      displayLarge = original.displayLarge.adapt(),
      displayMedium = original.displayMedium.adapt(),
      displaySmall = original.displaySmall.adapt(),
      headlineLarge = original.headlineLarge.adapt(),
      headlineMedium = original.headlineMedium.adapt(),
      headlineSmall = original.headlineSmall.adapt(),
      titleLarge = original.titleLarge.adapt(),
      titleMedium = original.titleMedium.adapt(),
      titleSmall = original.titleSmall.adapt(),
      bodyLarge = original.bodyLarge.adapt(),
      bodyMedium = original.bodyMedium.adapt(),
      bodySmall = original.bodySmall.adapt(),
      labelLarge = original.labelLarge.adapt(),
      labelMedium = original.labelMedium.adapt(),
      labelSmall = original.labelSmall.adapt(),
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
    text = platform.textForLayout(text, style),
    style = platform.styleForLayout(if (family != null && (style.fontFamily == null || style.fontFamily == FontFamily.Default)) {
      style.copy(fontFamily = family)
    } else style),
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
