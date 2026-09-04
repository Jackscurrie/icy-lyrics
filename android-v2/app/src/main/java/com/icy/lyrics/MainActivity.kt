package com.icy.lyrics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icy.lyrics.core.platform.auth.SpotifyAuthorizationLaunch
import com.icy.lyrics.ui.IcyLyricsApp
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: IcyLyricsViewModel by viewModels()
  private var spotifyAuthorizationJob: Job? = null
  private var spotifyAuthorizationLaunch: SpotifyAuthorizationLaunch? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    applySystemBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    setContent {
      val uiPlatform = rememberAndroidIcyUiPlatform()
      CompositionLocalProvider(LocalIcyUiPlatform provides uiPlatform) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        LaunchedEffect(isLandscape) { viewModel.setLandscape(isLandscape) }
        LaunchedEffect(state.settings.keepScreenAwake) {
          if (state.settings.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val ttmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
          if (uri != null) viewModel.importTtml(uri) else viewModel.cancelTtmlImport()
        }
        val bluetoothPermission = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission(),
        ) { viewModel.refreshPermissions(it) }

        IcyLyricsApp(
          state = state,
          isLandscape = isLandscape,
          onOpenNotificationAccess = {
            startActivity((application as IcyLyricsApplication).container.mediaTracker.notificationAccessIntent())
          },
          onRequestBluetoothPermission = {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
          },
          onPickTtml = {
            if (viewModel.prepareTtmlImport()) {
              ttmlPicker.launch(arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"))
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
          onSpicyEnabled = viewModel::setSpicyEnabled,
          onSpicyTokenSharingConsent = viewModel::setSpicyTokenSharingConsent,
          onConnectSpotify = ::connectSpotify,
          onCancelSpotifyAuthorization = ::cancelSpotifyAuthorization,
          onDisconnectSpotify = viewModel::disconnectSpotify,
          onLrclibEnabled = viewModel::setLrclibEnabled,
          onShareDiagnostics = { shareDiagnostics(state.diagnostics.asText()) },
          onClearDiagnostics = viewModel::clearDiagnostics,
          onDeleteSavedLyrics = viewModel::deleteSavedLyrics,
          onDismissMessage = viewModel::clearTransientMessage,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshPermissions(hasBluetoothPermission())
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applySystemBars(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
  }

  private fun hasBluetoothPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
      PackageManager.PERMISSION_GRANTED

  private fun connectSpotify() {
    if (spotifyAuthorizationJob?.isActive == true) return
    spotifyAuthorizationJob = lifecycleScope.launch {
      val authorization = viewModel.beginSpotifyAuthorization() ?: return@launch
      spotifyAuthorizationLaunch = authorization
      try {
        authorization.customTabsIntent().launchUrl(this@MainActivity, authorization.authorizationUri)
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
    // Closing the accepted socket first makes cancellation immediate even if a
    // localhost client connected but never completed its HTTP request.
    authorization?.close()
    spotifyAuthorizationJob?.cancel()
    spotifyAuthorizationJob = null
    spotifyAuthorizationLaunch = null
    lifecycleScope.launch { viewModel.cancelSpotifyAuthorization(authorization) }
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

  private fun applySystemBars(immersive: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, !immersive)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
    }
  }
}
