package com.icy.lyrics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler as AndroidBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import com.icy.lyrics.BuildConfig
import com.icy.lyrics.R
import java.text.DateFormat
import java.util.Date

@Composable fun rememberAndroidIcyUiPlatform(): IcyUiPlatform {
  val context = LocalContext.current
  return remember(context) { AndroidIcyUiPlatform(context) }
}

class AndroidIcyUiPlatform(private val context: Context) : IcyUiPlatform {
  override val versionName: String get() = BuildConfig.VERSION_NAME
  override val onboardingInstructions =
    "Android opens a system settings page. Enable Icy Lyrics, then come back here."
  override val emptyPlayerInstructions =
    "The player appears as soon as Spotify publishes its media session."
  override val aboutDescription =
    "A full-screen lyrics experience for Android and an independently distributed modification of Spicy Lyrics."
  override fun monotonicTimeMs(): Long = SystemClock.elapsedRealtime()
  override fun monotonicTimeNanos(): Long = System.nanoTime()
  override fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
  override fun copyDiagnostics(text: String) {
    context.getSystemService(ClipboardManager::class.java)
      ?.setPrimaryClip(ClipData.newPlainText("Icy Lyrics diagnostics", text))
  }
  override fun legalDocument(document: IcyLegalDocument): String =
    context.resources.openRawResource(
      when (document) {
        IcyLegalDocument.AGPL -> R.raw.agpl_3_0_or_later
        IcyLegalDocument.THIRD_PARTY -> R.raw.third_party_notices
      },
    ).bufferedReader().use { it.readText() }

  @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    AndroidBackHandler(enabled = enabled, onBack = onBack)
  }
  @Composable override fun ReducedMotionEnabled(): Boolean = rememberAndroidReducedMotionEnabled()
  @Composable override fun Background(
    artwork: ImageBitmap?, enabled: Boolean, style: BackgroundStyle, isPlaying: Boolean,
    modifier: Modifier, content: @Composable () -> Unit,
  ) = AndroidArtworkBackground(artwork?.asAndroidBitmap(), enabled, style, isPlaying, modifier, content)
}
