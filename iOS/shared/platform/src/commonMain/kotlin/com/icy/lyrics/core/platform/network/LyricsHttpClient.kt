package com.icy.lyrics.core.platform.network

import com.icy.lyrics.core.platform.runtime.epochMillis
import com.icy.lyrics.core.platform.runtime.parseHttpDate
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments

/** The transport must reject redirects and enforce [maxResponseBytes] while reading. */
fun interface LyricsHttpClient {
  suspend fun execute(request: Request, maxResponseBytes: Long, timeoutMs: Long): Response

  suspend fun execute(request: Request, maxResponseBytes: Long): Response =
    execute(request, maxResponseBytes, 30_000L)
}

class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

@ConsistentCopyVisibility
data class HttpUrl private constructor(private val value: String) {
  private val parsed get() = Url(value)
  val isHttps: Boolean get() = parsed.protocol.name == "https"
  val host: String get() = parsed.host
  val encodedPath: String get() = parsed.encodedPath
  fun newBuilder(): Builder = Builder(value)
  override fun toString(): String = value

  class Builder internal constructor(value: String) {
    private val delegate = URLBuilder(value)
    fun addPathSegment(value: String) = apply { delegate.appendPathSegments(listOf(value), encodeSlash = true) }
    fun addPathSegments(value: String) = apply { delegate.appendPathSegments(value.split('/')) }
    fun addQueryParameter(name: String, value: String?) = apply {
      if (value != null) delegate.parameters.append(name, value)
    }
    fun build(): HttpUrl = HttpUrl(delegate.buildString())
  }

  companion object {
    fun String.toHttpUrl(): HttpUrl {
      val url = Url(this)
      require(url.protocol.name == "https" || url.protocol.name == "http") { "Unsupported HTTP URL" }
      return HttpUrl(url.toString())
    }
  }
}

data class Request(val url: HttpUrl, val headers: Map<String, String>, val body: ByteArray?) {
  class Builder {
    private var url: HttpUrl? = null
    private val headers = linkedMapOf<String, String>()
    private var body: ByteArray? = null
    fun url(value: HttpUrl) = apply { url = value }
    fun header(name: String, value: String) = apply { headers[name] = value }
    fun get() = apply { body = null }
    fun post(value: ByteArray) = apply { body = value }
    fun build() = Request(requireNotNull(url), headers.toMap(), body)
  }
}

class Headers(private val values: Map<String, String>) {
  operator fun get(name: String): String? = values.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** Transport resources are closed before this bounded immutable response is returned. */
class Response(val code: Int, val headers: Headers, internal val bytes: ByteArray) {
  val isRedirect: Boolean get() = code in REDIRECT_CODES
  val isSuccessful: Boolean get() = code in 200..299
  fun close() = Unit

  private companion object {
    val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
  }
}

inline fun <T> Response.use(block: (Response) -> T): T = block(this)

fun Response.readUtf8Limited(maxBytes: Long): String {
  if (bytes.size.toLong() > maxBytes) throw NetworkException("Response exceeded the allowed size.")
  return bytes.decodeToString()
}

fun Headers.retryAfterMs(nowEpochMs: Long = epochMillis()): Long? {
  val value = this["Retry-After"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
  value.toLongOrNull()?.let { return it.coerceIn(0L, 86_400L) * 1_000L }
  return parseHttpDate(value)?.let { (it - nowEpochMs).coerceAtLeast(0L) }
}
