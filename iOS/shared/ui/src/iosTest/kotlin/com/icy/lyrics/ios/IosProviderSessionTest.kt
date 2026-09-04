@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.icy.lyrics.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosProviderSessionTest {
  @Test fun disconnectRejectsNewProviderWorkUntilFreshPlaybackConnects() = runTest {
    val session = IosProviderSession()
    session.disconnect()
    var calls = 0
    assertFailsWith<CancellationException> { session.resolve { ++calls } }
    assertEquals(0, calls)
    session.connect()
    assertEquals(1, session.resolve { ++calls })
  }

  @Test fun oldUncooperativeProviderCannotPublishAfterDisconnectAndNewConnection() = runTest {
    val session = IosProviderSession()
    var late: Continuation<String>? = null
    val published = mutableListOf<String>()
    val old = launch {
      published.add(session.resolve<String> { suspendCoroutine { late = it } })
    }
    runCurrent()
    session.disconnect()
    session.connect()
    published.add(session.resolve<String> { "new account" })
    late!!.resume("revoked account")
    runCurrent()
    old.join()
    assertTrue(old.isCancelled)
    assertEquals(listOf("new account"), published)
  }

  @Test fun disconnectCancelsAllConcurrentRequests() = runTest {
    val session = IosProviderSession()
    val pending = mutableListOf<Continuation<Unit>>()
    var published = 0
    val jobs = List(2) { launch {
      session.resolve { suspendCoroutine<Unit> { pending += it } }
      ++published
    } }
    runCurrent()
    session.disconnect()
    pending.forEach { it.resume(Unit) }
    runCurrent()
    jobs.forEach { it.join(); assertTrue(it.isCancelled) }
    assertEquals(0, published)
  }

  @Test fun cooperativeProviderIsCancelledWithoutWaitingForAResult() = runTest {
    val session = IosProviderSession()
    var unwound = false
    val request = launch {
      session.resolve { try { awaitCancellation() } finally { unwound = true } }
    }
    runCurrent()
    session.disconnect()
    runCurrent()
    request.join()
    assertTrue(request.isCancelled)
    assertTrue(unwound)
  }
}
