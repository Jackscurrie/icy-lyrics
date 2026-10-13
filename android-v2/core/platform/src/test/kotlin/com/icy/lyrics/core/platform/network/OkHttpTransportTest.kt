package com.icy.lyrics.core.platform.network

import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTransportTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun delayedResponseBodyIsReadOffTheCallerThreadAndClosedBeforeResuming() {
    server.enqueue(MockResponse().setBody("lyrics").setBodyDelay(100L, TimeUnit.MILLISECONDS))
    val readThreads = CopyOnWriteArrayList<Thread>()
    val callerThread = AtomicReference<Thread>()
    val closed = CompletableDeferred<Unit>()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val response = chain.proceed(chain.request())
      val original = requireNotNull(response.body)
      val source = object : ForwardingSource(original.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
          val thread = Thread.currentThread()
          readThreads += thread
          // JVM tests do not enforce Android's StrictMode network guard; this makes the exact
          // NetworkOnMainThreadException failure deterministic without depending on socket speed.
          check(thread !== callerThread.get()) { "Response body was read on the UI dispatcher" }
          return super.read(sink, byteCount)
        }

        override fun close() {
          super.close()
          closed.complete(Unit)
        }
      }.buffer()
      response.newBuilder().body(object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = source
      }).build()
    }.build()
    Executors.newSingleThreadExecutor { task ->
      Thread(task, CALLER_THREAD).also(callerThread::set)
    }
      .asCoroutineDispatcher().use { uiDispatcher ->
        runBlocking(uiDispatcher) {
          val response = OkHttpTransport(client).execute(request(), 1_024L, 5_000L)

          assertEquals("lyrics", response.readUtf8Limited(1_024L))
          assertSame(callerThread.get(), Thread.currentThread())
          assertTrue("A returned response must own no live body", closed.isCompleted)
        }
      }
    assertTrue(readThreads.isNotEmpty())
    assertFalse(readThreads.contains(callerThread.get()))
  }

  @Test
  fun cancellationWhileReadingBodyCancelsSocketWithoutWaitingForTimeout() = runBlocking {
    server.enqueue(MockResponse().setBody("lyrics").setBodyDelay(2L, TimeUnit.SECONDS))
    val readingBody = CompletableDeferred<Unit>()
    val callReference = AtomicReference<Call>()
    val client = OkHttpClient.Builder().eventListener(object : EventListener() {
      override fun callStart(call: Call) {
        callReference.set(call)
      }
    }).addInterceptor { chain ->
      val response = chain.proceed(chain.request())
      val original = requireNotNull(response.body)
      val source = object : ForwardingSource(original.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
          // responseBodyStart may wait for the first bytes; signal before the blocking socket
          // read so cancellation is tested while the server is still withholding the body.
          readingBody.complete(Unit)
          return super.read(sink, byteCount)
        }
      }.buffer()
      response.newBuilder().body(object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = source
      }).build()
    }.build()
    val pending = async { OkHttpTransport(client).execute(request(), 1_024L, 10_000L) }
    withTimeout(2_000L) { readingBody.await() }

    withTimeout(750L) { pending.cancelAndJoin() }

    assertTrue(pending.isCancelled)
    assertTrue(callReference.get().isCanceled())
  }

  @Test
  fun timeoutCoversBodyStreamingNotOnlyHeaders() = runBlocking {
    server.enqueue(MockResponse().setBody("lyrics").setBodyDelay(1L, TimeUnit.SECONDS))

    val failure = runCatching {
      OkHttpTransport(OkHttpClient()).execute(request(), 1_024L, 400L)
    }.exceptionOrNull()

    assertTrue(failure.toString(), failure is NetworkException)
    assertTrue(failure?.message.orEmpty().contains("timeout", ignoreCase = true))
    assertTrue(failure?.message.orEmpty().contains("stage=reading-response-body"))
    assertTrue(failure?.message.orEmpty().contains("http=200"))
  }

  @Test
  fun waitingForHeadersIsDistinguishedFromAStalledBody() = runBlocking {
    server.enqueue(MockResponse().setBody("lyrics").setHeadersDelay(1L, TimeUnit.SECONDS))
    val failure = runCatching {
      OkHttpTransport(OkHttpClient()).execute(request(), 1_024L, 400L)
    }.exceptionOrNull()

    assertTrue(failure?.message.orEmpty().contains("stage=waiting-response-headers"))
    assertTrue(failure?.message.orEmpty().contains("requestSent=true"))
    assertTrue(failure?.message.orEmpty().contains("http=none"))
  }

  @Test
  fun dnsFailureReportNeverCopiesHostIpOrExceptionMessage() = runBlocking {
    val client = OkHttpClient.Builder().dns {
      throw UnknownHostException("secret.example.test 192.0.2.42 Authorization=private")
    }.build()
    val failure = runCatching {
      OkHttpTransport(client).execute(
        Request.Builder().url("https://secret.example.test/api/ttml?token=secret".toHttpUrl()).build(),
        1_024L, 1_000L,
      )
    }.exceptionOrNull()
    val message = failure?.message.orEmpty()
    assertTrue(message, message.contains("DNS lookup failed"))
    assertTrue(message.contains("stage=DNS"))
    assertFalse(message.contains("secret"))
    assertFalse(message.contains("192.0.2.42"))
    assertFalse(message.contains("Authorization"))
  }

  @Test
  fun successfulConnectionReportPreservesExistingEventListener() = runBlocking {
    server.enqueue(MockResponse().setBody("lyrics"))
    var existingListenerCalled = false
    var report = ""
    val client = OkHttpClient.Builder().eventListener(object : EventListener() {
      override fun callEnd(call: Call) { existingListenerCalled = true }
    }).build()
    OkHttpTransport(client, onResponseReport = { report = it }).execute(request(), 1_024L, 2_000L)

    assertTrue(existingListenerCalled)
    assertTrue(report, report.contains("http=200"))
    assertTrue(report.contains("requestSent=true"))
    assertTrue(report.contains("protocol=http/1.1"))
    assertFalse(report.contains("/api/ttml"))
  }

  @Test
  fun diagnosticStorageFailureDoesNotDiscardSuccessfulLyrics() = runBlocking {
    server.enqueue(MockResponse().setBody("lyrics"))
    val response = OkHttpTransport(OkHttpClient(), onResponseReport = { error("Storage unavailable") })
      .execute(request(), 1_024L, 2_000L)
    assertEquals("lyrics", response.readUtf8Limited(1_024L))
  }

  @Test
  fun declaredOversizeBodyIsRejected() = runBlocking {
    server.enqueue(MockResponse().setBody("x".repeat(1_025)))
    assertOversizeRejected()
  }

  @Test
  fun chunkedOversizeBodyIsRejectedWithoutContentLength() = runBlocking {
    server.enqueue(MockResponse().setChunkedBody("x".repeat(1_025), 128))
    assertOversizeRejected()
  }

  @Test
  fun compressedBodyLimitAppliesToDecodedBytes() = runBlocking {
    val compressed = Buffer()
    GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(1_025)) }
    server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody(compressed))
    assertOversizeRejected()
  }

  @Test
  fun redirectsAreReturnedWithoutFollowingAndHttpFailuresDoNotReadBodies() = runBlocking {
    server.enqueue(
      MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected"))
        .setBody("Do not download").setBodyDelay(1L, TimeUnit.SECONDS),
    )

    val response = OkHttpTransport(OkHttpClient()).execute(request(), 1_024L, 500L)

    assertEquals(302, response.code)
    assertTrue(response.isRedirect)
    assertEquals("", response.readUtf8Limited(1_024L))
    assertEquals(1, server.requestCount)
  }

  private suspend fun assertOversizeRejected() {
    val failure = runCatching {
      OkHttpTransport(OkHttpClient()).execute(request(), 1_024L, 5_000L)
    }.exceptionOrNull()
    assertTrue(failure.toString(), failure is NetworkException)
    assertEquals("Response exceeded the allowed size.", failure?.message)
  }

  private fun request() = Request.Builder().url(server.url("/api/ttml").toString().toHttpUrl()).build()

  private companion object {
    const val CALLER_THREAD = "lyrics-main-dispatcher"
  }
}
