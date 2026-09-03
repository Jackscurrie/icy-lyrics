package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticInput
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSeverity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.network.await
import com.icy.lyrics.core.platform.network.readUtf8Limited
import com.icy.lyrics.core.platform.storage.TrackAliasRepository
import com.icy.lyrics.core.platform.storage.TrackKeys
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class SpotifyCatalogConfig(
  val baseUrl: HttpUrl = "https://api.spotify.com/v1/".toHttpUrl(),
  val maxResponseBytes: Long = 1L * 1_024L * 1_024L,
  val searchLimit: Int = 8,
  val requestTimeoutMs: Long = 8_000L,
  val allowInsecureForTests: Boolean = false,
) {
  init {
    require(
      allowInsecureForTests || (baseUrl.isHttps && baseUrl.host == "api.spotify.com"),
    ) { "Spotify catalog requests must use the official HTTPS host" }
    require(maxResponseBytes in 1L..4L * 1_024L * 1_024L)
    require(searchLimit in 1..10)
    require(requestTimeoutMs in 1_000L..30_000L)
  }
}

/**
 * Resolves notification-only metadata to a Spotify track URI.
 *
 * The active account's currently-playing item is preferred. Catalog search is
 * only accepted when title and artist are exact after normalization, the known
 * duration is compatible, and the best result is unambiguous. A successful
 * result is persisted so subsequent lyric requests do not repeat the lookup.
 */
class SpotifyTrackResolver(
  private val client: OkHttpClient,
  private val tokenSource: SpotifyAccessTokenSource,
  private val aliases: TrackAliasRepository,
  private val config: SpotifyCatalogConfig = SpotifyCatalogConfig(),
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }
  private val directClient = client.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .callTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .build()

  suspend fun resolve(track: TrackIdentity): String? {
    if (!TrackKeys.mayUseMetadataFallback(track)) return null
    aliases.resolve(track)?.takeIf(::isSpotifyTrackUri)?.let { return it }
    if (track.title.isBlank() || track.artists.none(String::isNotBlank)) return null

    val token = try {
      tokenSource.accessToken()?.takeIf(String::isNotBlank)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      log(track, "token-failed", null, "Spotify authorization was unavailable.")
      null
    } ?: return null
    val current = request(
      config.baseUrl.newBuilder()
        .addPathSegments("me/player/currently-playing")
        .build(),
      token,
      operation = "currently-playing",
      track = track,
    )
    if (current is SpotifyHttp.Success && current.body.isNotBlank()) {
      val candidate = runCatching {
        json.decodeFromString<CurrentlyPlayingResponse>(current.body).item
      }.getOrNull()
      if (candidate != null && candidate.confidence(track).isCurrentlyPlayingConfident) {
        return remember(track, candidate.uri, "spotify-current-playing")
      }
    }
    if (current is SpotifyHttp.Unauthorized || current is SpotifyHttp.Failed) return null

    val artist = track.artists.firstOrNull(String::isNotBlank).orEmpty()
    val query = "track:${track.title.trim().take(MAX_QUERY_FIELD_CHARS)} " +
      "artist:${artist.trim().take(MAX_QUERY_FIELD_CHARS)}"
    val searched = request(
      config.baseUrl.newBuilder()
        .addPathSegment("search")
        .addQueryParameter("q", query)
        .addQueryParameter("type", "track")
        .addQueryParameter("limit", config.searchLimit.toString())
        .build(),
      token,
      operation = "search",
      track = track,
    )
    if (searched !is SpotifyHttp.Success) return null
    val candidates = runCatching {
      json.decodeFromString<SearchResponse>(searched.body).tracks.items
    }.getOrElse {
      log(track, "search-parse", null, "Spotify returned malformed search data.")
      return null
    }
    val ranked = candidates
      .mapNotNull { candidate ->
        candidate.confidence(track).takeIf(MatchConfidence::isConfident)
          ?.let { candidate to it.score }
      }
      .filter { isSpotifyTrackUri(it.first.uri) }
      .distinctBy { it.first.uri }
      .sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    val runnerUp = ranked.getOrNull(1)
    if (runnerUp != null && runnerUp.second >= best.second - MIN_UNIQUE_SCORE_MARGIN) {
      log(track, "search-ambiguous", null, "Spotify search did not produce one unambiguous track.")
      return null
    }
    return remember(track, best.first.uri, "spotify-search-confidence")
  }

  private suspend fun remember(track: TrackIdentity, uri: String, evidence: String): String? {
    if (!isSpotifyTrackUri(uri)) return null
    aliases.remember(track, uri, evidence)
    return uri
  }

  private suspend fun request(
    url: HttpUrl,
    token: String,
    operation: String,
    track: TrackIdentity,
  ): SpotifyHttp {
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .header("Authorization", "Bearer $token")
      .get()
      .build()
    return try {
      directClient.newCall(request).await().use { response ->
        if (response.isRedirect) {
          log(track, "$operation-redirect", response.code, "Spotify refused an unexpected redirect.")
          return SpotifyHttp.Failed
        }
        if (response.code == 401) {
          log(track, "$operation-unauthorized", response.code, "Spotify authorization was rejected.")
          return SpotifyHttp.Unauthorized
        }
        if (response.code == 204 || response.code == 403 || response.code == 404) {
          return SpotifyHttp.Empty
        }
        if (!response.isSuccessful) {
          log(track, "$operation-http", response.code, "Spotify track lookup failed.")
          return SpotifyHttp.Failed
        }
        SpotifyHttp.Success(response.readUtf8Limited(config.maxResponseBytes))
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: IOException) {
      log(track, "$operation-network", null, error.message ?: "Spotify track lookup network error.")
      SpotifyHttp.Failed
    } catch (error: Exception) {
      log(track, "$operation-failed", null, error.message ?: "Spotify track lookup failed.")
      SpotifyHttp.Failed
    }
  }

  private suspend fun log(track: TrackIdentity, code: String, status: Int?, message: String) {
    diagnostics.record(
      DiagnosticInput(
        severity = DiagnosticSeverity.WARNING,
        component = "spotify-track-resolver",
        code = code,
        trackKey = track.exactStorageKey,
        httpStatus = status,
        message = message,
      ),
    )
  }

  private fun SpotifyTrack.confidence(expected: TrackIdentity): MatchConfidence {
    if (!isSpotifyTrackUri(uri)) return MatchConfidence.REJECTED
    if (normalize(name) != normalize(expected.title)) return MatchConfidence.REJECTED

    val expectedArtists = splitArtists(expected.artists)
    val actualArtists = artists.map { normalize(it.name) }.filter(String::isNotBlank)
    if (expectedArtists.none(actualArtists::contains)) return MatchConfidence.REJECTED

    val expectedDuration = expected.durationMs?.takeIf { it > 0L }
    if (expectedDuration != null && abs(expectedDuration - durationMs) > MAX_DURATION_MISMATCH_MS) {
      return MatchConfidence.REJECTED
    }
    val expectedIsrc = expected.isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank)
    val actualIsrc = externalIds?.isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank)
    if (expectedIsrc != null && actualIsrc != null && expectedIsrc != actualIsrc) {
      return MatchConfidence.REJECTED
    }

    var score = TITLE_SCORE + ARTIST_SCORE
    var hasVersionEvidence = false
    if (expectedDuration != null) {
      score += if (abs(expectedDuration - durationMs) <= CLOSE_DURATION_MS) 3 else 1
      hasVersionEvidence = true
    }
    if (expected.album.isNotBlank() && normalize(expected.album) == normalize(album.name)) {
      score += 2
      hasVersionEvidence = true
    }
    if (expectedIsrc != null && actualIsrc == expectedIsrc) {
      score += 6
      hasVersionEvidence = true
    }
    return MatchConfidence(score, hasVersionEvidence)
  }

  private sealed interface SpotifyHttp {
    data class Success(val body: String) : SpotifyHttp
    data object Empty : SpotifyHttp
    data object Unauthorized : SpotifyHttp
    data object Failed : SpotifyHttp
  }

  private data class MatchConfidence(val score: Int, val hasVersionEvidence: Boolean) {
    val isCurrentlyPlayingConfident: Boolean
      get() = score >= TITLE_SCORE + ARTIST_SCORE

    val isConfident: Boolean
      get() = score >= MIN_CONFIDENT_SCORE && hasVersionEvidence

    companion object {
      val REJECTED = MatchConfidence(0, false)
    }
  }

  @Serializable
  private data class CurrentlyPlayingResponse(val item: SpotifyTrack? = null)

  @Serializable
  private data class SearchResponse(val tracks: SpotifyTrackPage = SpotifyTrackPage())

  @Serializable
  private data class SpotifyTrackPage(val items: List<SpotifyTrack> = emptyList())

  @Serializable
  private data class SpotifyTrack(
    val uri: String = "",
    val name: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val album: SpotifyAlbum = SpotifyAlbum(),
    val artists: List<SpotifyArtist> = emptyList(),
    @SerialName("external_ids") val externalIds: SpotifyExternalIds? = null,
  )

  @Serializable
  private data class SpotifyAlbum(val name: String = "")

  @Serializable
  private data class SpotifyArtist(val name: String = "")

  @Serializable
  private data class SpotifyExternalIds(val isrc: String? = null)

  private companion object {
    val SPOTIFY_TRACK_URI = Regex("""^spotify:track:[A-Za-z0-9]{22}$""")
    const val TITLE_SCORE = 7
    const val ARTIST_SCORE = 6
    const val MIN_CONFIDENT_SCORE = TITLE_SCORE + ARTIST_SCORE + 1
    const val MIN_UNIQUE_SCORE_MARGIN = 2
    const val MAX_DURATION_MISMATCH_MS = 8_000L
    const val CLOSE_DURATION_MS = 2_000L
    const val MAX_QUERY_FIELD_CHARS = 200

    fun isSpotifyTrackUri(value: String): Boolean = SPOTIFY_TRACK_URI.matches(value)

    fun splitArtists(values: List<String>): List<String> = values
      .flatMap { value ->
        value.split(
          Regex("""(?i)\s*(?:,|;|&|\bfeat\.?\b|\bfeaturing\b|\bwith\b)\s*|\s+[x×]\s+"""),
        )
      }
      .map(::normalize)
      .filter(String::isNotBlank)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
      .lowercase(Locale.ROOT)
      .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
      .trim()
      .replace(Regex("""\s+"""), " ")
  }
}
