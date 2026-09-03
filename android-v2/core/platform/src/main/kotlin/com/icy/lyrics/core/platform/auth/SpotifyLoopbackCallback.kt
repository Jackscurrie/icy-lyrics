package com.icy.lyrics.core.platform.auth

import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SpotifyLoopbackCallbackSession internal constructor(
  private val server: ServerSocket,
  val redirectUri: String,
  private val callbackPath: String,
) : Closeable {
  @Volatile
  private var activeSocket: Socket? = null

  suspend fun awaitRedirect(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Uri = withTimeout(timeoutMs) {
    withContext(Dispatchers.IO) {
      val coroutineContext = currentCoroutineContext()
      while (!server.isClosed) {
        coroutineContext.ensureActive()
        val socket = try {
          server.accept()
        } catch (_: SocketTimeoutException) {
          continue
        } catch (error: SocketException) {
          if (server.isClosed) throw IllegalStateException("Spotify authorization callback was closed")
          throw error
        }
        activeSocket = socket
        try {
          socket.use {
            parseRequest(socket, coroutineContext)?.let { return@withContext it }
          }
        } finally {
          if (activeSocket === socket) activeSocket = null
        }
      }
      throw IllegalStateException("Spotify authorization callback was closed")
    }
  }

  override fun close() {
    runCatching { activeSocket?.close() }
    runCatching { server.close() }
  }

  private fun parseRequest(socket: Socket, coroutineContext: CoroutineContext): Uri? {
    val deadlineNanos = System.nanoTime() + CONNECTION_TIMEOUT_MS * 1_000_000L
    return try {
      val input = socket.getInputStream()
      val requestLine = readAsciiLine(
        input,
        socket,
        deadlineNanos,
        MAX_REQUEST_LINE_CHARS,
        coroutineContext,
      ) ?: throw InvalidHttpRequestException()
      val requestParts = requestLine.split(' ')
      if (requestParts.size != 3 || requestParts[0] != "GET" ||
        requestParts[2] !in setOf("HTTP/1.0", "HTTP/1.1")
      ) {
        throw InvalidHttpRequestException()
      }

      var host: String? = null
      var headerCount = 0
      var headersComplete = false
      while (headerCount < MAX_HEADER_LINES) {
        val line = readAsciiLine(
          input,
          socket,
          deadlineNanos,
          MAX_HEADER_LINE_CHARS,
          coroutineContext,
        ) ?: throw InvalidHttpRequestException()
        if (line.isEmpty()) {
          headersComplete = true
          break
        }
        headerCount += 1
        if (line.startsWith("Host:", ignoreCase = true)) host = line.substringAfter(':').trim()
      }
      if (!headersComplete) throw HttpRequestTooLargeException()

      val expectedAuthority = "127.0.0.1:${server.localPort}"
      val target = requestParts[1]
      val uri = target.takeIf { it.startsWith('/') && '\r' !in it && '\n' !in it }
        ?.let { Uri.parse("http://$expectedAuthority$it") }
      val valid = uri?.path == callbackPath && host.equals(expectedAuthority, ignoreCase = true)
      writeResponse(
        socket,
        if (valid) "200 OK" else "404 Not Found",
        if (valid) SUCCESS_HTML else NOT_FOUND_HTML,
      )
      uri.takeIf { valid }
    } catch (_: HttpRequestTooLargeException) {
      writeResponse(socket, "431 Request Header Fields Too Large", BAD_REQUEST_HTML)
      null
    } catch (_: SocketTimeoutException) {
      writeResponse(socket, "408 Request Timeout", BAD_REQUEST_HTML)
      null
    } catch (_: InvalidHttpRequestException) {
      writeResponse(socket, "400 Bad Request", BAD_REQUEST_HTML)
      null
    } catch (_: IOException) {
      null
    }
  }

  private fun readAsciiLine(
    input: InputStream,
    socket: Socket,
    deadlineNanos: Long,
    maxChars: Int,
    coroutineContext: CoroutineContext,
  ): String? {
    val result = StringBuilder(minOf(maxChars, 256))
    while (true) {
      coroutineContext.ensureActive()
      val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
      if (remainingMs <= 0) throw SocketTimeoutException("Spotify callback connection timed out")
      socket.soTimeout = minOf(READ_POLL_MS, remainingMs).coerceAtLeast(1)
      val next = try {
        input.read()
      } catch (_: SocketTimeoutException) {
        continue
      }
      if (next == -1) return null
      if (next == '\n'.code) {
        if (result.isNotEmpty() && result.last() == '\r') result.setLength(result.length - 1)
        return result.toString()
      }
      if (next !in 0x20..0x7e && next != '\r'.code) throw InvalidHttpRequestException()
      if (result.length >= maxChars) throw HttpRequestTooLargeException()
      result.append(next.toChar())
    }
  }

  private fun writeResponse(socket: Socket, status: String, html: String) {
    runCatching {
      val bytes = html.toByteArray(Charsets.UTF_8)
      socket.getOutputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
        writer.write("HTTP/1.1 $status\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(html)
        writer.flush()
      }
    }
  }

  private class HttpRequestTooLargeException : IOException()
  private class InvalidHttpRequestException : IOException()

  companion object {
    const val DEFAULT_TIMEOUT_MS = 5L * 60L * 1_000L
    private const val ACCEPT_POLL_MS = 250
    private const val READ_POLL_MS = 250
    private const val CONNECTION_TIMEOUT_MS = 2_000L
    private const val MAX_REQUEST_LINE_CHARS = 4_096
    private const val MAX_HEADER_LINE_CHARS = 4_096
    private const val MAX_HEADER_LINES = 100
    private const val SUCCESS_HTML = "<!doctype html><title>Icy Lyrics</title><p>Spotify connected. You can return to Icy Lyrics.</p>"
    private const val NOT_FOUND_HTML = "<!doctype html><title>Not found</title>"
    private const val BAD_REQUEST_HTML = "<!doctype html><title>Invalid request</title>"

    internal fun open(callbackPath: String): SpotifyLoopbackCallbackSession {
      val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
        reuseAddress = true
        soTimeout = ACCEPT_POLL_MS
      }
      val redirect = "http://127.0.0.1:${server.localPort}$callbackPath"
      return SpotifyLoopbackCallbackSession(server, redirect, callbackPath)
    }
  }
}
