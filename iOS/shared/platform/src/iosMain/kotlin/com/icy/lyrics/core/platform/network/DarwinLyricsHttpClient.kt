package com.icy.lyrics.core.platform.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class DarwinLyricsHttpClient : LyricsHttpClient {
  private val client = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
      requestTimeoutMillis = 30_000L
      connectTimeoutMillis = 10_000L
      socketTimeoutMillis = 20_000L
    }
  }

  override suspend fun execute(request: Request, maxResponseBytes: Long, timeoutMs: Long): Response {
    require(maxResponseBytes in 1L..8L * 1_024L * 1_024L)
    try {
      return withTimeoutOrNull(timeoutMs) {
        client.prepareRequest(request.url.toString()) {
          method = if (request.body == null) HttpMethod.Get else HttpMethod.Post
          request.headers.forEach { (name, value) -> headers.append(name, value) }
          request.body?.let { setBody(it) }
        }.execute responseBlock@ { response ->
          val headers = Headers(response.headers.entries().associate { it.key to it.value.joinToString(", ") })
          if (response.status.value !in 200..299) {
            return@responseBlock Response(response.status.value, headers, byteArrayOf())
          }
          val declared = response.headers["Content-Length"]?.toLongOrNull()
          if (declared != null && declared > maxResponseBytes) {
            throw NetworkException("Response exceeded the allowed size.")
          }
          val channel = response.bodyAsChannel()
          val buffer = ByteArray(maxResponseBytes.toInt() + 1)
          var total = 0
          while (total < buffer.size) {
            val count = channel.readAvailable(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
          }
          if (total.toLong() > maxResponseBytes) throw NetworkException("Response exceeded the allowed size.")
          Response(response.status.value, headers, buffer.copyOf(total))
        }
      } ?: throw NetworkException("Network request timed out.")
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: NetworkException) {
      throw failure
    } catch (failure: Exception) {
      throw NetworkException(failure.message ?: "Network request failed.", failure)
    }
  }

  fun close() = client.close()
}
