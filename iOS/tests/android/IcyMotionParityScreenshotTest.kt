package com.icy.lyrics.parity

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.ui.*
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Original production animations, one composition per side, opt-in and separate from all stills. */
@RunWith(AndroidJUnit4::class)
class IcyMotionParityScreenshotTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @Test fun captureLeft() = capture(MixedMediaSide.LEFT)
  @Test fun captureRight() = capture(MixedMediaSide.RIGHT)

  private fun capture(side: MixedMediaSide) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue("Motion captures are explicitly opt-in", arguments.getString("motionRunId") != null)
    val runId = requireNotNull(arguments.getString("motionRunId"))
    require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")))
    val sourceIdentity = JSONObject()
    sourceIdentity.put("textHashEncoding", "utf8-lf")
    for (name in listOf("referenceSourceManifestSha256", "motionFixtureSourceSha256", "fixtureDataSourceSha256")) {
      val hash = requireNotNull(arguments.getString(name))
      require(hash.matches(Regex("[0-9a-f]{64}")))
      sourceIdentity.put(name, hash)
    }
    val sourceAssets = JSONObject(String(android.util.Base64.decode(
      requireNotNull(arguments.getString("sourceAssetSha256Base64")), android.util.Base64.DEFAULT), Charsets.UTF_8))
    val systemFonts = JSONObject(String(android.util.Base64.decode(
      requireNotNull(arguments.getString("androidSystemFontSha256Base64")), android.util.Base64.DEFAULT), Charsets.UTF_8))
    val plan = IcyMixedLyricsMotionPlan
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "parity/motion-v1/$runId/${side.name.lowercase()}")
    check(!directory.exists() && directory.mkdirs()) { "Motion evidence already exists" }
    val frames = JSONArray()
    val actions = JSONArray()
    val compositions = JSONArray()
    var mounts = 0
    var disposals = 0
    var observedMode: LandscapeMode? = null
    var lastModeCompositionClockMs = 0L
    var lastFrameTimeNanos = 0L
    var initialSettleEndClockMs = 0L
    val metrics = AtomicReference<ComposeCaptureMetrics>()
    val hashes = mutableSetOf<String>()
    val original = IcyParityFixtures.state("landscape-mixed")
    val base = original.copy(settings = original.settings.copy(mixedMediaSide = side))
    val snapshot = requireNotNull(base.nowPlaying)
    check(!snapshot.isPlaying && snapshot.playbackSpeed == 0f)
    check(base.settings.backgroundStyle == BackgroundStyle.STATIC_BLURRED)
    var state by mutableStateOf(base)

    fun manifest(complete: Boolean) = File(directory, "manifest.json").writeText(JSONObject()
      .put("schemaVersion", 1).put("suite", plan.ID).put("complete", complete).put("runId", runId)
      .put("sourceIdentity", sourceIdentity)
      .put("sourceAssetSha256", sourceAssets)
      .put("androidSystemFontSha256", systemFonts)
      .put("profile", JSONObject().put("id", "android36-420dpi-landscape-v1").put("orientation", "landscape")
        .put("widthPx", 2400).put("heightPx", 1080).put("density", 2.625).put("fontScale", 1)
        .put("safeDrawingInsetsPx", JSONArray(listOf(0, 63, 0, 63)))
        .put("safeDrawingInsetsDp", JSONArray(listOf(0, 24, 0, 24))))
      .put("mediaSide", side.name).put("sequenceOrder", JSONArray(plan.sequenceIds))
      .put("actions", actions).put("frames", frames).put("compositions", compositions)
      .put("fixtureMounts", mounts).put("fixtureDisposals", disposals)
      .put("initialSettleEndClockMs", initialSettleEndClockMs)
      .put("frameIntervalMs", plan.FRAME_INTERVAL_MS).put("primeFrames", plan.PRIME_FRAMES)
      .put("fullSampleOffsetsMs", JSONArray(plan.completeOffsetsMs))
      .put("interruptedSampleOffsetsMs", JSONArray(plan.interruptedOffsetsMs))
      .put("backgroundStyle", "STATIC_BLURRED").put("reducedMotion", false)
      .put("fixedFrameTimeNanos", JSONObject.NULL)
      .put("captureBackend", "Android Compose root / PixelCopy")
      .put("systemClockPolicy", "Original Android SystemClock unchanged; paused zero-speed snapshot fixes playback position; Compose mainClock controls original springs")
      .put("appearanceParityVerified", false).toString(2))
    manifest(false)
    compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    check(compose.activity.resources.displayMetrics.widthPixels > compose.activity.resources.displayMetrics.heightPixels)
    compose.mainClock.autoAdvance = false
    compose.mainClock.advanceTimeByFrame()
    check(compose.mainClock.currentTime == plan.FRAME_INTERVAL_MS)
    compose.setContent {
      CaptureComposeMetrics { metrics.set(it) }
      val renderedMode = state.landscapeMode
      SideEffect {
        if (observedMode != renderedMode) {
          observedMode = renderedMode
          lastModeCompositionClockMs = compose.mainClock.currentTime
          compositions.put(JSONObject().put("mode", renderedMode.name).put("clockMs", lastModeCompositionClockMs))
        }
      }
      LaunchedEffect(Unit) { while (isActive) withFrameNanos { lastFrameTimeNanos = it } }
      MotionFixtureHost(state,
        onStep = { step -> state = state.copy(landscapeMode = state.landscapeMode.step(step)) },
        onEnter = { mounts++ }, onLeave = { disposals++ })
    }
    compose.mainClock.advanceTimeBy(plan.INITIAL_SETTLE_MS)
    compose.waitForIdle()
    initialSettleEndClockMs = compose.mainClock.currentTime

    fun frame(sequence: String, phase: String, target: LandscapeMode, actionClock: Long, primedClock: Long?, offset: Long?) {
      val clock = compose.mainClock.currentTime
      compose.waitForIdle()
      check(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1)
      check(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
      val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
      check(compose.mainClock.currentTime == clock) { "Capture advanced the controlled clock" }
      check(mounts == 1 && disposals == 0) { "Motion fixture was recreated" }
      check(state.landscapeMode == observedMode)
      check(state == base.copy(landscapeMode = state.landscapeMode))
      check(state.nowPlaying === snapshot && state.lyrics === base.lyrics)
      check(bitmap.width == 2400 && bitmap.height == 1080)
      val viewport = requireNotNull(metrics.get())
      check(viewport.density == 2.625f && viewport.fontScale == 1f && viewport.insets == listOf(0, 63, 0, 63))
      check(lastFrameTimeNanos > 0 && lastFrameTimeNanos <= clock * 1_000_000L)
      val id = "${frames.length().toString().padStart(2, '0')}-$sequence-$phase"
      val png = File(directory, "$id.png")
      png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      val hash = MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) }
      val osInsets = compose.activity.window.decorView.rootWindowInsets.getInsets(
        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
      val record = viewport.asJson().put("schemaVersion", 1).put("suite", plan.ID)
        .put("id", id).put("frameIndex", frames.length()).put("sequence", sequence).put("phase", phase)
        .put("requestedOffsetFromPrimedMs", offset ?: JSONObject.NULL)
        .put("targetMode", target.name).put("composedMode", state.landscapeMode.name).put("mediaSide", side.name)
        .put("trackKey", snapshot.identity.exactStorageKey).put("rawPositionMs", snapshot.positionMs).put("playbackSpeed", snapshot.playbackSpeed)
        .put("playbackState", snapshot.playbackState).put("isPlaying", snapshot.isPlaying)
        .put("actionClockMs", actionClock).put("primedClockMs", primedClock ?: JSONObject.NULL).put("captureClockMs", clock)
        .put("lastModeCompositionClockMs", lastModeCompositionClockMs).put("lastFrameTimeNanos", lastFrameTimeNanos)
        .put("widthPx", bitmap.width).put("heightPx", bitmap.height).put("pngBytes", png.length()).put("pngSha256", hash)
        .put("fixtureMounts", mounts).put("fixtureDisposals", disposals)
        .put("captureSurface", "Single Compose root; no overlay/dialog; no crop or resize")
        .put("composeRootCount", 1).put("dialogRootCount", 0)
        .put("osSafeDrawingInsetsPx", JSONArray(listOf(osInsets.left, osInsets.top, osInsets.right, osInsets.bottom)))
        .put("appearanceParityVerified", false)
      File(directory, "$id.json").writeText(record.toString(2))
      frames.put(record); hashes += hash; manifest(false)
    }

    fun transition(sequence: String, target: LandscapeMode, offsets: List<Long>, interrupts: String? = null) {
      val source = state.landscapeMode
      check(source != target)
      val actionClock = compose.mainClock.currentTime
      frame(sequence, "before", target, actionClock, null, null)
      val description = if (target == LandscapeMode.LYRICS) "Next fullscreen mode" else "Previous fullscreen mode"
      actions.put(JSONObject().put("sequence", sequence).put("fromMode", source.name).put("targetMode", target.name)
        .put("clockMs", actionClock).put("semanticControl", description).put("interruptsSequence", interrupts ?: JSONObject.NULL))
      // Skiko performClick invokes OnClick directly. Use the same production semantic action,
      // avoiding Android performClick's additional pointer press/release gesture and ripple.
      compose.onNodeWithContentDescription(description).assertIsEnabled()
        .performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
      check(compose.mainClock.currentTime == actionClock && state.landscapeMode == target)
      repeat(plan.PRIME_FRAMES) { compose.mainClock.advanceTimeByFrame() }
      val primedClock = compose.mainClock.currentTime
      check(primedClock - actionClock == plan.PRIME_FRAMES * plan.FRAME_INTERVAL_MS)
      for (offset in offsets) {
        val remaining = primedClock + offset - compose.mainClock.currentTime
        check(remaining >= 0)
        if (remaining > 0) compose.mainClock.advanceTimeBy(remaining)
        check(compose.mainClock.currentTime == primedClock + offset)
        frame(sequence, "t${offset.toString().padStart(4, '0')}", target, actionClock, primedClock, offset)
      }
    }
    transition(plan.sequenceIds[0], LandscapeMode.LYRICS, plan.completeOffsetsMs)
    transition(plan.sequenceIds[1], LandscapeMode.MIXED, plan.completeOffsetsMs)
    transition(plan.sequenceIds[2], LandscapeMode.LYRICS, plan.interruptedOffsetsMs)
    transition(plan.sequenceIds[3], LandscapeMode.MIXED, plan.completeOffsetsMs, plan.sequenceIds[2])
    check(frames.length() == plan.EXPECTED_FRAMES_PER_SIDE && mounts == 1 && disposals == 0)
    check((0 until compositions.length()).map { compositions.getJSONObject(it).getString("mode") } ==
      listOf("MIXED", "LYRICS", "MIXED", "LYRICS", "MIXED"))
    check(state.landscapeMode == LandscapeMode.MIXED && hashes.size > 1)
    manifest(true)
  }
}

@Composable private fun MotionFixtureHost(state: IcyLyricsUiState, onStep: (Int) -> Unit, onEnter: () -> Unit, onLeave: () -> Unit) {
  val platform = rememberAndroidIcyUiPlatform()
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
    IcyMotionFixtureScreen(state, onStep, onEnter, onLeave)
  }
}
