package com.icy.lyrics.core.platform.network

import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All packets stay on loopback; the unreachable route is simulated before socket.connect(). */
class IcyConnectionFallbackTest {
  private lateinit var server: MockWebServer
  private val clients = mutableListOf<OkHttpClient>()
  private val socketFactories = mutableListOf<StalledIpv6SocketFactory>()

  @After
  fun tearDown() {
    socketFactories.forEach(StalledIpv6SocketFactory::closeAll)
    clients.forEach {
      it.dispatcher.cancelAll()
      it.connectionPool.evictAll()
      it.dispatcher.executorService.shutdownNow()
    }
    if (::server.isInitialized) server.shutdown()
  }

  @Test
  fun stalledFirstIpv6RouteRacesIpv4WithoutDuplicatingTheLyricsPost() = runBlocking {
    startServer()
    server.enqueue(MockResponse().setBody("word-synced lyrics"))
    val sockets = sockets()
    val transport = OkHttpTransport(client(sockets), singleSubmission = true)

    val elapsed = measureTimeMillis {
      val response = transport.execute(request(), 1_024L, 15_000L)
      assertEquals("word-synced lyrics", response.readUtf8Limited(1_024L))
    }

    assertTrue("The initial IPv6 connect was not attempted", sockets.stalledConnectStarted.isCompleted)
    assertTrue("Fallback took ${elapsed}ms instead of racing the stalled 5-second route", elapsed < 2_000L)
    assertTrue("Losing connect must be closed", sockets.stalledConnectClosed.await(2L, TimeUnit.SECONDS))
    assertEquals(1, server.requestCount)
    val captured = server.takeRequest(1L, TimeUnit.SECONDS)!!
    assertEquals("POST", captured.method)
    assertEquals("/api/ttml", captured.path)
    assertEquals(PAYLOAD, captured.body.readUtf8())
    assertEquals("application/ttml+xml", captured.getHeader("Accept"))
  }

  @Test
  fun cancellingAStalledConnectClosesItBeforeItsTimeout() = runBlocking {
    startServer()
    val sockets = sockets()
    // No successful route in this test: cancellation, rather than a racing success, must release it.
    val transport = OkHttpTransport(client(sockets, includeIpv4 = false), singleSubmission = true)
    val pending = async { transport.execute(request(), 1_024L, 15_000L) }
    withTimeout(2_000L) { sockets.stalledConnectStarted.await() }

    withTimeout(750L) { pending.cancelAndJoin() }

    assertTrue(pending.isCancelled)
    assertTrue("Cancellation stranded a connecting socket", sockets.stalledConnectClosed.await(750L, TimeUnit.MILLISECONDS))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun racingRoutesDoesNotFollowRedirectsOrResendThePost() = runBlocking {
    startServer()
    server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/not-allowed")))
    val response = OkHttpTransport(client(sockets()), singleSubmission = true).execute(request(), 1_024L, 15_000L)

    assertEquals(307, response.code)
    assertTrue(response.isRedirect)
    assertEquals(1, server.requestCount)
    assertEquals("/api/ttml", server.takeRequest(1L, TimeUnit.SECONDS)!!.path)
  }

  @Test
  fun immediateServerRetryIsReturnedWithoutReplayingTheLyricsPost() = runBlocking {
    startServer()
    server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
    // A second response makes accidental automatic retry finish rather than hang until timeout.
    server.enqueue(MockResponse().setBody("must not be requested"))

    val response = OkHttpTransport(client(sockets()), singleSubmission = true)
      .execute(request(), 1_024L, 15_000L)

    assertEquals(503, response.code)
    assertEquals(1, server.requestCount)
    assertEquals(PAYLOAD, server.takeRequest(1L, TimeUnit.SECONDS)!!.body.readUtf8())
  }

  @Test
  fun disconnectAfterSubmissionDoesNotResendTheLyricsPost() = runBlocking {
    startServer()
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    server.enqueue(MockResponse().setBody("must not be requested"))

    val failure = runCatching {
      OkHttpTransport(client(sockets()), singleSubmission = true).execute(request(), 1_024L, 15_000L)
    }.exceptionOrNull()

    assertTrue(failure.toString(), failure is NetworkException)
    assertEquals(1, server.requestCount)
    assertEquals(PAYLOAD, server.takeRequest(1L, TimeUnit.SECONDS)!!.body.readUtf8())
  }

  @Test
  fun racingRoutesDoesNotAcceptAnUntrustedServerCertificate() = runBlocking {
    val certificate = HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST).build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    startServer(serverCertificates.sslSocketFactory())
    val sockets = sockets()

    // Deliberately use the default system trust manager, NOT the generated server certificate.
    val failure = runCatching {
      OkHttpTransport(client(sockets), singleSubmission = true).execute(request(https = true), 1_024L, 15_000L)
    }.exceptionOrNull()

    assertTrue(failure.toString(), failure is NetworkException)
    assertTrue("TLS verification was not preserved: $failure", failure?.cause is SSLHandshakeException)
    assertEquals("No lyric request may be sent before certificate validation", 0, server.requestCount)
    assertTrue(sockets.stalledConnectClosed.await(2L, TimeUnit.SECONDS))
  }

  @Test
  fun trustedCertificateStillNeedsToMatchTheRequestedHostname() = runBlocking {
    val certificate = HeldCertificate.Builder()
      .commonName("different-host.test").addSubjectAlternativeName("different-host.test").build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    startServer(serverCertificates.sslSocketFactory())
    val base = client(sockets())
    val client = base.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
      .also(clients::add)

    val failure = runCatching {
      OkHttpTransport(client, singleSubmission = true).execute(request(https = true), 1_024L, 15_000L)
    }.exceptionOrNull()

    assertTrue(failure.toString(), failure is NetworkException)
    assertTrue("Hostname verification was not preserved: $failure", failure?.cause is javax.net.ssl.SSLPeerUnverifiedException)
    assertEquals(0, server.requestCount)
  }

  private fun startServer(tls: SSLSocketFactory? = null) {
    server = MockWebServer().apply {
      if (tls != null) useHttps(tls, false)
      start(IPV4, 0)
    }
  }

  private fun sockets() = StalledIpv6SocketFactory().also(socketFactories::add)

  private fun client(sockets: StalledIpv6SocketFactory, includeIpv4: Boolean = true): OkHttpClient =
    OkHttpClient.Builder()
      // Start with the former sequential-route configuration; test the production policy override.
      .fastFallback(false)
      .connectTimeout(10L, TimeUnit.SECONDS)
      .socketFactory(sockets)
      .proxy(Proxy.NO_PROXY)
      .dns(Dns { host ->
        check(host == HOST) { "Tests must never use public DNS" }
        if (includeIpv4) listOf(IPV6, IPV4) else listOf(IPV6)
      })
      .build().let(::icyDatabaseHttpClient).also(clients::add)

  private fun request(https: Boolean = false): Request = Request.Builder()
    .url("${if (https) "https" else "http"}://$HOST:${server.port}/api/ttml".toHttpUrl())
    .header("Accept", "application/ttml+xml")
    .post(PAYLOAD.toByteArray())
    .build()

  private class StalledIpv6SocketFactory : SocketFactory() {
    val stalledConnectStarted = CompletableDeferred<Unit>()
    val stalledConnectClosed = CountDownLatch(1)
    private val sockets = CopyOnWriteArrayList<Socket>()

    override fun createSocket(): Socket = object : Socket() {
      private val closeSignal = CountDownLatch(1)
      @Volatile private var stalled = false

      override fun connect(endpoint: SocketAddress, timeout: Int) {
        val address = (endpoint as InetSocketAddress).address
        if (address !is Inet6Address) {
          super.connect(endpoint, timeout)
          return
        }
        stalled = true
        stalledConnectStarted.complete(Unit)
        // Do not send a SYN or depend on the host's IPv6 connectivity. Match a blackholed TCP
        // route while still supporting OkHttp's normal close-on-cancel behavior deterministically.
        val closed = closeSignal.await(timeout.toLong(), TimeUnit.MILLISECONDS)
        if (closed) throw SocketException("Test connect cancelled")
        throw SocketTimeoutException("Test IPv6 route timed out")
      }

      override fun close() {
        closeSignal.countDown()
        if (stalled) stalledConnectClosed.countDown()
        super.close()
      }
    }.also(sockets::add)

    override fun createSocket(host: String, port: Int): Socket =
      createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
      createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
      createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
      createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }

    fun closeAll() = sockets.forEach { runCatching { it.close() } }
  }

  private companion object {
    const val HOST = "icy-connection.test"
    const val PAYLOAD = "{\"track\":{\"uri\":\"spotify:track:0YJ9FWWHn9EfnN0lHwbzvV\"}}"
    val IPV6: InetAddress = Inet6Address.getByAddress(HOST, ByteArray(16).also { it[15] = 1 }, -1)
    val IPV4: InetAddress = InetAddress.getByAddress(HOST, byteArrayOf(127, 0, 0, 1))
  }
}
