@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.icy.lyrics.ui.BackgroundStyle
import com.icy.lyrics.ui.IcyMixedLyricsMotionPlan
import com.icy.lyrics.ui.IcyMotionFixtureScreen
import com.icy.lyrics.ui.IcyParityFixtures
import com.icy.lyrics.ui.IcyUiPlatform
import com.icy.lyrics.ui.LandscapeMode
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.MixedMediaSide
import kotlinx.coroutines.isActive
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Opt-in raster evidence. No original capture is replaced and no Android parity is assumed. */
class DeterministicIosMotionCaptureTest {
  @Test fun mixedLyricsMediaLeft() = captureMotion(MixedMediaSide.LEFT)
  @Test fun mixedLyricsMediaRight() = captureMotion(MixedMediaSide.RIGHT)

  private fun captureMotion(mediaSide: MixedMediaSide) = runAndroidReferenceUiTest(AndroidReferenceProfile.Landscape) {
    val profile = AndroidReferenceProfile.Landscape
    val plan = IcyMixedLyricsMotionPlan
    val assets = DeterministicFixtureAssets()
    val directory = assets.outputRoot / plan.ID / profile.id / mediaSide.name.lowercase()
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val records = mutableListOf<MotionFrameRecord>()
    val actions = mutableListOf<MotionActionRecord>()
    val compositions = mutableListOf<MotionCompositionRecord>()
    var mounts = 0
    var disposals = 0
    var observed: ObservedViewport? = null
    var observedMode: LandscapeMode? = null
    var lastFrameTimeNanos = 0L
    var lastModeCompositionClockMs = 0L
    var initialSettleEndMs = 0L
    var sourceIdentity = emptyMap<String, String>()
    var sourceAssets = emptyMap<String, String>()

    val original = IcyParityFixtures.state("landscape-mixed")
    val base = original.copy(settings = original.settings.copy(mixedMediaSide = mediaSide))
    val snapshot = assertNotNull(base.nowPlaying)
    assertTrue(!snapshot.isPlaying)
    assertEquals(0f, snapshot.playbackSpeed)
    assertEquals(BackgroundStyle.STATIC_BLURRED, base.settings.backgroundStyle)
    var state by mutableStateOf(base)

    fun writeManifest(complete: Boolean) = assets.write(
      directory / "manifest.json",
      json.encodeToString(MotionCaptureManifest(
        profile = profile, mediaSide = mediaSide.name, complete = complete,
        sequenceOrder = plan.sequenceIds, actions = actions.toList(), frames = records.toList(),
        compositions = compositions.toList(), fixtureMounts = mounts, fixtureDisposals = disposals,
        initialSettleEndClockMs = initialSettleEndMs,
        sourceIdentity = sourceIdentity, sourceAssetSha256 = sourceAssets,
        loadedAssetSha256 = assets.loadedAssetSha256.toMap(), locale = assets.locale, timezone = assets.timezone,
      )).encodeToByteArray(),
    )
    // A failed/interrupted execution must not expose an old complete manifest.
    writeManifest(complete = false)
    fun environmentMap(name: String): Map<String, String> = json.decodeFromString(
      assertNotNull(getenv(name)?.toKString(), "$name must be supplied by the opt-in Gradle task"),
    )
    sourceIdentity = environmentMap("ICY_MOTION_SOURCE_IDENTITY")
    sourceAssets = environmentMap("ICY_MOTION_FONT_HASHES")
    assertEquals("utf8-lf", sourceIdentity["textHashEncoding"])
    assertEquals(4, sourceIdentity.size)
    assertTrue(sourceAssets.isNotEmpty())
    assets.verifyOriginalFontBytes()
    for ((path, hash) in sourceAssets) assertEquals(hash, assets.loadedAssetSha256[path], "Font input changed: $path")
    mainClock.autoAdvance = false
    mainClock.advanceTimeByFrame()
    assertTrue(mainClock.currentTime > 0L)
    val native = assets.platform()
    val platform = object : IcyUiPlatform by native {
      override val fixedFrameTimeNanos: Long? = null
      override fun monotonicTimeMs(): Long = mainClock.currentTime
      override fun monotonicTimeNanos(): Long = mainClock.currentTime * 1_000_000L
      @Composable override fun ReducedMotionEnabled(): Boolean = false
    }

    setContent {
      val density = LocalDensity.current
      val direction = LocalLayoutDirection.current
      val safeDrawing = WindowInsets.safeDrawing
      val renderedMode = state.landscapeMode
      SideEffect {
        observed = ObservedViewport(density.density, density.fontScale, listOf(
          safeDrawing.getLeft(density, direction), safeDrawing.getTop(density),
          safeDrawing.getRight(density, direction), safeDrawing.getBottom(density),
        ))
        if (observedMode != renderedMode) {
          observedMode = renderedMode
          lastModeCompositionClockMs = mainClock.currentTime
          compositions += MotionCompositionRecord(renderedMode.name, mainClock.currentTime)
        }
      }
      // Observe actual frame timestamps without replacing the production animation clock.
      LaunchedEffect(Unit) {
        while (isActive) withFrameNanos { lastFrameTimeNanos = it }
      }
      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
        // No key, setContent replacement, or app relaunch between captured states.
        IcyMotionFixtureScreen(
          state = state,
          onStepLandscape = { step -> state = state.copy(landscapeMode = state.landscapeMode.step(step)) },
          onEnterComposition = { mounts++ },
          onLeaveComposition = { disposals++ },
        )
      }
    }
    mainClock.advanceTimeBy(plan.INITIAL_SETTLE_MS)
    waitForIdle()
    initialSettleEndMs = mainClock.currentTime

    fun captureFrame(sequence: String, phase: String, target: LandscapeMode, actionClockMs: Long,
                     primedClockMs: Long?, offsetMs: Long?) {
      val clockMs = mainClock.currentTime
      waitForIdle()
      val bitmap = captureToImage()
      assertEquals(clockMs, mainClock.currentTime, "Capture advanced the controlled clock")
      assertEquals(1, mounts, "The motion fixture was recreated")
      assertEquals(0, disposals, "The motion fixture left composition")
      assertEquals(state.landscapeMode, observedMode, "Captured mode has not composed")
      assertEquals(base.copy(landscapeMode = state.landscapeMode), state, "Only landscape mode may change")
      assertSame(snapshot, state.nowPlaying)
      assertSame(base.lyrics, state.lyrics)
      assertEquals(profile.widthPx, bitmap.width)
      assertEquals(profile.heightPx, bitmap.height)
      val viewport = assertNotNull(observed)
      assertEquals(profile.density, viewport.density)
      assertEquals(profile.fontScale, viewport.fontScale)
      assertEquals(profile.safeDrawingInsetsPx, viewport.safeDrawingInsetsPx)
      assertTrue(lastFrameTimeNanos > 0L)
      assertTrue(lastFrameTimeNanos <= clockMs * 1_000_000L)

      val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
      val png = try {
        val encoded = assertNotNull(image.encodeToData(EncodedImageFormat.PNG))
        try { encoded.bytes } finally { encoded.close() }
      } finally { image.close() }
      val name = "${records.size.toString().padStart(2, '0')}-$sequence-$phase"
      val record = MotionFrameRecord(
        id = name, frameIndex = records.size, sequence = sequence, phase = phase,
        requestedOffsetFromPrimedMs = offsetMs, targetMode = target.name, composedMode = state.landscapeMode.name,
        mediaSide = mediaSide.name, trackKey = snapshot.identity.exactStorageKey,
        rawPositionMs = snapshot.positionMs, playbackSpeed = snapshot.playbackSpeed,
        playbackState = snapshot.playbackState, isPlaying = snapshot.isPlaying,
        actionClockMs = actionClockMs, primedClockMs = primedClockMs, captureClockMs = clockMs,
        lastModeCompositionClockMs = lastModeCompositionClockMs, lastFrameTimeNanos = lastFrameTimeNanos,
        widthPx = bitmap.width, heightPx = bitmap.height, density = viewport.density, fontScale = viewport.fontScale,
        safeDrawingInsetsPx = viewport.safeDrawingInsetsPx, pngBytes = png.size,
        pngSha256 = png.toByteString().sha256().hex(), fixtureMounts = mounts, fixtureDisposals = disposals,
      )
      assets.write(directory / "$name.png", png)
      assets.write(directory / "$name.json", json.encodeToString(record).encodeToByteArray())
      records += record
      writeManifest(complete = false)
      println("iOS motion ${mediaSide.name}: $name at ${clockMs}ms, ${record.composedMode}")
    }

    fun transition(sequence: String, target: LandscapeMode, offsetsMs: List<Long>, interrupts: String? = null) {
      val source = state.landscapeMode
      assertTrue(source != target)
      val actionClock = mainClock.currentTime
      captureFrame(sequence, "before", target, actionClock, null, null)
      val description = if (target == LandscapeMode.LYRICS) "Next fullscreen mode" else "Previous fullscreen mode"
      actions += MotionActionRecord(sequence, source.name, target.name, actionClock, description, interrupts)
      // iOS performClick synthesizes a touch and advances one frame. Use the
      // same production OnClick action as the preserved Android motion lane.
      onNodeWithContentDescription(description).assertIsEnabled()
        .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it(), "The production control rejected its action") }
      assertEquals(actionClock, mainClock.currentTime, "Semantic click advanced the controlled clock")
      assertEquals(target, state.landscapeMode, "The production edge control did not update mode")
      repeat(plan.PRIME_FRAMES) { mainClock.advanceTimeByFrame() }
      val primedClock = mainClock.currentTime
      assertEquals(plan.PRIME_FRAMES * plan.FRAME_INTERVAL_MS, primedClock - actionClock)
      for (offset in offsetsMs) {
        val remaining = primedClock + offset - mainClock.currentTime
        assertTrue(remaining >= 0L)
        if (remaining > 0) mainClock.advanceTimeBy(remaining)
        assertEquals(primedClock + offset, mainClock.currentTime)
        captureFrame(sequence, "t${offset.toString().padStart(4, '0')}", target, actionClock, primedClock, offset)
      }
    }

    transition(plan.sequenceIds[0], LandscapeMode.LYRICS, plan.completeOffsetsMs)
    transition(plan.sequenceIds[1], LandscapeMode.MIXED, plan.completeOffsetsMs)
    transition(plan.sequenceIds[2], LandscapeMode.LYRICS, plan.interruptedOffsetsMs)
    transition(plan.sequenceIds[3], LandscapeMode.MIXED, plan.completeOffsetsMs, interrupts = plan.sequenceIds[2])

    assertEquals(plan.sequenceIds, actions.map { it.sequence })
    assertEquals(listOf("MIXED", "LYRICS", "MIXED", "LYRICS", "MIXED"), compositions.map { it.mode })
    assertEquals(plan.EXPECTED_FRAMES_PER_SIDE, records.size)
    assertEquals(1, mounts)
    assertEquals(0, disposals)
    assertEquals(LandscapeMode.MIXED, state.landscapeMode)
    assertTrue(records.map { it.pngSha256 }.distinct().size > 1, "Every captured frame was identical")
    writeManifest(complete = true)
  }
}

@Serializable internal data class MotionActionRecord(
  val sequence: String, val fromMode: String, val targetMode: String, val clockMs: Long,
  val semanticControl: String, val interruptsSequence: String? = null,
)

@Serializable internal data class MotionCompositionRecord(val mode: String, val clockMs: Long)

@Serializable internal data class MotionFrameRecord(
  val id: String, val frameIndex: Int, val sequence: String, val phase: String,
  val requestedOffsetFromPrimedMs: Long?, val targetMode: String, val composedMode: String,
  val mediaSide: String, val trackKey: String, val rawPositionMs: Long, val playbackSpeed: Float,
  val playbackState: Int, val isPlaying: Boolean,
  val actionClockMs: Long, val primedClockMs: Long?, val captureClockMs: Long,
  val lastModeCompositionClockMs: Long, val lastFrameTimeNanos: Long,
  val widthPx: Int, val heightPx: Int, val density: Float, val fontScale: Float,
  val safeDrawingInsetsPx: List<Int>, val pngBytes: Int, val pngSha256: String,
  val fixtureMounts: Int, val fixtureDisposals: Int,
)

@Serializable internal data class MotionCaptureManifest(
  val profile: AndroidReferenceProfile, val mediaSide: String, val complete: Boolean,
  val sequenceOrder: List<String>, val actions: List<MotionActionRecord>, val frames: List<MotionFrameRecord>,
  val compositions: List<MotionCompositionRecord>, val fixtureMounts: Int, val fixtureDisposals: Int,
  val initialSettleEndClockMs: Long, val loadedAssetSha256: Map<String, String>,
  val sourceIdentity: Map<String, String>, val sourceAssetSha256: Map<String, String>,
  val locale: String, val timezone: String,
  val suite: String = IcyMixedLyricsMotionPlan.ID,
  val schemaVersion: Int = 1,
  val captureBackend: String = "skia-raster",
  val renderer: String = "iOS offscreen raster; not UIKit/Metal",
  val composeVersion: String = "1.11.1",
  val frameIntervalMs: Long = IcyMixedLyricsMotionPlan.FRAME_INTERVAL_MS,
  val primeFrames: Int = IcyMixedLyricsMotionPlan.PRIME_FRAMES,
  val fullSampleOffsetsMs: List<Long> = IcyMixedLyricsMotionPlan.completeOffsetsMs,
  val interruptedSampleOffsetsMs: List<Long> = IcyMixedLyricsMotionPlan.interruptedOffsetsMs,
  val fixedFrameTimeNanos: Long? = null,
  val backgroundStyle: String = "STATIC_BLURRED",
  val reducedMotion: Boolean = false,
  val androidReferenceStatus: String = "44 preserved-Android reference frames captured; exact same-sequence iOS comparison pending",
  val appearanceParityVerified: Boolean = false,
)
