package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.platform.network.use
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.parser.LrcParser
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
import com.icy.lyrics.core.platform.network.readUtf8Limited
import com.icy.lyrics.core.platform.network.retryAfterMs
import com.icy.lyrics.core.platform.storage.CachedLyrics
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import com.icy.lyrics.core.platform.network.NetworkException
import com.icy.lyrics.core.platform.runtime.normalizeNfd
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.icy.lyrics.core.platform.network.Headers
import com.icy.lyrics.core.platform.network.HttpUrl
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.network.LyricsHttpClient
import com.icy.lyrics.core.platform.network.Request

data class LrclibConfig(
  val baseUrl: HttpUrl = "https://lrclib.net/api/".toHttpUrl(),
  val userAgent: String = "IcyLyricsAndroidV2/1.0",
  val requestSpacingMs: Long = 300L,
  val maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  val allowInsecureForTests: Boolean = false,
) {
  init {
    require(baseUrl.isHttps || allowInsecureForTests) { "LRCLIB must use HTTPS" }
    require(userAgent.isNotBlank()) { "LRCLIB requires an identifiable User-Agent" }
    require(requestSpacingMs in 0L..10_000L)
    require(maxResponseBytes in 1L..8L * 1_024L * 1_024L)
  }
}

class LrclibProvider(
  private val client: LyricsHttpClient,
  private val cache: LyricsCacheRepository,
  private val config: LrclibConfig = LrclibConfig(),
  private val enabled: suspend () -> Boolean = { true },
  private val online: () -> Boolean = { true },
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  private val wait: suspend (Long) -> Unit = { delay(it) },
) : LyricsProvider {
  override val id = LyricsProviderId.LRCLIB
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }

  override suspend fun fetch(request: LyricsRequest): ProviderResult {
    if (!enabled()) {
      return ProviderResult.Unavailable(ProviderUnavailableReason.DISABLED, "LRCLIB is disabled.")
    }

    val cached = if (request.allowCached) cache.get(id, request.track) else null
    when (cached) {
      is CachedLyrics.Hit -> if (
        cached.sourceVerified && cached.document.metadata.source == LyricsSource.LRCLIB
      ) {
        return cached.found("LRCLIB cache")
      } else {
        cache.invalidate(id, request.track)
      }
      is CachedLyrics.Negative -> return ProviderResult.NotFound("LRCLIB negative cache")
      null -> Unit
    }

    val stale = (cache.get(id, request.track, allowExpired = true) as? CachedLyrics.Hit)
      ?.takeIf { it.sourceVerified && it.document.metadata.source == LyricsSource.LRCLIB }
    if (!online()) {
      return stale?.found("Offline; showing stale LRCLIB cache")
        ?: ProviderResult.Unavailable(ProviderUnavailableReason.OFFLINE, "LRCLIB is unavailable offline.")
    }

    val title = request.track.title.trim()
    val artist = request.track.artists.joinToString(", ").trim()
    if (title.isBlank() || artist.isBlank()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.UNSUPPORTED_TRACK,
        "LRCLIB requires title and artist metadata.",
      )
    }

    val exact = getExact(request, title, artist)
    val exactParsed = when (exact) {
      is LrclibHttp.Success -> when (val parsed = parseSingle(exact.body, request)) {
        is ParseOutcome.Found -> parsed.lyrics
        ParseOutcome.NoLyrics -> null
        ParseOutcome.Instrumental -> {
          cache.putNegative(id, request.track)
          return ProviderResult.NotFound("LRCLIB marks this track as instrumental.")
        }
        is ParseOutcome.Malformed -> return parseFailure(request, "get", parsed.message, stale)
      }
      is LrclibHttp.NotFound -> null
      is LrclibHttp.Failed -> return stale?.found("Network error; showing stale LRCLIB cache")
        ?: exact.result
    }
    if (exactParsed != null && exactParsed.document.syncKind != LyricsSyncKind.STATIC) {
      return saveAndReturn(request, exactParsed)
    }

    if (config.requestSpacingMs > 0L) wait(config.requestSpacingMs)
    val search = search(request, title, artist)
    val searched = when (search) {
      is LrclibHttp.Success -> when (val parsed = parseSearch(search.body, request)) {
        is ParseOutcome.Found -> parsed.lyrics
        ParseOutcome.NoLyrics,
        ParseOutcome.Instrumental,
        -> null
        is ParseOutcome.Malformed -> {
          if (exactParsed != null) return saveAndReturn(request, exactParsed)
          return parseFailure(request, "search", parsed.message, stale)
        }
      }
      is LrclibHttp.NotFound -> null
      is LrclibHttp.Failed -> {
        if (exactParsed != null) return saveAndReturn(request, exactParsed)
        return stale?.found("Network error; showing stale LRCLIB cache") ?: search.result
      }
    }
    val selected = listOfNotNull(exactParsed, searched).maxWithOrNull(
      compareBy<ParsedLyrics> { it.document.syncKind.quality }.thenBy { it.matchScore },
    )
    if (selected != null) return saveAndReturn(request, selected)

    cache.putNegative(id, request.track)
    return ProviderResult.NotFound("LRCLIB found no confident lyric match.")
  }

  private suspend fun getExact(request: LyricsRequest, title: String, artist: String): LrclibHttp {
    val url = config.baseUrl.newBuilder()
      .addPathSegment("get")
      .addQueryParameter("track_name", title)
      .addQueryParameter("artist_name", artist)
      .apply {
        request.track.album.takeIf(String::isNotBlank)?.let { addQueryParameter("album_name", it) }
        request.track.durationMs?.takeIf { it > 0L }
          ?.let { addQueryParameter("duration", (it / 1_000.0).toString()) }
      }
      .build()
    return execute(url, request, "get")
  }

  private suspend fun search(request: LyricsRequest, title: String, artist: String): LrclibHttp {
    val url = config.baseUrl.newBuilder()
      .addPathSegment("search")
      .addQueryParameter("track_name", title)
      .addQueryParameter("artist_name", artist)
      .apply {
        request.track.album.takeIf(String::isNotBlank)?.let { addQueryParameter("album_name", it) }
      }
      .build()
    return execute(url, request, "search")
  }

  private suspend fun execute(
    url: HttpUrl,
    request: LyricsRequest,
    operation: String,
    rateLimitAttempt: Int = 0,
  ): LrclibHttp {
    val httpRequest = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .header("User-Agent", config.userAgent)
      .get()
      .build()
    return try {
      client.execute(httpRequest, config.maxResponseBytes).use { response ->
        if (response.isRedirect) {
          return LrclibHttp.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.SECURITY,
              "LRCLIB refused an unexpected redirect.",
            ),
          )
        }
        if (response.code == 404) return LrclibHttp.NotFound
        if (response.code == 429 && rateLimitAttempt == 0) {
          val retryAfter = response.headers.retryAfterMs() ?: DEFAULT_RATE_LIMIT_DELAY_MS
          if (retryAfter <= MAX_INLINE_RATE_LIMIT_DELAY_MS) {
            response.close()
            wait(retryAfter)
            return execute(url, request, operation, rateLimitAttempt = 1)
          }
        }
        if (!response.isSuccessful) {
          val retryAfter = response.headers.retryAfterMs()
          log(request, "$operation-http", response.code, "LRCLIB request failed.")
          return LrclibHttp.Failed(
            ProviderResult.Failure(
              ProviderFailureCategory.HTTP,
              "LRCLIB returned HTTP ${response.code}.",
              response.code,
              retryAfter,
            ),
          )
        }
        LrclibHttp.Success(response.readUtf8Limited(config.maxResponseBytes), response.headers)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: NetworkException) {
      log(request, "$operation-network", null, error.message ?: "LRCLIB network error")
      LrclibHttp.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.NETWORK,
          error.message ?: "Could not reach LRCLIB.",
        ),
      )
    } catch (error: Exception) {
      log(request, "$operation-failure", null, error.message ?: "LRCLIB request failed")
      LrclibHttp.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.UNKNOWN,
          error.message ?: "LRCLIB request failed.",
        ),
      )
    }
  }

  private fun parseSingle(raw: String, request: LyricsRequest): ParseOutcome {
    val entry = runCatching { json.decodeFromString<LrclibEntry>(raw) }
      .getOrElse { return ParseOutcome.Malformed("LRCLIB returned malformed exact-match JSON.") }
    if (entry.instrumental) return ParseOutcome.Instrumental
    return parseEntry(entry, request, matchScore = Int.MAX_VALUE)
      ?.let { ParseOutcome.Found(it) }
      ?: ParseOutcome.NoLyrics
  }

  private fun parseSearch(raw: String, request: LyricsRequest): ParseOutcome {
    val entries = runCatching { json.decodeFromString<List<LrclibEntry>>(raw) }
      .getOrElse { return ParseOutcome.Malformed("LRCLIB returned malformed search JSON.") }
    return entries.mapNotNull { entry ->
      val score = entry.matchScore(request)
      if (score < MIN_SEARCH_SCORE) null else parseEntry(entry, request, score)
    }.maxWithOrNull(compareBy<ParsedLyrics> { it.document.syncKind.quality }.thenBy { it.matchScore })
      ?.let { ParseOutcome.Found(it) }
      ?: ParseOutcome.NoLyrics
  }

  private fun parseEntry(entry: LrclibEntry, request: LyricsRequest, matchScore: Int): ParsedLyrics? {
    if (entry.instrumental) return null
    val candidates = listOfNotNull(
      entry.lyricsFile?.takeIf(String::isNotBlank)?.let { Triple("lyricsfile", it, true) },
      entry.syncedLyrics?.takeIf(String::isNotBlank)?.let { Triple("lrc", it, false) },
      entry.plainLyrics?.takeIf(String::isNotBlank)?.let { Triple("plain", it, false) },
    )
    for ((format, raw, _) in candidates) {
      val parsed = runCatching {
        when (format) {
          "lyricsfile" -> LyricsFileParser.parse(
            raw,
            request.track.exactStorageKey,
            LyricsSource.LRCLIB,
            request.track.durationMs,
          )
          "lrc" -> LrcParser.parse(
            raw,
            request.track.exactStorageKey,
            LyricsSource.LRCLIB,
            request.track.durationMs,
          )
          else -> LrcParser.parsePlain(raw, request.track.exactStorageKey, LyricsSource.LRCLIB)
        }
      }.getOrNull() ?: continue
      return ParsedLyrics(parsed, raw, format, matchScore)
    }
    return null
  }

  private suspend fun saveAndReturn(request: LyricsRequest, parsed: ParsedLyrics?): ProviderResult {
    if (parsed == null) return ProviderResult.NotFound("LRCLIB returned no displayable lyrics.")
    cache.put(
      provider = id,
      track = request.track,
      document = parsed.document,
      rawPayload = parsed.raw,
      rawFormat = parsed.format,
      sourceVerified = parsed.document.metadata.source == LyricsSource.LRCLIB,
    )
    return ProviderResult.Found(parsed.document, rawFormat = parsed.format, message = "LRCLIB")
  }

  private suspend fun parseFailure(
    request: LyricsRequest,
    operation: String,
    message: String,
    stale: CachedLyrics.Hit?,
  ): ProviderResult {
    log(request, "$operation-parse", null, message)
    return stale?.found("Parse error; showing stale LRCLIB cache")
      ?: ProviderResult.Failure(ProviderFailureCategory.PARSE, message)
  }

  private suspend fun log(
    request: LyricsRequest,
    code: String,
    httpStatus: Int?,
    message: String,
  ) {
    diagnostics.record(
      DiagnosticInput(
        severity = DiagnosticSeverity.WARNING,
        component = "lrclib",
        code = code,
        provider = id,
        trackKey = request.track.exactStorageKey,
        httpStatus = httpStatus,
        message = message,
      ),
    )
  }

  private fun CachedLyrics.Hit.found(message: String) = ProviderResult.Found(
    document = document,
    fromCache = true,
    rawFormat = rawFormat,
    message = message,
  )

  private sealed interface LrclibHttp {
    data class Success(val body: String, val headers: Headers) : LrclibHttp
    data object NotFound : LrclibHttp
    data class Failed(val result: ProviderResult.Failure) : LrclibHttp
  }

  private data class ParsedLyrics(
    val document: LyricsDocument,
    val raw: String,
    val format: String,
    val matchScore: Int,
  )

  private sealed interface ParseOutcome {
    data class Found(val lyrics: ParsedLyrics) : ParseOutcome
    data object NoLyrics : ParseOutcome
    data object Instrumental : ParseOutcome
    data class Malformed(val message: String) : ParseOutcome
  }

  @Serializable
  private data class LrclibEntry(
    val id: Long? = null,
    val name: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    @SerialName("lyricsfile") val lyricsFile: String? = null,
  ) {
    fun matchScore(request: LyricsRequest): Int {
      var score = 0
      val expectedTitle = normalize(request.track.title)
      val actualTitle = normalize(trackName ?: name.orEmpty())
      if (expectedTitle.isNotEmpty() && actualTitle == expectedTitle) score += 7
      else if (expectedTitle.isNotEmpty() && (actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle))) score += 3

      val expectedArtists = request.track.artists.map(::normalize).filter(String::isNotEmpty)
      val actualArtist = normalize(artistName.orEmpty())
      if (expectedArtists.any { it == actualArtist }) score += 6
      else if (expectedArtists.any { actualArtist.contains(it) || it.contains(actualArtist) }) score += 3

      if (request.track.album.isNotBlank() && normalize(request.track.album) == normalize(albumName.orEmpty())) score += 2
      val expectedDuration = request.track.durationMs
      val actualDuration = duration?.times(1_000.0)?.toLong()
      if (expectedDuration != null && actualDuration != null) {
        val durationDelta = abs(expectedDuration - actualDuration)
        if (durationDelta > MAX_DURATION_MISMATCH_MS) return 0
        if (durationDelta <= 2_000L) score += 3
      }
      return score
    }
  }

  private val LyricsSyncKind.quality: Int
    get() = when (this) {
      LyricsSyncKind.STATIC -> 1
      LyricsSyncKind.LINE -> 2
      LyricsSyncKind.SYLLABLE -> 3
    }

  companion object {
    private const val MIN_SEARCH_SCORE = 13
    private const val MAX_DURATION_MISMATCH_MS = 8_000L
    private const val DEFAULT_RATE_LIMIT_DELAY_MS = 1_000L
    private const val MAX_INLINE_RATE_LIMIT_DELAY_MS = 30_000L

    private fun normalize(value: String): String {
      return normalizeNfd(value)
        .replace(Regex("""\p{Mn}+"""), "")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
    }
  }
}
