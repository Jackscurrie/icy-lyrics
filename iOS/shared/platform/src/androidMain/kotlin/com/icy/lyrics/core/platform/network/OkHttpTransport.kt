package com.icy.lyrics.core.platform.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Keeps the established Android transport while sharing every provider decision. */
class OkHttpTransport(client: OkHttpClient) : LyricsHttpClient {
  private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  override suspend fun execute(request: Request, maxResponseBytes: Long, timeoutMs: Long): Response {
    val native = okhttp3.Request.Builder().url(request.url.toString()).apply {
      request.headers.forEach { (name, value) -> header(name, value) }
      request.body?.let { post(it.toRequestBody("application/json".toMediaType())) } ?: get()
    }.build()
    try {
      return client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        .newCall(native).await().use { response ->
          // Providers classify HTTP failures from status/headers before reading a body.
          val body = response.body.takeIf { response.isSuccessful }
          val bytes = if (body == null) byteArrayOf() else {
            if (body.contentLength() > maxResponseBytes) throw IOException("Response exceeded the allowed size.")
            val source = body.source()
            source.request(maxResponseBytes + 1L)
            if (source.buffer.size > maxResponseBytes) throw IOException("Response exceeded the allowed size.")
            source.readByteArray()
          }
          Response(response.code, Headers(response.headers.toMap()), bytes)
        }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: IOException) {
      throw NetworkException(failure.message ?: "Network request failed.", failure)
    }
  }
}
