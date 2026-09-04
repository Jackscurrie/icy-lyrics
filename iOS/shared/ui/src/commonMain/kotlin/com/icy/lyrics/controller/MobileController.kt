package com.icy.lyrics.controller

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.diagnostics.DiagnosticEvent
import com.icy.lyrics.core.platform.diagnostics.SecretRedactor
import com.icy.lyrics.core.platform.settings.AppSettings as StoredSettings
import com.icy.lyrics.core.platform.settings.BackgroundStyle as StoredBackground
import com.icy.lyrics.core.platform.settings.MixedSide
import com.icy.lyrics.core.platform.settings.SourceSelectionMode
import com.icy.lyrics.core.platform.storage.StoredLocalTtml
import com.icy.lyrics.core.platform.storage.TrackKeys
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.*
import com.icy.lyrics.lyricsQueueRetryDelayMs
import com.icy.lyrics.nextLyricsQueueRetryAttempt
import com.icy.lyrics.queuedHigherPriorityAttempt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

enum class PlaybackConnection { DISCONNECTED, CONNECTING, CONNECTED }

interface PlaybackGateway {
  val snapshots: StateFlow<NowPlayingSnapshot?>
  val connection: Flow<PlaybackConnection> get() = snapshots.map {
    if (it == null) PlaybackConnection.DISCONNECTED else PlaybackConnection.CONNECTED
  }.distinctUntilChanged()
  fun playPause()
  fun previous()
  fun next()
  fun seekTo(positionMs: Long)
}

data class MobileDeviceTiming(val key: String?, val name: String?, val offsetMs: Int?)

/** The native shell supplies I/O; both applications use this same presentation state machine. */
interface MobileBackend {
  val settings: Flow<StoredSettings>
  val library: Flow<List<StoredLocalTtml>>
  val diagnosticEvents: Flow<List<DiagnosticEvent>>
  val deviceTiming: Flow<MobileDeviceTiming> get() = emptyFlow()
  suspend fun currentSettings(): StoredSettings
  suspend fun updateSettings(value: StoredSettings)
  suspend fun resolve(track: TrackIdentity, allowCached: Boolean, requestId: Long): LyricsResolution
  suspend fun importTtml(track: TrackIdentity, text: String, sourceUri: String?)
  suspend fun deleteSavedLyrics(trackKey: String): Boolean
  suspend fun clearDiagnostics()
  suspend fun setDeviceTiming(value: Int?) { error("No Bluetooth media device is active.") }
}

class MobileController(
  private val scope: CoroutineScope,
  private val backend: MobileBackend,
  private val playback: PlaybackGateway,
  playbackReady: Boolean = false,
  authAvailable: Boolean = false,
) {
  internal val mutableState = MutableStateFlow(IcyLyricsUiState(notificationAccess = playbackReady, spotifyAuthAvailable = authAvailable))
  val state: StateFlow<IcyLyricsUiState> = mutableState.asStateFlow()
  private var generation = 0L
  private var lyricsJob: Job? = null
  private var overlayJob: Job? = null
  private var wasLandscape = false
  private val settingsMutex = Mutex()

  init {
    scope.launch { playback.snapshots.collect { snapshot -> mutableState.update { it.copy(nowPlaying = snapshot) } } }
    scope.launch { playback.snapshots.map { it?.identity }.distinctUntilChanged().collect { load(it) } }
    scope.launch { backend.settings.collect { settings -> mutableState.update { it.copy(settings = settings.toUi(it.settings)) } } }
    scope.launch { backend.deviceTiming.collect { timing -> mutableState.update { current -> current.copy(settings = current.settings.copy(
      activeBluetoothDeviceId = timing.key, activeBluetoothDeviceName = timing.name, activeBluetoothTimingOffsetMs = timing.offsetMs,
    )) } } }
    scope.launch { backend.library.collect { values -> mutableState.update { it.copy(library = values.map { item ->
      SavedLyricsUi(item.trackKey, item.title.ifBlank { item.trackKey }, item.artists.joinToString(", ").ifBlank { "Unknown artist" }, item.updatedAtEpochMs)
    }) } } }
    scope.launch { backend.diagnosticEvents.collect { events -> mutableState.update { it.copy(diagnostics = it.diagnostics.copy(events = events.map { event ->
      DiagnosticEventUi(event.createdAtEpochMs, event.severity.name, event.component, event.provider?.name, event.message, event.httpStatus)
    })) } } }
  }

  fun refreshPermissions(ready: Boolean, bluetoothPermission: Boolean) = mutableState.update {
    it.copy(notificationAccess = ready, bluetoothPermissionGranted = bluetoothPermission)
  }
  fun setAuthorization(inProgress: Boolean, connected: Boolean = state.value.spotifyConnected, message: String? = null) = mutableState.update {
    it.copy(spotifyAuthorizationInProgress = inProgress, spotifyConnected = connected, transientMessage = message?.safe())
  }
  fun setLandscape(value: Boolean) {
    if (value && !wasLandscape) mutableState.update { it.copy(landscapeMode = LandscapeMode.MIXED) }
    wasLandscape = value
  }
  fun navigate(value: AppDestination) = mutableState.update { it.copy(destination = value) }
  fun stepLandscape(value: Int) = mutableState.update { it.copy(landscapeMode = it.landscapeMode.step(value)) }
  fun showArtworkControls() {
    overlayJob?.cancel()
    mutableState.update { it.copy(artworkControlsVisible = true) }
    overlayJob = scope.launch { delay(3_000); mutableState.update { it.copy(artworkControlsVisible = false) } }
  }
  fun playPause() = playback.playPause()
  fun previous() = playback.previous()
  fun next() = playback.next()
  fun seekTo(value: Long) = playback.seekTo(value.coerceAtLeast(0))
  fun reloadLyrics() = load(state.value.nowPlaying?.identity, force = true)
  fun setGlobalTimingOffset(value: Int) = edit { copy(globalTimingOffsetMs = value.timingStep()) }
  fun setBluetoothTimingOffset(value: Int?) = launchAction { backend.setDeviceTiming(value?.timingStep()) }
  fun setRememberBluetoothOffsets(value: Boolean) = edit { copy(rememberBluetoothTiming = value) }
  fun setMixedMediaSide(value: MixedMediaSide) = edit { copy(mixedSide = if (value == MixedMediaSide.LEFT) MixedSide.LEFT else MixedSide.RIGHT) }
  fun setBackgroundStyle(value: BackgroundStyle) = edit { copy(backgroundStyle = if (value == BackgroundStyle.ANIMATED) StoredBackground.ANIMATED else StoredBackground.STATIC_BLURRED) }
  fun setBackgroundEnabled(value: Boolean) = edit { copy(backgroundEnabled = value) }
  fun setKeepScreenAwake(value: Boolean) = edit { copy(keepScreenAwake = value) }
  fun setUseLocalTtml(value: Boolean) = edit(true) { copy(useLocalTtml = value) }
  fun setRevealEnabled(value: Boolean) = edit { copy(revealEnabled = value) }
  fun setSourceStrategy(value: SourceStrategy) = edit(true) { copy(sourceSelectionMode = if (value == SourceStrategy.STRICT_PRIORITY) SourceSelectionMode.STRICT_PRIORITY else SourceSelectionMode.BETTER_SYNC) }
  fun setDebugEnabled(value: Boolean) = edit { copy(debugEnabled = value) }
  fun setSpicyEnabled(value: Boolean) = edit(true) { copy(spicyEnabled = value) }
  fun setSpicyTokenSharingConsent(value: Boolean) = edit(true) { copy(spicyTokenSharingConsent = value) }
  fun setLrclibEnabled(value: Boolean) = edit(true) { copy(lrclibEnabled = value) }

  fun prepareImport(): TrackIdentity? = state.value.nowPlaying?.identity.also {
    if (it == null) showMessage("Play the matching song before importing its TTML file.")
  }
  fun importTtml(track: TrackIdentity, text: String, sourceUri: String?) = launchAction {
    require(text.isNotBlank()) { "The selected TTML file was empty or unreadable." }
    require(text.length <= 2_000_000) { "That TTML file is larger than the 2 MB import limit." }
    backend.importTtml(track, text, sourceUri)
    showMessage("Saved TTML lyrics for ${track.title.ifBlank { "this song" }}.")
    // Persist to the captured identity, but never publish an old track after picker I/O.
    if (state.value.nowPlaying?.identity?.exactStorageKey == track.exactStorageKey) load(track, true)
  }
  fun deleteSavedLyrics(trackKey: String) = launchAction {
    val removed = backend.deleteSavedLyrics(trackKey)
    showMessage(if (removed) "Removed saved lyrics." else "Saved lyrics were already removed.")
    if (removed && state.value.nowPlaying?.identity?.exactStorageKey == trackKey) reloadLyrics()
  }
  fun clearDiagnostics() = launchAction {
    backend.clearDiagnostics()
    mutableState.update { it.copy(diagnostics = LyricsDiagnosticsUi(trackUri = it.nowPlaying?.identity?.exactStorageKey?.diagnosticKey())) }
    showMessage("Diagnostics cleared.")
  }
  fun showMessage(message: String) = mutableState.update { it.copy(transientMessage = message.safe()) }
  fun clearTransientMessage() = mutableState.update { it.copy(transientMessage = null) }

  private fun edit(reload: Boolean = false, transform: StoredSettings.() -> StoredSettings) = launchAction {
    settingsMutex.withLock { backend.updateSettings(backend.currentSettings().transform()) }
    if (reload) reloadLyrics()
  }
  private fun launchAction(action: suspend () -> Unit): Job = scope.launch {
    try { action() } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { showMessage(error.message ?: "The change could not be saved.") }
  }
  private fun load(track: TrackIdentity?, force: Boolean = false) {
    val currentGeneration = ++generation
    lyricsJob?.cancel()
    if (track == null) {
      mutableState.update { it.copy(lyrics = LyricsUiStatus.Idle, diagnostics = LyricsDiagnosticsUi(events = it.diagnostics.events)) }
      return
    }
    lyricsJob = scope.launch {
      try {
        var allowCached = !force
        var retries = 0
        var fallback: com.icy.lyrics.core.lyrics.model.LyricsDocument? = null
        while (currentGeneration == generation) {
          if (fallback == null) mutableState.update { it.copy(lyrics = LyricsUiStatus.Loading(force)) }
          val result = backend.resolve(track, allowCached, currentGeneration)
          if (currentGeneration != generation) return@launch
          val found = result as? LyricsResolution.Found
          val selected = found?.let { result.attempts.lastOrNull { attempt -> attempt.provider == found.provider } }
          val error = result.attempts.lastOrNull { it.outcome == ProviderAttemptOutcome.FAILED || it.outcome == ProviderAttemptOutcome.SOURCE_MISMATCH }?.message?.safe()
          mutableState.update { it.copy(diagnostics = it.diagnostics.copy(
            selectedSource = found?.document?.metadata?.source ?: fallback?.metadata?.source,
            selectedSyncKind = found?.document?.syncKind ?: fallback?.syncKind,
            fromCache = selected?.fromCache ?: (fallback != null && it.diagnostics.fromCache),
            trackUri = track.exactStorageKey.diagnosticKey(), error = error,
            attempts = result.attempts.map { attempt -> ProviderAttemptUi(
              attempt.provider.name, attempt.outcome.name, attempt.source?.name, attempt.syncKind,
              attempt.fromCache, attempt.message?.safe(), attempt.httpStatus, attempt.retryAfterMs,
            ) },
          )) }
          when (result) {
            is LyricsResolution.Found -> {
              mutableState.update { it.copy(lyrics = LyricsUiStatus.Ready(result.document)) }
              if (result.queuedHigherPriorityAttempt() == null) return@launch
              fallback = result.document
              allowCached = true
            }
            is LyricsResolution.Missing -> {
              if (fallback == null) mutableState.update { it.copy(lyrics = LyricsUiStatus.Empty("No lyrics were found in the enabled sources.")) }
              return@launch
            }
            is LyricsResolution.Pending -> {
              if (fallback == null) mutableState.update { it.copy(lyrics = LyricsUiStatus.Loading(false), transientMessage = result.message?.safe()) }
              allowCached = fallback != null
            }
          }
          delay(lyricsQueueRetryDelayMs(retries))
          retries = nextLyricsQueueRetryAttempt(retries)
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        if (generation != currentGeneration) return@launch
        val message = (error.message ?: "Lyrics could not be loaded.").safe()
        mutableState.update { it.copy(lyrics = LyricsUiStatus.Failed(message), diagnostics = it.diagnostics.copy(error = message)) }
      }
    }
  }
}

private fun StoredSettings.toUi(previous: AppSettings) = previous.copy(
  useLocalTtml = useLocalTtml, globalTimingOffsetMs = globalTimingOffsetMs,
  rememberBluetoothOffsets = rememberBluetoothTiming,
  mixedMediaSide = if (mixedSide == MixedSide.LEFT) MixedMediaSide.LEFT else MixedMediaSide.RIGHT,
  backgroundStyle = if (backgroundStyle == StoredBackground.ANIMATED) BackgroundStyle.ANIMATED else BackgroundStyle.STATIC_BLURRED,
  backgroundEnabled = backgroundEnabled, keepScreenAwake = keepScreenAwake, revealEnabled = revealEnabled,
  sourceStrategy = if (sourceSelectionMode == SourceSelectionMode.STRICT_PRIORITY) SourceStrategy.STRICT_PRIORITY else SourceStrategy.PREFER_BETTER_SYNC,
  debugEnabled = debugEnabled, spicyEnabled = spicyEnabled, spicyTokenSharingConsent = spicyTokenSharingConsent, lrclibEnabled = lrclibEnabled,
)
private fun Int.timingStep() = (coerceIn(-5_000, 5_000) / 10.0).roundToInt() * 10
private fun String.safe() = SecretRedactor.redact(this).take(1_000)
private fun String.diagnosticKey() = "sha256:${TrackKeys.privacyHash(this)}"
