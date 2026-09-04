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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IcyParityScreenshotTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun captureScenarios() {
    val arguments = InstrumentationRegistry.getArguments()
    val landscape = arguments.getString("landscape") == "true"
    val scenario = arguments.getString("scenario")
    val ids = scenario?.let(::listOf) ?: if (landscape) IcyParityFixtures.landscapeIds else IcyParityFixtures.portraitIds
    var current by mutableStateOf(ids.first())
    compose.mainClock.autoAdvance = false
    compose.waitUntil(timeoutMillis = 10_000) {
      val metrics = compose.activity.resources.displayMetrics
      (metrics.widthPixels > metrics.heightPixels) == landscape
    }
    compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    compose.setContent {
      val platform = rememberAndroidIcyUiPlatform()
      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
        key(current) { IcyParityFixtureScreen(current) }
      }
    }
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
      val insets = compose.activity.window.decorView.rootWindowInsets.getInsets(
        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
      )
      File(directory, "$id.json").writeText(
        "{\"widthPx\":${bitmap.width},\"heightPx\":${bitmap.height},\"density\":${metrics.density}," +
          "\"fontScale\":${compose.activity.resources.configuration.fontScale}," +
          "\"safeDrawingInsetsPx\":[${insets.left},${insets.top},${insets.right},${insets.bottom}]}",
      )
      File(directory, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
