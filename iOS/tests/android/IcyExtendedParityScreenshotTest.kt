package com.icy.lyrics.parity

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.ui.IcyParityFixtureScreen
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in suite. The original twenty fixtures and their capture clocks are untouched. */
@RunWith(AndroidJUnit4::class)
class IcyExtendedParityScreenshotTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val cases = listOf(
    ExtendedCase("portrait-expanded", "portrait", "Collapse lyrics"),
    ExtendedCase("settings-fullscreen", "settings", "Fullscreen", 2),
    ExtendedCase("settings-sources", "settings", "Lyric sources", 3),
    ExtendedCase("settings-troubleshooting", "settings", "Troubleshooting", 4),
    ExtendedCase("settings-privacy", "settings", "Privacy", 5),
    ExtendedCase("token-consent", "settings", "Allow token sharing?", 3, dialog = true),
    ExtendedCase("legal-lower", "legal", "Online policies", 3),
    ExtendedCase("legal-agpl", "legal", "GNU AGPL v3 or later", 2, dialog = true),
    ExtendedCase("legal-agpl-scrolled", "legal", "GNU AGPL v3 or later", 2, dialog = true, scrollDialog = true),
    ExtendedCase("legal-third-party", "legal", "Third-party notices", 3, dialog = true),
    ExtendedCase("legal-third-party-scrolled", "legal", "Third-party notices", 3, dialog = true, scrollDialog = true),
  )

  @Test fun captureExtendedSurfaces() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue("Extended captures are explicitly opt-in", arguments.getString("extendedRunId") != null)
    val requested = arguments.getString("extendedCase")
    val selected = if (requested == null) cases else listOf(requireNotNull(cases.find { it.id == requested }))
    val runId = requireNotNull(arguments.getString("extendedRunId")) { "Use the separate extended capture script" }
    require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")))
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "parity/extended-v1/$runId")
    check(!directory.exists()) { "Extended capture run already exists; evidence will not be overwritten" }
    check(directory.mkdirs())
    val automation = instrumentation.uiAutomation
    val priorServiceInfo = automation.serviceInfo
    automation.serviceInfo = automation.serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    val completed = JSONArray()
    fun manifest(complete: Boolean) = File(directory, "manifest.json").writeText(JSONObject()
      .put("schemaVersion", 1).put("suite", "extended-v1").put("runId", runId)
      .put("complete", complete).put("caseOrder", JSONArray(selected.map { it.id }))
      .put("captures", completed).put("appearanceParityVerified", false)
      .put("originalTwentyFixturesModified", false).toString(2))
    manifest(false)
    try {
      check(compose.activity.resources.displayMetrics.widthPixels < compose.activity.resources.displayMetrics.heightPixels) {
        "Extended-v1 requires an owned portrait viewport"
      }
      compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
      var current by mutableStateOf(selected.first())
      val observed = AtomicReference<ComposeCaptureMetrics>()
      compose.mainClock.autoAdvance = false
      compose.setContent {
        CaptureComposeMetrics { observed.set(it) }
        key(current.id) { ExtendedFixtureHost(current.base) }
      }
      for ((caseIndex, case) in selected.withIndex()) {
        val actions = JSONArray()
        compose.runOnUiThread { current = case }
        settle()
        actions.put(JSONObject().put("action", "openExistingFixture").put("fixture", case.base)
          .put("clockTimeMillis", compose.mainClock.currentTime))
        if (case.sectionIndex != null) {
          compose.onNode(hasScrollToIndexAction()).performScrollToIndex(case.sectionIndex)
          settle()
          actions.put(JSONObject().put("action", "scrollLazyColumnToIndex").put("index", case.sectionIndex)
            .put("clockTimeMillis", compose.mainClock.currentTime))
        }
        when {
          case.id == "portrait-expanded" -> clickDescription("Expand lyrics", actions)
          case.id == "token-consent" -> {
            // The original Row has no Semantics node: its three switches share
            // a card parent. Disambiguate using the measured label/description
            // vertical span, then click the unique semantic control (no fixed tap coordinates).
            val rowWithSpicyLabel = hasAnyDescendant(hasText("Spicy Lyrics"))
            val labelBounds = compose.onNodeWithText("Spicy Lyrics", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val descriptionBounds = compose.onNodeWithText("Experimental provider using a connected Spotify session.", useUnmergedTree = true)
              .fetchSemanticsNode().boundsInRoot
            val switch = compose.onAllNodes(isToggleable() and hasParent(rowWithSpicyLabel), useUnmergedTree = true)
              .fetchSemanticsNodes().single { it.boundsInRoot.center.y in labelBounds.top..descriptionBounds.bottom }
            compose.onNode(SemanticsMatcher("Spicy Lyrics row switch") { it.id == switch.id }, useUnmergedTree = true).performClick()
            settle()
            actions.put(JSONObject().put("action", "clickSwitchInRow").put("rowText", "Spicy Lyrics")
              .put("rowTextVerticalSpanPx", JSONArray(listOf(labelBounds.top, descriptionBounds.bottom)))
              .put("switchBoundsInRootPx", JSONArray(listOf(switch.boundsInRoot.left, switch.boundsInRoot.top,
                switch.boundsInRoot.right, switch.boundsInRoot.bottom)))
              .put("clockTimeMillis", compose.mainClock.currentTime))
          }
          case.id.startsWith("legal-agpl") -> clickText("Read the full license offline", actions)
          case.id.startsWith("legal-third-party") -> clickText("Read third-party notices offline", actions)
        }
        if (case.scrollDialog) {
          val dialogScroll = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true)
          val before = dialogScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
          val distance = 360f * requireNotNull(observed.get()).density
          dialogScroll.performSemanticsAction(SemanticsActions.ScrollBy) { check(it(0f, distance)) }
          settle()
          val after = dialogScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
          check(after > before) { "Legal document did not actually scroll" }
          actions.put(JSONObject().put("action", "scrollDialog").put("requestedDistanceDp", 360)
            .put("requestedDistancePx", distance).put("beforePx", before).put("afterPx", after)
            .put("clockTimeMillis", compose.mainClock.currentTime))
        }
        val anchor = if (case.id == "portrait-expanded") compose.onNodeWithContentDescription(case.anchor)
          else compose.onNodeWithText(case.anchor)
        anchor.assertIsDisplayed()
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val dialogs = compose.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes()
        check(!case.dialog || (dialogs.isNotEmpty() && roots.size >= 2)) { "Dialog must have its own Compose window" }
        instrumentation.waitForIdleSync()
        // UiAutomation composites all app/dialog windows and their dimming backdrop.
        // It also contains real system bars, whose bounds are explicitly recorded.
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Whole-display screenshot unavailable" }
        try {
          val metrics = requireNotNull(observed.get())
          val display = compose.activity.resources.displayMetrics
          check(bitmap.width == display.widthPixels && bitmap.height == display.heightPixels)
          val windows = JSONArray(automation.windows.map { window ->
            val bounds = Rect().also(window::getBoundsInScreen)
            JSONObject().put("type", window.type).put("layer", window.layer)
              .put("boundsPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
              .put("title", window.title?.toString()).put("active", window.isActive).put("focused", window.isFocused)
          })
          val location = IntArray(2)
          val decor = compose.activity.window.decorView
          compose.runOnUiThread { decor.getLocationOnScreen(location) }
          val insets = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or
            android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
          val record = metrics.asJson().put("schemaVersion", 1).put("suite", "extended-v1")
            .put("caseId", case.id).put("baseFixture", case.base).put("caseIndex", caseIndex)
            .put("caseOrder", JSONArray(selected.map { it.id })).put("runId", runId)
            .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
            .put("captureSurface", "UiAutomation whole display; app, dialog, dimming and real system bars; no crop or resize")
            .put("clockTimeMillis", compose.mainClock.currentTime).put("settleAfterEachActionMillis", 2_000)
            .put("actions", actions).put("anchor", case.anchor).put("dialogExpected", case.dialog)
            .put("composeRootCount", roots.size).put("dialogRootCount", dialogs.size)
            .put("activityWindowBoundsPx", JSONArray(listOf(location[0], location[1], location[0] + decor.width, location[1] + decor.height)))
            .put("osSafeDrawingInsetsPx", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
            .put("windows", windows).put("semantics", semanticsJson())
            .put("appearanceParityVerified", false)
          val png = File(directory, "${case.id}.png")
          png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          record.put("pngBytes", png.length()).put("pngSha256", sha256(png.readBytes()))
          File(directory, "${case.id}.json").writeText(record.toString(2))
          completed.put(JSONObject().put("caseId", case.id).put("pngSha256", record.getString("pngSha256")))
          manifest(false)
          println("Captured extended-v1 ${case.id}: ${bitmap.width}x${bitmap.height}, ${roots.size} roots, ${dialogs.size} dialogs")
        } finally { bitmap.recycle() }
        if (case.dialog) {
          clickText(if (case.id == "token-consent") "Cancel" else "Close", actions)
          compose.onAllNodes(isDialog()).assertCountEquals(0)
        }
      }
      manifest(true)
    } finally { automation.serviceInfo = priorServiceInfo }
  }

  private fun settle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
  private fun clickText(text: String, actions: JSONArray) {
    compose.onNodeWithText(text).performClick(); settle()
    actions.put(JSONObject().put("action", "clickText").put("text", text).put("clockTimeMillis", compose.mainClock.currentTime))
  }
  private fun clickDescription(description: String, actions: JSONArray) {
    compose.onNodeWithContentDescription(description).performClick(); settle()
    actions.put(JSONObject().put("action", "clickContentDescription").put("description", description)
      .put("clockTimeMillis", compose.mainClock.currentTime))
  }
  private fun semanticsJson(): JSONArray = JSONArray(compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
    .fetchSemanticsNodes().mapNotNull { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }
      val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("\n")
      val scroll = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
      if (text == null && description == null && scroll == null) null else {
        val bounds = node.boundsInWindow
        JSONObject().put("textPrefix", text?.take(180)).put("textLength", text?.length)
          .put("contentDescription", description)
          .put("boundsInWindowPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
          .also { if (scroll != null) it.put("scrollValue", scroll.value()).put("scrollMaxValue", scroll.maxValue()) }
      }
    })
  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private data class ExtendedCase(val id: String, val base: String, val anchor: String,
    val sectionIndex: Int? = null, val dialog: Boolean = false, val scrollDialog: Boolean = false)
}

@Composable private fun ExtendedFixtureHost(baseId: String) {
  val platform = rememberAndroidIcyUiPlatform()
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) { IcyParityFixtureScreen(baseId) }
}
