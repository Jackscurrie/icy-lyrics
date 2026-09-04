package com.icy.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/** Calls the production composable with frozen data; no network or playback adapter is started. */
@Composable fun IcyParityFixtureScreen(id: String) {
  val native = LocalIcyUiPlatform.current
  val platform = remember(native, id) { object : IcyUiPlatform by native {
    override val fixedFrameTimeNanos = IcyParityFixtures.FRAME_TIME_NANOS
    override fun monotonicTimeMs() = 0L
    override fun monotonicTimeNanos() = fixedFrameTimeNanos
    override val versionName = "1.0.0-alpha01"
    override val onboardingInstructions = "Android opens a system settings page. Enable Icy Lyrics, then come back here."
    override val emptyPlayerInstructions = "The player appears as soon as Spotify publishes its media session."
    override val aboutDescription = "A full-screen lyrics experience for Android and an independently distributed modification of Spicy Lyrics."
    @Composable override fun ReducedMotionEnabled() = IcyParityFixtures.reducedMotion(id)
  } }
  val state = remember(id) { IcyParityFixtures.state(id) }
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
    IcyLyricsApp(
      state = state, isLandscape = IcyParityFixtures.isLandscape(id),
      onOpenNotificationAccess = {},
      onRequestBluetoothPermission = {},
      onPickTtml = {},
      onNavigate = {},
      onStepLandscape = {},
      onShowArtworkControls = {},
      onPlayPause = {},
      onPrevious = {},
      onNext = {},
      onSeek = {},
      onReload = {},
      onGlobalTimingOffset = {},
      onBluetoothTimingOffset = {},
      onRememberBluetoothOffsets = {},
      onMixedMediaSide = {},
      onBackgroundStyle = {},
      onBackgroundEnabled = {},
      onKeepScreenAwake = {},
      onUseLocalTtml = {},
      onRevealEnabled = {},
      onSourceStrategy = {},
      onDebugEnabled = {},
      onSpicyEnabled = {},
      onSpicyTokenSharingConsent = {},
      onConnectSpotify = {},
      onCancelSpotifyAuthorization = {},
      onDisconnectSpotify = {},
      onLrclibEnabled = {},
      onShareDiagnostics = {},
      onClearDiagnostics = {},
      onDeleteSavedLyrics = {},
      onDismissMessage = {},
    )
  }
}
