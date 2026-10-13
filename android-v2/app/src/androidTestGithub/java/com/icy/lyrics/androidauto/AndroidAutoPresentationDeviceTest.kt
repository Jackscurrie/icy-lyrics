package com.icy.lyrics.androidauto

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.Display
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icy.lyrics.IcyLyricsApplication
import com.icy.lyrics.MainActivity
import com.icy.lyrics.loadOptionalMixedModePresentation
import com.icy.lyrics.ui.MixedMediaSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Exercises the merged manifest/reflection hook without emulating a driving state. */
@RunWith(AndroidJUnit4::class)
class AndroidAutoPresentationDeviceTest {
  @get:Rule val compose = createEmptyComposeRule()

  @Test
  fun mergedManifestLoadsTheCarPresentationButNormalPhoneDisplayKeepsSharedPhoneLayout() {
    val application = ApplicationProvider.getApplicationContext<IcyLyricsApplication>()
    assumeFalse("Run on an emulator without current-song notification access",
      application.container.mediaTracker.hasNotificationAccess())
    val packageInfo = application.packageManager.getApplicationInfo(
      application.packageName, PackageManager.GET_META_DATA,
    )
    assertEquals(
      AndroidAutoMixedModePresentation::class.java.name,
      packageInfo.metaData.getString("com.icy.lyrics.OPTIONAL_MIXED_MODE_PRESENTATION"),
    )
    val service = application.packageManager.getServiceInfo(
      ComponentName(application, IcyLyricsCarMediaService::class.java), 0,
    )
    assertTrue(service.exported)
    val active = AtomicReference<Pair<Boolean, Boolean>?>(null)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertEquals(Display.DEFAULT_DISPLAY, activity.display!!.displayId)
        val presentation = loadOptionalMixedModePresentation(activity)
        assertTrue("The real reflective constructor must load, not silently fall back",
          presentation is AndroidAutoMixedModePresentation)
        activity.setContent {
          val left = presentation.isActive(MixedMediaSide.LEFT)
          val right = presentation.isActive(MixedMediaSide.RIGHT)
          SideEffect { active.set(left to right) }
        }
      }
      compose.waitUntil(10_000) { active.get() != null }
      assertFalse("Phone left-side layout must not become the parked car layout", active.get()!!.first)
      assertFalse("Phone right-side layout must not become the parked car layout", active.get()!!.second)
    }
  }
}
