package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.parser.IcyLyricsJsonParser
import com.icy.lyrics.core.lyrics.parser.LyricsFileParser
import com.icy.lyrics.core.lyrics.provider.LyricsProvider
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderFailureCategory
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.lyrics.provider.ProviderUnavailableReason
import com.icy.lyrics.core.platform.diagnostics.DiagnosticInput
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSeverity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.network.await
import com.icy.lyrics.core.platform.network.readUtf8Limited
import com.icy.lyrics.core.platform.storage.CachedLyrics
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

fun interface SpotifyAccessTokenSource {
  suspend fun accessToken(): String?

  /**
   * Returns a different token after [rejectedToken] receives a 401/403, when the
   * source can safely refresh it. The provider retries at most once and never
   * retries the same token text.
   */
  suspend fun refreshAfterRejection(rejectedToken: String): String? = null
}

data class SpicyLyricsConfig(
  val endpoint: HttpUrl = "https://api.spicylyrics.org/query".toHttpUrl(),
  val compatibilityVersion: String = "6.3.12",
  val maxResponseBytes: Long = 4L * 1_024L * 1_024L,
  val allowInsecureForTests: Boolean = false,
) {
  init {
    require(endpoint.isHttps || allowInsecureForTests) { "Spicy Lyrics must use HTTPS" }
    require(endpoint.encodedPath == "/query" || allowInsecureForTests) {
      "Spicy Lyrics endpoint must be the documented client /query path"
    }
    require(compatibilityVersion.isNotBlank())
    require(maxResponseBytes in 1L..8L * 1_024L * 1_024L)
  }
}

/** Shared by the three source adapters so one host/auth failure is paid only once per pass. */
class SpicyHostCircuitBreaker(
  private val clock: () -> Long = System::currentTimeMillis,
  private val coolDownMs: Long = 5_000L,
) {
  init {
    require(coolDownMs in 0L..60_000L)
  }

  private var blockedUntilEpochMs = 0L
  private var blockedMessage = "Spicy Lyrics is temporarily unavailable."

  @Synchronized
  fun currentMessage(): String? {
    if (clock() >= blockedUntilEpochMs) return null
    return blockedMessage
  }

  @Synchronized
  fun trip(message: String) {
    blockedUntilEpochMs = clock() + coolDownMs
    blockedMessage = message.take(160)
  }
}

/** Android implementation of the desktop player's production /query protocol. */
class SpicyLyricsProvider(
  override val id: LyricsProviderId = LyricsProviderId.SPICY,
  private val client: OkHttpClient,
  private val tokenSource: SpotifyAccessTokenSource,
  private val cache: LyricsCacheRepository,
  private val config: SpicyLyricsConfig = SpicyLyricsConfig(),
  private val enabled: suspend () -> Boolean = { false },
  private val tokenSharingConsent: suspend () -> Boolean = { false },
  private val online: () -> Boolean = { true },
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  private val hostCircuitBreaker: SpicyHostCircuitBreaker = SpicyHostCircuitBreaker(),
) : LyricsProvider {
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }
  private val payloadDecoder = SpicyPayloadDecoder()
  private val directClient = client.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  init {
    require(id in SUPPORTED_PROVIDER_IDS) { "The Spicy query bridge only supports spl, spt, and aml" }
  }

  override suspend fun fetch(request: LyricsRequest): ProviderResult {
    if (!enabled()) {
      return ProviderResult.Unavailable(ProviderUnavailableReason.DISABLED, "Spicy Lyrics is disabled.")
    }
    if (!tokenSharingConsent()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.NOT_CONFIGURED,
        "Spotify token sharing with Spicy Lyrics has not been approved.",
      )
    }
    val trackId = request.track.spotifyTrackId
      ?: return ProviderResult.Unavailable(
        ProviderUnavailableReason.UNSUPPORTED_TRACK,
        "Spicy Lyrics requires an exact Spotify track URI.",
      )

    val cached = if (request.allowCached) cache.get(id, request.track) else null
    when (cached) {
      is CachedLyrics.Hit -> {
        if (cached.isUsableForProvider()) {
          return cached.found("${sourceLabel()} cache")
        }
        cache.invalidate(id, request.track)
      }
      // Builds before the normal-player parity fix queried the API with a
      // Creator-only source hint. A 404 produced by that different contract
      // must never prevent the corrected request from reaching the network.
      is CachedLyrics.Negative -> cache.invalidate(id, request.track)
      null -> Unit
    }
    val stale = (cache.get(id, request.track, allowExpired = true) as? CachedLyrics.Hit)
      ?.takeIf { it.isUsableForProvider() }
    if (!online()) {
      return stale?.found("Offline; showing stale Spicy Lyrics cache")
        ?: ProviderResult.Unavailable(ProviderUnavailableReason.OFFLINE, "Spicy Lyrics is unavailable offline.")
    }
    hostCircuitBreaker.currentMessage()?.let { message ->
      return stale?.found("Host unavailable; showing stale Spicy Lyrics cache")
        ?: ProviderResult.Unavailable(ProviderUnavailableReason.OFFLINE, message)
    }

    val token = tokenSource.accessToken()?.usableAccessToken()
      ?: return ProviderResult.Unavailable(
        ProviderUnavailableReason.AUTH_REQUIRED,
        "Spicy Lyrics needs an experimental Spotify PKCE token.",
      )

    val firstAttempt = execute(trackId, token, request)
    val response = if (firstAttempt.isAuthRejection()) {
      val replacement = tokenSource.refreshAfterRejection(token)
        ?.usableAccessToken()
        ?.takeUnless { it == token }
      if (replacement != null) execute(trackId, replacement, request) else firstAttempt
    } else {
      firstAttempt
    }
    if (response.isAuthRejection()) {
      hostCircuitBreaker.trip("Spicy Lyrics rejected the Spotify authorization for this lookup.")
      log(request, "auth", response.httpStatus(), "Spicy Lyrics rejected the Spotify token.")
    }

    return when (response) {
      QueryAttempt.Queued -> ProviderResult.Queued(
        message = "${sourceLabel()} is still preparing this track.",
      )
      is QueryAttempt.Complete -> when (val parsed = parseQuery(response.envelope, request)) {
        is ParsedQuery.Found -> {
          cache.put(
            provider = id,
            track = request.track,
            document = parsed.document,
            rawPayload = parsed.raw.take(MAX_CACHED_RAW_CHARS),
            rawFormat = cacheFormat(parsed.format),
            sourceVerified = true,
          )
          ProviderResult.Found(
            document = parsed.document,
            rawFormat = parsed.format,
            message = sourceLabel(),
            validatedForProvider = id.takeIf { it == LyricsProviderId.SPICY },
          )
        }
        is ParsedQuery.NotFound -> {
          ProviderResult.NotFound(parsed.message)
        }
        is ParsedQuery.Failed -> parsed.result
      }
      is QueryAttempt.Failed -> stale?.found("Network error; showing stale Spicy Lyrics cache")
        ?: response.result
    }
  }

  private suspend fun execute(
    trackId: String,
    token: String,
    lyricsRequest: LyricsRequest,
  ): QueryAttempt {
    val requestJson = buildJsonObject {
      put("queries", buildJsonArray {
        add(buildJsonObject {
          put("operation", "lyrics")
          putJsonObject("variables") {
            put("id", trackId)
            put("auth", AUTH_HEADER)
            requestedSourceCode()?.let { put("source", it) }
          }
        })
      })
      putJsonObject("client") { put("version", config.compatibilityVersion) }
    }.toString()
    val requestBody = requestJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE)
    val request = Request.Builder()
      .url(config.endpoint)
      // Match desktop Query.buildQueryHeaders exactly. OkHttp's String body
      // otherwise advertises an added charset even though the bytes are JSON.
      .header("Content-Type", "application/json")
      // fetch() supplies these automatically in desktop Spotify's Chromium
      // shell. Native OkHttp does not, so spell out the same browser request
      // context used by the independently verified Android client contract.
      .header("Accept", "*/*")
      .header("Accept-Language", "en-US,en;q=0.9")
      .header("Origin", SPOTIFY_XPUI_ORIGIN)
      .header("Referer", "$SPOTIFY_XPUI_ORIGIN/")
      .header("Sec-Fetch-Dest", "empty")
      .header("Sec-Fetch-Mode", "cors")
      .header("Sec-Fetch-Site", "cross-site")
      .header("SpicyLyrics-Version", config.compatibilityVersion)
      .header("User-Agent", SPOTIFY_DESKTOP_USER_AGENT)
      .header("X-mode", "2")
      .header(AUTH_HEADER, "Bearer $token")
      .post(requestBody)
      .build()

    return try {
      directClient.newCall(request).await().use { response ->
        if (response.isRedirect) {
          hostCircuitBreaker.trip("Spicy Lyrics refused a credential-bearing redirect.")
          return QueryAttempt.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.SECURITY,
              "Spicy Lyrics refused a redirect for a credential-bearing request.",
            ),
          )
        }
        if (response.code == 401 || response.code == 403) {
          return QueryAttempt.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.HTTP,
              "Spicy Lyrics rejected the Spotify token.",
              response.code,
            ),
          )
        }
        if (!response.isSuccessful) {
          if (response.code >= 500) {
            hostCircuitBreaker.trip("Spicy Lyrics is temporarily unavailable after a server error.")
          }
          log(lyricsRequest, "http", response.code, "Spicy Lyrics request failed.")
          return QueryAttempt.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.HTTP,
              "Spicy Lyrics returned HTTP ${response.code}.",
              response.code,
            ),
          )
        }
        val raw = response.readUtf8Limited(config.maxResponseBytes)
        val envelope = runCatching { decodeEnvelope(raw) }.getOrElse { error ->
          return QueryAttempt.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.PARSE,
              error.message ?: "Spicy Lyrics returned an invalid query envelope.",
              response.code,
            ),
          )
        }
        if (envelope.httpStatus == 401 || envelope.httpStatus == 403) {
          return QueryAttempt.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.HTTP,
              "Spicy Lyrics rejected the Spotify token.",
              envelope.httpStatus,
            ),
          )
        }
        // Desktop queues only a 503 in query result slot "0". An outer HTTP
        // 503 is a failed Query request and must not be reinterpreted here.
        if (envelope.httpStatus == 503) QueryAttempt.Queued
        else QueryAttempt.Complete(envelope)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: IOException) {
      hostCircuitBreaker.trip("Spicy Lyrics is temporarily unavailable after a network error.")
      log(lyricsRequest, "network", null, error.message ?: "Spicy Lyrics network error")
      QueryAttempt.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.NETWORK,
          error.message ?: "Could not reach Spicy Lyrics.",
        ),
      )
    } catch (error: Exception) {
      log(lyricsRequest, "failure", null, error.message ?: "Spicy Lyrics request failed")
      QueryAttempt.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.UNKNOWN,
          error.message ?: "Spicy Lyrics request failed.",
        ),
      )
    }
  }

  private fun decodeEnvelope(raw: String): QueryEnvelope {
    val root = json.parseToJsonElement(raw)
    val jobs = (root as? JsonObject)?.get("queries") as? JsonArray
      ?: throw IllegalArgumentException("Spicy Lyrics returned an invalid query response")
    // Spicy can return an HTTP-200 transport envelope whose query result is an
    // auth failure. Some deployments use `status` (and may omit operationId),
    // so identify only 401/403 aliases before applying desktop's slot-0 rule.
    jobs.mapNotNull { it as? JsonObject }
      .firstNotNullOfOrNull { it.innerAuthRejectionStatus() }
      ?.let { return QueryEnvelope(it, null, null) }
    // Query.ts stores jobs by operationId and fetchLyrics.ts reads only "0".
    // Map.set keeps the last duplicate, so mirror that detail as well.
    val job = jobs.mapNotNull { it as? JsonObject }
      .lastOrNull { candidate -> candidate.string("operationId") == "0" }
      ?: return QueryEnvelope(404, null, null)
    val result = job["result"] as? JsonObject
      ?: return QueryEnvelope(404, null, null)
    return QueryEnvelope(
      // fetchLyrics.ts requires a numeric 200/404/503. A missing or string
      // status must not be promoted to a successful response.
      httpStatus = result.int("httpStatus") ?: 0,
      // fetchLyrics.ts reads only lyricsQuery.data. `Result` belongs to decoded
      // lyric payloads, not to the query envelope itself.
      data = result["data"],
      format = result.string("format"),
    )
  }

  private fun parseQuery(envelope: QueryEnvelope, request: LyricsRequest): ParsedQuery {
    if (envelope.httpStatus == 404 || envelope.data == null) {
      return ParsedQuery.NotFound("Spicy Lyrics found no lyrics for this track.")
    }
    if (envelope.httpStatus == 401 || envelope.httpStatus == 403) {
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.HTTP,
          "Spicy Lyrics rejected the Spotify token.",
          envelope.httpStatus,
        ),
      )
    }
    if (envelope.httpStatus != 200) {
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.HTTP,
          "Spicy Lyrics query returned status ${envelope.httpStatus}.",
          envelope.httpStatus,
        ),
      )
    }

    val decoded = runCatching { decodePayload(envelope.data) }.getOrElse { error ->
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.PARSE,
          error.message ?: "Could not decode the Spicy Lyrics payload.",
        ),
      )
    }
    val serialized = when (decoded) {
      is JsonPrimitive -> decoded.contentOrNull.orEmpty()
      else -> json.encodeToString(decoded)
    }
    if (UPDATE_SENTINEL_MARKERS.all { serialized.contains(it, ignoreCase = true) }) {
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.HTTP,
          "Spicy Lyrics requires a newer compatibility version.",
          426,
        ),
      )
    }
    val isRawTtml = decoded is JsonPrimitive &&
      decoded.content.removePrefix("\uFEFF").trimStart().startsWith("<")
    if (decoded is JsonPrimitive && !isRawTtml) {
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.PARSE,
          "Could not decode the Spicy Lyrics payload.",
        ),
      )
    }
    val explicitSource = decoded.explicitSource()
    if (id != LyricsProviderId.SPICY) {
      if (explicitSource == null) {
        return ParsedQuery.NotFound(
          "${sourceLabel()} was unavailable because the response did not identify that source.",
        )
      }
      if (LyricsSource.fromCode(explicitSource) != id.expectedSource) {
        return ParsedQuery.NotFound(
          "${sourceLabel()} was unavailable; Spicy Lyrics returned $explicitSource instead.",
        )
      }
    }

    val document = runCatching {
      when (decoded) {
        is JsonObject -> IcyLyricsJsonParser.parse(
          json.encodeToString(decoded),
          request.track.exactStorageKey,
          sourceOverride = when {
            id != LyricsProviderId.SPICY -> id.expectedSource
            explicitSource == null -> LyricsSource.SPICY
            else -> null
          },
        )
        is JsonPrimitive -> {
          require(decoded.content.removePrefix("\uFEFF").trimStart().startsWith("<")) {
            "Spicy Lyrics returned a non-TTML text payload"
          }
          LyricsFileParser.parse(
            decoded.content,
            request.track.exactStorageKey,
            LyricsSource.SPICY,
            request.track.durationMs,
          )
        }
        else -> throw IllegalArgumentException("Spicy Lyrics payload has an unsupported shape")
      }
    }.getOrElse { error ->
      return ParsedQuery.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.PARSE,
          error.message ?: "Could not parse the Spicy Lyrics document.",
        ),
      )
    }
    val format = envelope.format ?: when {
      serialized.trimStart().startsWith("<") -> "ttml"
      decoded is JsonObject -> "json"
      else -> "text"
    }
    return ParsedQuery.Found(document, serialized, format)
  }

  private fun decodePayload(value: JsonElement, depth: Int = 0): JsonElement {
    require(depth <= 4) { "Spicy payload wrappers are nested too deeply" }
    val unpacked = if (value is JsonArray && value.size == 2 && value.all { it is JsonArray }) {
      payloadDecoder.unpack(value)
    } else {
      value
    }
    if (unpacked is JsonObject && unpacked["Result"] != null) {
      return decodePayload(unpacked.getValue("Result"), depth + 1)
    }
    if (unpacked is JsonPrimitive && unpacked.isString) {
      val content = unpacked.content.trim()
      if (content.startsWith("{") || content.startsWith("[")) {
        return decodePayload(json.parseToJsonElement(content), depth + 1)
      }
    }
    return unpacked
  }

  private suspend fun log(request: LyricsRequest, code: String, status: Int?, message: String) {
    diagnostics.record(
      DiagnosticInput(
        severity = DiagnosticSeverity.WARNING,
        component = "spicy-query",
        code = code,
        provider = id,
        trackKey = request.track.exactStorageKey,
        httpStatus = status,
        message = message,
      ),
    )
  }

  private fun CachedLyrics.Hit.found(message: String) = ProviderResult.Found(
    document = document,
    fromCache = true,
    rawFormat = publicRawFormat(),
    message = message,
    validatedForProvider = id.takeIf { it == LyricsProviderId.SPICY },
  )

  private fun CachedLyrics.Hit.isUsableForProvider(): Boolean =
    sourceVerified && if (id == LyricsProviderId.SPICY) {
      // Positive rows written before the normal-player parity fix came from a
      // Creator-hinted request. Refresh them once instead of allowing an old
      // line-synced fallback to mask the corrected automatic desktop query.
      rawFormat?.startsWith(PRIMARY_CACHE_FORMAT_PREFIX) == true
    } else {
      document.metadata.source == id.expectedSource
    }

  private fun cacheFormat(format: String): String =
    if (id == LyricsProviderId.SPICY) "$PRIMARY_CACHE_FORMAT_PREFIX$format" else format

  private fun CachedLyrics.Hit.publicRawFormat(): String? =
    if (id == LyricsProviderId.SPICY) rawFormat?.removePrefix(PRIMARY_CACHE_FORMAT_PREFIX) else rawFormat

  private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

  private fun JsonObject.int(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

  private fun JsonObject.innerAuthRejectionStatus(): Int? {
    val result = (get("result") ?: get("Result")) as? JsonObject ?: return null
    val status = listOf("httpStatus", "HttpStatus", "status", "Status")
      .firstNotNullOfOrNull { key -> (result[key] as? JsonPrimitive)?.intOrNull }
    return status?.takeIf { it == 401 || it == 403 }
  }

  private fun JsonElement.explicitSource(): String? {
    val obj = this as? JsonObject ?: return null
    return obj.string("source") ?: obj.string("Source") ?: obj.string("provider")
      ?: obj.string("Provider")
      ?: obj["Result"]?.explicitSource()
  }

  private fun requestedSourceCode(): String? = when (id) {
    // The primary route must be byte-for-byte equivalent to normal desktop
    // playback. Source hints exist only for the later explicit fallbacks.
    LyricsProviderId.SPICY -> null
    LyricsProviderId.SPOTIFY -> LyricsSource.SPOTIFY.code
    LyricsProviderId.APPLE_MUSIC -> LyricsSource.APPLE_MUSIC.code
    else -> null
  }

  private fun sourceLabel(): String = when (id) {
    LyricsProviderId.SPICY -> "Spicy Lyrics database"
    LyricsProviderId.SPOTIFY -> "Spotify-backed lyrics"
    LyricsProviderId.APPLE_MUSIC -> "Apple Music-backed lyrics"
    else -> "Spicy Lyrics"
  }

  private fun String.usableAccessToken(): String? = trim()
    .takeIf { it.isNotEmpty() && it != "0" }

  private fun QueryAttempt.isAuthRejection(): Boolean =
    this is QueryAttempt.Failed && result.httpStatus in AUTH_REJECTION_STATUSES

  private fun QueryAttempt.httpStatus(): Int? =
    (this as? QueryAttempt.Failed)?.result?.httpStatus

  private data class QueryEnvelope(
    val httpStatus: Int,
    val data: JsonElement?,
    val format: String?,
  )

  private sealed interface QueryAttempt {
    data class Complete(val envelope: QueryEnvelope) : QueryAttempt
    data object Queued : QueryAttempt
    data class Failed(val result: ProviderResult.Failure) : QueryAttempt
  }

  private sealed interface ParsedQuery {
    data class Found(
      val document: LyricsDocument,
      val raw: String,
      val format: String,
    ) : ParsedQuery
    data class NotFound(val message: String) : ParsedQuery
    data class Failed(val result: ProviderResult.Failure) : ParsedQuery
  }

  companion object {
    private const val AUTH_HEADER = "SpicyLyrics-WebAuth"
    private const val SPOTIFY_XPUI_ORIGIN = "https://xpui.app.spotify.com"
    private const val SPOTIFY_DESKTOP_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Spotify/1.2.63 Chrome/132.0.6834.210 Electron/34.3.1 Safari/537.36"
    private const val PRIMARY_CACHE_FORMAT_PREFIX = "spicy-auto-v1:"
    private const val MAX_CACHED_RAW_CHARS = 1_000_000
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val UPDATE_SENTINEL_MARKERS = listOf(
      "please update spicy lyrics",
      "you can do so immediately by restarting spotify",
      "the cool spicetify extension",
    )
    private val AUTH_REJECTION_STATUSES = setOf(401, 403)
    private val SUPPORTED_PROVIDER_IDS = setOf(
      LyricsProviderId.SPICY,
      LyricsProviderId.SPOTIFY,
      LyricsProviderId.APPLE_MUSIC,
    )
  }
}
