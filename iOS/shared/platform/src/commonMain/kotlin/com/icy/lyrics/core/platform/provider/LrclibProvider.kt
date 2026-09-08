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
import com.icy.lyrics.core.platform.runtime.epochMillis
import com.icy.lyrics.core.platform.runtime.normalizeNfd
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  val userAgent: String =
    "IcyLyricsAndroid/1.1.0 (https://jackscurrie.com/icy-lyrics; jack@jackscurrie.com)",
  val requestSpacingMs: Long = 300L,
  val maxResponseBytes: Long = 2L * 1_024L * 1_024L,
  val allowInsecureForTests: Boolean = false,
  val negativeCachePolicyEpochMs: Long = 1_788_595_200_000L,
) {
  init {
    require(baseUrl.isHttps || allowInsecureForTests) { "LRCLIB must use HTTPS" }
    require(userAgent.isNotBlank()) { "LRCLIB requires an identifiable User-Agent" }
    require(
      requestSpacingMs in 200L..500L || (allowInsecureForTests && requestSpacingMs == 0L),
    ) { "LRCLIB sequential requests must be spaced by 200-500 ms" }
    require(negativeCachePolicyEpochMs >= 0L)
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
  private val clock: () -> Long = ::epochMillis,
) : LyricsProvider {
  override val id = LyricsProviderId.LRCLIB
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }
  private val rateLimitMutex = Mutex()
  private var rateLimitedUntilEpochMs = 0L

  override suspend fun fetch(request: LyricsRequest): ProviderResult {
    if (!enabled()) {
      return ProviderResult.Unavailable(ProviderUnavailableReason.DISABLED, "LRCLIB is disabled.")
    }

    val cached = if (request.allowCached) cache.get(id, request.track) else null
    when (cached) {
      is CachedLyrics.Hit -> if (
        cached.sourceVerified &&
        cached.document.metadata.source == LyricsSource.LRCLIB &&
        cached.rawFormat?.startsWith(CACHE_FORMAT_PREFIX) == true &&
        cached.document.syncKind != LyricsSyncKind.STATIC
      ) {
        return cached.found("LRCLIB cache")
      } else if (!cached.sourceVerified ||
        cached.document.metadata.source != LyricsSource.LRCLIB ||
        cached.rawFormat?.startsWith(CACHE_FORMAT_PREFIX) != true
      ) {
        cache.invalidate(id, request.track)
      }
      is CachedLyrics.Negative -> if (
        cached.fetchedAtEpochMs >= config.negativeCachePolicyEpochMs
      ) {
        return ProviderResult.NotFound("LRCLIB negative cache")
      } else {
        // Query and matching policy v2 fixes polluted Spotify artist metadata and
        // broadens the search ladder. Negatives written by the old policy are not
        // evidence that LRCLIB lacks lyrics, so invalidate them once on upgrade.
        cache.invalidate(id, request.track)
      }
      null -> Unit
    }

    val stale = (cache.get(id, request.track, allowExpired = true) as? CachedLyrics.Hit)
      ?.takeIf {
        it.sourceVerified &&
          it.document.metadata.source == LyricsSource.LRCLIB &&
          it.rawFormat?.startsWith(CACHE_FORMAT_PREFIX) == true
      }
    if (!online()) {
      return stale?.found("Offline; showing stale LRCLIB cache")
        ?: ProviderResult.Unavailable(ProviderUnavailableReason.OFFLINE, "LRCLIB is unavailable offline.")
    }
    currentRateLimitDelayMs()?.let { retryAfterMs ->
      return stale?.found("Rate limited; showing stale LRCLIB cache")
        ?: ProviderResult.Queued(
          retryAfterMs = retryAfterMs,
          message = "LRCLIB is temporarily rate-limited.",
        )
    }

    val query = LrclibQueryIdentity.from(request)
    if (query.title.isBlank() || query.artist.isBlank()) {
      return ProviderResult.Unavailable(
        ProviderUnavailableReason.UNSUPPORTED_TRACK,
        "LRCLIB requires title and artist metadata.",
      )
    }

    var requestCount = 0
    suspend fun spaced(block: suspend () -> LrclibHttp): LrclibHttp {
      if (requestCount > 0 && config.requestSpacingMs > 0L) wait(config.requestSpacingMs)
      requestCount += 1
      return block()
    }

    var best: ParsedLyrics? = null
    var lastFailure: ProviderResult.Failure? = null
    var rejectedIdentityCandidate = false

    when (val exact = spaced { getExact(request, query) }) {
      is LrclibHttp.Success -> when (val parsed = parseSingle(exact.body, request, query)) {
        is ParseOutcome.Found -> best = parsed.lyrics
        ParseOutcome.NoLyrics,
        ParseOutcome.Instrumental,
        -> Unit
        ParseOutcome.Rejected -> rejectedIdentityCandidate = true
        is ParseOutcome.Malformed -> {
          log(request, "get-parse", null, parsed.message)
          lastFailure = ProviderResult.Failure(ProviderFailureCategory.PARSE, parsed.message)
        }
      }
      is LrclibHttp.NotFound -> Unit
      is LrclibHttp.Failed -> {
        if (exact.result.httpStatus == 429) return rateLimited(request, exact.result, stale)
        lastFailure = exact.result
      }
    }
    best?.takeIf { it.document.syncKind == LyricsSyncKind.SYLLABLE }
      ?.let { return saveAndReturn(request, it) }

    for (variant in searchVariants(query)) {
      when (val search = spaced { search(request, variant) }) {
        is LrclibHttp.Success -> when (val parsed = parseSearch(search.body, request, query)) {
          is ParseOutcome.Found -> {
            best = selectBetter(best, parsed.lyrics)
            best?.takeIf {
              it.document.syncKind == LyricsSyncKind.SYLLABLE &&
                it.matchScore >= MAX_MATCH_SCORE
            }?.let { return saveAndReturn(request, it) }
          }
          ParseOutcome.NoLyrics,
          ParseOutcome.Instrumental,
          -> Unit
          ParseOutcome.Rejected -> rejectedIdentityCandidate = true
          is ParseOutcome.Malformed -> {
            log(request, "${variant.operation}-parse", null, parsed.message)
            lastFailure = ProviderResult.Failure(ProviderFailureCategory.PARSE, parsed.message)
          }
        }
        is LrclibHttp.NotFound -> Unit
        is LrclibHttp.Failed -> {
          if (search.result.httpStatus == 429) {
            return rateLimited(request, search.result, stale, best)
          }
          lastFailure = search.result
        }
      }
    }
    if (best != null) return saveAndReturn(request, best)
    if (lastFailure != null) {
      return stale?.found("Network error; showing stale LRCLIB cache") ?: lastFailure
    }
    if (rejectedIdentityCandidate) {
      return stale?.found("No confident fresh match; showing stale LRCLIB cache")
        ?: ProviderResult.NotFound("LRCLIB results did not match this exact track identity.")
    }

    cache.putNegative(id, request.track, ttlMs = NEGATIVE_CACHE_TTL_MS)
    return ProviderResult.NotFound("LRCLIB found no confident lyric match.")
  }

  private suspend fun getExact(request: LyricsRequest, query: LrclibQueryIdentity): LrclibHttp {
    val url = config.baseUrl.newBuilder()
      .addPathSegment("get")
      .addQueryParameter("track_name", query.title)
      .addQueryParameter("artist_name", query.artist)
      .apply {
        query.album.takeIf(String::isNotBlank)?.let { addQueryParameter("album_name", it) }
        query.durationMs?.takeIf { it > 0L }
          ?.let { addQueryParameter("duration", (it / 1_000.0).toString()) }
      }
      .build()
    return execute(url, request, "get")
  }

  private fun searchVariants(query: LrclibQueryIdentity): List<SearchVariant> = buildList {
    if (query.album.isNotBlank()) {
      add(SearchVariant.Structured(query.title, query.artist, query.album, "search-album"))
    }
    add(SearchVariant.Structured(query.title, query.artist, null, "search-artist"))
    add(SearchVariant.Broad("${query.title} ${query.artist}".trim(), "search-broad"))
    add(SearchVariant.Structured(query.title, null, null, "search-title"))
    stripTitleSuffixes(query.title)
      .takeIf { normalize(it) != normalize(query.title) }
      ?.let { add(SearchVariant.Structured(it, null, null, "search-title-base")) }
  }.distinctBy(SearchVariant::key)

  private suspend fun search(request: LyricsRequest, variant: SearchVariant): LrclibHttp {
    val builder = config.baseUrl.newBuilder()
      .addPathSegment("search")
    when (variant) {
      is SearchVariant.Structured -> builder
        .addQueryParameter("track_name", variant.title)
        .apply {
          variant.artist?.takeIf(String::isNotBlank)
            ?.let { addQueryParameter("artist_name", it) }
          variant.album?.takeIf(String::isNotBlank)
            ?.let { addQueryParameter("album_name", it) }
        }
      is SearchVariant.Broad -> builder.addQueryParameter("q", variant.query)
    }
    return execute(builder.build(), request, variant.operation)
  }

  private sealed interface SearchVariant {
    val operation: String
    val key: String

    data class Structured(
      val title: String,
      val artist: String?,
      val album: String?,
      override val operation: String,
    ) : SearchVariant {
      override val key: String = "structured|$title|$artist|${album.orEmpty()}"
    }

    data class Broad(
      val query: String,
      override val operation: String,
    ) : SearchVariant {
      override val key: String = "broad|$query"
    }
  }

  private suspend fun execute(
    url: HttpUrl,
    request: LyricsRequest,
    operation: String,
    retryAttempt: Int = 0,
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
        if (response.code in RETRYABLE_HTTP_CODES && retryAttempt == 0) {
          val retryAfter = response.headers.retryAfterMs()
            ?: if (response.code == 429) DEFAULT_RATE_LIMIT_DELAY_MS else DEFAULT_SERVER_RETRY_DELAY_MS
          if (retryAfter <= MAX_INLINE_RATE_LIMIT_DELAY_MS) {
            response.close()
            wait(retryAfter)
            return execute(url, request, operation, retryAttempt = 1)
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
      val detail = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}"
        .trim().trimEnd(':')
        .ifBlank { "LRCLIB request failed" }
      log(request, "$operation-failure", null, detail)
      LrclibHttp.Failed(
        ProviderResult.Failure(
          ProviderFailureCategory.UNKNOWN,
          detail,
        ),
      )
    }
  }

  private fun parseSingle(
    raw: String,
    request: LyricsRequest,
    query: LrclibQueryIdentity,
  ): ParseOutcome {
    val entry = runCatching { json.decodeFromString<LrclibEntry>(raw) }
      .getOrElse { return ParseOutcome.Malformed("LRCLIB returned malformed exact-match JSON.") }
    val match = entry.match(query) ?: return ParseOutcome.Rejected
    if (entry.instrumental) return ParseOutcome.Instrumental
    return parseEntry(entry, request, match.score)
      ?.let { ParseOutcome.Found(it) }
      ?: ParseOutcome.NoLyrics
  }

  private fun parseSearch(
    raw: String,
    request: LyricsRequest,
    query: LrclibQueryIdentity,
  ): ParseOutcome {
    val entries = runCatching { json.decodeFromString<List<LrclibEntry>>(raw) }
      .getOrElse { return ParseOutcome.Malformed("LRCLIB returned malformed search JSON.") }
    val matchedEntries = entries.mapNotNull { entry ->
      val match = entry.match(query) ?: return@mapNotNull null
      entry to match
    }
    if (entries.isNotEmpty() && matchedEntries.isEmpty()) return ParseOutcome.Rejected
    return matchedEntries.mapNotNull { (entry, match) ->
      parseEntry(entry, request, match.score)
    }.maxWithOrNull(PARSED_LYRICS_COMPARATOR)
      ?.let { ParseOutcome.Found(it) }
      ?: ParseOutcome.NoLyrics
  }

  private fun parseEntry(entry: LrclibEntry, request: LyricsRequest, matchScore: Int): ParsedLyrics? {
    if (entry.instrumental) return null
    val candidates = listOfNotNull(
      entry.lyricsFile?.takeIf(String::isNotBlank)?.let { RawCandidate("lyricsfile", it, 3) },
      entry.syncedLyrics?.takeIf(String::isNotBlank)?.let { RawCandidate("lrc", it, 2) },
      entry.plainLyrics?.takeIf(String::isNotBlank)?.let { RawCandidate("plain", it, 1) },
    )
    return candidates.mapNotNull { candidate ->
      val parsed = runCatching {
        when (candidate.format) {
          "lyricsfile" -> LyricsFileParser.parse(
            candidate.raw,
            request.track.exactStorageKey,
            LyricsSource.LRCLIB,
            request.track.durationMs,
          )
          "lrc" -> LrcParser.parse(
            candidate.raw,
            request.track.exactStorageKey,
            LyricsSource.LRCLIB,
            request.track.durationMs,
          )
          else -> LrcParser.parsePlain(
            candidate.raw,
            request.track.exactStorageKey,
            LyricsSource.LRCLIB,
          )
        }
      }.getOrNull() ?: return@mapNotNull null
      ParsedLyrics(parsed, candidate.raw, candidate.format, matchScore, candidate.formatPriority)
    }.maxWithOrNull(PARSED_LYRICS_COMPARATOR)
  }

  private fun selectBetter(first: ParsedLyrics?, second: ParsedLyrics?): ParsedLyrics? =
    listOfNotNull(first, second).maxWithOrNull(PARSED_LYRICS_COMPARATOR)

  private suspend fun saveAndReturn(request: LyricsRequest, parsed: ParsedLyrics?): ProviderResult {
    if (parsed == null) return ProviderResult.NotFound("LRCLIB returned no displayable lyrics.")
    cache.put(
      provider = id,
      track = request.track,
      document = parsed.document,
      rawPayload = parsed.raw,
      rawFormat = "$CACHE_FORMAT_PREFIX${parsed.format}",
      sourceVerified = parsed.document.metadata.source == LyricsSource.LRCLIB,
    )
    return ProviderResult.Found(parsed.document, rawFormat = parsed.format, message = "LRCLIB")
  }

  private suspend fun currentRateLimitDelayMs(): Long? = rateLimitMutex.withLock {
    (rateLimitedUntilEpochMs - clock()).takeIf { it > 0L }
  }

  private suspend fun rateLimited(
    request: LyricsRequest,
    failure: ProviderResult.Failure,
    stale: CachedLyrics.Hit?,
    best: ParsedLyrics? = null,
  ): ProviderResult {
    val retryAfterMs = rateLimitMutex.withLock {
      val requestedDelay = (failure.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOL_DOWN_MS)
        .coerceIn(MIN_RATE_LIMIT_COOL_DOWN_MS, MAX_RATE_LIMIT_COOL_DOWN_MS)
      rateLimitedUntilEpochMs = maxOf(rateLimitedUntilEpochMs, clock() + requestedDelay)
      (rateLimitedUntilEpochMs - clock()).coerceAtLeast(MIN_RATE_LIMIT_COOL_DOWN_MS)
    }
    log(request, "rate-limit-circuit", 429, "LRCLIB rate limit opened the request cooldown.")
    if (best != null) return saveAndReturn(request, best)
    return stale?.found("Rate limited; showing stale LRCLIB cache")
      ?: ProviderResult.Queued(
        retryAfterMs = retryAfterMs,
        message = "LRCLIB is temporarily rate-limited.",
      )
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
    rawFormat = rawFormat?.removePrefix(CACHE_FORMAT_PREFIX),
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
    val formatPriority: Int,
  )

  private data class RawCandidate(
    val format: String,
    val raw: String,
    val formatPriority: Int,
  )

  private data class LrclibQueryIdentity(
    val title: String,
    val artists: List<String>,
    val artist: String,
    val album: String,
    val durationMs: Long?,
  ) {
    companion object {
      fun from(request: LyricsRequest): LrclibQueryIdentity {
        val artists = request.track.artists
          .flatMap(::splitRepeatedMetadata)
          .map(::removeSpotifyQualityBadge)
          .filter(String::isNotBlank)
          .distinctBy(::normalize)
        return LrclibQueryIdentity(
          title = request.track.title.trim(),
          artists = artists,
          artist = artists.joinToString(", "),
          album = collapseRepeatedMetadata(request.track.album),
          durationMs = request.track.durationMs,
        )
      }
    }
  }

  private data class LrclibMatch(val score: Int)

  private sealed interface ParseOutcome {
    data class Found(val lyrics: ParsedLyrics) : ParseOutcome
    data object NoLyrics : ParseOutcome
    data object Instrumental : ParseOutcome
    data object Rejected : ParseOutcome
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
    fun match(query: LrclibQueryIdentity): LrclibMatch? {
      val expectedTitle = normalize(query.title)
      val actualTitle = normalize(trackName ?: name.orEmpty())
      if (expectedTitle.isBlank() || actualTitle.isBlank()) return null

      val expectedArtistCredits = query.artists
        .flatMap(::splitArtistCredits)
        .map(::normalize)
        .filter(String::isNotEmpty)
      val actualArtistCredits = splitArtistCredits(artistName.orEmpty())
        .map(::removeSpotifyQualityBadge)
        .map(::normalize)
        .filter(String::isNotEmpty)
      val expectedArtists = expectedArtistCredits.toSet()
      val actualArtists = actualArtistCredits.toSet()
      if (expectedArtists.isEmpty() || actualArtists.isEmpty()) return null
      val combinedArtistMatches = normalize(query.artist) == normalize(artistName.orEmpty())
      val creditSetMatches = expectedArtists == actualArtists
      val sharedArtistCredits = expectedArtists.intersect(actualArtists)

      val expectedAlbums = splitRepeatedMetadata(query.album).map(::normalize).filter(String::isNotEmpty)
      val actualAlbums = splitRepeatedMetadata(albumName.orEmpty()).map(::normalize).filter(String::isNotEmpty)
      val albumMatches = expectedAlbums.isNotEmpty() && expectedAlbums.any(actualAlbums::contains)

      val expectedDuration = query.durationMs
      val actualDuration = duration?.times(1_000.0)?.toLong()
      var durationScore = 0
      if (expectedDuration != null && actualDuration != null) {
        val durationDelta = abs(expectedDuration - actualDuration)
        if (durationDelta > MAX_DURATION_MISMATCH_MS) return null
        durationScore = if (durationDelta <= EXACT_DURATION_TOLERANCE_MS) 4 else 2
      }

      val expectedPrimaryArtist = expectedArtistCredits.firstOrNull().orEmpty()
      val actualPrimaryArtist = actualArtistCredits.firstOrNull().orEmpty()
      val primaryArtistMatches = expectedPrimaryArtist == actualPrimaryArtist ||
        normalizedPhraseContains(expectedPrimaryArtist, actualPrimaryArtist)
      val corroboratedPartialArtist = sharedArtistCredits.isNotEmpty() && (
        (primaryArtistMatches && (albumMatches || durationScore > 0)) ||
          (albumMatches && durationScore > 0)
        )
      val containedArtist = primaryArtistMatches && normalizedPhraseContains(
        normalize(query.artist),
        normalize(artistName.orEmpty()),
      ) && (albumMatches || durationScore > 0)
      if (!combinedArtistMatches && !creditSetMatches &&
        !corroboratedPartialArtist && !containedArtist
      ) return null

      val exactTitle = actualTitle == expectedTitle
      val editionTitle = !exactTitle && baseTitle(query.title) == baseTitle(trackName ?: name.orEmpty())
      if (!exactTitle && !(editionTitle && (albumMatches || durationScore > 0))) return null

      val score = (if (exactTitle) 10 else 6) +
        (if (combinedArtistMatches) 9 else if (creditSetMatches) 8 else 6) +
        (if (albumMatches) 3 else 0) +
        durationScore
      return LrclibMatch(score)
    }
  }

  companion object {
    private const val MAX_DURATION_MISMATCH_MS = 8_000L
    private const val EXACT_DURATION_TOLERANCE_MS = 2_000L
    private const val DEFAULT_RATE_LIMIT_DELAY_MS = 1_000L
    private const val DEFAULT_SERVER_RETRY_DELAY_MS = 500L
    private const val MAX_INLINE_RATE_LIMIT_DELAY_MS = 30_000L
    private const val DEFAULT_RATE_LIMIT_COOL_DOWN_MS = 5_000L
    private const val MIN_RATE_LIMIT_COOL_DOWN_MS = 1_000L
    private const val MAX_RATE_LIMIT_COOL_DOWN_MS = 24L * 60L * 60L * 1_000L
    private const val NEGATIVE_CACHE_TTL_MS = 5L * 60L * 1_000L
    private const val MIN_CONTAINED_ARTIST_LENGTH = 4
    private const val MAX_MATCH_SCORE = 26
    private const val CACHE_FORMAT_PREFIX = "lrclib-search-v2:"
    private val RETRYABLE_HTTP_CODES = setOf(429, 502, 503, 504)
    private val SPOTIFY_QUALITY_BADGE = Regex(
      """\s*[•·]\s*Lossless\s*$""",
      RegexOption.IGNORE_CASE,
    )
    private val REPEATED_METADATA_SEPARATOR = Regex("""[\u001F;]+""")
    private val ARTIST_CREDIT_SEPARATOR = Regex(
      """(?i)[\u001F;,/&×+]+|\s+(?:feat(?:uring)?\.?|ft\.?|with|x)\s+""",
    )
    private val FEATURE_SUFFIX = Regex(
      """(?i)(?:(?:\s*[-–—]\s*|\s*[\(\[]\s*)(?:feat(?:uring)?\.?|ft\.?|with)|\s+(?:feat(?:uring)?\.?|ft\.?))\s+[^)\]]+(?:[)\]])?\s*$""",
    )
    private val EDITION_SUFFIX = Regex(
      """(?i)\s*(?:[-–—]\s*|\(\s*|\[\s*)(?:\d{4}\s+)?(?:remaster(?:ed)?|live(?:\s+(?:at|from))?|radio edit|single edit|album version|deluxe version|acoustic version|mono|stereo)[^\])]*(?:\)|\])?\s*$""",
    )
    // Prefer the most confidently identified recording. For candidates with
    // equal identity confidence, choose the richest timing representation.
    private val PARSED_LYRICS_COMPARATOR = compareBy<ParsedLyrics> {
      it.matchScore
    }.thenBy { syncQuality(it.document.syncKind) }.thenBy(ParsedLyrics::formatPriority)

    private fun syncQuality(kind: LyricsSyncKind): Int = when (kind) {
      LyricsSyncKind.STATIC -> 1
      LyricsSyncKind.LINE -> 2
      LyricsSyncKind.SYLLABLE -> 3
    }

    private fun removeSpotifyQualityBadge(value: String): String {
      val original = value.trim()
      return SPOTIFY_QUALITY_BADGE.replace(original, "").trim().ifBlank { original }
    }

    private fun splitRepeatedMetadata(value: String): List<String> =
      value.split(REPEATED_METADATA_SEPARATOR).map(String::trim).filter(String::isNotBlank)

    private fun splitArtistCredits(value: String): List<String> =
      value.split(ARTIST_CREDIT_SEPARATOR).map(String::trim).filter(String::isNotBlank)

    private fun normalizedPhraseContains(first: String, second: String): Boolean {
      if (first.isBlank() || second.isBlank()) return false
      val shorter = if (first.length <= second.length) first else second
      val longer = if (first.length <= second.length) second else first
      return shorter.length >= MIN_CONTAINED_ARTIST_LENGTH &&
        " $longer ".contains(" $shorter ")
    }

    private fun collapseRepeatedMetadata(value: String): String {
      val parts = splitRepeatedMetadata(value)
      if (parts.isEmpty()) return value.trim()
      val unique = parts.distinctBy(::normalize)
      return if (unique.size == 1) unique.single() else value.trim()
    }

    private fun stripTitleSuffixes(value: String): String {
      var current = value.trim()
      while (current.isNotBlank()) {
        val stripped = current
          .replace(FEATURE_SUFFIX, "")
          .replace(EDITION_SUFFIX, "")
          .trim()
        if (stripped.isBlank() || stripped == current) return current
        current = stripped
      }
      return value.trim()
    }

    private fun baseTitle(value: String): String = normalize(stripTitleSuffixes(value))

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
