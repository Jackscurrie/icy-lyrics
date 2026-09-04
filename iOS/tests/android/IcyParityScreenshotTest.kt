package com.icy.lyrics.parity

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.ui.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IcyParityScreenshotTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun captureScenarios() {
    val arguments = InstrumentationRegistry.getArguments()
    val profile = NativeViewportProfile.from(arguments)
    val landscape = arguments.getString("landscape") == "true"
    val scenario = arguments.getString("scenario")
    val ids = scenario?.let(::listOf) ?: if (landscape) IcyParityFixtures.landscapeIds else IcyParityFixtures.portraitIds
    var current by mutableStateOf(ids.first())
    compose.mainClock.autoAdvance = false
    if (profile != null) {
      // The owned emulator changed rotation when the test activity started,
      // after the shell set user_rotation. Pin only this
      // measured-profile activity; all actual geometry checks remain below.
      compose.activityRule.scenario.onActivity {
        it.requestedOrientation = if (landscape)
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      }
    }
    compose.waitUntil(timeoutMillis = 10_000) {
      val metrics = compose.activity.resources.displayMetrics
      (metrics.widthPixels > metrics.heightPixels) == landscape
    }
    compose.activityRule.scenario.onActivity {
      it.enableEdgeToEdge()
      profile?.installInsets(it)
    }
    val effectiveMetrics = AtomicReference<ComposeCaptureMetrics>()
    compose.setContent {
      CaptureComposeMetrics { effectiveMetrics.set(it) }
      val platform = rememberAndroidIcyUiPlatform()
      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
        key(current) { IcyParityFixtureScreen(current) }
      }
    }
    if (profile != null) compose.runOnUiThread { compose.activity.window.decorView.requestApplyInsets() }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "parity").apply { mkdirs() }
    for (id in ids) {
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
        "settings put global animator_duration_scale " + if (IcyParityFixtures.reducedMotion(id)) "0" else "1",
      ).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
      }
      compose.runOnUiThread { current = id }
      compose.mainClock.advanceTimeBy(2_000)
      compose.waitForIdle()
      val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
      val metrics = compose.activity.resources.displayMetrics
      val osInsets = compose.activity.window.decorView.rootWindowInsets.getInsets(
        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime(),
      )
      val effective = requireNotNull(effectiveMetrics.get())
      if (profile != null) {
        check(bitmap.width == profile.widthPx && bitmap.height == profile.heightPx) {
          "Native viewport mismatch: captured ${bitmap.width}x${bitmap.height}, requested ${profile.widthPx}x${profile.heightPx}"
        }
        check(profile.matches(effective)) { "Effective Compose density/fontScale/insets do not match native profile" }
      }
      val metadata = effective.asJson().put("widthPx", bitmap.width).put("heightPx", bitmap.height)
        .put("scenario", id).put("captureSurface", "Compose root; no resize or crop")
        .put("osSafeDrawingInsetsPx", JSONArray(listOf(osInsets.left, osInsets.top, osInsets.right, osInsets.bottom)))
        .put("osDensity", metrics.density).put("osFontScale", compose.activity.resources.configuration.fontScale)
        .put("insetDispatch", if (profile == null) "native Android" else "measured profile injected at content parent before Compose listener")
      if (profile != null) metadata.put("profileId", profile.id)
      File(directory, "$id.json").writeText(metadata.toString(2))
      File(directory, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
