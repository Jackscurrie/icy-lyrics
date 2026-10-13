package com.icy.lyrics.core.platform.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Keeps the established Android transport while sharing every provider decision. */
class OkHttpTransport(
  client: OkHttpClient,
  private val singleSubmission: Boolean = false,
  private val onResponseReport: suspend (String) -> Unit = {},
) : LyricsHttpClient {
  private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  override suspend fun execute(request: Request, maxResponseBytes: Long, timeoutMs: Long): Response {
    require(maxResponseBytes in 1L..8L * 1_024L * 1_024L)
    require(timeoutMs > 0L)
    val native = okhttp3.Request.Builder().url(request.url.toString()).apply {
      request.headers.forEach { (name, value) -> header(name, value) }
      request.body?.let {
        val body = it.toRequestBody("application/json".toMediaType())
        post(if (singleSubmission) object : RequestBody() {
          override fun contentType() = body.contentType()
          override fun contentLength() = body.contentLength()
          override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
          // Allow TCP fallback, but never replay this POST after it has started transmitting.
          override fun isOneShot() = true
        } else body)
      } ?: get()
    }.build()
    val trace = ConnectionTrace()
    try {
      val response = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .eventListenerFactory { call -> client.eventListenerFactory.create(call) + trace }.build()
        .newCall(native).awaitBuffered(maxResponseBytes)
      try {
        onResponseReport(trace.summary())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Diagnostic storage is best-effort; it must not discard a valid lyrics response.
      }
      return response
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: IOException) {
      throw NetworkException(trace.failureMessage(failure), failure)
    }
  }

  /**
   * Headers are not the end of an HTTP request. Keep the continuation suspended until the bounded
   * body has been consumed and closed on OkHttp's worker, never on the caller's main/UI dispatcher.
   * Cancellation remains attached to the call during body streaming, so changing songs also closes
   * an in-flight response instead of waiting for its socket timeout.
   */
  private suspend fun Call.awaitBuffered(maxResponseBytes: Long): Response =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancel() }
      enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
          if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: okhttp3.Response) {
          if (!continuation.isActive) {
            response.close()
            return
          }
          try {
            val buffered = response.use { response ->
              // Providers classify HTTP failures from status/headers before reading a body.
              val body = response.body.takeIf { response.isSuccessful }
              val bytes = if (body == null) byteArrayOf() else {
                if (body.contentLength() > maxResponseBytes) {
                  throw IOException("Response exceeded the allowed size.")
                }
                val source = body.source()
                source.request(maxResponseBytes + 1L)
                if (source.buffer.size > maxResponseBytes) {
                  throw IOException("Response exceeded the allowed size.")
                }
                source.readByteArray()
              }
              Response(response.code, Headers(response.headers.toMap()), bytes)
            }
            // Only immutable bytes cross the dispatcher boundary. If cancellation wins the race,
            // no live response/socket is stranded in a discarded resumed value.
            if (continuation.isActive) continuation.resume(buffered)
          } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
          }
        }
      })
    }
}
