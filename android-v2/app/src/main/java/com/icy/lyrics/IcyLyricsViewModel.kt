package com.icy.lyrics

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationLaunch
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationResult
import com.icy.lyrics.core.platform.diagnostics.SecretRedactor
import com.icy.lyrics.core.platform.settings.AppSettings as PersistedAppSettings
import com.icy.lyrics.core.platform.settings.BackgroundStyle as PersistedBackgroundStyle
import com.icy.lyrics.core.platform.settings.MixedSide as PersistedMixedSide
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.settings.SourceSelectionMode
import com.icy.lyrics.core.platform.storage.TrackKeys
import com.icy.lyrics.core.platform.timing.EffectiveTimingOffset
import com.icy.lyrics.ui.AppDestination
import com.icy.lyrics.ui.AppSettings as UiAppSettings
import com.icy.lyrics.ui.BackgroundStyle
import com.icy.lyrics.ui.DiagnosticEventUi
import com.icy.lyrics.ui.IcyLyricsUiState
import com.icy.lyrics.ui.LandscapeMode
import com.icy.lyrics.ui.LyricsDiagnosticsUi
import com.icy.lyrics.ui.LyricsUiStatus
import com.icy.lyrics.ui.MixedMediaSide
import com.icy.lyrics.ui.ProviderAttemptUi
import com.icy.lyrics.ui.SavedLyricsUi
import com.icy.lyrics.ui.SourceStrategy
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IcyLyricsViewModel(application: Application) : AndroidViewModel(application) {
  private val container = (application as IcyLyricsApplication).container
  private val services = container.services
  private val mutableState = MutableStateFlow(
    IcyLyricsUiState(
      notificationAccess = container.mediaTracker.hasNotificationAccess(),
      spotifyAuthAvailable = services.spotifyPkceClient != null,
    ),
  )
  private val trackGeneration = AtomicLong(0L)
  private var lyricsJob: Job? = null
  private var artworkControlsJob: Job? = null
  private var wasLandscape = false

  val state: StateFlow<IcyLyricsUiState> = mutableState.asStateFlow()

  init {
    services.spotifyPkceClient?.let { spotify ->
      viewModelScope.launch(Dispatchers.IO) {
        val connected = runCatching { spotify.accessToken() != null }.getOrDefault(false)
        mutableState.update { it.copy(spotifyConnected = connected) }
      }
    }
    viewModelScope.launch {
      container.mediaTracker.snapshots.collect { snapshot ->
        mutableState.update { it.copy(nowPlaying = snapshot) }
      }
    }
    viewModelScope.launch {
      container.mediaTracker.snapshots
        .map { it?.identity }
        .distinctUntilChanged()
        .collect(::beginLyricsLoad)
    }
    viewModelScope.launch {
      services.settings.settings.collect { settings ->
        mutableState.update { current -> current.copy(settings = settings.toUi(current.settings)) }
      }
    }
    viewModelScope.launch {
      services.bluetoothTimingResolver.effectiveOffset.collect { timing ->
        mutableState.update { current ->
          current.copy(settings = current.settings.withEffectiveTiming(timing))
        }
      }
    }
    viewModelScope.launch {
      services.localTtmlRepository.observeLibrary().collect { stored ->
        mutableState.update { current ->
          current.copy(
            library = stored.map { item ->
              SavedLyricsUi(
                trackUri = item.trackKey,
                title = item.title.ifBlank { item.trackKey },
                artist = item.artists.joinToString(", ").ifBlank { "Unknown artist" },
                updatedAtEpochMs = item.updatedAtEpochMs,
              )
            },
          )
        }
      }
    }
    viewModelScope.launch {
      services.diagnostics.observeRecent(limit = 200).collect { events ->
        mutableState.update { current ->
          current.copy(
            diagnostics = current.diagnostics.copy(
              events = events.map { event ->
                DiagnosticEventUi(
                  createdAtEpochMs = event.createdAtEpochMs,
                  severity = event.severity.name,
                  component = event.component,
                  provider = event.provider?.name,
                  message = event.message,
                  httpStatus = event.httpStatus,
                )
              },
            ),
          )
        }
      }
    }
  }

  fun refreshPermissions(bluetoothPermissionGranted: Boolean) {
    container.mediaTracker.start()
    services.bluetoothRouteMonitor.refreshPermission()
    mutableState.update {
      it.copy(
        notificationAccess = container.mediaTracker.hasNotificationAccess(),
        bluetoothPermissionGranted = bluetoothPermissionGranted,
      )
    }
    services.spotifyPkceClient?.let { spotify ->
      viewModelScope.launch(Dispatchers.IO) {
        val connected = runCatching { spotify.hasAuthorization() }.getOrDefault(false)
        mutableState.update { it.copy(spotifyConnected = connected) }
      }
    }
  }

  fun setLandscape(isLandscape: Boolean) {
    if (isLandscape && !wasLandscape) {
      mutableState.update { it.copy(landscapeMode = LandscapeMode.MIXED) }
    }
    wasLandscape = isLandscape
  }

  fun stepLandscape(direction: Int) {
    mutableState.update { it.copy(landscapeMode = it.landscapeMode.step(direction)) }
  }

  fun showArtworkControls() {
    artworkControlsJob?.cancel()
    mutableState.update { it.copy(artworkControlsVisible = true) }
    artworkControlsJob = viewModelScope.launch {
      delay(3_000L)
      mutableState.update { it.copy(artworkControlsVisible = false) }
    }
  }

  fun navigate(destination: AppDestination) = mutableState.update { it.copy(destination = destination) }
  fun playPause() = container.mediaTracker.playPause()
  fun previous() = container.mediaTracker.previous()
  fun next() = container.mediaTracker.next()
  fun seekTo(positionMs: Long) = container.mediaTracker.seekTo(positionMs)
  fun reloadLyrics() = beginLyricsLoad(mutableState.value.nowPlaying?.identity, force = true)

  fun setGlobalTimingOffset(value: Int) = persistSetting {
    setGlobalTimingOffsetMs(value.roundToTimingStep())
  }

  fun setBluetoothTimingOffset(value: Int?) {
    viewModelScope.launch {
      runCatching {
        if (value == null) {
          val key = mutableState.value.settings.activeBluetoothDeviceId
            ?: error("No Bluetooth media device is active.")
          services.deviceTimingRepository.delete(key)
        } else {
          check(services.bluetoothTimingResolver.rememberForCurrentRoute(value.roundToTimingStep())) {
            "No Bluetooth media device is active."
          }
        }
      }.onFailure(::showError)
    }
  }

  fun setRememberBluetoothOffsets(value: Boolean) = persistSetting { setRememberBluetoothTiming(value) }
  fun setMixedMediaSide(value: MixedMediaSide) = persistSetting {
    setMixedSide(if (value == MixedMediaSide.LEFT) PersistedMixedSide.LEFT else PersistedMixedSide.RIGHT)
  }
  fun setBackgroundStyle(value: BackgroundStyle) = persistSetting {
    setBackgroundStyle(
      if (value == BackgroundStyle.ANIMATED) {
        PersistedBackgroundStyle.ANIMATED
      } else {
        PersistedBackgroundStyle.STATIC_BLURRED
      },
    )
  }
  fun setBackgroundEnabled(value: Boolean) = persistSetting { setBackgroundEnabled(value) }
  fun setKeepScreenAwake(value: Boolean) = persistSetting { setKeepScreenAwake(value) }
  fun setUseLocalTtml(value: Boolean) = persistSetting(reloadCurrentTrack = true) {
    setUseLocalTtml(value)
  }
  fun setRevealEnabled(value: Boolean) = persistSetting { setRevealEnabled(value) }
  fun setSourceStrategy(value: SourceStrategy) = persistSetting(reloadCurrentTrack = true) {
    setSourceSelectionMode(
      if (value == SourceStrategy.STRICT_PRIORITY) {
        SourceSelectionMode.STRICT_PRIORITY
      } else {
        SourceSelectionMode.BETTER_SYNC
      },
    )
  }
  fun setDebugEnabled(value: Boolean) = persistSetting { setDebugEnabled(value) }
  fun setSpicyEnabled(value: Boolean) = persistSetting(reloadCurrentTrack = true) {
    setSpicyEnabled(value)
  }
  fun setSpicyTokenSharingConsent(value: Boolean) = persistSetting(reloadCurrentTrack = true) {
    setSpicyTokenSharingConsent(value)
  }
  fun setLrclibEnabled(value: Boolean) = persistSetting(reloadCurrentTrack = true) {
    setLrclibEnabled(value)
  }

  suspend fun beginSpotifyAuthorization(): SpotifyAuthorizationLaunch? {
    val spotify = services.spotifyPkceClient
    if (spotify == null) {
      mutableState.update {
        it.copy(transientMessage = "Add spotifyClientId to local.properties to connect Spotify.")
      }
      return null
    }
    mutableState.update { it.copy(spotifyAuthorizationInProgress = true) }
    return try {
      withContext(Dispatchers.IO) { spotify.beginAuthorization() }
    } catch (cancelled: CancellationException) {
      mutableState.update { it.copy(spotifyAuthorizationInProgress = false) }
      throw cancelled
    } catch (error: Throwable) {
      mutableState.update {
        it.copy(
          spotifyAuthorizationInProgress = false,
          transientMessage = error.message.redactedOrNull() ?: "Spotify authorization could not start.",
        )
      }
      null
    }
  }

  suspend fun completeSpotifyAuthorization(launch: SpotifyAuthorizationLaunch) {
    val spotify = services.spotifyPkceClient ?: return
    val result = withContext(Dispatchers.IO) { spotify.completeAuthorization(launch) }
    when (result) {
      is SpotifyAuthorizationResult.Success -> {
        mutableState.update {
          it.copy(
            spotifyConnected = true,
            spotifyAuthorizationInProgress = false,
            transientMessage = "Spotify connected securely.",
          )
        }
        reloadLyrics()
      }
      is SpotifyAuthorizationResult.Cancelled -> mutableState.update {
        it.copy(
          spotifyAuthorizationInProgress = false,
          transientMessage = "Spotify connection was cancelled: ${SecretRedactor.redact(result.reason)}",
        )
      }
      is SpotifyAuthorizationResult.Failure -> mutableState.update {
        it.copy(
          spotifyAuthorizationInProgress = false,
          transientMessage = SecretRedactor.redact(result.message),
        )
      }
    }
  }

  suspend fun cancelSpotifyAuthorization(launch: SpotifyAuthorizationLaunch?) {
    val spotify = services.spotifyPkceClient
    withContext(Dispatchers.IO) {
      if (spotify != null) spotify.cancelAuthorization(launch) else launch?.close()
    }
    mutableState.update {
      it.copy(
        spotifyAuthorizationInProgress = false,
        transientMessage = "Spotify connection was cancelled.",
      )
    }
  }

  suspend fun failSpotifyAuthorization(launch: SpotifyAuthorizationLaunch?, error: Throwable) {
    val spotify = services.spotifyPkceClient
    withContext(Dispatchers.IO) {
      if (spotify != null) spotify.cancelAuthorization(launch) else launch?.close()
    }
    mutableState.update {
      it.copy(
        spotifyAuthorizationInProgress = false,
        transientMessage = error.message.redactedOrNull() ?: "Spotify authorization could not continue.",
      )
    }
  }

  fun disconnectSpotify() {
    val spotify = services.spotifyPkceClient ?: return
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { spotify.disconnect() }
        .onSuccess {
          mutableState.update {
            it.copy(spotifyConnected = false, transientMessage = "Spotify disconnected.")
          }
          beginLyricsLoad(mutableState.value.nowPlaying?.identity)
        }
        .onFailure(::showError)
    }
  }

  fun clearDiagnostics() {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { services.diagnostics.clear() }
        .onSuccess {
          mutableState.update { current ->
            current.copy(
              diagnostics = LyricsDiagnosticsUi(
                trackUri = current.nowPlaying?.identity?.exactStorageKey?.diagnosticTrackId(),
              ),
              transientMessage = "Diagnostics cleared.",
            )
          }
        }
        .onFailure(::showError)
    }
  }

  fun importTtml(uri: Uri) {
    val track = mutableState.value.nowPlaying?.identity
    if (track == null) {
      mutableState.update { it.copy(transientMessage = "Play the matching song before importing its TTML file.") }
      return
    }
    viewModelScope.launch {
      val readResult = withContext(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readTextLimited(MAX_TTML_CHARS) }
        }
      }
      val text = readResult.getOrElse { error ->
        mutableState.update {
          it.copy(
            transientMessage = error.message.redactedOrNull()
              ?: "The selected TTML file was unreadable.",
          )
        }
        return@launch
      }
      if (text.isNullOrBlank()) {
        mutableState.update { it.copy(transientMessage = "The selected TTML file was empty or unreadable.") }
        return@launch
      }
      saveImportedTtml(track, text, uri.toString())
    }
  }

  fun deleteSavedLyrics(trackUri: String) {
    viewModelScope.launch {
      runCatching { services.localTtmlRepository.deleteByTrackKey(trackUri) }
        .onSuccess { removed ->
          mutableState.update {
            it.copy(transientMessage = if (removed) "Removed saved lyrics." else "Saved lyrics were already removed.")
          }
          val current = mutableState.value.nowPlaying?.identity
          if (removed && current?.exactStorageKey == trackUri) beginLyricsLoad(current, force = true)
        }
        .onFailure(::showError)
    }
  }

  fun clearTransientMessage() = mutableState.update { it.copy(transientMessage = null) }

  private fun beginLyricsLoad(track: TrackIdentity?, force: Boolean = false) {
    val generation = trackGeneration.incrementAndGet()
    lyricsJob?.cancel()
    if (track == null) {
      mutableState.update { current ->
        current.copy(
          lyrics = LyricsUiStatus.Idle,
          diagnostics = LyricsDiagnosticsUi(events = current.diagnostics.events),
        )
      }
      return
    }
    lyricsJob = viewModelScope.launch {
      try {
        var allowCached = !force
        var completedQueueRetries = 0
        var visibleFallback: com.icy.lyrics.core.lyrics.model.LyricsDocument? = null
        while (generation == trackGeneration.get()) {
          if (visibleFallback == null) {
            mutableState.update { it.copy(lyrics = LyricsUiStatus.Loading(retainingPrevious = force)) }
          }
          val result = resolveTrack(track, allowCached, generation)
          if (generation != trackGeneration.get()) return@launch
          val selectedAttempt = (result as? LyricsResolution.Found)
            ?.let { found -> result.attempts.lastOrNull { it.provider == found.provider } }
          val error = result.attempts.lastOrNull { it.outcome.isError }?.message.redactedOrNull()
          mutableState.update { current ->
            current.copy(
              diagnostics = current.diagnostics.copy(
                selectedSource = (result as? LyricsResolution.Found)?.document?.metadata?.source
                  ?: visibleFallback?.metadata?.source,
                selectedSyncKind = (result as? LyricsResolution.Found)?.document?.syncKind
                  ?: visibleFallback?.syncKind,
                fromCache = selectedAttempt?.fromCache
                  ?: if (visibleFallback == null) false else current.diagnostics.fromCache,
                trackUri = track.exactStorageKey.diagnosticTrackId(),
                attempts = result.attempts.map { it.toUi() },
                error = error,
              ),
            )
          }
          when (result) {
            is LyricsResolution.Found -> {
              mutableState.update { it.copy(lyrics = LyricsUiStatus.Ready(result.document)) }
              if (result.queuedHigherPriorityAttempt() == null) return@launch
              visibleFallback = result.document
              val waitMs = lyricsQueueRetryDelayMs(completedQueueRetries)
              completedQueueRetries = nextLyricsQueueRetryAttempt(completedQueueRetries)
              delay(waitMs)
              // The visible fallback was cached by its provider. Reusing it
              // keeps each promotion check focused on the queued source.
              allowCached = true
            }
            is LyricsResolution.Missing -> {
              if (visibleFallback == null) {
                mutableState.update {
                  it.copy(lyrics = LyricsUiStatus.Empty("No lyrics were found in the enabled sources."))
                }
              }
              return@launch
            }
            is LyricsResolution.Pending -> {
              val waitMs = lyricsQueueRetryDelayMs(completedQueueRetries)
              if (visibleFallback == null) {
                mutableState.update {
                  it.copy(
                    lyrics = LyricsUiStatus.Loading(retainingPrevious = false),
                    transientMessage = result.message.redactedOrNull(),
                  )
                }
              }
              completedQueueRetries = nextLyricsQueueRetryAttempt(completedQueueRetries)
              delay(waitMs)
              allowCached = visibleFallback != null
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (generation != trackGeneration.get()) return@launch
        val message = error.message.redactedOrNull() ?: "Lyrics could not be loaded."
        mutableState.update { current ->
          current.copy(
            lyrics = LyricsUiStatus.Failed(message),
            diagnostics = current.diagnostics.copy(error = message),
          )
        }
      }
    }
  }

  /** Metadata aliases must never displace an exact local TTML match. */
  private suspend fun resolveTrack(
    track: TrackIdentity,
    allowCached: Boolean,
    requestId: Long,
  ): LyricsResolution {
    if (services.settings.current().useLocalTtml) {
      services.localTtmlRepository.get(track)?.let { local ->
        return LyricsResolution.Found(
          document = local.document,
          provider = LyricsProviderId.LOCAL_TTML,
          attempts = listOf(
            ProviderAttempt(
              provider = LyricsProviderId.LOCAL_TTML,
              outcome = ProviderAttemptOutcome.FOUND,
              source = local.document.metadata.source,
              syncKind = local.document.syncKind.name,
              message = "Saved local TTML",
            ),
          ),
        )
      }
    }
    val alias = services.spotifyTrackResolver.resolve(track)
    val resolvedTrack = alias?.takeIf(String::isNotBlank)?.let { track.copy(uri = it) } ?: track
    val resolution = services.lyricsResolver.resolve(
      LyricsRequest(
        track = resolvedTrack,
        allowCached = allowCached,
        requestId = requestId,
      ),
    )
    services.spotifyPkceClient?.let { spotify ->
      val connected = spotify.hasAuthorization()
      mutableState.update { it.copy(spotifyConnected = connected) }
    }
    return resolution
  }

  private suspend fun saveImportedTtml(track: TrackIdentity, text: String, sourceUri: String?) {
    runCatching {
      services.localTtmlProvider.import(track, text, sourceUri = sourceUri)
    }.onSuccess {
      mutableState.update { it.copy(transientMessage = "Saved TTML lyrics for ${track.title.ifBlank { "this song" }}.") }
      beginLyricsLoad(track, force = true)
    }.onFailure { error ->
      mutableState.update {
        it.copy(transientMessage = error.message.redactedOrNull() ?: "That TTML file could not be parsed.")
      }
    }
  }

  private fun persistSetting(
    reloadCurrentTrack: Boolean = false,
    block: suspend SettingsRepository.() -> Unit,
  ) {
    viewModelScope.launch {
      runCatching { services.settings.block() }
        .onSuccess {
          if (reloadCurrentTrack) beginLyricsLoad(mutableState.value.nowPlaying?.identity)
        }
        .onFailure(::showError)
    }
  }

  private fun showError(error: Throwable) {
    mutableState.update {
      it.copy(transientMessage = error.message.redactedOrNull() ?: "The change could not be saved.")
    }
  }

  private fun PersistedAppSettings.toUi(previous: UiAppSettings): UiAppSettings = previous.copy(
    useLocalTtml = useLocalTtml,
    globalTimingOffsetMs = globalTimingOffsetMs,
    rememberBluetoothOffsets = rememberBluetoothTiming,
    mixedMediaSide = if (mixedSide == PersistedMixedSide.LEFT) MixedMediaSide.LEFT else MixedMediaSide.RIGHT,
    backgroundStyle = if (backgroundStyle == PersistedBackgroundStyle.ANIMATED) {
      BackgroundStyle.ANIMATED
    } else {
      BackgroundStyle.STATIC_BLURRED
    },
    backgroundEnabled = backgroundEnabled,
    keepScreenAwake = keepScreenAwake,
    revealEnabled = revealEnabled,
    sourceStrategy = if (sourceSelectionMode == SourceSelectionMode.STRICT_PRIORITY) {
      SourceStrategy.STRICT_PRIORITY
    } else {
      SourceStrategy.PREFER_BETTER_SYNC
    },
    debugEnabled = debugEnabled,
    spicyEnabled = spicyEnabled,
    spicyTokenSharingConsent = spicyTokenSharingConsent,
    lrclibEnabled = lrclibEnabled,
  )

  private fun UiAppSettings.withEffectiveTiming(timing: EffectiveTimingOffset): UiAppSettings = copy(
    activeBluetoothDeviceId = timing.route?.deviceKey,
    activeBluetoothDeviceName = timing.route?.displayName,
    activeBluetoothTimingOffsetMs = timing.offsetMs.takeIf {
      timing.route != null && timing.usingRememberedDeviceOffset
    },
  )

  private fun ProviderAttempt.toUi() = ProviderAttemptUi(
    provider = provider.name,
    outcome = outcome.name,
    source = source?.name,
    syncKind = syncKind,
    fromCache = fromCache,
    message = message.redactedOrNull(),
    httpStatus = httpStatus,
    retryAfterMs = retryAfterMs,
  )

  private val ProviderAttemptOutcome.isError: Boolean
    get() = this == ProviderAttemptOutcome.FAILED || this == ProviderAttemptOutcome.SOURCE_MISMATCH

  private fun Int.roundToTimingStep(): Int =
    (coerceIn(-5_000, 5_000) / 10.0).roundToInt() * 10

  private fun String.diagnosticTrackId(): String = "sha256:${TrackKeys.privacyHash(this)}"

  private fun String?.redactedOrNull(): String? = this
    ?.takeIf(String::isNotBlank)
    ?.let(SecretRedactor::redact)
    ?.take(1_000)

  private companion object {
    const val MAX_TTML_CHARS = 2_000_000
  }
}

private fun java.io.Reader.readTextLimited(maxChars: Int): String {
  val buffer = CharArray(8_192)
  val result = StringBuilder(minOf(maxChars, 64 * 1_024))
  while (true) {
    val count = read(buffer)
    if (count < 0) return result.toString()
    if (result.length + count > maxChars) {
      throw IllegalArgumentException("That TTML file is larger than the 2 MB import limit.")
    }
    result.append(buffer, 0, count)
  }
}
