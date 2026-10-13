package com.icy.lyrics.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.MediaBrowserCompat
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.IcyLyricsApplication
import com.icy.lyrics.IcyLyricsViewModel
import com.icy.lyrics.PhoneSessionLease
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Offline emulator checks: no notification grant, Spotify commands, or network fixtures. */
@RunWith(AndroidJUnit4::class)
class AndroidAutoServiceDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val application = ApplicationProvider.getApplicationContext<IcyLyricsApplication>()

  @Test
  fun realMediaBrowserConnectionKeepsSharedPhoneSessionAliveUntilServiceUnbinds() {
    assumeFalse("Run on an emulator without current-song notification access",
      application.container.mediaTracker.hasNotificationAccess())
    lateinit var phoneLease: PhoneSessionLease
    lateinit var original: IcyLyricsViewModel
    lateinit var browser: MediaBrowserCompat
    val connected = CountDownLatch(1)
    val failure = AtomicReference<String?>(null)
    instrumentation.runOnMainSync {
      phoneLease = application.container.acquirePhoneSession()
      original = phoneLease.viewModel
      browser = MediaBrowserCompat(
        application,
        ComponentName(application, IcyLyricsCarMediaService::class.java),
        object : MediaBrowserCompat.ConnectionCallback() {
          override fun onConnected() { connected.countDown() }
          override fun onConnectionFailed() {
            failure.set("The app's own verified media browser could not connect")
            connected.countDown()
          }
        },
        null,
      )
      browser.connect()
    }
    try {
      assertTrue("Android must bind and start the actual car media service",
        connected.await(10, TimeUnit.SECONDS))
      assertNull(failure.get())
      instrumentation.runOnMainSync {
        assertTrue(browser.isConnected)
        assertNotNull(browser.sessionToken)
        assertSame(original, application.container.phoneViewModel)
        phoneLease.close()
        assertFalse("Auto must retain the existing resolver after the phone owner closes",
          original.viewModelScope.coroutineContext[Job]!!.isCancelled)
        assertSame(original, application.container.phoneViewModel)
        browser.disconnect()
      }
      assertTrue("Unbinding the last car browser must release its session lease", waitUntil {
        original.viewModelScope.coroutineContext[Job]!!.isCancelled
      })
      instrumentation.runOnMainSync {
        application.container.acquirePhoneSession().use { reopened ->
          assertNotSame("A finished service must not retain a dead session", original, reopened.viewModel)
        }
      }
    } finally {
      instrumentation.runOnMainSync {
        browser.disconnect()
        phoneLease.close()
      }
    }
  }

  @Test
  fun rootPolicyUsesRealPackageOwnershipAndRejectsAnInstalledUntrustedForeignUid() {
    // Exercise onGetRoot with the real PackageManager and platform media trust
    // checker. Only the Context is attached: no service lifecycle or playback
    // request is invoked by this focused policy test.
    val ownUid = application.applicationInfo.uid
    val testPackage = instrumentation.context.packageName
    val testUid = instrumentation.context.applicationInfo.uid
    assertTrue("The instrumentation APK must have a separate installed UID", testUid != ownUid)

    instrumentation.runOnMainSync {
      val service = IcyLyricsCarMediaService()
      ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        .apply { isAccessible = true }.invoke(service, application)
      assertNotNull(service.onGetRoot(application.packageName, ownUid, null))
      assertNull("A real foreign app without media privileges must not read current lyrics",
        service.onGetRoot(testPackage, testUid, null))
      assertNull("Using a trusted-looking package with another app's UID must fail",
        service.onGetRoot(application.packageName, testUid, null))
      assertNull("An invented package must fail even with our own UID",
        service.onGetRoot("invalid.icy.browser.caller", ownUid, null))
    }
  }

  private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      instrumentation.waitForIdleSync()
      if (condition()) return true
      Thread.sleep(50)
    }
    return false
  }
}
