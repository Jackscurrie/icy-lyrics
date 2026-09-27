package com.icy.lyrics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationLaunch
import com.icy.lyrics.ui.AppDestination
import com.icy.lyrics.ui.IcyLyricsApp
import com.icy.lyrics.ui.LandscapeMode
import com.icy.lyrics.ui.LandscapeRemoteCommand
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.LocalOptionalMixedModePresentation
import com.icy.lyrics.ui.LyricsUiStatus
import com.icy.lyrics.ui.SourceBadge
import com.icy.lyrics.ui.TvLandscapeChrome
import com.icy.lyrics.ui.TvPerformanceBackground
import com.icy.lyrics.ui.TvPerformanceMode
import com.icy.lyrics.ui.icyColors
import com.icy.lyrics.ui.icyTypography
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class TvMainActivity : ComponentActivity() {
  private val viewModel: IcyLyricsViewModel by viewModels()
  private val landscapeRemoteCommands = MutableSharedFlow<LandscapeRemoteCommand>(
    extraBufferCapacity = 16,
  )
  private val consumedRemoteKeyDowns = mutableSetOf<Int>()
  private val deferredConfirmKeys = mutableSetOf<Int>()
  private val longConfirmKeys = mutableSetOf<Int>()
  private val confirmLongPressJobs = mutableMapOf<Int, Job>()
  private val horizontalPressDirections = mutableMapOf<Int, Int>()
  private val doubleHorizontalActions = mutableMapOf<Int, TvRemoteAction>()
  private val remoteDoubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
  private var pendingHorizontalTap: TvPendingHorizontalTap? = null
  private var pendingHorizontalTapJob: Job? = null
  private var scrubberPreviewJob: Job? = null
  private var spotifyAuthorizationJob: Job? = null
  private var spotifyAuthorizationLaunch: SpotifyAuthorizationLaunch? = null
  private var remoteNavigationState by mutableStateOf(TvRemoteNavigationState())
  private lateinit var tvPerformancePreferences: TvPerformancePreferences
  private var tvPerformanceMode by mutableStateOf(TvPerformanceMode.OFF)
  private var tvPerformanceBackground by mutableStateOf(
    TvPerformanceBackground.STATIC_BLURRED,
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tvPerformancePreferences = TvPerformancePreferences(this)
    tvPerformanceMode = tvPerformancePreferences.mode()
    tvPerformanceBackground = tvPerformancePreferences.background()
    enableEdgeToEdge()
    applyImmersiveWindow()
    viewModel.setLandscape(true)
    setContent {
      val uiPlatform = rememberAndroidIcyUiPlatform()
      CompositionLocalProvider(
        LocalIcyUiPlatform provides uiPlatform,
        // The TV distribution never loads the personal mixed-mode renderer or
        // the public phone-only Lyric Creator settings entry.
        LocalOptionalMixedModePresentation provides null,
      ) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.setLandscape(true) }
        LaunchedEffect(state.settings.keepScreenAwake) {
          if (state.settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          }
        }
        LaunchedEffect(
          remoteNavigationState.overlayVisible,
          remoteNavigationState.sourceBadgeVisible,
          remoteNavigationState.interactionRevision,
        ) {
          if (
            remoteNavigationState.overlayVisible &&
            remoteNavigationState.sourceBadgeVisible
          ) {
            delay(TV_REMOTE_SOURCE_BADGE_TIMEOUT_MS)
            remoteNavigationState = remoteNavigationState.hideSourceBadge()
          }
        }
        LaunchedEffect(
          state.destination,
          state.notificationAccess,
          state.nowPlaying == null,
        ) {
          if (
            state.destination != AppDestination.PLAYER ||
            !state.notificationAccess ||
            state.nowPlaying == null
          ) {
            cancelRemoteGestures(clearConsumedKeys = false)
            hideRemoteControls()
          }
        }

        val ttmlPicker = rememberLauncherForActivityResult(
          ActivityResultContracts.OpenDocument(),
        ) { uri ->
          if (uri != null) viewModel.importTtml(uri) else viewModel.cancelTtmlImport()
        }
        val bluetoothPermission = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission(),
        ) { viewModel.refreshPermissions(it) }

        Box(
          modifier = Modifier.fillMaxSize(),
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
          ) {
            IcyLyricsApp(
              state = state,
              isLandscape = true,
              onOpenNotificationAccess = {
                startActivity((application as IcyLyricsApplication).container.mediaTracker.notificationAccessIntent())
              },
              onRequestBluetoothPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                  viewModel.refreshPermissions(hasBluetoothPermission())
                }
              },
              onPickTtml = {
                if (viewModel.prepareTtmlImport()) {
                  ttmlPicker.launch(
                    arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                  )
                }
              },
              onNavigate = viewModel::navigate,
              onStepLandscape = viewModel::stepLandscape,
              onShowArtworkControls = viewModel::showArtworkControls,
              onPlayPause = viewModel::playPause,
              onPrevious = viewModel::previous,
              onNext = viewModel::next,
              onSeek = viewModel::seekTo,
              onReload = viewModel::reloadLyrics,
              onGlobalTimingOffset = viewModel::setGlobalTimingOffset,
              onBluetoothTimingOffset = viewModel::setBluetoothTimingOffset,
              onRememberBluetoothOffsets = viewModel::setRememberBluetoothOffsets,
              onMixedMediaSide = viewModel::setMixedMediaSide,
              onBackgroundStyle = viewModel::setBackgroundStyle,
              onBackgroundEnabled = viewModel::setBackgroundEnabled,
              onKeepScreenAwake = viewModel::setKeepScreenAwake,
              onUseLocalTtml = viewModel::setUseLocalTtml,
              onRevealEnabled = viewModel::setRevealEnabled,
              onSourceStrategy = viewModel::setSourceStrategy,
              onDebugEnabled = viewModel::setDebugEnabled,
              onIcyDatabaseEnabled = viewModel::setIcyDatabaseEnabled,
              onAppleMusicEnabled = viewModel::setAppleMusicEnabled,
              onAppleMusicTokenSharingConsent = viewModel::setAppleMusicTokenSharingConsent,
              onConnectSpotify = ::connectSpotify,
              onCancelSpotifyAuthorization = ::cancelSpotifyAuthorization,
              onDisconnectSpotify = viewModel::disconnectSpotify,
              onLrclibEnabled = viewModel::setLrclibEnabled,
              onShareDiagnostics = { shareDiagnostics(state.diagnostics.asText()) },
              onClearDiagnostics = viewModel::clearDiagnostics,
              onDeleteSavedLyrics = viewModel::deleteSavedLyrics,
              onDismissMessage = viewModel::clearTransientMessage,
              launchExperienceEnabled = true,
              landscapeRemoteCommands = landscapeRemoteCommands,
              onLandscapeRemoteActivateWithoutSelection = {
                viewModel.playPause()
              },
              requestInitialSettingsFocus = true,
              tvSettingsRemoteNavigationEnabled = true,
              tvLandscapeChrome = TvLandscapeChrome(
                overlayVisible = remoteNavigationState.overlayVisible,
                scrubberFocused = remoteNavigationState.focusTarget ==
                  TvRemoteFocusTarget.SCRUBBER,
                focusedControlIndex = remoteNavigationState.focusedControl.ordinal,
                previewPositionMs = remoteNavigationState.scrubberPositionMs,
                performanceMode = tvPerformanceMode,
                performanceBackground = tvPerformanceBackground,
              ),
              onTvPerformanceMode = ::updateTvPerformanceMode,
              onTvPerformanceBackground = ::updateTvPerformanceBackground,
            )
          }
          MaterialTheme(colorScheme = icyColors(), typography = icyTypography()) {
            AnimatedVisibility(
              visible = remoteNavigationState.overlayVisible &&
                remoteNavigationState.sourceBadgeVisible,
              enter = fadeIn(),
              exit = fadeOut(),
              modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 20.dp),
            ) {
              SourceBadge(state)
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.setLandscape(true)
    viewModel.refreshPermissions(hasBluetoothPermission())
  }

  private fun updateTvPerformanceMode(value: TvPerformanceMode) {
    tvPerformanceMode = value
    tvPerformancePreferences.setMode(value)
  }

  private fun updateTvPerformanceBackground(value: TvPerformanceBackground) {
    tvPerformanceBackground = value
    tvPerformancePreferences.setBackground(value)
  }

  private fun connectSpotify() {
    if (spotifyAuthorizationJob?.isActive == true) return
    spotifyAuthorizationJob = lifecycleScope.launch {
      val authorization = viewModel.beginSpotifyAuthorization() ?: return@launch
      spotifyAuthorizationLaunch = authorization
      try {
        authorization.customTabsIntent().launchUrl(this@TvMainActivity, authorization.authorizationUri)
        viewModel.completeSpotifyAuthorization(authorization)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        viewModel.failSpotifyAuthorization(authorization, error)
      } finally {
        if (spotifyAuthorizationLaunch === authorization) spotifyAuthorizationLaunch = null
      }
    }
  }

  private fun cancelSpotifyAuthorization() {
    val authorization = spotifyAuthorizationLaunch
    authorization?.close()
    spotifyAuthorizationJob?.cancel()
    spotifyAuthorizationJob = null
    spotifyAuthorizationLaunch = null
    lifecycleScope.launch { viewModel.cancelSpotifyAuthorization(authorization) }
  }

  override fun onStop() {
    cancelRemoteGestures()
    hideRemoteControls()
    super.onStop()
  }

  // Intercept player commands before a focused Compose Slider/IconButton can
  // consume D-pad or OK. Outside the player, Compose keeps normal TV focus and
  // BackHandler behavior (including dismissing dialogs before leaving Settings).
  // ComponentActivity narrows this inherited platform hook to its library group,
  // but an Activity-level pre-dispatch hook is intentional for this TV-only shell.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    if (handleRemoteKeyEvent(event)) true else super.dispatchKeyEvent(event)

  private fun handleRemoteKeyEvent(event: KeyEvent): Boolean {
    // Always retire keys captured by the player, even if their DOWN action
    // navigated to Settings and changed shouldCapturePlayerRemote() meanwhile.
    if (event.action == KeyEvent.ACTION_UP && event.keyCode in consumedRemoteKeyDowns) {
      consumedRemoteKeyDowns.remove(event.keyCode)
      confirmLongPressJobs.remove(event.keyCode)?.cancel()
      val canceled = event.flags and KeyEvent.FLAG_CANCELED != 0
      if (isTvConfirmKey(event.keyCode)) {
        val wasDeferred = deferredConfirmKeys.remove(event.keyCode)
        val wasLongPress = longConfirmKeys.remove(event.keyCode)
        if (!canceled && wasDeferred && !wasLongPress && shouldCapturePlayerRemote()) {
          viewModel.playPause()
        }
      }
      horizontalPressDirections.remove(event.keyCode)?.let { direction ->
        if (!canceled && shouldCapturePlayerRemote()) {
          scheduleCleanHorizontalTap(direction, event.eventTime)
        }
      }
      doubleHorizontalActions.remove(event.keyCode)?.let { action ->
        if (!canceled && shouldCapturePlayerRemote()) dispatchRemoteAction(action)
      }
      return true
    }

    if (
      !shouldCapturePlayerRemote() ||
      !isTvPlayerRemoteKey(event.keyCode, remoteNavigationState.overlayVisible)
    ) {
      return false
    }

    if (event.action != KeyEvent.ACTION_DOWN) return false

    consumedRemoteKeyDowns += event.keyCode
    val navigationAtKeyDown = remoteNavigationState

    // A clean-view OK click is intentionally resolved on key-up. That leaves
    // the key's repeat/long-press event free to open the overlay without first
    // toggling playback.
    if (isTvConfirmKey(event.keyCode) && !navigationAtKeyDown.overlayVisible) {
      if (event.repeatCount == 0) {
        flushPendingHorizontalTap()
        deferredConfirmKeys += event.keyCode
        confirmLongPressJobs.remove(event.keyCode)?.cancel()
        confirmLongPressJobs[event.keyCode] = lifecycleScope.launch {
          delay(ViewConfiguration.getLongPressTimeout().toLong())
          triggerLongConfirm(event.keyCode)
        }
      }
      if (
        (event.isLongPress || event.repeatCount > 0) &&
        event.keyCode !in longConfirmKeys
      ) {
        triggerLongConfirm(event.keyCode)
      }
      return true
    }

    // Horizontal taps in the clean player need a short deferral so a second
    // same-direction tap can become previous/next without also changing view.
    if (
      !navigationAtKeyDown.overlayVisible &&
      (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
    ) {
      if (event.repeatCount == 0) {
        val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        val normalizedTime = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val decision = resolveTvHorizontalSecondDown(
          pending = pendingHorizontalTap,
          direction = direction,
          eventTimeMs = normalizedTime,
          windowMs = remoteDoubleTapTimeoutMs,
        )
        if (decision.consumeAsDoubleTap) {
          pendingHorizontalTapJob?.cancel()
          pendingHorizontalTapJob = null
          pendingHorizontalTap = null
          decision.immediateAction?.let { doubleHorizontalActions[event.keyCode] = it }
        } else {
          if (decision.immediateAction != null) flushPendingHorizontalTap()
          horizontalPressDirections[event.keyCode] = direction
        }
      }
      return true
    }

    if (event.repeatCount == 0) flushPendingHorizontalTap()
    if (
      event.repeatCount > 0 &&
      !isRepeatableTvRemoteKey(event.keyCode, navigationAtKeyDown)
    ) {
      return true
    }

    if (navigationAtKeyDown.overlayVisible) {
      remoteNavigationState = remoteNavigationState.registerInteraction()
    }
    tvRemoteActionForKey(
      keyCode = event.keyCode,
      repeatCount = event.repeatCount,
      navigationState = navigationAtKeyDown,
    )?.let(::dispatchRemoteAction)
    return true
  }

  private fun shouldCapturePlayerRemote(): Boolean = viewModel.state.value.let { state ->
    state.destination == AppDestination.PLAYER &&
      state.notificationAccess &&
      state.nowPlaying != null
  }

  private fun dispatchRemoteAction(action: TvRemoteAction) {
    when (action) {
      is TvRemoteAction.Lyrics -> {
        val state = viewModel.state.value
        val lyricsCanHandleCommand = isTimedLyricsRemoteReady(state.landscapeMode, state.lyrics)
        if (lyricsCanHandleCommand) {
          landscapeRemoteCommands.tryEmit(action.command)
        }
      }
      is TvRemoteAction.StepLandscape -> {
        viewModel.stepLandscape(action.direction)
      }
      is TvRemoteAction.MoveControlFocus -> {
        remoteNavigationState = remoteNavigationState.moveControlFocus(action.direction)
      }
      TvRemoteAction.FocusScrubber -> {
        scrubberPreviewJob?.cancel()
        scrubberPreviewJob = null
        remoteNavigationState = remoteNavigationState.focusScrubber()
      }
      TvRemoteAction.FocusControls -> {
        scrubberPreviewJob?.cancel()
        scrubberPreviewJob = null
        remoteNavigationState = remoteNavigationState.focusControls()
      }
      is TvRemoteAction.SeekBy -> {
        seekRemoteBy(action.deltaMs)
      }
      TvRemoteAction.ActivateFocusedControl -> {
        activateRemoteControl(remoteNavigationState.focusedControl)
      }
      is TvRemoteAction.ShowControls -> showRemoteControls(action.initialFocus)
      TvRemoteAction.DismissControls -> hideRemoteControls()
      TvRemoteAction.PlayPause -> {
        viewModel.playPause()
      }
      TvRemoteAction.PreviousTrack -> {
        viewModel.previous()
      }
      TvRemoteAction.NextTrack -> {
        viewModel.next()
      }
      is TvRemoteAction.HorizontalTap -> scheduleCleanHorizontalTap(action.direction)
    }
  }

  private fun showRemoteControls(initialFocus: TvRemoteControl) {
    landscapeRemoteCommands.tryEmit(LandscapeRemoteCommand.CLEAR_SELECTION)
    remoteNavigationState = remoteNavigationState.show(initialFocus)
  }

  private fun hideRemoteControls() {
    scrubberPreviewJob?.cancel()
    scrubberPreviewJob = null
    remoteNavigationState = remoteNavigationState.hide()
  }

  private fun registerRemoteControlsInteraction() {
    if (remoteNavigationState.overlayVisible) {
      remoteNavigationState = remoteNavigationState.registerInteraction()
    }
  }

  private fun seekRemoteBy(deltaMs: Long) {
    val snapshot = viewModel.state.value.nowPlaying ?: return
    val current = remoteNavigationState.scrubberPositionMs
      ?: snapshot.currentPositionMs(SystemClock.elapsedRealtime())
    val target = (current + deltaMs).let { candidate ->
      snapshot.durationMs?.takeIf { it > 0L }
        ?.let { candidate.coerceIn(0L, it) }
        ?: candidate.coerceAtLeast(0L)
    }
    viewModel.seekTo(target)
    remoteNavigationState = remoteNavigationState.updateScrubberPosition(target)
    scrubberPreviewJob?.cancel()
    scrubberPreviewJob = lifecycleScope.launch {
      // The fixed target is only an optimistic acknowledgement. Returning to
      // the live MediaSession clock prevents the wavy timeline from appearing
      // paused if a player is slow to publish its post-seek state.
      delay(TV_REMOTE_SCRUBBER_PREVIEW_TIMEOUT_MS)
      if (remoteNavigationState.scrubberPositionMs == target) {
        remoteNavigationState = remoteNavigationState.resetScrubberPosition()
      }
      scrubberPreviewJob = null
    }
  }

  private fun scheduleCleanHorizontalTap(
    direction: Int,
    eventTimeMs: Long = SystemClock.uptimeMillis(),
  ) {
    pendingHorizontalTapJob?.cancel()
    val normalizedTime = eventTimeMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
    val scheduledTap = TvPendingHorizontalTap(
      direction = direction.coerceIn(-1, 1),
      eventTimeMs = normalizedTime,
    )
    pendingHorizontalTap = scheduledTap
    pendingHorizontalTapJob = lifecycleScope.launch {
      delay(remoteDoubleTapTimeoutMs)
      if (pendingHorizontalTap == scheduledTap) {
        pendingHorizontalTap = null
        pendingHorizontalTapJob = null
        dispatchRemoteAction(TvRemoteAction.StepLandscape(scheduledTap.direction))
      }
    }
  }

  private fun flushPendingHorizontalTap() {
    pendingHorizontalTapJob?.cancel()
    pendingHorizontalTapJob = null
    val pending = pendingHorizontalTap ?: return
    pendingHorizontalTap = null
    dispatchRemoteAction(TvRemoteAction.StepLandscape(pending.direction))
  }

  private fun cancelPendingHorizontalTap() {
    pendingHorizontalTapJob?.cancel()
    pendingHorizontalTapJob = null
    pendingHorizontalTap = null
  }

  private fun triggerLongConfirm(keyCode: Int) {
    if (keyCode !in deferredConfirmKeys || !shouldCapturePlayerRemote()) return
    deferredConfirmKeys -= keyCode
    longConfirmKeys += keyCode
    confirmLongPressJobs.remove(keyCode)?.cancel()
    showRemoteControls(TvRemoteControl.PLAY_PAUSE)
  }

  private fun cancelRemoteGestures(clearConsumedKeys: Boolean = true) {
    cancelPendingHorizontalTap()
    scrubberPreviewJob?.cancel()
    scrubberPreviewJob = null
    confirmLongPressJobs.values.forEach(Job::cancel)
    confirmLongPressJobs.clear()
    deferredConfirmKeys.clear()
    longConfirmKeys.clear()
    horizontalPressDirections.clear()
    doubleHorizontalActions.clear()
    if (clearConsumedKeys) consumedRemoteKeyDowns.clear()
  }

  private fun activateRemoteControl(control: TvRemoteControl) {
    when (control) {
      TvRemoteControl.SETTINGS -> {
        hideRemoteControls()
        viewModel.navigate(AppDestination.SETTINGS)
      }
      TvRemoteControl.PREVIOUS_TRACK -> {
        viewModel.previous()
        remoteNavigationState = remoteNavigationState.resetScrubberPosition().registerInteraction()
      }
      TvRemoteControl.PLAY_PAUSE -> {
        viewModel.playPause()
        registerRemoteControlsInteraction()
      }
      TvRemoteControl.NEXT_TRACK -> {
        viewModel.next()
        remoteNavigationState = remoteNavigationState.resetScrubberPosition().registerInteraction()
      }
    }
  }

  private fun hasBluetoothPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Manifest.permission.BLUETOOTH_CONNECT
    } else {
      Manifest.permission.BLUETOOTH
    }
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun shareDiagnostics(text: String) {
    startActivity(
      Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_SUBJECT, "Icy Lyrics diagnostics")
          putExtra(Intent.EXTRA_TEXT, text)
        },
        "Share diagnostics",
      ),
    )
  }

  private fun applyImmersiveWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
  }
}

/** TV-only transport order matches the icon row shown below the title stack. */
internal enum class TvRemoteControl {
  PREVIOUS_TRACK,
  PLAY_PAUSE,
  NEXT_TRACK,
  SETTINGS;

  fun step(direction: Int): TvRemoteControl {
    val next = (ordinal + direction.coerceIn(-1, 1)).coerceIn(0, entries.lastIndex)
    return entries[next]
  }
}

internal enum class TvRemoteFocusTarget {
  CONTROLS,
  SCRUBBER,
}

internal data class TvRemoteNavigationState(
  val overlayVisible: Boolean = false,
  val focusTarget: TvRemoteFocusTarget = TvRemoteFocusTarget.CONTROLS,
  val focusedControl: TvRemoteControl = TvRemoteControl.PLAY_PAUSE,
  val scrubberPositionMs: Long? = null,
  val sourceBadgeVisible: Boolean = false,
  val interactionRevision: Long = 0L,
) {
  // Transitional alias retained for the TV layout while its visual layer is
  // split out of the activity.
  val controlsVisible: Boolean get() = overlayVisible

  fun show(initialFocus: TvRemoteControl = TvRemoteControl.PLAY_PAUSE): TvRemoteNavigationState =
    copy(
      overlayVisible = true,
      focusTarget = TvRemoteFocusTarget.CONTROLS,
      focusedControl = initialFocus,
      scrubberPositionMs = null,
      sourceBadgeVisible = true,
      interactionRevision = interactionRevision + 1L,
    )

  fun hide(): TvRemoteNavigationState = if (!overlayVisible) {
    this
  } else {
    copy(
      overlayVisible = false,
      focusTarget = TvRemoteFocusTarget.CONTROLS,
      scrubberPositionMs = null,
      sourceBadgeVisible = false,
      interactionRevision = interactionRevision + 1L,
    )
  }

  fun moveControlFocus(direction: Int): TvRemoteNavigationState {
    if (!overlayVisible || focusTarget != TvRemoteFocusTarget.CONTROLS) return this
    return copy(
      focusedControl = focusedControl.step(direction),
      sourceBadgeVisible = true,
      interactionRevision = interactionRevision + 1L,
    )
  }

  fun focusScrubber(): TvRemoteNavigationState = if (!overlayVisible) {
    this
  } else {
    copy(
      focusTarget = TvRemoteFocusTarget.SCRUBBER,
      scrubberPositionMs = null,
      sourceBadgeVisible = true,
      interactionRevision = interactionRevision + 1L,
    )
  }

  fun focusControls(): TvRemoteNavigationState = if (!overlayVisible) {
    this
  } else {
    copy(
      focusTarget = TvRemoteFocusTarget.CONTROLS,
      scrubberPositionMs = null,
      sourceBadgeVisible = true,
      interactionRevision = interactionRevision + 1L,
    )
  }

  fun updateScrubberPosition(positionMs: Long): TvRemoteNavigationState = copy(
    scrubberPositionMs = positionMs.coerceAtLeast(0L),
    sourceBadgeVisible = true,
    interactionRevision = interactionRevision + 1L,
  )

  fun resetScrubberPosition(): TvRemoteNavigationState = copy(scrubberPositionMs = null)

  fun registerInteraction(): TvRemoteNavigationState = if (overlayVisible) {
    copy(sourceBadgeVisible = true, interactionRevision = interactionRevision + 1L)
  } else {
    this
  }

  fun hideSourceBadge(): TvRemoteNavigationState = if (!sourceBadgeVisible) {
    this
  } else {
    copy(sourceBadgeVisible = false)
  }
}

internal sealed interface TvRemoteAction {
  data class Lyrics(val command: LandscapeRemoteCommand) : TvRemoteAction
  data class StepLandscape(val direction: Int) : TvRemoteAction
  data class HorizontalTap(val direction: Int) : TvRemoteAction
  data class MoveControlFocus(val direction: Int) : TvRemoteAction
  data class SeekBy(val deltaMs: Long) : TvRemoteAction
  data class ShowControls(val initialFocus: TvRemoteControl) : TvRemoteAction
  data object FocusScrubber : TvRemoteAction
  data object FocusControls : TvRemoteAction
  data object ActivateFocusedControl : TvRemoteAction
  data object DismissControls : TvRemoteAction
  data object PlayPause : TvRemoteAction
  data object PreviousTrack : TvRemoteAction
  data object NextTrack : TvRemoteAction
}

internal fun tvRemoteActionForKey(
  keyCode: Int,
  repeatCount: Int,
  navigationState: TvRemoteNavigationState = TvRemoteNavigationState(),
): TvRemoteAction? {
  if (repeatCount > 0 && !isRepeatableTvRemoteKey(keyCode, navigationState)) return null

  val overlayVisible = navigationState.overlayVisible
  val scrubberFocused = navigationState.focusTarget == TvRemoteFocusTarget.SCRUBBER

  return when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> if (!overlayVisible) {
      TvRemoteAction.Lyrics(LandscapeRemoteCommand.PREVIOUS_LYRIC)
    } else if (!scrubberFocused) {
      TvRemoteAction.FocusScrubber
    } else {
      null
    }
    KeyEvent.KEYCODE_DPAD_DOWN -> if (!overlayVisible) {
      TvRemoteAction.Lyrics(LandscapeRemoteCommand.NEXT_LYRIC)
    } else if (scrubberFocused) {
      TvRemoteAction.FocusControls
    } else {
      null
    }
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    -> if (overlayVisible && !scrubberFocused) {
      TvRemoteAction.ActivateFocusedControl
    } else {
      if (overlayVisible) null else TvRemoteAction.PlayPause
    }
    KeyEvent.KEYCODE_DPAD_LEFT -> if (!overlayVisible) {
      TvRemoteAction.HorizontalTap(-1)
    } else if (scrubberFocused) {
      TvRemoteAction.SeekBy(-TV_REMOTE_SEEK_STEP_MS)
    } else {
      TvRemoteAction.MoveControlFocus(-1)
    }
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!overlayVisible) {
      TvRemoteAction.HorizontalTap(1)
    } else if (scrubberFocused) {
      TvRemoteAction.SeekBy(TV_REMOTE_SEEK_STEP_MS)
    } else {
      TvRemoteAction.MoveControlFocus(1)
    }
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_SETTINGS,
    -> TvRemoteAction.ShowControls(TvRemoteControl.SETTINGS)
    KeyEvent.KEYCODE_BACK -> if (overlayVisible) TvRemoteAction.DismissControls else null
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK,
    -> TvRemoteAction.PlayPause
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvRemoteAction.PreviousTrack
    KeyEvent.KEYCODE_MEDIA_NEXT -> TvRemoteAction.NextTrack
    else -> null
  }
}

internal fun isRepeatableTvRemoteKey(
  keyCode: Int,
  navigationState: TvRemoteNavigationState = TvRemoteNavigationState(),
): Boolean = when {
  !navigationState.overlayVisible ->
    keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
  navigationState.focusTarget == TvRemoteFocusTarget.SCRUBBER ->
    keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
  else -> false
}

internal fun isTvPlayerRemoteKey(
  keyCode: Int,
  controlsVisible: Boolean = false,
): Boolean = when (keyCode) {
  KeyEvent.KEYCODE_DPAD_UP,
  KeyEvent.KEYCODE_DPAD_DOWN,
  KeyEvent.KEYCODE_DPAD_LEFT,
  KeyEvent.KEYCODE_DPAD_RIGHT,
  KeyEvent.KEYCODE_DPAD_CENTER,
  KeyEvent.KEYCODE_ENTER,
  KeyEvent.KEYCODE_NUMPAD_ENTER,
  KeyEvent.KEYCODE_BUTTON_A,
  KeyEvent.KEYCODE_MENU,
  KeyEvent.KEYCODE_SETTINGS,
  KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
  KeyEvent.KEYCODE_HEADSETHOOK,
  KeyEvent.KEYCODE_MEDIA_PREVIOUS,
  KeyEvent.KEYCODE_MEDIA_NEXT,
  -> true
  KeyEvent.KEYCODE_BACK -> controlsVisible
  else -> false
}

internal fun isTvConfirmKey(keyCode: Int): Boolean = when (keyCode) {
  KeyEvent.KEYCODE_DPAD_CENTER,
  KeyEvent.KEYCODE_ENTER,
  KeyEvent.KEYCODE_NUMPAD_ENTER,
  KeyEvent.KEYCODE_BUTTON_A,
  -> true
  else -> false
}

internal data class TvPendingHorizontalTap(
  val direction: Int,
  val eventTimeMs: Long,
)

internal data class TvHorizontalSecondDownDecision(
  val immediateAction: TvRemoteAction? = null,
  val consumeAsDoubleTap: Boolean = false,
)

/** Resolves a second key-down against a single tap pending since key-up. */
internal fun resolveTvHorizontalSecondDown(
  pending: TvPendingHorizontalTap?,
  direction: Int,
  eventTimeMs: Long,
  windowMs: Long = TV_REMOTE_DOUBLE_TAP_WINDOW_MS,
): TvHorizontalSecondDownDecision {
  val normalizedDirection = direction.coerceIn(-1, 1).takeIf { it != 0 }
    ?: return TvHorizontalSecondDownDecision()
  val isQuickSecondTap = pending != null &&
    pending.direction == normalizedDirection &&
    eventTimeMs >= pending.eventTimeMs &&
    eventTimeMs - pending.eventTimeMs <= windowMs
  if (isQuickSecondTap) {
    return TvHorizontalSecondDownDecision(
      immediateAction = if (normalizedDirection < 0) {
        TvRemoteAction.PreviousTrack
      } else {
        TvRemoteAction.NextTrack
      },
      consumeAsDoubleTap = true,
    )
  }

  return TvHorizontalSecondDownDecision(
    immediateAction = pending?.let { TvRemoteAction.StepLandscape(it.direction) },
  )
}

internal fun tvBackDestination(destination: AppDestination): AppDestination? = when (destination) {
  AppDestination.SETTINGS -> AppDestination.PLAYER
  AppDestination.LIBRARY,
  AppDestination.DEBUG,
  AppDestination.ABOUT_LEGAL,
  -> AppDestination.SETTINGS
  AppDestination.PLAYER -> null
}

internal fun isTimedLyricsRemoteReady(
  landscapeMode: LandscapeMode,
  lyrics: LyricsUiStatus,
): Boolean {
  val ready = lyrics as? LyricsUiStatus.Ready ?: return false
  if (landscapeMode != LandscapeMode.MIXED && landscapeMode != LandscapeMode.LYRICS) return false
  return when (val document = ready.document) {
    is LineLyrics -> document.lines.isNotEmpty()
    is SyllableLyrics -> document.lines.isNotEmpty()
    is StaticLyrics -> false
  }
}

internal const val TV_REMOTE_SOURCE_BADGE_TIMEOUT_MS = 3_000L
internal const val TV_REMOTE_DOUBLE_TAP_WINDOW_MS = 300L
internal const val TV_REMOTE_SEEK_STEP_MS = 5_000L
internal const val TV_REMOTE_SCRUBBER_PREVIEW_TIMEOUT_MS = 700L
