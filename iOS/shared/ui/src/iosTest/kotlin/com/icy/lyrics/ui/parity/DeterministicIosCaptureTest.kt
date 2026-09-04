@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import com.icy.lyrics.ui.IcyParityFixtureScreen
import com.icy.lyrics.ui.LocalIcyUiPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Deterministic offscreen iOS captures, distinct from the production UIKit/Metal screenshots. */
class DeterministicIosCaptureTest {
  @Test fun capturePortraitSequence() = captureSequence(AndroidReferenceProfile.Portrait)
  @Test fun captureLandscapeSequence() = captureSequence(AndroidReferenceProfile.Landscape)

  private fun captureSequence(profile: AndroidReferenceProfile) = runAndroidReferenceUiTest(profile) {
    val assets = DeterministicFixtureAssets()
    val directory = assets.outputRoot / profile.id
    val records = mutableListOf<DeterministicCaptureRecord>()
    val json = Json { prettyPrint = true; encodeDefaults = true }
    fun writeManifest(complete: Boolean) = assets.write(
      directory / "manifest.json",
      json.encodeToString(DeterministicCaptureManifest(
        profile = profile, complete = complete, scenarioOrder = profile.scenarioIds,
        captures = records.toList(), loadedAssetSha256 = assets.loadedAssetSha256.toMap(),
        locale = assets.locale, timezone = assets.timezone,
        formattedDates = assets.formattedDates.toMap(),
      )).encodeToByteArray(),
    )
    // An interrupted run must never leave a previous completed manifest looking current.
    writeManifest(complete = false)
    assets.verifyOriginalFontBytes()
    val platform = assets.platform()

    var current by mutableStateOf(profile.scenarioIds.first())
    var observed: ObservedViewport? = null
    mainClock.autoAdvance = false
    setContent {
      val density = LocalDensity.current
      val direction = LocalLayoutDirection.current
      val safeDrawing = WindowInsets.safeDrawing
      SideEffect {
        observed = ObservedViewport(density.density, density.fontScale, listOf(
          safeDrawing.getLeft(density, direction), safeDrawing.getTop(density),
          safeDrawing.getRight(density, direction), safeDrawing.getBottom(density),
        ))
      }
      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
        key(current) { IcyParityFixtureScreen(current) }
      }
    }

    for ((index, scenario) in profile.scenarioIds.withIndex()) {
      val startTimeMs = mainClock.currentTime
      runOnUiThread { current = scenario }
      // Preserve the complete Android sequence and its original 2,000ms advancement per scene.
      mainClock.advanceTimeBy(2_000)
      waitForIdle()
      val image = captureToImage()
      val endTimeMs = mainClock.currentTime
      assertEquals(2_000L, endTimeMs - startTimeMs, "Capture must not advance the controlled clock")
      assertEquals(profile.widthPx, image.width)
      assertEquals(profile.heightPx, image.height)
      val viewport = assertNotNull(observed)
      assertEquals(profile.density, viewport.density)
      assertEquals(profile.fontScale, viewport.fontScale)
      assertEquals(profile.safeDrawingInsetsPx, viewport.safeDrawingInsetsPx)

      val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
      val png = try {
        val encoded = requireNotNull(skiaImage.encodeToData(EncodedImageFormat.PNG))
        try { encoded.bytes } finally { encoded.close() }
      } finally { skiaImage.close() }
      assertTrue(png.size > 8 && png.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10))
      val record = DeterministicCaptureRecord(
        profileId = profile.id, scenario = scenario, scenarioIndex = index, scenarioOrder = profile.scenarioIds,
        widthPx = image.width, heightPx = image.height,
        density = viewport.density, fontScale = viewport.fontScale, safeDrawingInsetsPx = viewport.safeDrawingInsetsPx,
        clockStartMs = startTimeMs, clockTimeMillis = endTimeMs,
        pngBytes = png.size, pngSha256 = png.toByteString().sha256().hex(),
        locale = assets.locale, timezone = assets.timezone, formattedDates = assets.formattedDates.toMap(),
      )
      assets.write(directory / "$scenario.png", png)
      assets.write(directory / "$scenario.json", json.encodeToString(record).encodeToByteArray())
      records += record
      writeManifest(complete = false)
      println("Deterministic iOS ${profile.id}: ${index + 1}/${profile.scenarioIds.size} $scenario at ${endTimeMs}ms (${png.size} PNG bytes)")
    }
    assertEquals(profile.scenarioIds, records.map { it.scenario })
    writeManifest(complete = true)
  }
}

@Serializable
internal data class ObservedViewport(
  val density: Float,
  val fontScale: Float,
  val safeDrawingInsetsPx: List<Int>,
)

@Serializable
internal data class DeterministicCaptureRecord(
  val profileId: String,
  val scenario: String,
  val scenarioIndex: Int,
  val scenarioOrder: List<String>,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val fontScale: Float,
  val safeDrawingInsetsPx: List<Int>,
  val locale: String,
  val timezone: String,
  val formattedDates: Map<String, String>,
  val clockStartMs: Long,
  val clockTimeMillis: Long,
  val pngBytes: Int,
  val pngSha256: String,
  val captureBackend: String = "skia-raster",
)

@Serializable
internal data class DeterministicCaptureManifest(
  val profile: AndroidReferenceProfile,
  val complete: Boolean,
  val scenarioOrder: List<String>,
  val captures: List<DeterministicCaptureRecord>,
  val loadedAssetSha256: Map<String, String>,
  val locale: String,
  val timezone: String,
  val formattedDates: Map<String, String>,
  val captureBackend: String = "skia-raster",
  val renderer: String = "iOS Skia offscreen raster; not UIKit/Metal",
  val composeVersion: String = "1.11.1",
  val frameIntervalMs: Int = 16,
  val millisecondsPerScenario: Int = 2_000,
  val dispatcher: String = "Explicit UnconfinedTestDispatcher, matching preserved Android harness",
  val springPolicy: String = "Original spring animator; no target-scene or reduced-motion bypass",
  val androidReferenceArchiveSha256: String = "b12f2e9e119de948e22017524a9b49fcc0cc144bdaaff26bc00bec79df45f8bf",
  val appearanceParityVerified: Boolean = false,
)
