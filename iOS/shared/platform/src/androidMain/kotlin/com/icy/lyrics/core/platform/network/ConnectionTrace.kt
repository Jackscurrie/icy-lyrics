package com.icy.lyrics.core.platform.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener

/** Per-call counters only: never retain URLs, addresses, headers, lyrics or request bodies. */
internal class ConnectionTrace : EventListener() {
  private val startedNs = System.nanoTime()
  @Volatile private var stage = "queued"
  @Volatile private var dns = "not-needed-or-not-started"
  private val v4Attempts = AtomicInteger()
  private val v6Attempts = AtomicInteger()
  private val otherAttempts = AtomicInteger()
  @Volatile private var connected = false
  @Volatile private var protocol = "none"
  @Volatile private var family = "none"
  @Volatile private var reused = false
  @Volatile private var sent = false
  @Volatile private var status = 0

  override fun dnsStart(call: Call, domainName: String) { stage = "DNS" }

  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    dns = "v4:${inetAddressList.count { it is Inet4Address }},v6:${inetAddressList.count { it is Inet6Address }}"
    stage = "route-selection"
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    when (inetSocketAddress.address) {
      is Inet4Address -> v4Attempts.incrementAndGet()
      is Inet6Address -> v6Attempts.incrementAndGet()
      else -> otherAttempts.incrementAndGet()
    }
    if (!connected) stage = "TCP-connect"
  }

  override fun secureConnectStart(call: Call) { stage = "TLS-handshake" }

  override fun connectionAcquired(call: Call, connection: Connection) {
    connected = true
    reused = v4Attempts.get() + v6Attempts.get() + otherAttempts.get() == 0
    protocol = connection.protocol().toString()
    family = when (connection.route().socketAddress.address) {
      is Inet4Address -> "IPv4"
      is Inet6Address -> "IPv6"
      else -> "other"
    }
    stage = "connected"
  }

  override fun requestHeadersStart(call: Call) { stage = "sending-headers" }
  override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
    if (request.body == null) { sent = true; stage = "waiting-response-headers" }
  }
  override fun requestBodyStart(call: Call) { stage = "sending-body" }
  override fun requestBodyEnd(call: Call, byteCount: Long) {
    sent = true
    stage = "waiting-response-headers"
  }
  override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
    status = response.code
    // responseBodyStart is emitted only after the first bytes arrive, too late for a stalled body.
    stage = "reading-response-body"
  }

  fun summary(): String = "stage=$stage; elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)}; " +
    "dns=$dns; tcp=v4:${v4Attempts.get()},v6:${v6Attempts.get()},other:${otherAttempts.get()}; " +
    "connected=$family; protocol=$protocol; reused=$reused; requestSent=$sent; http=${status.takeIf { it > 0 } ?: "none"}"

  fun failureMessage(error: IOException): String {
    // Platform exception messages can contain IP addresses, hostnames or request URLs.
    val reason = when {
      error.message == "Response exceeded the allowed size." -> return error.message!!
      error is UnknownHostException -> "DNS lookup failed"
      error is SSLException -> "TLS validation or handshake failed"
      error is InterruptedIOException -> "Connection timeout or interrupted I/O"
      error is ConnectException -> "Could not establish a connection"
      else -> "Network I/O failed"
    }
    return "$reason (${"OkHttp/5.3.2"}; ${summary()})"
  }
}
