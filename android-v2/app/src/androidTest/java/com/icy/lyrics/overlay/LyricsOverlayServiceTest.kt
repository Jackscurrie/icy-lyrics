package com.icy.lyrics.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Emulator-only shell permission setup; no notification/Spotify credentials or playback changes. */
@RunWith(AndroidJUnit4::class)
class LyricsOverlayServiceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test fun floatingWindowExpandsCollapsesAndStopsWithoutActivityLifecycleOwners() {
    val automation = instrumentation.uiAutomation
    automation.serviceInfo = automation.serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    val saved = context.getSharedPreferences(OverlayPreferences.FILE, Context.MODE_PRIVATE)
    val originalPreferences = saved.all.toMap()
    val originalAppOp = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)")
      .find(shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
    shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    assertTrue(Settings.canDrawOverlays(context))
    OverlayPreferences(context).use { it.setEnabled(false) }
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      try {
        scenario.onActivity { assertTrue(LyricsOverlayService.start(it)) }
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue(waitUntil { node("Open floating Icy Lyrics") == null })
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        assertTrue(click("Open floating Icy Lyrics"))
        assertTrue(waitUntil { node("Collapse to floating button") != null })
        assertTrue(click("Collapse to floating button"))
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        assertTrue(click("Open floating Icy Lyrics"))
        assertTrue(waitUntil { node("Turn off lyrics overlay") != null })
        assertTrue(click("Turn off lyrics overlay"))
        assertTrue(waitUntil { node("Open floating Icy Lyrics") == null && node("Turn off lyrics overlay") == null })
        OverlayPreferences(context).use { assertFalse(it.options.value.enabled) }
        scenario.onActivity { assertTrue(LyricsOverlayService.start(it)) }
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
        assertTrue(waitUntil {
          node("Open floating Icy Lyrics") == null &&
            !saved.getBoolean("enabled", true)
        })
      } finally {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        LyricsOverlayService.stop(context)
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $originalAppOp")
        saved.edit().clear().apply {
          originalPreferences.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) }
        }.commit()
      }
    }
  }

  @Test fun floatingButtonIsSlightlySmallerAndPanelResizesOnlyVerticallyAcrossCollapse() {
    val automation = instrumentation.uiAutomation
    automation.serviceInfo = automation.serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    val saved = context.getSharedPreferences(OverlayPreferences.FILE, Context.MODE_PRIVATE)
    val originalPreferences = saved.all.toMap()
    val originalAppOp = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)")
      .find(shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")
    shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    assertTrue(Settings.canDrawOverlays(context))
    OverlayPreferences(context).use { it.setEnabled(false) }
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      try {
        scenario.onActivity { assertTrue(LyricsOverlayService.start(it)) }
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        val expectedBubbleSide = (56 * context.resources.displayMetrics.density).roundToInt()
        val bubble = windowBounds("Open floating Icy Lyrics")!!
        assertEquals(expectedBubbleSide, bubble.width())
        assertEquals(expectedBubbleSide, bubble.height())

        assertTrue(click("Open floating Icy Lyrics"))
        assertTrue(waitUntil { node("Resize lyrics window height") != null })
        val original = settledWindowBounds("Resize lyrics window height")
        val shrinkBy = (96 * context.resources.displayMetrics.density).roundToInt()
        swipeHandle(deltaX = 0, deltaY = -shrinkBy)
        assertTrue("Dragging the corner upwards must shorten the real overlay window", waitUntil {
          windowBounds("Resize lyrics window height")?.height()?.let {
            it < original.height() - shrinkBy / 2
          } == true
        })
        val resized = settledWindowBounds("Resize lyrics window height")
        assertEquals("Resizing must not move the left edge", original.left, resized.left)
        assertEquals("Resizing must not move the top edge", original.top, resized.top)
        assertEquals("Resizing must not change the width", original.width(), resized.width())

        swipeHandle(deltaX = -shrinkBy, deltaY = 0)
        assertEquals("Horizontal dragging must not resize or move the panel", resized,
          settledWindowBounds("Resize lyrics window height"))

        swipeHandle(deltaX = 0, deltaY = shrinkBy / 2)
        assertTrue("Dragging down must grow the overlay again", waitUntil {
          windowBounds("Resize lyrics window height")?.height()?.let { it > resized.height() + shrinkBy / 8 } == true
        })
        val grown = settledWindowBounds("Resize lyrics window height")
        assertEquals(original.left, grown.left)
        assertEquals(original.top, grown.top)
        assertEquals(original.width(), grown.width())

        assertTrue(click("Collapse to floating button"))
        assertTrue(waitUntil { node("Open floating Icy Lyrics") != null })
        assertTrue(click("Open floating Icy Lyrics"))
        assertTrue(waitUntil { node("Resize lyrics window height") != null })
        val reopened = settledWindowBounds("Resize lyrics window height")
        assertEquals("Reopening must retain the selected height", grown.height(), reopened.height())
        assertEquals(original.width(), reopened.width())
      } finally {
        LyricsOverlayService.stop(context)
        val stopped = waitUntil {
          node("Open floating Icy Lyrics") == null && node("Resize lyrics window height") == null
        }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $originalAppOp")
        saved.edit().clear().apply {
          originalPreferences.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) }
        }.commit()
        assertTrue("The overlay window must disappear when stopped", stopped)
      }
    }
  }

  @Test fun settingsInstancesSynchronizeAndIntroductionRemainsDismissed() {
    val saved = context.getSharedPreferences(OverlayPreferences.FILE, Context.MODE_PRIVATE)
    val original = saved.all.toMap()
    saved.edit().clear().commit()
    try { OverlayPreferences(context).use { first ->
      OverlayPreferences(context).use { second ->
        assertTrue("Record animation defaults on", first.options.value.spinArtwork)
        assertTrue(second.options.value.spinArtwork)
        instrumentation.runOnMainSync {
          first.setShowArtwork(false)
          first.setShowTitles(false)
          first.setBackgroundEnabled(false)
          first.setSpinArtwork(false)
          first.markBannerSeen()
        }
        assertTrue(waitUntil {
          !second.options.value.showArtwork && second.options.value.bannerSeen && !second.options.value.spinArtwork
        })
        assertFalse(second.options.value.showTitles)
        assertFalse(second.options.value.backgroundEnabled)
        assertFalse(second.options.value.enabled)
      }
      OverlayPreferences(context).use { recreated -> assertEquals(first.options.value, recreated.options.value) }
    } } finally {
      saved.edit().clear().apply {
        original.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) }
      }.commit()
    }
  }

  private fun windowBounds(description: String): Rect? = node(description)?.window?.let { window ->
    Rect().also(window::getBoundsInScreen)
  }

  /**
   * The gesture process finishing is not a WindowManager/accessibility layout
   * fence. On a busy emulator, the half-distance threshold above can observe
   * an intermediate rectangle before the final resize frame is published.
   * Settle each gesture before using its rectangle as the next gesture's
   * reference; keep the exact equality check for horizontal-only movement.
   */
  private fun settledWindowBounds(description: String): Rect {
    val deadline = System.nanoTime() + 8_000_000_000L
    var previous: Rect? = null
    var stableSince = System.nanoTime()
    while (System.nanoTime() < deadline) {
      instrumentation.waitForIdleSync()
      val current = windowBounds(description)
      val now = System.nanoTime()
      if (current == null || current != previous) {
        previous = current
        stableSince = now
      } else if (now - stableSince >= 400_000_000L) {
        return current
      }
      Thread.sleep(50)
    }
    throw AssertionError("Floating window did not settle: $description; last bounds=$previous")
  }

  /** Exercise the actual WindowManager touch route, not only the composable callback. */
  private fun swipeHandle(deltaX: Int, deltaY: Int) {
    val bounds = Rect().also { node("Resize lyrics window height")!!.getBoundsInScreen(it) }
    val x = bounds.centerX()
    val y = bounds.centerY()
    shell("input swipe $x $y ${x + deltaX} ${y + deltaY} 600")
  }

  private fun node(description: String): AccessibilityNodeInfo? =
    instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
      .mapNotNull { findNode(it, description) }.firstOrNull()

  private fun findNode(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
    if (node.contentDescription?.toString() == description) return node
    for (index in 0 until node.childCount) {
      val child = node.getChild(index) ?: continue
      findNode(child, description)?.let { return it }
    }
    return null
  }

  /** Compose exposes an Icon's description separately from its enclosing clickable IconButton. */
  private fun click(description: String): Boolean {
    var target = node(description)
    while (target != null) {
      if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      target = target.parent
    }
    return false
  }

  private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + 8_000_000_000L
    while (System.nanoTime() < deadline) {
      instrumentation.waitForIdleSync()
      if (condition()) return true
      Thread.sleep(100)
    }
    return false
  }

  private fun shell(command: String): String =
    instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
      java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
    }
}
