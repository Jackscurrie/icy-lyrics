package com.icy.lyrics.overlay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.icy.lyrics.ui.IcyText

/** Android-only controls. These preferences never change the main player's appearance. */
@Composable
internal fun OverlaySettingsEntry(activity: ComponentActivity) {
  val preferences = remember(activity) { OverlayPreferences(activity) }
  DisposableEffect(preferences) { onDispose { preferences.close() } }
  val options by preferences.options.collectAsState()
  var permissionGranted by remember(activity) { mutableStateOf(Settings.canDrawOverlays(activity)) }
  var showGuide by rememberSaveable { mutableStateOf(false) }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  val notificationPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { /* Optional: denying notifications must not disable an explicitly enabled overlay. */ }

  DisposableEffect(activity, preferences) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        permissionGranted = Settings.canDrawOverlays(activity)
        if (permissionGranted) {
          showGuide = false
          error = null
        } else if (preferences.options.value.enabled) {
          LyricsOverlayService.stop(activity)
        }
      }
    }
    activity.lifecycle.addObserver(observer)
    onDispose { activity.lifecycle.removeObserver(observer) }
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = Color.White.copy(alpha = 0.07f),
  ) {
    Column(
      Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      IcyText(
        "Lyrics overlay",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
      )
      IcyText(
        "Keep a movable album-cover button above Spotify and other apps. Tap it to open your lyrics; drag the button or the panel's top handle to move it. Drag the bottom-right grip up or down to change the window's height.",
        color = Color.White.copy(alpha = 0.78f),
        style = MaterialTheme.typography.bodyMedium,
      )
      if (!permissionGranted) {
        Button(onClick = { showGuide = true }) {
          IcyText("Set up overlay permission")
        }
      } else {
        IcyText(
          "Display over other apps is allowed",
          color = Color(0xFF9DDCA8),
          style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { showGuide = true }) { IcyText("Permission guide") }
      }
      OverlaySwitchRow(
        title = "Enable lyrics overlay",
        description = if (permissionGranted) {
          "Show the floating button. Turn this off to remove it."
        } else {
          "Allow display over other apps using the guide above first."
        },
        checked = options.enabled && permissionGranted,
        enabled = permissionGranted,
        onCheckedChange = { enabled ->
          if (!enabled) {
            LyricsOverlayService.stop(activity)
            error = null
          } else if (!Settings.canDrawOverlays(activity)) {
            permissionGranted = false
            showGuide = true
          } else if (LyricsOverlayService.start(activity)) {
            error = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
              ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
            ) {
              notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          } else {
            error = "Android could not start the overlay. Check the permission, then try again."
          }
        },
      )
      if (error != null) {
        IcyText(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }
      HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
      IcyText(
        "Overlay appearance only",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      OverlaySwitchRow(
        "Spinning album cover",
        "Rotate the floating cover while music plays. Turn off to keep the cover still. Without artwork, the Icy Lyrics logo is shown.",
        options.spinArtwork,
        onCheckedChange = preferences::setSpinArtwork,
      )
      OverlaySwitchRow(
        "Background",
        "Use the album background inside the overlay.",
        options.backgroundEnabled,
        onCheckedChange = preferences::setBackgroundEnabled,
      )
      OverlaySwitchRow(
        "Album art",
        "Show the cover above the lyrics.",
        options.showArtwork,
        onCheckedChange = preferences::setShowArtwork,
      )
      OverlaySwitchRow(
        "Title and artist",
        "Show the song title and artist above the lyrics.",
        options.showTitles,
        onCheckedChange = preferences::setShowTitles,
      )
    }
  }

  if (showGuide) {
    OverlayPermissionGuide(
      onDismiss = { showGuide = false },
      onOpenSettings = {
        error = null
        if (!openOverlayPermissionSettings(activity)) {
          error = "Open Android Settings → Apps → Special app access → Display over other apps, then select Icy Lyrics."
          showGuide = false
        }
      },
    )
  }
}

@Composable
private fun OverlaySwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      IcyText(title, fontWeight = FontWeight.SemiBold)
      IcyText(description, color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodySmall)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = title },
    )
  }
}

/** Same title/body/steps-card/blue-action structure as notification-access onboarding. */
@Composable
private fun OverlayPermissionGuide(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B101A)) {
      Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 56.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Surface(
            modifier = Modifier.sizeIn(maxWidth = 540.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.09f),
          ) {
            Column(
              Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              IcyText(
                "Allow Display Over Other Apps",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
              )
              Spacer(Modifier.height(14.dp))
              IcyText(
                "This lets Icy Lyrics show a movable button and your lyrics while you stay in Spotify. It does not let Icy Lyrics read other apps' screens.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
              )
              Spacer(Modifier.height(22.dp))
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.24f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  IcyText("How do I do that?", fontWeight = FontWeight.Bold)
                  overlayPermissionSteps.forEach { step ->
                    IcyText(step, color = Color.White.copy(alpha = 0.82f))
                  }
                }
              }
              Spacer(Modifier.height(22.dp))
              Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8FD1FF), contentColor = Color(0xFF071A2A)),
              ) {
                IcyText("Open Overlay Permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close permission guide", tint = Color.White)
        }
      }
    }
  }
}

internal val overlayPermissionSteps = listOf(
  "1. Tap the blue button below to open the settings screen",
  "2. Tap Icy Lyrics if Android shows a list of apps (you may need to scroll to find it)",
  "3. Turn on the switch next to \"Allow display over other apps\" (some phones call this \"Appear on top\")",
  "4. Use Android's back button feature to return here",
  "5. Turn on \"Enable lyrics overlay\" in Icy Lyrics",
)

private fun openOverlayPermissionSettings(activity: ComponentActivity): Boolean {
  val intents = listOf(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
  )
  for (intent in intents) {
    try {
      activity.startActivity(intent)
      return true
    } catch (_: ActivityNotFoundException) {
      // OEMs may provide only the generic special-access list.
    } catch (_: SecurityException) {
      // Offer the next standard destination rather than crashing the settings screen.
    }
  }
  return false
}

/** Shown on the first actual player opening, never over first-run permissions or startup. */
@Composable
internal fun OverlayIntroBanner(onSetup: () -> Unit, modifier: Modifier = Modifier) {
  val activity = LocalContext.current.findComponentActivity() ?: return
  val preferences = remember(activity) { OverlayPreferences(activity) }
  DisposableEffect(preferences) { onDispose { preferences.close() } }
  val session = remember(activity) {
    ViewModelProvider(activity, object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OverlayIntroSession(preferences.options.value.bannerSeen) as T
    })[OverlayIntroSession::class.java]
  }
  DisposableEffect(activity, session) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) session.dismiss()
    }
    activity.lifecycle.addObserver(observer)
    onDispose { activity.lifecycle.removeObserver(observer) }
  }
  if (!session.visible) return

  // Persist on display, not only on X: a later app opening must not advertise again.
  // The Activity ViewModel retains visibility across rotation without retaining Context.
  LaunchedEffect(session) { preferences.markBannerSeen() }
  Surface(
    modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    shape = RoundedCornerShape(18.dp),
    color = Color(0xFF172D44),
    shadowElevation = 8.dp,
  ) {
    Row(
      Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(Icons.Default.Layers, contentDescription = null, tint = Color(0xFF8FD1FF), modifier = Modifier.padding(top = 8.dp))
      Column(Modifier.weight(1f)) {
        IcyText("New: lyrics over Spotify", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        IcyText(
          "Open your lyrics without leaving your music app.",
          color = Color.White.copy(alpha = 0.8f),
          style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { preferences.markBannerSeen(); session.dismiss(); onSetup() }) {
          IcyText("Set up overlay")
        }
      }
      IconButton(onClick = { preferences.markBannerSeen(); session.dismiss() }) {
        Icon(Icons.Default.Close, contentDescription = "Dismiss overlay announcement forever", tint = Color.White)
      }
    }
  }
}

internal class OverlayIntroSession(alreadySeen: Boolean) : ViewModel() {
  var visible by mutableStateOf(!alreadySeen)
    private set

  fun dismiss() { visible = false }
}

private fun Context.findComponentActivity(): ComponentActivity? {
  var candidate: Context = this
  while (candidate is ContextWrapper) {
    if (candidate is ComponentActivity) return candidate
    val next = candidate.baseContext
    if (next === candidate) break
    candidate = next
  }
  return candidate as? ComponentActivity
}
