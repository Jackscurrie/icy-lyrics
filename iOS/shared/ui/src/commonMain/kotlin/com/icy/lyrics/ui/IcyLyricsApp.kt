package com.icy.lyrics.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.icy.lyrics.ui.icons.Icons
import com.icy.lyrics.ui.icons.automirrored.filled.ArrowBack
import com.icy.lyrics.ui.icons.filled.Bluetooth
import com.icy.lyrics.ui.icons.filled.BugReport
import com.icy.lyrics.ui.icons.filled.Close
import com.icy.lyrics.ui.icons.filled.FolderOpen
import com.icy.lyrics.ui.icons.filled.Fullscreen
import com.icy.lyrics.ui.icons.filled.FullscreenExit
import com.icy.lyrics.ui.icons.filled.Info
import com.icy.lyrics.ui.icons.filled.LibraryMusic
import com.icy.lyrics.ui.icons.filled.Pause
import com.icy.lyrics.ui.icons.filled.PlayArrow
import com.icy.lyrics.ui.icons.filled.Refresh
import com.icy.lyrics.ui.icons.filled.Settings
import com.icy.lyrics.ui.icons.filled.SkipNext
import com.icy.lyrics.ui.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.icy.lyrics.ui.IcyText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.media.NowPlayingSnapshot
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun IcyLyricsApp(
  state: IcyLyricsUiState,
  isLandscape: Boolean,
  onOpenNotificationAccess: () -> Unit,
  onRequestBluetoothPermission: () -> Unit,
  onPickTtml: () -> Unit,
  onNavigate: (AppDestination) -> Unit,
  onStepLandscape: (Int) -> Unit,
  onShowArtworkControls: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  onReload: () -> Unit,
  onGlobalTimingOffset: (Int) -> Unit,
  onBluetoothTimingOffset: (Int?) -> Unit,
  onRememberBluetoothOffsets: (Boolean) -> Unit,
  onMixedMediaSide: (MixedMediaSide) -> Unit,
  onBackgroundStyle: (BackgroundStyle) -> Unit,
  onBackgroundEnabled: (Boolean) -> Unit,
  onKeepScreenAwake: (Boolean) -> Unit,
  onUseLocalTtml: (Boolean) -> Unit,
  onRevealEnabled: (Boolean) -> Unit,
  onSourceStrategy: (SourceStrategy) -> Unit,
  onDebugEnabled: (Boolean) -> Unit,
  onSpicyEnabled: (Boolean) -> Unit,
  onSpicyTokenSharingConsent: (Boolean) -> Unit,
  onConnectSpotify: () -> Unit,
  onCancelSpotifyAuthorization: () -> Unit,
  onDisconnectSpotify: () -> Unit,
  onLrclibEnabled: (Boolean) -> Unit,
  onShareDiagnostics: () -> Unit,
  onClearDiagnostics: () -> Unit,
  onDeleteSavedLyrics: (String) -> Unit,
  onDismissMessage: () -> Unit,
) {
  val snackbarHost = remember { SnackbarHostState() }
  IcyBackHandler(enabled = state.destination != AppDestination.PLAYER) {
    onNavigate(
      if (
        state.destination == AppDestination.LIBRARY ||
        state.destination == AppDestination.DEBUG ||
        state.destination == AppDestination.ABOUT_LEGAL
      ) {
        AppDestination.SETTINGS
      } else {
        AppDestination.PLAYER
      },
    )
  }
  LaunchedEffect(state.transientMessage) {
    state.transientMessage?.let {
      snackbarHost.showSnackbar(it)
      onDismissMessage()
    }
  }

  MaterialTheme(colorScheme = icyColors(), typography = icyTypography()) {
    Scaffold(
      containerColor = Color.Black,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = { SnackbarHost(snackbarHost) },
    ) { scaffoldPadding ->
      Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
        when (state.destination) {
          AppDestination.SETTINGS -> SettingsScreen(
            state = state,
            onBack = { onNavigate(AppDestination.PLAYER) },
            onNavigate = onNavigate,
            onPickTtml = onPickTtml,
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onGlobalTimingOffset = onGlobalTimingOffset,
            onBluetoothTimingOffset = onBluetoothTimingOffset,
            onRememberBluetoothOffsets = onRememberBluetoothOffsets,
            onMixedMediaSide = onMixedMediaSide,
            onBackgroundStyle = onBackgroundStyle,
            onBackgroundEnabled = onBackgroundEnabled,
            onKeepScreenAwake = onKeepScreenAwake,
            onUseLocalTtml = onUseLocalTtml,
            onRevealEnabled = onRevealEnabled,
            onSourceStrategy = onSourceStrategy,
            onDebugEnabled = onDebugEnabled,
            onSpicyEnabled = onSpicyEnabled,
            onSpicyTokenSharingConsent = onSpicyTokenSharingConsent,
            onConnectSpotify = onConnectSpotify,
            onCancelSpotifyAuthorization = onCancelSpotifyAuthorization,
            onDisconnectSpotify = onDisconnectSpotify,
            onLrclibEnabled = onLrclibEnabled,
          )
          AppDestination.LIBRARY -> LibraryScreen(
            items = state.library,
            onBack = { onNavigate(AppDestination.SETTINGS) },
            onImport = onPickTtml,
            onDelete = onDeleteSavedLyrics,
          )
          AppDestination.DEBUG -> DebugScreen(
            diagnostics = state.diagnostics,
            onBack = { onNavigate(AppDestination.SETTINGS) },
            onReload = onReload,
            onShare = onShareDiagnostics,
            onClear = onClearDiagnostics,
          )
          AppDestination.ABOUT_LEGAL -> AboutLegalScreen(
            onBack = { onNavigate(AppDestination.SETTINGS) },
          )
          AppDestination.PLAYER -> PlayerHost(
            state = state,
            isLandscape = isLandscape,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onSettings = { onNavigate(AppDestination.SETTINGS) },
            onPickTtml = onPickTtml,
            onStepLandscape = onStepLandscape,
            onShowArtworkControls = onShowArtworkControls,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeek = onSeek,
            onReload = onReload,
          )
        }
      }
    }
  }
}

@Composable
private fun PlayerHost(
  state: IcyLyricsUiState,
  isLandscape: Boolean,
  onOpenNotificationAccess: () -> Unit,
  onSettings: () -> Unit,
  onPickTtml: () -> Unit,
  onStepLandscape: (Int) -> Unit,
  onShowArtworkControls: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  onReload: () -> Unit,
) {
  if (!state.notificationAccess) {
    Onboarding(onOpenNotificationAccess)
    return
  }
  val snapshot = state.nowPlaying
  if (snapshot == null) {
    EmptyPlayer(onSettings)
    return
  }
  val playbackFrame by rememberPlaybackFrame(snapshot, state.settings.effectiveTimingOffsetMs)
  // The lyric clock includes the single shared +100 ms perceptual lead and user timing.
  // Transport remains on the raw media-session clock so seeking never inherits lyric offsets.
  val playbackPosition = playbackFrame?.rawPositionMs ?: snapshot.currentPositionMs(LocalIcyUiPlatform.current.monotonicTimeMs())
  val position = playbackFrame?.lyricsPositionMs ?: playbackPosition
  ArtworkBackground(
    artwork = snapshot.artwork,
    enabled = state.settings.backgroundEnabled,
    style = state.settings.backgroundStyle,
    isPlaying = snapshot.isPlaying,
  ) {
    if (isLandscape) {
      LandscapePlayer(
        state = state,
        snapshot = snapshot,
        positionMs = position,
        playbackPositionMs = playbackPosition,
        onSettings = onSettings,
        onStep = onStepLandscape,
        onShowArtworkControls = onShowArtworkControls,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onSeek = onSeek,
      )
    } else {
      PortraitPlayer(
        state = state,
        snapshot = snapshot,
        positionMs = position,
        playbackPositionMs = playbackPosition,
        onSettings = onSettings,
        onPickTtml = onPickTtml,
        onReload = onReload,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onSeek = onSeek,
      )
    }
  }
}

@Composable
private fun Onboarding(onOpenNotificationAccess: () -> Unit) {
  CenteredPage {
    Text("Welcome to Icy Lyrics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(
      "Allow now-playing access so Icy Lyrics can follow Spotify without controlling your account.",
      color = Color.White.copy(alpha = 0.72f),
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
    Button(onClick = onOpenNotificationAccess) { Text("Allow now-playing access") }
    Spacer(Modifier.height(10.dp))
    Text(
      LocalIcyUiPlatform.current.onboardingInstructions,
      style = MaterialTheme.typography.bodySmall,
      color = Color.White.copy(alpha = 0.52f),
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun EmptyPlayer(onSettings: () -> Unit) {
  CenteredPage {
    Text("Play something in Spotify", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(LocalIcyUiPlatform.current.emptyPlayerInstructions, color = Color.White.copy(alpha = 0.68f))
    Spacer(Modifier.height(18.dp))
    TextButton(onClick = onSettings) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("Settings") }
  }
}

@Composable
private fun PortraitPlayer(
  state: IcyLyricsUiState,
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  playbackPositionMs: Long,
  onSettings: () -> Unit,
  onPickTtml: () -> Unit,
  onReload: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  var lyricsExpanded by remember(snapshot.identity.exactStorageKey) { mutableStateOf(false) }
  val lyricsPresentation = portraitLyricsPresentation(lyricsExpanded)
  IcyBackHandler(enabled = lyricsExpanded) { lyricsExpanded = false }

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
  ) {
    val initialLayout = portraitPlayerLayout(
      viewportWidth = maxWidth,
      viewportHeight = maxHeight,
      fontScale = LocalDensity.current.fontScale,
    )
    val titleBlockHeight = rememberTrackTitlesHeight(
      snapshot = snapshot,
      width = maxWidth,
      compact = initialLayout.compact,
    )
    val layout = portraitPlayerLayout(
      viewportWidth = maxWidth,
      viewportHeight = maxHeight,
      fontScale = LocalDensity.current.fontScale,
      titleBlockHeight = titleBlockHeight,
    )
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (lyricsPresentation.showPlayerChrome) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          SourceBadge(state)
          Row {
            IconButton(onClick = onPickTtml) { Icon(Icons.Default.FolderOpen, "Import local lyrics") }
            IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, "Reload lyrics") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Open settings") }
          }
        }
        Artwork(snapshot.artwork, Modifier.size(layout.artworkSize))
        Spacer(Modifier.height(layout.artworkTitleSpacing))
        TrackTitles(
          snapshot = snapshot,
          centered = true,
          compact = layout.compact,
        )
        Spacer(Modifier.height(layout.sectionSpacing))
        if (layout.showPlaybackButtons) {
          Transport(
            snapshot = snapshot,
            positionMs = playbackPositionMs,
            compact = true,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeek = onSeek,
          )
        } else {
          // In very short split-screen windows, keep every required section by
          // collapsing transport to the scrubber instead of squeezing lyrics out.
          PlaybackTimeline(
            snapshot = snapshot,
            positionMs = playbackPositionMs,
            onSeek = onSeek,
          )
        }
        Spacer(Modifier.height(layout.sectionSpacing))
      }
      PortraitLyricsPane(
        state = state,
        snapshot = snapshot,
        positionMs = positionMs,
        playbackPositionMs = playbackPositionMs,
        presentation = lyricsPresentation,
        modifier = Modifier.fillMaxWidth().weight(1f),
        onToggleExpanded = { lyricsExpanded = !lyricsExpanded },
        onSeek = onSeek,
      )
    }
  }
}

internal data class PortraitLyricsPresentation(
  val showPlayerChrome: Boolean,
  val focusPresentation: Boolean,
  val toggleContentDescription: String,
)

internal fun portraitLyricsPresentation(expanded: Boolean): PortraitLyricsPresentation =
  PortraitLyricsPresentation(
    showPlayerChrome = !expanded,
    // Portrait fullscreen deliberately retains the regular, scrollable lyric
    // presentation instead of adopting landscape's three-line focus mode.
    focusPresentation = false,
    toggleContentDescription = if (expanded) "Collapse lyrics" else "Expand lyrics",
  )

@Composable
private fun PortraitLyricsPane(
  state: IcyLyricsUiState,
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  playbackPositionMs: Long,
  presentation: PortraitLyricsPresentation,
  modifier: Modifier = Modifier,
  onToggleExpanded: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  Column(modifier) {
    IconButton(
      onClick = onToggleExpanded,
      modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.36f)),
    ) {
      Icon(
        imageVector = if (presentation.showPlayerChrome) Icons.Default.Fullscreen else Icons.Default.FullscreenExit,
        contentDescription = presentation.toggleContentDescription,
      )
    }
    LyricsStatusPane(
      status = state.lyrics,
      positionMs = positionMs,
      rawPositionMs = playbackPositionMs,
      durationMs = snapshot.durationMs,
      reveal = state.settings.revealEnabled,
      focusPresentation = presentation.focusPresentation,
      modifier = Modifier.fillMaxWidth().weight(1f),
      onSeek = onSeek,
    )
  }
}

internal data class PortraitPlayerLayout(
  val artworkSize: androidx.compose.ui.unit.Dp,
  val compact: Boolean,
  val showPlaybackButtons: Boolean,
  val artworkTitleSpacing: androidx.compose.ui.unit.Dp,
  val sectionSpacing: androidx.compose.ui.unit.Dp,
  val estimatedLyricsHeight: androidx.compose.ui.unit.Dp,
)

/**
 * Keeps a real lyric viewport in short/split-screen portrait windows. Values
 * estimate Material's line and control heights; Compose's final `weight(1f)`
 * receives any measurement slack.
 */
internal fun portraitPlayerLayout(
  viewportWidth: androidx.compose.ui.unit.Dp,
  viewportHeight: androidx.compose.ui.unit.Dp,
  fontScale: Float = 1f,
  titleBlockHeight: androidx.compose.ui.unit.Dp? = null,
): PortraitPlayerLayout {
  val safeFontScale = fontScale.coerceAtLeast(1f)
  val compact = viewportHeight < 700.dp || safeFontScale > 1.15f
  val showPlaybackButtons = viewportHeight >= 480.dp && safeFontScale <= 1.45f
  val artworkTitleSpacing = if (compact) 4.dp else 14.dp
  val sectionSpacing = if (compact) 4.dp else 8.dp
  val idealArtwork = minOf(
    viewportWidth * if (compact) 0.52f else 0.66f,
    if (compact) 240.dp else 310.dp,
  ).coerceAtLeast(0.dp)

  val headerHeight = 48.dp
  val titleHeight = titleBlockHeight?.coerceAtLeast(0.dp)
    ?: ((if (compact) 48.dp else 64.dp) * safeFontScale)
  val timelineHeight = 64.dp * safeFontScale
  val playbackButtonsHeight = if (showPlaybackButtons) 48.dp else 0.dp
  val fixedHeight = headerHeight + artworkTitleSpacing + sectionSpacing * 2 +
    titleHeight + timelineHeight + playbackButtonsHeight
  val availableForArtworkAndLyrics = (viewportHeight - fixedHeight).coerceAtLeast(0.dp)
  val minimumLyricsHeight = if (viewportHeight < 400.dp) 56.dp else 96.dp
  val minimumArtwork = minOf(
    72.dp,
    (availableForArtworkAndLyrics - minimumLyricsHeight).coerceAtLeast(0.dp),
  )
  val preferredLyricsHeight = maxOf(
    minimumLyricsHeight,
    viewportHeight * if (compact) 0.34f else 0.30f,
  )
  val reservedLyricsHeight = minOf(
    preferredLyricsHeight,
    (availableForArtworkAndLyrics - minimumArtwork).coerceAtLeast(0.dp),
  )
  val artworkSize = minOf(
    idealArtwork,
    (availableForArtworkAndLyrics - reservedLyricsHeight).coerceAtLeast(0.dp),
  )
  val estimatedLyricsHeight = (availableForArtworkAndLyrics - artworkSize).coerceAtLeast(0.dp)

  return PortraitPlayerLayout(
    artworkSize = artworkSize,
    compact = compact,
    showPlaybackButtons = showPlaybackButtons,
    artworkTitleSpacing = artworkTitleSpacing,
    sectionSpacing = sectionSpacing,
    estimatedLyricsHeight = estimatedLyricsHeight,
  )
}

@Composable
private fun LandscapePlayer(
  state: IcyLyricsUiState,
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  playbackPositionMs: Long,
  onSettings: () -> Unit,
  onStep: (Int) -> Unit,
  onShowArtworkControls: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  val reducedMotion = rememberReducedMotionEnabled()
  val desktopEasing = remember { CubicBezierEasing(0.16f, 1f, 0.3f, 1f) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val edgeWidth = landscapeEdgeGutter(maxWidth)
    val lyricsLayout = landscapeLyricsLayout(state.landscapeMode, state.settings.mixedMediaSide)
    val desktopMixedLayout = rememberDesktopMixedLayout(
      viewportWidth = maxWidth,
      viewportHeight = maxHeight,
      mediaSide = state.settings.mixedMediaSide,
      snapshot = snapshot,
      fontScale = LocalDensity.current.fontScale,
    )
    val lyricsContentWidth = (maxWidth - lyricsLayout.outerHorizontalInset * 2).coerceAtLeast(0.dp)
    val desiredLyricsX = if (state.landscapeMode == LandscapeMode.MIXED) {
      desktopMixedLayout.lyricsStart
    } else {
      lyricsLayout.outerHorizontalInset + lyricsContentWidth * lyricsLayout.startFraction
    }
    val desiredLyricsWidth = if (state.landscapeMode == LandscapeMode.MIXED) {
      desktopMixedLayout.lyricsWidth
    } else {
      lyricsContentWidth * lyricsLayout.widthFraction
    }
    val safeLyricsBounds = if (state.landscapeMode == LandscapeMode.MIXED) {
      landscapeLyricsBounds(
        viewportWidth = maxWidth,
        desiredStart = desiredLyricsX,
        desiredWidth = desiredLyricsWidth,
        edgeGutter = edgeWidth,
      )
    } else {
      // Focus lyrics already reserve this same seven-percent inset inside the
      // canvas. Keeping its host full-width avoids applying that inset twice.
      LandscapeLyricsBounds(desiredLyricsX, desiredLyricsWidth)
    }
    val targetLyricsX = safeLyricsBounds.start
    val targetLyricsWidth = safeLyricsBounds.width
    val targetLyricsY = if (state.landscapeMode == LandscapeMode.MIXED) 0.dp else lyricsLayout.verticalInset
    val targetLyricsHeight = if (state.landscapeMode == LandscapeMode.MIXED) {
      maxHeight
    } else {
      (maxHeight - lyricsLayout.verticalInset * 2).coerceAtLeast(0.dp)
    }
    val boundsDurationMs = if (reducedMotion) 0 else LANDSCAPE_BOUNDS_TRANSITION_MS
    val lyricsX by animateDpAsState(
      targetValue = targetLyricsX,
      animationSpec = tween(durationMillis = boundsDurationMs, easing = desktopEasing),
      label = "landscape-lyrics-x",
    )
    val lyricsWidth by animateDpAsState(
      targetValue = targetLyricsWidth,
      animationSpec = tween(durationMillis = boundsDurationMs, easing = desktopEasing),
      label = "landscape-lyrics-width",
    )
    val lyricsY by animateDpAsState(
      targetValue = targetLyricsY,
      animationSpec = tween(durationMillis = boundsDurationMs, easing = desktopEasing),
      label = "landscape-lyrics-y",
    )
    val lyricsHeight by animateDpAsState(
      targetValue = targetLyricsHeight,
      animationSpec = tween(durationMillis = boundsDurationMs, easing = desktopEasing),
      label = "landscape-lyrics-height",
    )
    val targetFocusPresentation = lyricsLayout.focusPresentation
    var renderedFocusPresentation by remember { mutableStateOf(targetFocusPresentation) }
    var presentationVisible by remember { mutableStateOf(true) }
    LaunchedEffect(targetFocusPresentation, reducedMotion) {
      if (renderedFocusPresentation == targetFocusPresentation) {
        presentationVisible = true
      } else if (reducedMotion) {
        renderedFocusPresentation = targetFocusPresentation
        presentationVisible = true
      } else {
        // Typography changes alter wrapping and row geometry. Hide that switch
        // briefly while the shared host is already moving to its new bounds.
        presentationVisible = false
        delay(LANDSCAPE_PRESENTATION_FADE_OUT_MS.toLong())
        renderedFocusPresentation = targetFocusPresentation
        presentationVisible = true
      }
    }
    val presentationAlpha by animateFloatAsState(
      targetValue = if (presentationVisible) 1f else 0f,
      animationSpec = tween(
        durationMillis = if (reducedMotion) 0 else if (presentationVisible) {
          LANDSCAPE_PRESENTATION_FADE_IN_MS
        } else {
          LANDSCAPE_PRESENTATION_FADE_OUT_MS
        },
        easing = desktopEasing,
      ),
      label = "landscape-lyrics-presentation-alpha",
    )
    AnimatedContent(
      targetState = state.landscapeMode,
      transitionSpec = {
        if (reducedMotion) {
          fadeIn(tween(durationMillis = 90)).togetherWith(fadeOut(tween(durationMillis = 90)))
        } else {
          (
            fadeIn(tween(durationMillis = 440, easing = desktopEasing)) +
              scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(durationMillis = 440, easing = desktopEasing),
              )
            ).togetherWith(
            fadeOut(tween(durationMillis = 440, easing = desktopEasing)) +
              scaleOut(
                targetScale = 1.015f,
                animationSpec = tween(durationMillis = 440, easing = desktopEasing),
              ),
          )
        }
      },
      label = "landscape-mode",
      modifier = Modifier.fillMaxSize(),
    ) { mode ->
      val controlsPolicy = landscapeMediaControlsPolicy(mode)
      when (mode) {
        LandscapeMode.ARTWORK_ONLY -> ArtworkOnlyMode(
          snapshot = snapshot,
          positionMs = playbackPositionMs,
          controlsVisible = state.artworkControlsVisible && controlsPolicy.centerTapRevealEnabled,
          onCenterTap = onShowArtworkControls,
          onPlayPause = onPlayPause,
          onPrevious = onPrevious,
          onNext = onNext,
          onSeek = onSeek,
        )
        LandscapeMode.ARTWORK_TITLES -> ArtworkTitlesMode(
          snapshot = snapshot,
          positionMs = playbackPositionMs,
          controlsVisible = state.artworkControlsVisible && controlsPolicy.centerTapRevealEnabled,
          onCenterTap = onShowArtworkControls,
          onPlayPause = onPlayPause,
          onPrevious = onPrevious,
          onNext = onNext,
          onSeek = onSeek,
        )
        LandscapeMode.MIXED -> MixedMode(
          snapshot = snapshot,
          playbackPositionMs = playbackPositionMs,
          layout = desktopMixedLayout,
          showPersistentPlaybackButtons = controlsPolicy.persistentPlaybackButtons,
          onPlayPause = onPlayPause,
          onPrevious = onPrevious,
          onNext = onNext,
          onSeek = onSeek,
        )
        LandscapeMode.LYRICS -> Box(Modifier.fillMaxSize())
      }
    }
    // Keep one lyric subtree alive while its mixed/fullscreen bounds move. In
    // particular, switching MIXED <-> LYRICS must not recreate the scene engine
    // or its spring animator just because AnimatedContent replaces its mode body.
    AnimatedVisibility(
      visible = lyricsLayout.visible,
      enter = fadeIn(tween(durationMillis = if (reducedMotion) 0 else 220)),
      exit = fadeOut(tween(durationMillis = if (reducedMotion) 0 else 160)),
      modifier = Modifier
        .offset { IntOffset(lyricsX.roundToPx(), lyricsY.roundToPx()) }
        .width(lyricsWidth)
        .height(lyricsHeight),
    ) {
      LyricsStatusPane(
        status = state.lyrics,
        positionMs = positionMs,
        rawPositionMs = playbackPositionMs,
        durationMs = snapshot.durationMs,
        reveal = state.settings.revealEnabled,
        focusPresentation = renderedFocusPresentation,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = presentationAlpha }
          .padding(
            horizontal = if (state.landscapeMode == LandscapeMode.MIXED) {
              0.dp
            } else {
              lyricsLayout.innerHorizontalPadding
            },
          ),
        onSeek = onSeek,
      )
    }
    LandscapeEdge(
      left = true,
      enabled = state.landscapeMode != LandscapeMode.ARTWORK_ONLY,
      width = edgeWidth,
      onClick = { onStep(-1) },
    )
    LandscapeEdge(
      left = false,
      enabled = state.landscapeMode != LandscapeMode.LYRICS,
      width = edgeWidth,
      onClick = { onStep(1) },
    )
    // Keep the settled mixed view visually identical to desktop. Settings stay
    // available in the other fullscreen modes and in portrait.
    if (state.landscapeMode != LandscapeMode.MIXED) {
      IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
        Icon(Icons.Default.Settings, "Open settings")
      }
    }
  }
}

@Composable
private fun ArtworkOnlyMode(
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  controlsVisible: Boolean,
  onCenterTap: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  val centerTapInteractions = remember { MutableInteractionSource() }
  Box(
    Modifier.fillMaxSize().padding(horizontal = 100.dp, vertical = 18.dp)
      .clickable(
        interactionSource = centerTapInteractions,
        indication = null,
        role = Role.Button,
        onClickLabel = "Show playback controls",
        onClick = onCenterTap,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Artwork(snapshot.artwork, Modifier.fillMaxHeight(0.9f).aspectRatio(1f))
    PlaybackControlsOverlay(
      snapshot = snapshot,
      positionMs = positionMs,
      visible = controlsVisible,
      onPlayPause = onPlayPause,
      onPrevious = onPrevious,
      onNext = onNext,
      onSeek = onSeek,
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.64f),
    )
  }
}

@Composable
private fun ArtworkTitlesMode(
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  controlsVisible: Boolean,
  onCenterTap: () -> Unit,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  val centerTapInteractions = remember { MutableInteractionSource() }
  Box(
    Modifier
      .fillMaxSize()
      .clickable(
        interactionSource = centerTapInteractions,
        indication = null,
        role = Role.Button,
        onClickLabel = "Show playback controls",
        onClick = onCenterTap,
      ),
  ) {
    Column(
      Modifier.fillMaxSize().padding(horizontal = 110.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Artwork(snapshot.artwork, Modifier.weight(1f).aspectRatio(1f))
      Spacer(Modifier.height(14.dp))
      TrackTitles(snapshot, centered = true)
    }
    PlaybackControlsOverlay(
      snapshot = snapshot,
      positionMs = positionMs,
      visible = controlsVisible,
      onPlayPause = onPlayPause,
      onPrevious = onPrevious,
      onNext = onNext,
      onSeek = onSeek,
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.64f).padding(bottom = 12.dp),
    )
  }
}

@Composable
private fun PlaybackControlsOverlay(
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  visible: Boolean,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(120)),
    exit = fadeOut(tween(160)),
    modifier = modifier,
  ) {
    Surface(color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(24.dp)) {
      Transport(snapshot, positionMs, false, onPlayPause, onPrevious, onNext, onSeek, Modifier.padding(12.dp))
    }
  }
}

@Composable
private fun MixedMode(
  snapshot: NowPlayingSnapshot,
  playbackPositionMs: Long,
  layout: DesktopMixedLayout,
  showPersistentPlaybackButtons: Boolean,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    Column(
      Modifier
        .offset(x = layout.mediaStart, y = layout.mediaTop)
        .width(layout.artworkSize),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Artwork(snapshot.artwork, Modifier.size(layout.artworkSize))
      Spacer(Modifier.height(layout.artworkTitleSpacing))
      TrackTitles(snapshot, centered = true, compact = true)
      if (showPersistentPlaybackButtons) {
        Box(
          modifier = Modifier.fillMaxWidth().height(layout.playbackButtonsHeight),
          contentAlignment = Alignment.Center,
        ) {
          // MIXED owns one permanent timeline below this row. Keep the transport
          // visible without obscuring the art or adding a duplicate scrubber.
          PlaybackButtons(
            snapshot = snapshot,
            compact = true,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
          )
        }
      }
      PlaybackTimeline(
        snapshot = snapshot,
        positionMs = playbackPositionMs,
        onSeek = onSeek,
        inlineTimeLabels = layout.inlineTimeLabels,
        inlineTrackGap = layout.timelineTrackGap,
        inlineLabelWidth = layout.timelineLabelWidth,
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            start = layout.timelineHorizontalPadding,
            top = layout.timelineTopPadding,
            end = layout.timelineHorizontalPadding,
          ),
      )
    }
  }
}

internal data class LandscapeMediaControlsPolicy(
  val centerTapRevealEnabled: Boolean,
  val persistentPlaybackButtons: Boolean,
)

internal fun landscapeMediaControlsPolicy(mode: LandscapeMode): LandscapeMediaControlsPolicy = when (mode) {
  LandscapeMode.MIXED -> LandscapeMediaControlsPolicy(
    centerTapRevealEnabled = false,
    persistentPlaybackButtons = true,
  )
  LandscapeMode.ARTWORK_ONLY,
  LandscapeMode.ARTWORK_TITLES,
  -> LandscapeMediaControlsPolicy(
    centerTapRevealEnabled = true,
    persistentPlaybackButtons = false,
  )
  LandscapeMode.LYRICS -> LandscapeMediaControlsPolicy(
    centerTapRevealEnabled = false,
    persistentPlaybackButtons = false,
  )
}

internal data class DesktopMixedLayout(
  val artworkSize: androidx.compose.ui.unit.Dp,
  val mediaStart: androidx.compose.ui.unit.Dp,
  val mediaTop: androidx.compose.ui.unit.Dp,
  val artworkTitleSpacing: androidx.compose.ui.unit.Dp,
  val playbackButtonsHeight: androidx.compose.ui.unit.Dp,
  val timelineTopPadding: androidx.compose.ui.unit.Dp,
  val timelineHorizontalPadding: androidx.compose.ui.unit.Dp,
  val timelineTrackGap: androidx.compose.ui.unit.Dp,
  val timelineLabelWidth: androidx.compose.ui.unit.Dp,
  val inlineTimeLabels: Boolean,
  val estimatedMediaBottom: androidx.compose.ui.unit.Dp,
  val lyricsStart: androidx.compose.ui.unit.Dp,
  val lyricsWidth: androidx.compose.ui.unit.Dp,
)

/** Responsive port of the desktop fullscreen variables in ContentBox.css. */
internal fun desktopMixedLayout(
  viewportWidth: androidx.compose.ui.unit.Dp,
  viewportHeight: androidx.compose.ui.unit.Dp,
  mediaSide: MixedMediaSide,
  fontScale: Float = 1f,
  titleBlockHeight: androidx.compose.ui.unit.Dp? = null,
): DesktopMixedLayout {
  val safeFontScale = fontScale.coerceAtLeast(1f)
  val idealArtworkSize = (minOf(viewportWidth * 0.30f, viewportHeight * 0.52f) * 1.10f)
    .coerceAtLeast(0.dp)
  val titleHeight = titleBlockHeight?.coerceAtLeast(0.dp) ?: (48.dp * safeFontScale)
  val playbackButtonsHeight = 48.dp
  fun supportsInlineLabels(artworkSize: androidx.compose.ui.unit.Dp): Boolean {
    val labelWidth = maxOf(artworkSize * 0.080f, 48.dp * safeFontScale)
    val horizontalPadding = artworkSize * 0.010f
    val gap = artworkSize * 0.021f
    val trackWidth = artworkSize - horizontalPadding * 2 - labelWidth * 2 - gap * 2
    return trackWidth >= 64.dp
  }
  var inlineTimeLabels = supportsInlineLabels(idealArtworkSize)
  fun timelineHeight(): androidx.compose.ui.unit.Dp = if (inlineTimeLabels) {
    48.dp
  } else {
    48.dp + 20.dp * safeFontScale
  }
  fun heightCappedArtwork(): androidx.compose.ui.unit.Dp {
    val fixedStackHeight = timelineHeight() + titleHeight + playbackButtonsHeight
    return minOf(
      idealArtworkSize,
      ((viewportHeight - 16.dp - fixedStackHeight) / 1.075f).coerceAtLeast(0.dp),
    )
  }
  var artworkSize = heightCappedArtwork()
  val recomputedInline = supportsInlineLabels(artworkSize)
  if (recomputedInline != inlineTimeLabels) {
    inlineTimeLabels = recomputedInline
    artworkSize = heightCappedArtwork()
  }
  val mediaEdgeGap = (viewportWidth / 2f - artworkSize - artworkSize / 4f)
    .coerceAtLeast(0.dp)
  val mediaStart = if (mediaSide == MixedMediaSide.LEFT) {
    mediaEdgeGap
  } else {
    (viewportWidth - mediaEdgeGap - artworkSize).coerceAtLeast(0.dp)
  }
  val timelineTopPadding = artworkSize * 0.045f
  val artworkTitleSpacing = artworkSize * 0.030f
  val stackHeight = artworkSize + artworkTitleSpacing + titleHeight +
    playbackButtonsHeight + timelineTopPadding + timelineHeight()
  val desktopMediaTop = (viewportHeight / 2f - artworkSize * 0.625f).coerceAtLeast(0.dp)
  val mediaTop = minOf(
    desktopMediaTop,
    (viewportHeight - stackHeight - 8.dp).coerceAtLeast(8.dp),
  )

  // Desktop lyrics padding follows the NowBar's right spacing (art / 4) and
  // its outer edge gap. Swapping sides mirrors those paddings exactly.
  val lyricPaddingBesideMedia = (viewportWidth / 2f - (artworkSize / 4f) * 0.30f)
    .coerceIn(0.dp, viewportWidth)
  val lyricOuterPadding = (mediaEdgeGap * 0.35f).coerceAtLeast(0.dp)
  val lyricsStart = if (mediaSide == MixedMediaSide.LEFT) {
    lyricPaddingBesideMedia
  } else {
    lyricOuterPadding
  }
  val lyricsEnd = if (mediaSide == MixedMediaSide.LEFT) {
    (viewportWidth - lyricOuterPadding).coerceAtLeast(lyricsStart)
  } else {
    (viewportWidth - lyricPaddingBesideMedia).coerceAtLeast(lyricsStart)
  }

  return DesktopMixedLayout(
    artworkSize = artworkSize,
    mediaStart = mediaStart,
    mediaTop = mediaTop,
    artworkTitleSpacing = artworkTitleSpacing,
    playbackButtonsHeight = playbackButtonsHeight,
    timelineTopPadding = timelineTopPadding,
    timelineHorizontalPadding = artworkSize * 0.010f,
    timelineTrackGap = artworkSize * 0.021f,
    timelineLabelWidth = maxOf(artworkSize * 0.080f, 48.dp * safeFontScale),
    inlineTimeLabels = inlineTimeLabels,
    estimatedMediaBottom = mediaTop + stackHeight,
    lyricsStart = lyricsStart,
    lyricsWidth = (lyricsEnd - lyricsStart).coerceAtLeast(0.dp),
  )
}

@Composable
private fun rememberDesktopMixedLayout(
  viewportWidth: androidx.compose.ui.unit.Dp,
  viewportHeight: androidx.compose.ui.unit.Dp,
  mediaSide: MixedMediaSide,
  snapshot: NowPlayingSnapshot,
  fontScale: Float,
): DesktopMixedLayout {
  val density = LocalDensity.current
  val textMeasurer = rememberIcyTextMeasurer(cacheSize = 8)
  val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
  val artistStyle = MaterialTheme.typography.bodyMedium
  return remember(
    viewportWidth,
    viewportHeight,
    mediaSide,
    snapshot.displayTitle,
    snapshot.displayArtist,
    fontScale,
    density,
    textMeasurer,
    titleStyle,
    artistStyle,
  ) {
    var layout = desktopMixedLayout(viewportWidth, viewportHeight, mediaSide, fontScale)
    // Artwork width affects wrapping, while wrapped title height affects the
    // height-capped artwork. Re-measure a bounded number of times; this sequence
    // only shrinks at wrap thresholds and settles quickly in practice.
    repeat(8) {
      val widthPx = with(density) { layout.artworkSize.roundToPx() }.coerceAtLeast(1)
      val titleHeightPx = measureTrackTitlesHeightPx(
        textMeasurer = textMeasurer,
        title = snapshot.displayTitle,
        artist = snapshot.displayArtist,
        widthPx = widthPx,
        titleStyle = titleStyle,
        artistStyle = artistStyle,
        spacingPx = with(density) { TRACK_TITLES_SPACING.roundToPx() },
      )
      layout = desktopMixedLayout(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        mediaSide = mediaSide,
        fontScale = fontScale,
        titleBlockHeight = with(density) { titleHeightPx.toDp() },
      )
    }
    layout
  }
}

internal data class LandscapeLyricsLayout(
  val visible: Boolean,
  val focusPresentation: Boolean,
  val outerHorizontalInset: androidx.compose.ui.unit.Dp,
  val innerHorizontalPadding: androidx.compose.ui.unit.Dp,
  val verticalInset: androidx.compose.ui.unit.Dp,
  val startFraction: Float,
  val widthFraction: Float,
)

internal const val LANDSCAPE_BOUNDS_TRANSITION_MS = 440
internal const val LANDSCAPE_PRESENTATION_FADE_OUT_MS = 120
internal const val LANDSCAPE_PRESENTATION_FADE_IN_MS = 180

internal data class LandscapeLyricsBounds(
  val start: androidx.compose.ui.unit.Dp,
  val width: androidx.compose.ui.unit.Dp,
)

/** A dedicated tap gutter prevents the navigation layer covering lyric hit targets. */
internal fun landscapeEdgeGutter(
  viewportWidth: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = minOf(
  128.dp,
  maxOf(48.dp, viewportWidth * 0.07f),
  (viewportWidth / 2f).coerceAtLeast(0.dp),
)

internal fun landscapeLyricsBounds(
  viewportWidth: androidx.compose.ui.unit.Dp,
  desiredStart: androidx.compose.ui.unit.Dp,
  desiredWidth: androidx.compose.ui.unit.Dp,
  edgeGutter: androidx.compose.ui.unit.Dp,
): LandscapeLyricsBounds {
  val safeViewportWidth = viewportWidth.coerceAtLeast(0.dp)
  val safeGutter = edgeGutter.coerceIn(0.dp, safeViewportWidth / 2f)
  val safeStart = desiredStart.coerceIn(safeGutter, safeViewportWidth - safeGutter)
  val desiredEnd = desiredStart + desiredWidth.coerceAtLeast(0.dp)
  val safeEnd = desiredEnd.coerceIn(safeStart, safeViewportWidth - safeGutter)
  return LandscapeLyricsBounds(start = safeStart, width = safeEnd - safeStart)
}

internal fun landscapeLyricsLayout(
  mode: LandscapeMode,
  mixedMediaSide: MixedMediaSide,
): LandscapeLyricsLayout = when (mode) {
  LandscapeMode.MIXED -> LandscapeLyricsLayout(
    visible = true,
    focusPresentation = false,
    outerHorizontalInset = 90.dp,
    innerHorizontalPadding = 20.dp,
    verticalInset = 18.dp,
    startFraction = if (mixedMediaSide == MixedMediaSide.LEFT) 0.44f else 0f,
    widthFraction = 0.56f,
  )
  LandscapeMode.LYRICS -> LandscapeLyricsLayout(
    visible = true,
    focusPresentation = true,
    outerHorizontalInset = 0.dp,
    innerHorizontalPadding = 0.dp,
    verticalInset = 20.dp,
    startFraction = 0f,
    widthFraction = 1f,
  )
  LandscapeMode.ARTWORK_ONLY,
  LandscapeMode.ARTWORK_TITLES,
  -> LandscapeLyricsLayout(
    visible = false,
    focusPresentation = false,
    outerHorizontalInset = 90.dp,
    innerHorizontalPadding = 20.dp,
    verticalInset = 18.dp,
    startFraction = if (mixedMediaSide == MixedMediaSide.LEFT) 0.44f else 0f,
    widthFraction = 0.56f,
  )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LandscapeEdge(
  left: Boolean,
  enabled: Boolean,
  width: androidx.compose.ui.unit.Dp,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val overlayAlpha by animateFloatAsState(
    targetValue = if (isPressed && enabled) 0.52f else 0f,
    animationSpec = tween(durationMillis = if (isPressed) 45 else 130),
    label = if (left) "previous-edge-gradient" else "next-edge-gradient",
  )
  Box(
    modifier = Modifier
      .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
      .fillMaxHeight()
      .width(width)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClickLabel = if (left) "Previous fullscreen mode" else "Next fullscreen mode",
        onClick = onClick,
      )
      .semantics {
        contentDescription = if (enabled) {
          if (left) "Previous fullscreen mode" else "Next fullscreen mode"
        } else {
          if (left) "First fullscreen mode" else "Last fullscreen mode"
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            colors = if (left) {
              listOf(Color.Black.copy(alpha = overlayAlpha), Color.Transparent)
            } else {
              listOf(Color.Transparent, Color.Black.copy(alpha = overlayAlpha))
            },
          ),
        ),
    )
  }
}

@Composable
private fun Artwork(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
  Box(
    modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap == null) {
      Text("Icy", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
    } else {
      Image(
        bitmap,
        contentDescription = "Album artwork",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun rememberTrackTitlesHeight(
  snapshot: NowPlayingSnapshot,
  width: androidx.compose.ui.unit.Dp,
  compact: Boolean,
): androidx.compose.ui.unit.Dp {
  val density = LocalDensity.current
  val textMeasurer = rememberIcyTextMeasurer(cacheSize = 8)
  val titleStyle = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
    .copy(fontWeight = FontWeight.Bold)
  val artistStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
  return remember(
    snapshot.displayTitle,
    snapshot.displayArtist,
    width,
    compact,
    density,
    textMeasurer,
    titleStyle,
    artistStyle,
  ) {
    val heightPx = measureTrackTitlesHeightPx(
      textMeasurer = textMeasurer,
      title = snapshot.displayTitle,
      artist = snapshot.displayArtist,
      widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1),
      titleStyle = titleStyle,
      artistStyle = artistStyle,
      spacingPx = with(density) { TRACK_TITLES_SPACING.roundToPx() },
    )
    with(density) { heightPx.toDp() }
  }
}

private val TRACK_TITLES_SPACING = 4.dp

private fun measureTrackTitlesHeightPx(
  textMeasurer: IcyTextMeasurer,
  title: String,
  artist: String,
  widthPx: Int,
  titleStyle: androidx.compose.ui.text.TextStyle,
  artistStyle: androidx.compose.ui.text.TextStyle,
  spacingPx: Int,
): Int {
  val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
  val titleHeight = textMeasurer.measure(
    text = title,
    style = titleStyle,
    overflow = TextOverflow.Clip,
    softWrap = true,
    maxLines = Int.MAX_VALUE,
    constraints = constraints,
  ).size.height
  val artistHeight = textMeasurer.measure(
    text = artist,
    style = artistStyle,
    overflow = TextOverflow.Clip,
    softWrap = true,
    maxLines = Int.MAX_VALUE,
    constraints = constraints,
  ).size.height
  return titleHeight + spacingPx.coerceAtLeast(0) + artistHeight
}

@Composable
private fun TrackTitles(
  snapshot: NowPlayingSnapshot,
  centered: Boolean,
  compact: Boolean = false,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    verticalArrangement = Arrangement.spacedBy(TRACK_TITLES_SPACING),
  ) {
    Text(
      snapshot.displayTitle,
      style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
      maxLines = Int.MAX_VALUE,
      softWrap = true,
      overflow = TextOverflow.Clip,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      snapshot.displayArtist,
      style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
      color = Color.White.copy(alpha = 0.68f),
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
      maxLines = Int.MAX_VALUE,
      softWrap = true,
      overflow = TextOverflow.Clip,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Transport(
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  compact: Boolean,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onSeek: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    PlaybackButtons(snapshot, compact, onPlayPause, onPrevious, onNext)
    PlaybackTimeline(snapshot, positionMs, onSeek)
  }
}

@Composable
private fun PlaybackButtons(
  snapshot: NowPlayingSnapshot,
  compact: Boolean,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous track") }
    IconButton(onClick = onPlayPause, modifier = Modifier.size(if (compact) 48.dp else 58.dp)) {
      Icon(
        if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        if (snapshot.isPlaying) "Pause" else "Play",
        modifier = Modifier.size(if (compact) 34.dp else 42.dp),
      )
    }
    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next track") }
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaybackTimeline(
  snapshot: NowPlayingSnapshot,
  positionMs: Long,
  onSeek: (Long) -> Unit,
  inlineTimeLabels: Boolean = false,
  inlineTrackGap: androidx.compose.ui.unit.Dp = 0.dp,
  inlineLabelWidth: androidx.compose.ui.unit.Dp = 48.dp,
  modifier: Modifier = Modifier,
) {
  val duration = snapshot.durationMs?.takeIf { it > 0L } ?: return
  var dragPreviewMs by remember(snapshot.identity.exactStorageKey, duration) {
    mutableStateOf<Float?>(null)
  }
  val displayedPositionMs = dragPreviewMs?.toLong() ?: positionMs.coerceIn(0L, duration)
  val sliderInteractions = remember { MutableInteractionSource() }
  val sliderColors = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
  )
  val density = LocalDensity.current
  val fontScale = density.fontScale
  val popupOffsetPx = with(density) { scrubPopupVerticalOffset(fontScale).roundToPx() }
  val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
    Slider(
      value = displayedPositionMs.toFloat(),
      valueRange = 0f..duration.toFloat(),
      onValueChange = { dragPreviewMs = it.coerceIn(0f, duration.toFloat()) },
      onValueChangeFinished = {
        dragPreviewMs?.let { onSeek(it.toLong()) }
        dragPreviewMs = null
      },
      colors = sliderColors,
      interactionSource = sliderInteractions,
      thumb = {
        Box(
          Modifier.width(4.dp).height(28.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (dragPreviewMs != null) {
            Box(
              Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(Color.White),
            )
            Popup(
              alignment = Alignment.TopCenter,
              offset = IntOffset(0, popupOffsetPx),
              properties = PopupProperties(focusable = false),
            ) {
              Surface(
                color = Color.Black.copy(alpha = 0.88f),
                contentColor = Color.White,
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 6.dp,
              ) {
                Text(
                  formatTime(displayedPositionMs),
                  modifier = Modifier
                    .sizeIn(minWidth = scrubPopupMinimumWidth(fontScale))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics {
                      contentDescription = "Scrub position ${formatTime(displayedPositionMs)}"
                    },
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
          }
        }
      },
      track = { sliderState ->
        WavySliderTrack(
          sliderState = sliderState,
          isPlaying = snapshot.isPlaying,
          activeColor = Color.White,
          inactiveColor = Color.White.copy(alpha = 0.24f),
        )
      },
      modifier = sliderModifier,
    )
  }

  if (inlineTimeLabels) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
      TimelineLabel(displayedPositionMs, TextAlign.End, inlineLabelWidth)
      Spacer(Modifier.width(inlineTrackGap))
      slider(Modifier.weight(1f))
      Spacer(Modifier.width(inlineTrackGap))
      TimelineLabel(duration, TextAlign.Start, inlineLabelWidth)
    }
  } else {
    Column(modifier.fillMaxWidth()) {
      slider(Modifier.fillMaxWidth())
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          formatTime(displayedPositionMs),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.62f),
        )
        Text(
          formatTime(duration),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.62f),
        )
      }
    }
  }
}

@Composable
private fun TimelineLabel(
  valueMs: Long,
  alignment: TextAlign,
  width: androidx.compose.ui.unit.Dp,
) {
  Text(
    formatTime(valueMs),
    modifier = Modifier.width(width),
    style = MaterialTheme.typography.labelLarge,
    color = Color.White.copy(alpha = 0.70f),
    fontWeight = FontWeight.Medium,
    textAlign = alignment,
    maxLines = 1,
  )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WavySliderTrack(
  sliderState: SliderState,
  isPlaying: Boolean,
  activeColor: Color,
  inactiveColor: Color,
) {
  val reducedMotion = rememberReducedMotionEnabled()
  val phase = remember { Animatable(0f) }
  val fixedFrame = LocalIcyUiPlatform.current.fixedFrameTimeNanos
  LaunchedEffect(isPlaying, reducedMotion, fixedFrame) {
    if (fixedFrame != null) {
      phase.snapTo(if (reducedMotion) 0f else (fixedFrame / 950_000_000.0 % 1.0).toFloat())
    } else if (reducedMotion) {
      phase.snapTo(0f)
    } else if (isPlaying) {
      // Cancelling this effect on pause freezes the current phase. Resuming
      // continues from that exact value instead of snapping the wave to zero.
      while (true) {
        phase.animateTo(
          targetValue = phase.value + 1f,
          animationSpec = tween(durationMillis = 950, easing = LinearEasing),
        )
        phase.snapTo(phase.value % 1f)
      }
    }
  }
  Canvas(Modifier.fillMaxWidth().height(28.dp)) {
    val centerY = size.height / 2f
    val wavelengthPx = 20.dp.toPx()
    val amplitudePx = 3.5.dp.toPx()
    val activeStrokeWidthPx = 4.dp.toPx()
    val inactiveStrokeWidthPx = 3.dp.toPx()
    val fraction = sliderState.coercedValueAsFraction.coerceIn(0f, 1f)
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val geometry = wavyTrackGeometry(
      widthPx = size.width,
      fraction = fraction,
      isRtl = isRtl,
      capClearancePx = (activeStrokeWidthPx + inactiveStrokeWidthPx) / 2f,
    )

    if (geometry.hasInactiveTrack) {
      drawLine(
        color = inactiveColor,
        start = Offset(geometry.inactiveStartPx, centerY),
        end = Offset(geometry.inactiveEndPx, centerY),
        strokeWidth = inactiveStrokeWidthPx,
        cap = StrokeCap.Round,
      )
    }
    if (geometry.activeLengthPx > 0f) {
      val path = Path().apply {
        fun xAt(distance: Float): Float = geometry.activeStartPx +
          (if (isRtl) -distance else distance)
        moveTo(
          xAt(0f),
          centerY + flowingWavyTrackOffset(
            distanceAlongTrackPx = 0f,
            activeWidthPx = geometry.activeLengthPx,
            wavelengthPx = wavelengthPx,
            amplitudePx = amplitudePx,
            phasePx = phase.value * wavelengthPx,
          ),
        )
        var distance = 1f
        while (distance < geometry.activeLengthPx) {
          lineTo(
            xAt(distance),
            centerY + flowingWavyTrackOffset(
              distanceAlongTrackPx = distance,
              activeWidthPx = geometry.activeLengthPx,
              wavelengthPx = wavelengthPx,
              amplitudePx = amplitudePx,
              phasePx = phase.value * wavelengthPx,
            ),
          )
          distance += 1f
        }
        // Settle the advancing edge onto the inactive rail's centerline. The
        // inactive rail starts beyond both rounded caps, so it never appears
        // behind the played wave while the two segments still read as one
        // continuous timeline.
        lineTo(
          xAt(geometry.activeLengthPx),
          centerY + flowingWavyTrackOffset(
            distanceAlongTrackPx = geometry.activeLengthPx,
            activeWidthPx = geometry.activeLengthPx,
            wavelengthPx = wavelengthPx,
            amplitudePx = amplitudePx,
            phasePx = phase.value * wavelengthPx,
          ),
        )
      }
      drawPath(
        path = path,
        color = activeColor,
        style = Stroke(width = activeStrokeWidthPx, cap = StrokeCap.Round),
      )
    }
  }
}

internal data class WavyTrackGeometry(
  val activeStartPx: Float,
  val activeEndPx: Float,
  val activeLengthPx: Float,
  val inactiveStartPx: Float,
  val inactiveEndPx: Float,
) {
  val hasInactiveTrack: Boolean
    get() = inactiveEndPx > inactiveStartPx
}

/**
 * Separates the physical active and inactive strokes, including their round
 * caps. In RTL only playback direction changes; the geometry is an exact
 * horizontal mirror of LTR.
 */
internal fun wavyTrackGeometry(
  widthPx: Float,
  fraction: Float,
  isRtl: Boolean,
  capClearancePx: Float,
): WavyTrackGeometry {
  val width = widthPx.coerceAtLeast(0f)
  val activeLength = width * fraction.coerceIn(0f, 1f)
  val activeStart = if (isRtl) width else 0f
  val activeEnd = if (isRtl) width - activeLength else activeLength
  val clearance = if (activeLength > 0f) capClearancePx.coerceAtLeast(0f) else 0f
  val inactiveStart = if (isRtl) 0f else (activeEnd + clearance).coerceAtMost(width)
  val inactiveEnd = if (isRtl) (activeEnd - clearance).coerceAtLeast(0f) else width
  return WavyTrackGeometry(
    activeStartPx = activeStart,
    activeEndPx = activeEnd,
    activeLengthPx = activeLength,
    inactiveStartPx = inactiveStart,
    inactiveEndPx = inactiveEnd,
  )
}

internal fun wavyTrackOffset(
  xPx: Float,
  wavelengthPx: Float,
  amplitudePx: Float,
  phasePx: Float,
): Float {
  if (wavelengthPx <= 0f || amplitudePx == 0f) return 0f
  // Increasing phase moves the waveform toward greater playback distance: to
  // the right in LTR and to the left in RTL, because RTL maps distance back to x.
  return sin(((xPx - phasePx) / wavelengthPx) * (2.0 * PI)).toFloat() * amplitudePx
}

internal fun flowingWavyTrackOffset(
  distanceAlongTrackPx: Float,
  activeWidthPx: Float,
  wavelengthPx: Float,
  amplitudePx: Float,
  phasePx: Float,
): Float {
  if (activeWidthPx <= 0f || wavelengthPx <= 0f || amplitudePx == 0f) return 0f
  val distance = distanceAlongTrackPx.coerceIn(0f, activeWidthPx)
  val taperLength = minOf(wavelengthPx * 0.75f, activeWidthPx).coerceAtLeast(0.001f)
  fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }
  val playheadEnvelope = smoothStep((activeWidthPx - distance) / taperLength)
  return wavyTrackOffset(distance, wavelengthPx, amplitudePx, phasePx) * playheadEnvelope
}

internal fun scrubPopupVerticalOffset(fontScale: Float): androidx.compose.ui.unit.Dp =
  -(48.dp + 18.dp * (fontScale.coerceAtLeast(1f) - 1f))

internal fun scrubPopupMinimumWidth(fontScale: Float): androidx.compose.ui.unit.Dp =
  56.dp * fontScale.coerceAtLeast(1f)

@Composable
private fun LyricsStatusPane(
  status: LyricsUiStatus,
  positionMs: Long,
  rawPositionMs: Long,
  durationMs: Long?,
  reveal: Boolean,
  focusPresentation: Boolean,
  modifier: Modifier = Modifier,
  onSeek: (Long) -> Unit,
) {
  when (status) {
    is LyricsUiStatus.Ready -> LyricsCanvas(
      status.document,
      positionMs,
      rawPositionMs,
      durationMs,
      reveal,
      focusPresentation,
      modifier,
      onSeek,
    )
    is LyricsUiStatus.Loading -> StatusMessage("Searching for lyrics…", modifier)
    is LyricsUiStatus.Empty -> StatusMessage(status.message, modifier)
    is LyricsUiStatus.Failed -> StatusMessage(status.message, modifier)
    LyricsUiStatus.Idle -> StatusMessage("Waiting for a track", modifier)
  }
}

@Composable
private fun StatusMessage(message: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(message, color = Color.White.copy(alpha = 0.68f), textAlign = TextAlign.Center)
  }
}

@Composable
private fun SourceBadge(state: IcyLyricsUiState) {
  val ready = state.lyrics as? LyricsUiStatus.Ready
  val source = ready?.document?.metadata?.source
  AssistChip(
    onClick = {},
    enabled = false,
    label = { Text(source?.displayName() ?: "Lyrics") },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
  state: IcyLyricsUiState,
  onBack: () -> Unit,
  onNavigate: (AppDestination) -> Unit,
  onPickTtml: () -> Unit,
  onRequestBluetoothPermission: () -> Unit,
  onGlobalTimingOffset: (Int) -> Unit,
  onBluetoothTimingOffset: (Int?) -> Unit,
  onRememberBluetoothOffsets: (Boolean) -> Unit,
  onMixedMediaSide: (MixedMediaSide) -> Unit,
  onBackgroundStyle: (BackgroundStyle) -> Unit,
  onBackgroundEnabled: (Boolean) -> Unit,
  onKeepScreenAwake: (Boolean) -> Unit,
  onUseLocalTtml: (Boolean) -> Unit,
  onRevealEnabled: (Boolean) -> Unit,
  onSourceStrategy: (SourceStrategy) -> Unit,
  onDebugEnabled: (Boolean) -> Unit,
  onSpicyEnabled: (Boolean) -> Unit,
  onSpicyTokenSharingConsent: (Boolean) -> Unit,
  onConnectSpotify: () -> Unit,
  onCancelSpotifyAuthorization: () -> Unit,
  onDisconnectSpotify: () -> Unit,
  onLrclibEnabled: (Boolean) -> Unit,
) {
  val settings = state.settings
  val uriHandler = LocalUriHandler.current
  var showSpicyConsent by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp)) {
    ScreenHeader("Settings", onBack)
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        SettingsCard("Local lyrics") {
          ToggleRow("Use saved TTML first", "Saved lyrics always win when enabled.", settings.useLocalTtml, onUseLocalTtml)
          Text(
            "Imported TTML is always saved to your local library.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.58f),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickTtml) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Import TTML") }
            TextButton(onClick = { onNavigate(AppDestination.LIBRARY) }) { Text("Open library") }
          }
        }
      }
      item {
        SettingsCard("Timing") {
          TimingSlider(settings.globalTimingOffsetMs, onGlobalTimingOffset)
          TextButton(onClick = { onGlobalTimingOffset(0) }) { Text("Reset global timing") }
          ToggleRow(
            "Remember Bluetooth devices",
            "A device-specific value replaces the global value while connected.",
            settings.rememberBluetoothOffsets,
            onRememberBluetoothOffsets,
          )
          if (!state.bluetoothPermissionGranted) {
            Button(onClick = onRequestBluetoothPermission) {
              Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Allow Bluetooth devices")
            }
          } else if (settings.activeBluetoothDeviceName != null && settings.rememberBluetoothOffsets) {
            val deviceOffset = settings.activeBluetoothTimingOffsetMs ?: settings.globalTimingOffsetMs
            Text("Connected: ${settings.activeBluetoothDeviceName}", fontWeight = FontWeight.SemiBold)
            TimingSlider(
              deviceOffset,
              { onBluetoothTimingOffset(it) },
            )
            TextButton(onClick = { onBluetoothTimingOffset(null) }) { Text("Use global timing") }
          } else if (settings.activeBluetoothDeviceName != null) {
            Text(
              "Connected: ${settings.activeBluetoothDeviceName}; using global timing (${offsetLabel(settings.globalTimingOffsetMs)}).",
              color = Color.White.copy(alpha = 0.58f),
            )
          } else {
            Text("No Bluetooth media device is active.", color = Color.White.copy(alpha = 0.58f))
          }
        }
      }
      item {
        SettingsCard("Fullscreen") {
          ToggleRow(
            "Artwork background",
            "Turn off for a plain black background.",
            settings.backgroundEnabled,
            onBackgroundEnabled,
          )
          if (settings.backgroundEnabled) {
            Text("Background style", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(BackgroundStyle.entries, settings.backgroundStyle, { it.label }, onBackgroundStyle)
          }
          ToggleRow("Reveal", "Hide lyrics until they are sung.", settings.revealEnabled, onRevealEnabled)
          Text("Mixed layout", style = MaterialTheme.typography.titleSmall)
          ChoiceRow(MixedMediaSide.entries, settings.mixedMediaSide, { it.label }, onMixedMediaSide)
          ToggleRow("Keep screen awake", "Prevent the display from sleeping while Icy Lyrics is open.", settings.keepScreenAwake, onKeepScreenAwake)
        }
      }
      item {
        SettingsCard("Lyric sources") {
          ChoiceRow(SourceStrategy.entries, settings.sourceStrategy, { it.label }, onSourceStrategy)
          ToggleRow(
            "Spicy Lyrics",
            "Experimental provider using a connected Spotify session.",
            settings.spicyEnabled,
          ) { enabled ->
            if (enabled && !settings.spicyTokenSharingConsent) showSpicyConsent = true
            else onSpicyEnabled(enabled)
          }
          ToggleRow(
            "Share Spotify token",
            "Allows Spicy Lyrics to receive the short-lived Spotify access token for lyric lookup.",
            settings.spicyTokenSharingConsent,
          ) { consent ->
            if (consent) showSpicyConsent = true else onSpicyTokenSharingConsent(false)
          }
          when {
            !state.spotifyAuthAvailable -> Text(
              "Spotify developer client ID is not configured. Local TTML and LRCLIB still work.",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.58f),
            )
            state.spotifyConnected -> Button(onClick = onDisconnectSpotify) { Text("Disconnect Spotify") }
            state.spotifyAuthorizationInProgress -> Button(onClick = onCancelSpotifyAuthorization) {
              Text("Cancel Spotify connection")
            }
            else -> Button(onClick = onConnectSpotify) { Text("Connect Spotify") }
          }
          ToggleRow("LRCLIB", "Search LRCLIB after the Spicy database misses.", settings.lrclibEnabled, onLrclibEnabled)
        }
      }
      item {
        SettingsCard("Troubleshooting") {
          ToggleRow("Debug logging", "Keep privacy-safe provider and playback diagnostics.", settings.debugEnabled, onDebugEnabled)
          Button(onClick = { onNavigate(AppDestination.DEBUG) }) {
            Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); Text("View diagnostics")
          }
        }
      }
      item {
        SettingsCard("Privacy") {
          Text(
            "See how Icy Lyrics handles playback access, lyric requests, local TTML, Spotify credentials, Bluetooth timing, and diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.58f),
          )
          TextButton(onClick = { uriHandler.openUri(LegalInfo.PRIVACY_POLICY_URL) }) {
            Text("Open privacy policy")
          }
        }
      }
      item {
        SettingsCard("About & legal") {
          Text(
            "View credits, copyright notices, licenses, and project links.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.58f),
          )
          Button(onClick = { onNavigate(AppDestination.ABOUT_LEGAL) }) {
            Icon(Icons.Default.Info, null)
            Spacer(Modifier.width(6.dp))
            Text("Credits and licenses")
          }
        }
      }
      item { Spacer(Modifier.height(20.dp)) }
    }
  }
  if (showSpicyConsent) {
    AlertDialog(
      onDismissRequest = { showSpicyConsent = false },
      title = { Text("Allow token sharing?") },
      text = {
        Text(
          "Spicy Lyrics is an experimental third-party service. Icy Lyrics will send it your short-lived Spotify access token only for lyric requests. The token is never written to diagnostics.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showSpicyConsent = false
            onSpicyTokenSharingConsent(true)
            onSpicyEnabled(true)
          },
        ) { Text("Allow and enable") }
      },
      dismissButton = {
        TextButton(onClick = { showSpicyConsent = false }) { Text("Cancel") }
      },
    )
  }
}

private data class OfflineLegalDocument(
  val title: String,
  val resourceId: IcyLegalDocument,
)

@Composable
private fun AboutLegalScreen(onBack: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  var selectedDocument by remember { mutableStateOf<OfflineLegalDocument?>(null) }

  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp)) {
    ScreenHeader("About & legal", onBack)
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        SettingsCard("Icy Lyrics") {
          Text("Version ${LocalIcyUiPlatform.current.versionName}", color = Color.White.copy(alpha = 0.62f))
          Text("Developed by ${LegalInfo.DEVELOPER}.", fontWeight = FontWeight.SemiBold)
          Text(
            LocalIcyUiPlatform.current.aboutDescription,
            color = Color.White.copy(alpha = 0.72f),
          )
        }
      }
      item {
        SettingsCard("Credits & copyright") {
          Text("Spicy Lyrics by ${LegalInfo.UPSTREAM_CREATOR}.", fontWeight = FontWeight.SemiBold)
          Text(LegalInfo.MODIFICATION_NOTICE, color = Color.White.copy(alpha = 0.72f))
          Text(LegalInfo.NON_AFFILIATION_NOTICE, color = Color.White.copy(alpha = 0.72f))
          TextButton(onClick = { uriHandler.openUri(LegalInfo.UPSTREAM_REPOSITORY_URL) }) {
            Text("Open the Spicy Lyrics repository")
          }
        }
      }
      item {
        SettingsCard("GNU AGPL") {
          Text(LegalInfo.LICENSE_NOTICE, color = Color.White.copy(alpha = 0.72f))
          Text(LegalInfo.WARRANTY_NOTICE, color = Color.White.copy(alpha = 0.62f))
          Button(
            onClick = {
              selectedDocument = OfflineLegalDocument(
                title = "GNU AGPL v3 or later",
                resourceId = IcyLegalDocument.AGPL,
              )
            },
          ) {
            Text("Read the full license offline")
          }
        }
      }
      item {
        SettingsCard("Third-party software") {
          Text(LegalInfo.KAWARP_NOTICE, color = Color.White.copy(alpha = 0.72f))
          Button(
            onClick = {
              selectedDocument = OfflineLegalDocument(
                title = "Third-party notices",
                resourceId = IcyLegalDocument.THIRD_PARTY,
              )
            },
          ) {
            Text("Read third-party notices offline")
          }
        }
      }
      item {
        SettingsCard("Online policies") {
          TextButton(onClick = { uriHandler.openUri(LegalInfo.PRIVACY_POLICY_URL) }) {
            Text("Privacy policy")
          }
          TextButton(onClick = { uriHandler.openUri(LegalInfo.LEGAL_URL) }) {
            Text("Source & legal notices")
          }
        }
      }
      item { Spacer(Modifier.height(20.dp)) }
    }
  }

  selectedDocument?.let { document ->
    OfflineLegalDocumentDialog(
      document = document,
      onDismiss = { selectedDocument = null },
    )
  }
}

@Composable
private fun OfflineLegalDocumentDialog(
  document: OfflineLegalDocument,
  onDismiss: () -> Unit,
) {
  val platform = LocalIcyUiPlatform.current
  val text = remember(platform, document.resourceId) { platform.legalDocument(document.resourceId) }
  val scrollState = rememberScrollState()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(document.title) },
    text = {
      SelectionContainer {
        Text(
          text = text,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 520.dp)
            .verticalScroll(scrollState),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

@Composable
private fun LibraryScreen(
  items: List<SavedLyricsUi>,
  onBack: () -> Unit,
  onImport: () -> Unit,
  onDelete: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp)) {
    ScreenHeader("Local lyrics", onBack) {
      IconButton(onClick = onImport) { Icon(Icons.Default.FolderOpen, "Import TTML") }
    }
    if (items.isEmpty()) {
      CenteredPage {
        Icon(Icons.Default.LibraryMusic, null, Modifier.size(52.dp), tint = Color.White.copy(alpha = 0.55f))
        Spacer(Modifier.height(12.dp))
        Text("No saved local lyrics", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImport) { Text("Import TTML") }
      }
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.trackUri }) { item ->
          Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.07f)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text(item.artist, color = Color.White.copy(alpha = 0.62f))
                Text(
                  "Updated ${LocalIcyUiPlatform.current.formatDateTime(item.updatedAtEpochMs)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White.copy(alpha = 0.45f),
                )
              }
              IconButton(onClick = { onDelete(item.trackUri) }) { Icon(Icons.Default.Close, "Delete ${item.title}") }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugScreen(
  diagnostics: LyricsDiagnosticsUi,
  onBack: () -> Unit,
  onReload: () -> Unit,
  onShare: () -> Unit,
  onClear: () -> Unit,
) {
  val platform = LocalIcyUiPlatform.current
  val text = diagnostics.asText()
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp)) {
    ScreenHeader("Diagnostics", onBack) {
      IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, "Reload lyrics") }
    }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color.White.copy(alpha = 0.07f),
      modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
      Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Current lyric request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = {
            platform.copyDiagnostics(text)
          }) { Text("Copy") }
          TextButton(onClick = onShare) { Text("Share") }
          TextButton(onClick = onClear) { Text("Clear") }
        }
      }
    }
  }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
  Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    Text(
      title,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f).semantics { heading() },
    )
    actions()
  }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.07f), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
      HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
      content()
    }
  }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.58f))
    }
    Spacer(Modifier.width(12.dp))
    Switch(checked, onCheckedChange = onChange)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    values.forEach { value ->
      FilterChip(selected = value == selected, onClick = { onSelected(value) }, label = { Text(label(value)) })
    }
  }
}

@Composable
private fun TimingSlider(value: Int, onChange: (Int) -> Unit) {
  var pendingValue by remember(value) { mutableFloatStateOf(value.coerceIn(-5_000, 5_000).toFloat()) }
  Column {
    Text(offsetLabel(pendingValue.roundToInt()), color = MaterialTheme.colorScheme.primary)
    Slider(
      value = pendingValue,
      valueRange = -5_000f..5_000f,
      steps = 999,
      onValueChange = { pendingValue = (it / 10f).roundToInt() * 10f },
      onValueChangeFinished = { onChange(pendingValue.roundToInt()) },
    )
  }
}

@Composable
private fun CenteredPage(content: @Composable ColumnScope.() -> Unit) {
  Column(
    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
  )
}

@Composable
private fun icyColors() = androidx.compose.material3.darkColorScheme(
  primary = Color(0xFF8FD7FF),
  secondary = Color(0xFFB7E9FF),
  background = Color.Black,
  surface = Color(0xFF101519),
  onPrimary = Color(0xFF06151D),
  onBackground = Color.White,
  onSurface = Color.White,
)

internal fun formatTime(valueMs: Long): String {
  val total = (valueMs.coerceAtLeast(0L) / 1_000L)
  return (total / 60L).toString() + ":" + (total % 60L).toString().padStart(2, '0')
}

private fun offsetLabel(value: Int): String = when {
  value > 0 -> "Delay lyrics by $value ms"
  value < 0 -> "Rush lyrics by ${-value} ms"
  else -> "No timing adjustment"
}

private fun LyricsSource.displayName(): String = when (this) {
  LyricsSource.LOCAL_TTML -> "Local TTML"
  LyricsSource.SPICY -> "Spicy Lyrics"
  LyricsSource.SPOTIFY -> "Spotify"
  LyricsSource.APPLE_MUSIC -> "Apple Music"
  LyricsSource.LRCLIB -> "LRCLIB"
  LyricsSource.UNKNOWN -> "Lyrics"
}
