package com.icy.lyrics

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSessionDeviceTest {
  @Test fun activityAndOverlayShareOneResolverUntilBothClose() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val session = PhoneSession(application)
      val activity = session.acquire()
      val first = activity.viewModel
      val overlay = session.acquire()
      assertSame(first, overlay.viewModel)
      activity.close()
      activity.close() // Lease close is safe even if framework cleanup repeats it.
      assertSame(first, overlay.viewModel)
      assertFalse(first.viewModelScope.coroutineContext[Job]!!.isCancelled)
      overlay.close()
      assertTrue(first.viewModelScope.coroutineContext[Job]!!.isCancelled)
      session.acquire().use { nextVisit -> assertNotSame(first, nextVisit.viewModel) }
    }
  }
}
