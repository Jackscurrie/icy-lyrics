package com.icy.lyrics.overlay

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlaySettingsDeviceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext
  private var originalValues: Map<String, *> = emptyMap<String, Any>()
  private var permissionWasAllowed = false
  private var permissionMonitor: Instrumentation.ActivityMonitor? = null

  @Before fun resetIntroAndPermission() {
    val preferences = context.getSharedPreferences(OverlayPreferences.FILE, Context.MODE_PRIVATE)
    originalValues = preferences.all
    preferences.edit().clear().commit()
    permissionWasAllowed = Settings.canDrawOverlays(context)
    setOverlayAppOp("deny")
  }

  @After fun restoreTestState() {
    permissionMonitor?.let(instrumentation::removeMonitor)
    val editor = context.getSharedPreferences(OverlayPreferences.FILE, Context.MODE_PRIVATE).edit().clear()
    originalValues.forEach { (key, value) -> if (value is Boolean) editor.putBoolean(key, value) }
    editor.commit()
    setOverlayAppOp(if (permissionWasAllowed) "allow" else "default")
  }

  @Test fun introPersistsAsSeenButSurvivesRotationUntilClosed() {
    showBanner()
    compose.onNodeWithText("New: lyrics over Spotify").assertIsDisplayed()
    OverlayPreferences(context).use { assertTrue(it.options.value.bannerSeen) }

    compose.activityRule.scenario.recreate()
    showBanner()
    compose.onNodeWithText("New: lyrics over Spotify").assertIsDisplayed()
    compose.onNodeWithContentDescription("Dismiss overlay announcement forever").performClick()
    compose.onNodeWithText("New: lyrics over Spotify").assertDoesNotExist()

    compose.activityRule.scenario.recreate()
    showBanner()
    compose.onNodeWithText("New: lyrics over Spotify").assertDoesNotExist()
  }

  @Test fun seenIntroIsNotShownOnNewActivitySession() {
    OverlayPreferences(context).use { it.markBannerSeen() }
    showBanner()
    compose.onNodeWithText("New: lyrics over Spotify").assertDoesNotExist()
    OverlayPreferences(context).use { assertFalse(it.options.value.enabled) }
  }

  @Test fun returningToTheSameActivityAfterLeavingDoesNotRepeatTheIntro() {
    showBanner()
    compose.onNodeWithText("New: lyrics over Spotify").assertIsDisplayed()
    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()
    compose.onNodeWithText("New: lyrics over Spotify").assertDoesNotExist()
  }

  @Test fun permissionSettingsOnlyLaunchFromExplicitBlueGuideButton() {
    val filter = IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { addDataScheme("package") }
    permissionMonitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
    compose.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        CompositionLocalProvider(LocalIcyUiPlatform provides rememberAndroidIcyUiPlatform()) {
          MaterialTheme(colorScheme = darkColorScheme()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { OverlaySettingsEntry(activity) }
          }
        }
      }
    }
    compose.waitForIdle()
    assertEquals(0, permissionMonitor!!.hits)
    compose.onNodeWithContentDescription("Enable lyrics overlay").assertIsNotEnabled()
    compose.onNodeWithContentDescription("Background").assertExists()
    compose.onNodeWithContentDescription("Album art").assertExists()
    compose.onNodeWithContentDescription("Title and artist").assertExists()
    compose.onNodeWithContentDescription("Spinning album cover").assertIsOn()
    compose.onNodeWithText("Set up overlay permission").performScrollTo().performClick()
    compose.onNodeWithText("Allow Display Over Other Apps").assertIsDisplayed()
    compose.onNodeWithText("How do I do that?").assertIsDisplayed()
    assertEquals(0, permissionMonitor!!.hits)
    OverlayPreferences(context).use { assertFalse(it.options.value.enabled) }

    compose.onNodeWithText("Open Overlay Permission").performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals(1, permissionMonitor!!.hits)
    OverlayPreferences(context).use { assertFalse(it.options.value.enabled) }
  }

  @Test fun spinningCoverIsDefaultOnAndCanBeDisabledWithoutEnablingOverlay() {
    compose.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        CompositionLocalProvider(LocalIcyUiPlatform provides rememberAndroidIcyUiPlatform()) {
          MaterialTheme(colorScheme = darkColorScheme()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { OverlaySettingsEntry(activity) }
          }
        }
      }
    }
    compose.onNodeWithContentDescription("Spinning album cover").performScrollTo().assertIsOn().performClick()
    compose.onNodeWithContentDescription("Spinning album cover").assertIsOff()
    OverlayPreferences(context).use {
      assertFalse(it.options.value.spinArtwork)
      assertFalse(it.options.value.enabled)
      assertTrue(it.options.value.showArtwork)
      assertTrue(it.options.value.showTitles)
    }
  }

  private fun showBanner() {
    compose.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        CompositionLocalProvider(LocalIcyUiPlatform provides rememberAndroidIcyUiPlatform()) {
          MaterialTheme(colorScheme = darkColorScheme()) { OverlayIntroBanner(onSetup = {}) }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun setOverlayAppOp(mode: String) {
    ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode"),
    ).bufferedReader().use { it.readText() }
  }
}
