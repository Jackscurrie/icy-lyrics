package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.parser.TtmlParseException
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import com.icy.lyrics.core.lyrics.provider.LyricsProvider
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderFailureCategory
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.lyrics.provider.ProviderUnavailableReason
import com.icy.lyrics.core.platform.diagnostics.DiagnosticInput
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSeverity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.network.HttpUrl
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.network.LyricsHttpClient
import com.icy.lyrics.core.platform.network.NetworkException
import com.icy.lyrics.core.platform.network.Request
import com.icy.lyrics.core.platform.network.readUtf8Limited
import com.icy.lyrics.core.platform.network.retryAfterMs
import com.icy.lyrics.core.platform.network.use
import com.icy.lyrics.core.platform.storage.CachedLyrics
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class IcyLyricsDatabaseConfig(
  val endpoint: HttpUrl = "https://jackscurrie.com/api/ttml".toHttpUrl(),
  val userAgent: String =
    "IcyLyricsAndroid/1.1.0 (+https://jackscurrie.com/icy-lyrics)",
  val maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  val requestTimeoutMs: Long = 15_000L,
  val positiveCacheTtlMs: Long = 30L * 24L * 60L * 60L * 1_000L,
  val negativeCacheTtlMs: Long = 60L * 60L * 1_000L,
  val allowInsecureForTests: Boolean = false,
) {
  init {
    require(endpoint.isHttps || allowInsecureForTests) {
      "The Icy Lyrics Database endpoint must use HTTPS"
    }
    require(userAgent.isNotBlank()) { "The Icy Lyrics Database requires a User-Agent" }
    require(maxResponseBytes in 1L..MAX_RESPONSE_BYTES) {
      "The Icy Lyrics Database response limit cannot exceed 2 MiB"
    }
    require(requestTimeoutMs in 1L..60_000L)
    require(positiveCacheTtlMs in 0L..MAX_CACHE_TTL_MS)
    require(negativeCacheTtlMs in 0L..MAX_CACHE_TTL_MS)
  }

  private companion object {
    const val MAX_RESPONSE_BYTES = 2L * 1_024L * 1_024L
    const val MAX_CACHE_TTL_MS = 365L * 24L * 60L * 60L * 1_000L
  }
}

/**
 * Exact-identity client for the first-party Icy Lyrics TTML endpoint.
 *
 * A second cache lookup is deliberately made while [requestMutex] is held. If two app surfaces
 * observe the same song change together, only the first request reaches the website and the other
 * consumes the newly-persisted result. An explicit reload (`allowCached = false`) bypasses both
 * cache checks.
 */
class IcyLyricsDatabaseProvider(
  private val client: LyricsHttpClient,
  private val cache: LyricsCacheRepository,
  private val config: IcyLyricsDatabaseConfig = IcyLyricsDatabaseConfig(),
  private val enabled: suspend () -> Boolean = { true },
  private val online: () -> Boolean = { true },
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : LyricsProvider {
  override val id: LyricsProviderId = LyricsProviderId.ICY_DATABASE

  private val json = Json { encodeDefaults = true }
  private val requestMutex = Mutex()

  override suspend fun fetch(request: LyricsRequest): ProviderResult {
    if (!enabled()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.DISABLED,
        "Icy Lyrics Database is disabled.",
      )
    }
    if (!request.track.hasIcyDatabaseUri()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.UNSUPPORTED_TRACK,
        "Icy Lyrics Database requires a complete Spotify track URI.",
      )
    }

    if (request.allowCached) cachedResult(request)?.let { return it }
    if (!online()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.OFFLINE,
        "Icy Lyrics Database is unavailable offline.",
      )
    }

    return requestMutex.withLock {
      // Re-check after waiting: another controller may just have loaded this exact track.
      if (request.allowCached) cachedResult(request)?.let { return@withLock it }
      if (!online()) {
        return@withLock ProviderResult.Unavailable(
          ProviderUnavailableReason.OFFLINE,
          "Icy Lyrics Database is unavailable offline.",
        )
      }
      fetchNetwork(request)
    }
  }

  private suspend fun cachedResult(request: LyricsRequest): ProviderResult? {
    return when (val cached = cache.get(id, request.track)) {
      is CachedLyrics.Hit -> {
        if (
          cached.sourceVerified &&
          cached.document.metadata.source == LyricsSource.ICY_DATABASE &&
          cached.rawFormat == CACHE_FORMAT
        ) {
          ProviderResult.Found(
            document = cached.document,
            fromCache = true,
            rawFormat = RAW_FORMAT,
            message = "Icy Lyrics Database cache",
          )
        } else {
          cache.invalidate(id, request.track)
          null
        }
      }
      is CachedLyrics.Negative ->
        ProviderResult.NotFound("Icy Lyrics Database negative cache")
      null -> null
    }
  }

  private suspend fun fetchNetwork(request: LyricsRequest): ProviderResult {
    val body = json.encodeToString(
      IcyLyricsRequestBody(IcyLyricsTrackRequest(request.track.exactStorageKey)),
    ).encodeToByteArray()
    val httpRequest = Request.Builder()
      .url(config.endpoint)
      .header("Accept", TTML_MEDIA_TYPE)
      .header("Content-Type", JSON_MEDIA_TYPE)
      .header("User-Agent", config.userAgent)
      .post(body)
      .build()

    return try {
      client.execute(httpRequest, config.maxResponseBytes, config.requestTimeoutMs).use { response ->
        if (response.isRedirect) {
          log(
            request,
            DiagnosticSeverity.ERROR,
            "redirect-refused",
            response.code,
            "Icy Lyrics Database refused an unexpected redirect.",
          )
          return ProviderResult.Failure(
            ProviderFailureCategory.SECURITY,
            "Icy Lyrics Database refused an unexpected redirect.",
            httpStatus = response.code,
          )
        }
        if (response.code == 404) {
          cache.putNegative(id, request.track, config.negativeCacheTtlMs)
          log(
            request,
            DiagnosticSeverity.INFO,
            "not-found",
            response.code,
            "Icy Lyrics Database has no saved lyrics for this track.",
          )
          return ProviderResult.NotFound(
            "Icy Lyrics Database has no saved lyrics for this track.",
          )
        }
        if (response.code == 429) {
          val retryAfterMs = response.headers.retryAfterMs() ?: DEFAULT_RETRY_AFTER_MS
          log(
            request,
            DiagnosticSeverity.WARNING,
            "rate-limited",
            response.code,
            "Icy Lyrics Database is temporarily rate-limited.",
          )
          // Do not return Queued here. The controller automatically retries queued higher-priority
          // providers; that would defeat the one-request-per-song-load bandwidth guarantee.
          return ProviderResult.Failure(
            category = ProviderFailureCategory.HTTP,
            message = "Icy Lyrics Database is temporarily rate-limited.",
            httpStatus = response.code,
            retryAfterMs = retryAfterMs,
          )
        }
        if (response.code != 200) {
          log(
            request,
            DiagnosticSeverity.WARNING,
            "http-failure",
            response.code,
            "Icy Lyrics Database returned HTTP ${response.code}.",
          )
          return ProviderResult.Failure(
            ProviderFailureCategory.HTTP,
            "Icy Lyrics Database returned HTTP ${response.code}.",
            httpStatus = response.code,
            retryAfterMs = response.headers.retryAfterMs(),
          )
        }

        val rawTtml = response.readUtf8Limited(config.maxResponseBytes)
        val document = try {
          TtmlParser.parse(
            rawTtml = rawTtml,
            trackUri = request.track.exactStorageKey,
            source = LyricsSource.ICY_DATABASE,
          )
        } catch (error: TtmlParseException) {
          val message = error.message?.takeIf(String::isNotBlank)
            ?: "Icy Lyrics Database returned invalid TTML."
          log(request, DiagnosticSeverity.ERROR, "ttml-parse", response.code, message)
          return ProviderResult.Failure(
            ProviderFailureCategory.PARSE,
            "Icy Lyrics Database returned invalid TTML: $message",
            httpStatus = response.code,
          )
        }

        cache.put(
          provider = id,
          track = request.track,
          document = document,
          ttlMs = config.positiveCacheTtlMs,
          rawFormat = CACHE_FORMAT,
          sourceVerified = true,
        )
        ProviderResult.Found(
          document = document,
          rawFormat = RAW_FORMAT,
          message = "Icy Lyrics Database",
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: NetworkException) {
      val message = error.message?.takeIf(String::isNotBlank)
        ?: "Could not reach Icy Lyrics Database."
      log(request, DiagnosticSeverity.WARNING, "network-failure", null, message)
      ProviderResult.Failure(ProviderFailureCategory.NETWORK, message)
    } catch (error: Exception) {
      val message = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}"
        .trim()
        .trimEnd(':')
        .ifBlank { "Icy Lyrics Database request failed." }
      log(request, DiagnosticSeverity.ERROR, "unexpected-failure", null, message)
      ProviderResult.Failure(ProviderFailureCategory.UNKNOWN, message)
    }
  }

  private suspend fun log(
    request: LyricsRequest,
    severity: DiagnosticSeverity,
    code: String,
    httpStatus: Int?,
    message: String,
  ) {
    diagnostics.record(
      DiagnosticInput(
        severity = severity,
        component = "icy-database",
        code = code,
        provider = id,
        trackKey = request.track.exactStorageKey,
        httpStatus = httpStatus,
        message = message,
      ),
    )
  }

  // Local-track URIs remain exact local import/cache keys. They often contain
  // human-readable artist, album and title fields and cannot match the public
  // catalog database, so never transmit them to this endpoint.
  private fun TrackIdentity.hasIcyDatabaseUri(): Boolean = SPOTIFY_TRACK_URI.matches(uri)

  private companion object {
    const val TTML_MEDIA_TYPE = "application/ttml+xml"
    const val JSON_MEDIA_TYPE = "application/json"
    const val RAW_FORMAT = "ttml"
    const val CACHE_FORMAT = "icy-ttml-v1"
    const val DEFAULT_RETRY_AFTER_MS = 60_000L
    val SPOTIFY_TRACK_URI = Regex("^spotify:track:[A-Za-z0-9]{22}$", RegexOption.IGNORE_CASE)
  }
}

@Serializable
private data class IcyLyricsRequestBody(val track: IcyLyricsTrackRequest)

@Serializable
private data class IcyLyricsTrackRequest(val uri: String)
