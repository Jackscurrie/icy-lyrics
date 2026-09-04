@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ios

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import com.icy.lyrics.controller.*
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.ios.IosServices
import com.icy.lyrics.core.platform.provider.SpotifyAccessTokenSource
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.NSData
import platform.UIKit.UIViewController
import platform.posix.memcpy
import kotlin.coroutines.resume

/** Only OS operations cross Swift/Objective-C. The actual screens and state machine are shared. */
interface IosHost {
  fun playPause()
  fun previous()
  fun next()
  fun seekTo(positionMs: Long)
  fun connectSpotify(forLyrics: Boolean)
  fun ensureLyricsAuthorization()
  fun cancelLyricsAuthorization()
  fun cancelSpotifyAuthorization()
  fun disconnectSpotify()
  fun pickTtml()
  fun shareDiagnostics(text: String)
  fun setKeepAwake(enabled: Boolean)
  fun lyricsAccessToken(rejectedToken: String?, completion: (String?) -> Unit)
}

class IosAppController(private val host: IosHost, versionName: String, authAvailable: Boolean) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val snapshot = MutableStateFlow<NowPlayingSnapshot?>(null)
  private val playbackConnection = MutableStateFlow(PlaybackConnection.DISCONNECTED)
  private val uiPlatform = IosIcyUiPlatform(versionName)
  private val providerSession = IosProviderSession()
  private var providerActionGeneration = 0L
  private val services = IosServices.create(object : SpotifyAccessTokenSource {
    override suspend fun accessToken(): String? = requestToken(null)
    override suspend fun refreshAfterRejection(rejectedToken: String): String? = requestToken(rejectedToken)
    private suspend fun requestToken(rejected: String?): String? = withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        host.lyricsAccessToken(rejected) { token -> if (continuation.isActive) continuation.resume(token) }
      }
    }
  })
  private val controller = MobileController(scope, object : MobileBackend {
    override val settings get() = services.settings
    override val library get() = services.library
    override val diagnosticEvents get() = services.diagnosticEvents
    override suspend fun currentSettings() = services.currentSettings()
    override suspend fun updateSettings(value: com.icy.lyrics.core.platform.settings.AppSettings) = services.updateSettings(value)
    override suspend fun resolve(track: TrackIdentity, allowCached: Boolean, requestId: Long) =
      providerSession.resolve { services.resolve(track, allowCached, requestId) }
    override suspend fun importTtml(track: TrackIdentity, text: String, sourceUri: String?) = services.importTtml(track, text, sourceUri)
    override suspend fun deleteSavedLyrics(trackKey: String) = services.deleteSavedLyrics(trackKey)
    override suspend fun clearDiagnostics() = services.clearDiagnostics()
  }, object : PlaybackGateway {
    override val snapshots: StateFlow<NowPlayingSnapshot?> = snapshot
    override val connection: StateFlow<PlaybackConnection> = playbackConnection
    override fun playPause() = host.playPause()
    override fun previous() = host.previous()
    override fun next() = host.next()
    override fun seekTo(positionMs: Long) = host.seekTo(positionMs)
  }, authAvailable = authAvailable)
  private var pendingImport: TrackIdentity? = null

  init {
    scope.launch { try { services.initialize() } catch (error: Exception) { showError(error.message ?: "Storage could not be initialized.") } }
    scope.launch { controller.state.map { it.settings.keepScreenAwake }.distinctUntilChanged().collect(host::setKeepAwake) }
  }

  fun makeViewController(): UIViewController = ComposeUIViewController {
    ProvideAndroidFontScaling {
      CompositionLocalProvider(LocalIcyUiPlatform provides uiPlatform) {
        val state by controller.state.collectAsState()
        BoxWithConstraints {
          val landscape = maxWidth > maxHeight
          LaunchedEffect(landscape) { controller.setLandscape(landscape) }
          IcyLyricsApp(
            state = state, isLandscape = landscape,
            onOpenNotificationAccess = { host.connectSpotify(false) },
            onRequestBluetoothPermission = { showError("Automatic Spotify Bluetooth output identification is unavailable on this iPhone. Global timing remains active.") },
            onPickTtml = { if (pendingImport == null) { pendingImport = controller.prepareImport(); if (pendingImport != null) host.pickTtml() } },
            onNavigate = controller::navigate, onStepLandscape = controller::stepLandscape,
            onShowArtworkControls = controller::showArtworkControls, onPlayPause = controller::playPause,
            onPrevious = controller::previous, onNext = controller::next, onSeek = controller::seekTo,
            onReload = controller::reloadLyrics, onGlobalTimingOffset = { controller.setGlobalTimingOffset(it) },
            onBluetoothTimingOffset = { controller.setBluetoothTimingOffset(it) },
            onRememberBluetoothOffsets = { controller.setRememberBluetoothOffsets(it) },
            onMixedMediaSide = { controller.setMixedMediaSide(it) }, onBackgroundStyle = { controller.setBackgroundStyle(it) },
            onBackgroundEnabled = { controller.setBackgroundEnabled(it) }, onKeepScreenAwake = { controller.setKeepScreenAwake(it) },
            onUseLocalTtml = { controller.setUseLocalTtml(it) }, onRevealEnabled = { controller.setRevealEnabled(it) },
            onSourceStrategy = { controller.setSourceStrategy(it) }, onDebugEnabled = { controller.setDebugEnabled(it) },
            onSpicyEnabled = ::setSpicyEnabled,
            onSpicyTokenSharingConsent = ::setSpicyConsent,
            onConnectSpotify = { host.connectSpotify(false) }, onCancelSpotifyAuthorization = host::cancelSpotifyAuthorization,
            onDisconnectSpotify = host::disconnectSpotify, onLrclibEnabled = { controller.setLrclibEnabled(it) },
            onShareDiagnostics = { host.shareDiagnostics(controller.state.value.diagnostics.asText()) },
            onClearDiagnostics = { controller.clearDiagnostics() }, onDeleteSavedLyrics = { controller.deleteSavedLyrics(it) },
            onDismissMessage = controller::clearTransientMessage,
          )
        }
      }
    }
  }

  fun updatePlayback(uri: String, title: String, artist: String, album: String, durationMs: Long,
                     positionMs: Long, speed: Float, playing: Boolean, actions: Long) {
    providerSession.connect()
    playbackConnection.value = PlaybackConnection.CONNECTED
    val previous = snapshot.value
    snapshot.value = NowPlayingSnapshot("com.spotify.client", title, artist, album, durationMs.takeIf { it > 0 },
      positionMs.coerceAtLeast(0), speed, if (playing) 3 else 2,
      previous?.artwork.takeIf { previous?.rawUri == uri }, uiPlatform.monotonicTimeMs(), uri, uri, emptyMap(), actions)
    controller.refreshPermissions(true, false)
  }
  fun updateArtwork(data: NSData, forUri: String) {
    if (snapshot.value?.rawUri != forUri || data.length > 16uL * 1024uL * 1024uL) return
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isEmpty()) return
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    val artwork = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: return
    snapshot.update { it?.takeIf { current -> current.rawUri == forUri }?.copy(artwork = artwork) ?: it }
  }
  fun playbackDisconnected(message: String?) {
    playbackConnection.value = PlaybackConnection.DISCONNECTED
    snapshot.update { it?.copy(positionMs = it.currentPositionMs(uiPlatform.monotonicTimeMs()), playbackState = 2,
      capturedAtElapsedMs = uiPlatform.monotonicTimeMs(), availableActions = 0) }
    if (message != null) controller.showMessage(message)
  }
  fun playbackConnecting() { playbackConnection.value = PlaybackConnection.CONNECTING }
  fun authorizationChanged(inProgress: Boolean, connected: Boolean, message: String?) {
    controller.setAuthorization(inProgress, connected, message)
    // On iOS this existing permission flag means account access is available;
    // waiting for a first App Remote snapshot would hide Settings/Disconnect.
    controller.refreshPermissions(connected, false)
  }
  fun lyricsAuthorizationReady() { controller.reloadLyrics() }
  fun accountDisconnected() {
    ++providerActionGeneration
    providerSession.disconnect()
    pendingImport = null
    playbackConnection.value = PlaybackConnection.DISCONNECTED
    snapshot.value = null
    controller.setAuthorization(false, false)
    controller.refreshPermissions(false, false)
  }
  private fun setSpicyEnabled(value: Boolean) {
    val action = controller.setSpicyEnabled(value)
    requestLyricsAuthorizationAfter(action, value)
  }
  private fun setSpicyConsent(value: Boolean) {
    val action = controller.setSpicyTokenSharingConsent(value)
    requestLyricsAuthorizationAfter(action, value)
  }
  private fun requestLyricsAuthorizationAfter(action: Job, requested: Boolean) {
    val generation = ++providerActionGeneration
    if (!requested) host.cancelLyricsAuthorization()
    scope.launch {
      try {
        action.join()
        if (!requested || action.isCancelled || generation != providerActionGeneration) return@launch
        val settings = services.currentSettings()
        if (generation == providerActionGeneration && settings.spicyEnabled && settings.spicyTokenSharingConsent) {
          host.ensureLyricsAuthorization()
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { if (generation == providerActionGeneration) showError(error.message ?: "The change could not be saved.") }
    }
  }
  fun completeImport(text: String, sourceUri: String?) {
    val track = pendingImport ?: return
    pendingImport = null
    controller.importTtml(track, text, sourceUri)
  }
  fun cancelImport() { pendingImport = null }
  fun showError(message: String) = controller.showMessage(message)
  fun close() { providerSession.disconnect(); scope.cancel(); services.close(); host.setKeepAwake(false) }
}

/** Cancels active I/O without closing durable storage or cancelling settings writes. */
internal class IosProviderSession {
  private var enabled = true
  private var generation = 0L
  private val requests = mutableSetOf<Job>()
  fun connect() { enabled = true }
  fun disconnect() {
    enabled = false
    ++generation
    requests.toList().forEach { it.cancel() }
  }
  suspend fun <T> resolve(action: suspend () -> T): T = coroutineScope {
    if (!enabled) throw CancellationException("Spotify account disconnected")
    val requestGeneration = generation
    val request = coroutineContext.job
    requests += request
    try {
      val value = action()
      ensureActive()
      if (requestGeneration != generation) throw CancellationException("Spotify account changed")
      value
    } finally { requests -= request }
  }
}
