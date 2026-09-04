package com.icy.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Opt-in motion instrumentation around the production screen. The caller owns
 * state and its clock, and must retain this composition between sampled frames.
 * Kept separate from IcyParityFixtureScreen to preserve the original 20 references.
 */
@Composable fun IcyMotionFixtureScreen(
  state: IcyLyricsUiState,
  onStepLandscape: (Int) -> Unit,
  onEnterComposition: () -> Unit,
  onLeaveComposition: () -> Unit,
) {
  DisposableEffect(Unit) {
    onEnterComposition()
    onDispose { onLeaveComposition() }
  }
  IcyLyricsApp(
    state = state,
    isLandscape = true,
    onOpenNotificationAccess = {},
    onRequestBluetoothPermission = {},
    onPickTtml = {},
    onNavigate = {},
    onStepLandscape = onStepLandscape,
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

/** Frame-aligned samples shared with the future preserved-Android motion capture lane. */
object IcyMixedLyricsMotionPlan {
  const val ID = "mixed-lyrics-motion-v1"
  const val FRAME_INTERVAL_MS = 16L
  const val INITIAL_SETTLE_MS = 2_000L
  // One frame applies target state; the next supplies its first animation timestamp.
  const val PRIME_FRAMES = 2
  val completeOffsetsMs = listOf(0L, 128L, 224L, 448L, 2_000L)
  val interruptedOffsetsMs = listOf(0L, 128L, 224L)
  val sequenceIds = listOf("mixed-to-lyrics", "lyrics-to-mixed", "interrupted-mixed-to-lyrics", "reverse-to-mixed")
  const val EXPECTED_FRAMES_PER_SIDE = 22 // One pre-action image plus each listed offset per sequence.
}
