package com.icy.lyrics.core.platform.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
  continuation.invokeOnCancellation { cancel() }
  enqueue(
    object : Callback {
      override fun onFailure(call: Call, error: IOException) {
        if (!continuation.isCompleted) continuation.resumeWithException(error)
      }

      override fun onResponse(call: Call, response: Response) {
        if (continuation.isCompleted) {
          response.close()
        } else {
          continuation.resume(response)
        }
      }
    },
  )
}

internal fun Response.readUtf8Limited(maxBytes: Long): String {
  require(maxBytes > 0L)
  val body = body ?: return ""
  val declared = body.contentLength()
  if (declared > maxBytes) throw IOException("Response exceeded the allowed size.")
  val source = body.source()
  source.request(maxBytes + 1L)
  if (source.buffer.size > maxBytes) throw IOException("Response exceeded the allowed size.")
  return source.readUtf8()
}

internal fun Headers.retryAfterMs(nowEpochMs: Long = System.currentTimeMillis()): Long? {
  val value = this["Retry-After"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
  value.toLongOrNull()?.let { seconds ->
    return seconds.coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
  }
  return runCatching {
    val instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    (instant.toEpochMilli() - nowEpochMs).coerceAtLeast(0L)
  }.getOrNull()
}

private const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
