@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.icy.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler as ComposeBackHandler
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSOperationQueue
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIPasteboard
import platform.posix.memcpy

/** Reads checked-in assets copied unchanged into the app's IcyAssets resource folder. */
internal fun readIcyAsset(relativePath: String): ByteArray {
  val resourcePath = requireNotNull(NSBundle.mainBundle.resourcePath)
  val path = "$resourcePath/IcyAssets/$relativePath"
  val data = requireNotNull(NSData.dataWithContentsOfFile(path)) { "Missing bundled Icy Lyrics asset: $relativePath" }
  return ByteArray(data.length.toInt()).also { result ->
    if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
  }
}

class IosIcyUiPlatform(
  override val versionName: String,
  override val fixedFrameTimeNanos: Long? = null,
  private val assetLoader: (String) -> ByteArray = ::readIcyAsset,
) : IcyUiPlatform {
  override val onboardingInstructions =
    "Connect Spotify, allow access in the system sign-in window, then come back here."
  override val emptyPlayerInstructions =
    "The player appears as soon as Spotify shares its current playback."
  override val aboutDescription =
    "A full-screen lyrics experience for iPhone and an independently distributed modification of Spicy Lyrics."
  override val fontFamily: FontFamily by lazy {
    val bytes = assetLoader("font/Roboto-Regular.ttf")
    FontFamily((100..900 step 100).map { value ->
      val weight = FontWeight(value)
      Font(
        identity = "icy-android36-roboto-$value",
        data = bytes,
        weight = weight,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
          FontVariation.weight(value), FontVariation.width(100f), FontVariation.italic(0f),
        ),
      )
    })
  }
  private val fallbackFonts by lazy { IosAndroidFontFallback(assetLoader) }
  override fun fontFallback(text: androidx.compose.ui.text.AnnotatedString, weight: FontWeight?) = fallbackFonts.apply(text, weight)
  override fun monotonicTimeMs(): Long = fixedFrameTimeNanos?.div(1_000_000)
    ?: (NSProcessInfo.processInfo.systemUptime * 1_000).toLong()
  override fun monotonicTimeNanos(): Long = fixedFrameTimeNanos ?: (CACurrentMediaTime() * 1_000_000_000).toLong()
  override fun formatDateTime(epochMs: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterShortStyle
  }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))
  override fun copyDiagnostics(text: String) { UIPasteboard.generalPasteboard.string = text }
  override fun legalDocument(document: IcyLegalDocument): String = assetLoader(
    when (document) {
      IcyLegalDocument.AGPL -> "legal/agpl_3_0_or_later.txt"
      IcyLegalDocument.THIRD_PARTY -> "legal/third_party_notices.txt"
    },
  ).decodeToString()

  @Suppress("DEPRECATION")
  @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    ComposeBackHandler(enabled = enabled, onBack = onBack)

  @Composable override fun ReducedMotionEnabled(): Boolean {
    var enabled by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
      val observer = NSNotificationCenter.defaultCenter.addObserverForName(
        UIAccessibilityReduceMotionStatusDidChangeNotification, null, NSOperationQueue.mainQueue,
      ) { enabled = UIAccessibilityIsReduceMotionEnabled() }
      onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return enabled
  }

  @Composable override fun Background(
    artwork: ImageBitmap?, enabled: Boolean, style: BackgroundStyle, isPlaying: Boolean,
    modifier: Modifier, content: @Composable () -> Unit,
  ) = IosArtworkBackground(artwork, enabled, style, isPlaying, modifier, content)
}
