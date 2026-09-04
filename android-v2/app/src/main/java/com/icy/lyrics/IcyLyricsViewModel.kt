package com.icy.lyrics

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icy.lyrics.controller.AndroidMobileBackend
import com.icy.lyrics.controller.MobileController
import com.icy.lyrics.controller.PlaybackGateway
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationLaunch
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationResult
import com.icy.lyrics.core.platform.diagnostics.SecretRedactor
import com.icy.lyrics.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

class IcyLyricsViewModel(application: Application) : AndroidViewModel(application) {
  private val container = (application as IcyLyricsApplication).container
  private val services = container.services
  private val controller = MobileController(viewModelScope, AndroidMobileBackend(services), object : PlaybackGateway {
    override val snapshots get() = container.mediaTracker.snapshots
    override fun playPause() = container.mediaTracker.playPause()
    override fun previous() { container.mediaTracker.previous() }
    override fun next() { container.mediaTracker.next() }
    override fun seekTo(positionMs: Long) { container.mediaTracker.seekTo(positionMs) }
  }, container.mediaTracker.hasNotificationAccess(), services.spotifyPkceClient != null)
  private val mutableState get() = controller.mutableState
  private var pendingImportTrack: TrackIdentity? = null
  val state get() = controller.state

  init {
    services.spotifyPkceClient?.let { spotify ->
      viewModelScope.launch {
        val connected = withContext(Dispatchers.IO) { runCatching { spotify.accessToken() != null }.getOrDefault(false) }
        controller.setAuthorization(false, connected)
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

  fun setLandscape(isLandscape: Boolean) { controller.setLandscape(isLandscape) }
  fun stepLandscape(direction: Int) { controller.stepLandscape(direction) }
  fun navigate(destination: AppDestination) { controller.navigate(destination) }
  fun seekTo(positionMs: Long) { controller.seekTo(positionMs) }
  fun setGlobalTimingOffset(value: Int) { controller.setGlobalTimingOffset(value) }
  fun setBluetoothTimingOffset(value: Int?) { controller.setBluetoothTimingOffset(value) }
  fun setRememberBluetoothOffsets(value: Boolean) { controller.setRememberBluetoothOffsets(value) }
  fun setMixedMediaSide(value: MixedMediaSide) { controller.setMixedMediaSide(value) }
  fun setBackgroundStyle(value: BackgroundStyle) { controller.setBackgroundStyle(value) }
  fun setBackgroundEnabled(value: Boolean) { controller.setBackgroundEnabled(value) }
  fun setKeepScreenAwake(value: Boolean) { controller.setKeepScreenAwake(value) }
  fun setUseLocalTtml(value: Boolean) { controller.setUseLocalTtml(value) }
  fun setRevealEnabled(value: Boolean) { controller.setRevealEnabled(value) }
  fun setSourceStrategy(value: SourceStrategy) { controller.setSourceStrategy(value) }
  fun setDebugEnabled(value: Boolean) { controller.setDebugEnabled(value) }
  fun setSpicyEnabled(value: Boolean) { controller.setSpicyEnabled(value) }
  fun setSpicyTokenSharingConsent(value: Boolean) { controller.setSpicyTokenSharingConsent(value) }
  fun setLrclibEnabled(value: Boolean) { controller.setLrclibEnabled(value) }
  fun deleteSavedLyrics(trackUri: String) { controller.deleteSavedLyrics(trackUri) }
  fun showArtworkControls() { controller.showArtworkControls() }
  fun playPause() { controller.playPause() }
  fun previous() { controller.previous() }
  fun next() { controller.next() }
  fun reloadLyrics() { controller.reloadLyrics() }
  fun clearDiagnostics() { controller.clearDiagnostics() }
  fun clearTransientMessage() { controller.clearTransientMessage() }

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
          controller.reloadLyrics()
        }
        .onFailure(::showError)
    }
  }


  fun prepareTtmlImport(): Boolean {
    if (pendingImportTrack != null) return false
    pendingImportTrack = controller.prepareImport()
    return pendingImportTrack != null
  }
  fun cancelTtmlImport() { pendingImportTrack = null }
  fun importTtml(uri: Uri) {
    // Bind at picker launch, so switching songs while Files is open cannot
    // attach the selected lyrics to a different track.
    val track = pendingImportTrack ?: return
    pendingImportTrack = null
    viewModelScope.launch {
      try {
        val text = withContext(Dispatchers.IO) {
          getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readTextLimited(2_000_000) }
        } ?: error("The selected TTML file was empty or unreadable.")
        controller.importTtml(track, text, uri.toString())
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { showError(error) }
    }
  }
  private fun showError(error: Throwable) = controller.showMessage(error.message ?: "The change could not be saved.")
  private fun String?.redactedOrNull(): String? = this?.takeIf(String::isNotBlank)?.let(SecretRedactor::redact)?.take(1_000)
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
