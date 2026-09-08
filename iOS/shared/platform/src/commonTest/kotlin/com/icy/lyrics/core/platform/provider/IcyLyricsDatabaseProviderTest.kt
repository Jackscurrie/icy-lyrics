package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderFailureCategory
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.lyrics.provider.ProviderUnavailableReason
import com.icy.lyrics.core.platform.database.LyricsCacheDao
import com.icy.lyrics.core.platform.database.LyricsCacheEntity
import com.icy.lyrics.core.platform.network.Headers
import com.icy.lyrics.core.platform.network.LyricsHttpClient
import com.icy.lyrics.core.platform.network.NetworkException
import com.icy.lyrics.core.platform.network.Request
import com.icy.lyrics.core.platform.network.Response
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs the shared provider contract on Kotlin/Native through iosSimulatorArm64Test. */
class IcyLyricsDatabaseProviderTest {
  @Test
  fun exactRequestParsesTtmlWithIcyProvenance() = runBlocking {
    val client = RecordingClient { _, _, _ -> response(200, WORD_TTML) }
    val fixture = Fixture(client)

    val result = assertIs<ProviderResult.Found>(fixture.provider.fetch(LyricsRequest(TRACK)))

    assertEquals(LyricsSource.ICY_DATABASE, result.document.metadata.source)
    assertEquals("Icy Lyrics Database", result.document.metadata.sourceLabel)
    assertEquals("ttml", result.rawFormat)
    assertFalse(result.fromCache)
    val call = client.calls.single()
    assertEquals("https://jackscurrie.com/api/ttml", call.request.url.toString())
    assertEquals("application/ttml+xml", call.request.headers["Accept"])
    assertEquals("application/json", call.request.headers["Content-Type"])
    assertEquals("IcyLyricsIOSContractTest/1.1.0", call.request.headers["User-Agent"])
    assertEquals(setOf("Accept", "Content-Type", "User-Agent"), call.request.headers.keys)
    assertEquals(
      "{\"track\":{\"uri\":\"spotify:track:0YJ9FWWHn9EfnN0lHwbzvV\"}}",
      call.request.body?.decodeToString(),
    )
    assertEquals(2L * 1_024L * 1_024L, call.maxResponseBytes)
    assertEquals(15_000L, call.timeoutMs)
  }

  @Test
  fun completeLocalUriStaysPrivateToLocalStorage() = runBlocking {
    val client = RecordingClient { _, _, _ -> error("Transport must not be called") }
    val fixture = Fixture(client)
    val local = TRACK.copy(uri = "spotify:local:Radiohead:In%20Rainbows:Jigsaw:248")

    val result = fixture.provider.fetch(LyricsRequest(local))

    assertEquals(
      ProviderUnavailableReason.UNSUPPORTED_TRACK,
      assertIs<ProviderResult.Unavailable>(result).reason,
    )
    assertTrue(client.calls.isEmpty())
  }

  @Test
  fun disabledOfflineAndIncompleteTracksDoNotReachTransport() = runBlocking {
    val client = RecordingClient { _, _, _ -> error("Transport must not be called") }
    val disabled = Fixture(client, enabled = { false }).provider.fetch(LyricsRequest(TRACK))
    val offline = Fixture(client, online = { false }).provider.fetch(LyricsRequest(TRACK))
    val incomplete = Fixture(client).provider.fetch(
      LyricsRequest(TRACK.copy(uri = "spotify:track:incomplete")),
    )

    assertEquals(ProviderUnavailableReason.DISABLED, assertIs<ProviderResult.Unavailable>(disabled).reason)
    assertEquals(ProviderUnavailableReason.OFFLINE, assertIs<ProviderResult.Unavailable>(offline).reason)
    assertEquals(
      ProviderUnavailableReason.UNSUPPORTED_TRACK,
      assertIs<ProviderResult.Unavailable>(incomplete).reason,
    )
    assertTrue(client.calls.isEmpty())
  }

  @Test
  fun positiveCacheSurvivesNormalLoadsAndExplicitReloadBypassesIt() = runBlocking {
    val responses = ArrayDeque(
      listOf(response(200, lineTtml("First version")), response(200, lineTtml("Reloaded version"))),
    )
    val client = RecordingClient { _, _, _ -> responses.removeFirst() }
    val fixture = Fixture(client)

    assertIs<ProviderResult.Found>(fixture.provider.fetch(LyricsRequest(TRACK)))
    fixture.now += 29L * DAY_MS
    val cached = assertIs<ProviderResult.Found>(fixture.provider.fetch(LyricsRequest(TRACK)))
    val reloaded = assertIs<ProviderResult.Found>(
      fixture.provider.fetch(LyricsRequest(TRACK, allowCached = false)),
    )

    assertTrue(cached.fromCache)
    assertFalse(reloaded.fromCache)
    assertEquals("Reloaded version", assertIs<LineLyrics>(reloaded.document).lines.single().text)
    assertEquals(2, client.calls.size)
    val row = fixture.dao.get(LyricsProviderId.ICY_DATABASE.name, TRACK.uri)!!
    assertEquals(30L * DAY_MS, row.expiresAtEpochMs - row.fetchedAtEpochMs)
  }

  @Test
  fun notFoundIsNegativeCachedForOneHour() = runBlocking {
    val responses = ArrayDeque(listOf(response(404), response(200, WORD_TTML)))
    val client = RecordingClient { _, _, _ -> responses.removeFirst() }
    val fixture = Fixture(client)

    assertIs<ProviderResult.NotFound>(fixture.provider.fetch(LyricsRequest(TRACK)))
    assertIs<ProviderResult.NotFound>(fixture.provider.fetch(LyricsRequest(TRACK)))
    fixture.now += HOUR_MS + 1L
    assertIs<ProviderResult.Found>(fixture.provider.fetch(LyricsRequest(TRACK)))

    assertEquals(2, client.calls.size)
  }

  @Test
  fun concurrentAutomaticLoadsShareOneRequest() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val client = RecordingClient { _, _, _ ->
      entered.complete(Unit)
      release.await()
      response(200, WORD_TTML)
    }
    val fixture = Fixture(client)

    val first = async { fixture.provider.fetch(LyricsRequest(TRACK)) }
    entered.await()
    val second = async { fixture.provider.fetch(LyricsRequest(TRACK)) }
    yield()
    release.complete(Unit)
    val results = awaitAll(first, second)

    assertTrue(results.all { it is ProviderResult.Found })
    assertEquals(1, results.count { (it as ProviderResult.Found).fromCache })
    assertEquals(1, client.calls.size)
  }

  @Test
  fun rateLimitAndRedirectRemainTerminalFailuresForThisProviderAttempt() = runBlocking {
    val responses = ArrayDeque(
      listOf(
        response(429, headers = mapOf("Retry-After" to "7")),
        response(302, headers = mapOf("Location" to "https://example.invalid/elsewhere")),
      ),
    )
    val client = RecordingClient { _, _, _ -> responses.removeFirst() }
    val fixture = Fixture(client)

    val limited = assertIs<ProviderResult.Failure>(fixture.provider.fetch(LyricsRequest(TRACK)))
    val redirected = assertIs<ProviderResult.Failure>(fixture.provider.fetch(LyricsRequest(TRACK)))

    assertEquals(ProviderFailureCategory.HTTP, limited.category)
    assertEquals(429, limited.httpStatus)
    assertEquals(7_000L, limited.retryAfterMs)
    assertEquals(ProviderFailureCategory.SECURITY, redirected.category)
    assertEquals(302, redirected.httpStatus)
    assertEquals(2, client.calls.size)
  }

  @Test
  fun malformedOversizedAndCancelledResponsesKeepTheirFailureSemantics() = runBlocking {
    val malformed = Fixture(
      RecordingClient { _, _, _ -> response(200, "not TTML") },
    ).provider.fetch(LyricsRequest(TRACK))
    assertEquals(ProviderFailureCategory.PARSE, assertIs<ProviderResult.Failure>(malformed).category)

    val oversized = Fixture(
      RecordingClient { _, _, _ -> throw NetworkException("Response exceeded the allowed size.") },
    ).provider.fetch(LyricsRequest(TRACK))
    assertEquals(ProviderFailureCategory.NETWORK, assertIs<ProviderResult.Failure>(oversized).category)

    val cancelledProvider = Fixture(
      RecordingClient { _, _, _ -> throw CancellationException("cancelled") },
    ).provider
    var propagated = false
    try {
      cancelledProvider.fetch(LyricsRequest(TRACK))
    } catch (_: CancellationException) {
      propagated = true
    }
    assertTrue(propagated)
  }

  private class Fixture(
    client: LyricsHttpClient,
    enabled: suspend () -> Boolean = { true },
    online: () -> Boolean = { true },
  ) {
    var now = 1_800_000_000_000L
    val dao = InMemoryLyricsCacheDao()
    private val cache = LyricsCacheRepository(dao) { now }
    val provider = IcyLyricsDatabaseProvider(
      client = client,
      cache = cache,
      config = IcyLyricsDatabaseConfig(userAgent = "IcyLyricsIOSContractTest/1.1.0"),
      enabled = enabled,
      online = online,
    )
  }

  private data class RecordedCall(
    val request: Request,
    val maxResponseBytes: Long,
    val timeoutMs: Long,
  )

  private class RecordingClient(
    private val response: suspend (Request, Long, Long) -> Response,
  ) : LyricsHttpClient {
    val calls = mutableListOf<RecordedCall>()

    override suspend fun execute(
      request: Request,
      maxResponseBytes: Long,
      timeoutMs: Long,
    ): Response {
      calls += RecordedCall(request, maxResponseBytes, timeoutMs)
      return response(request, maxResponseBytes, timeoutMs)
    }
  }

  private class InMemoryLyricsCacheDao : LyricsCacheDao {
    private val rows = linkedMapOf<Pair<String, String>, LyricsCacheEntity>()

    override suspend fun get(providerId: String, trackKey: String): LyricsCacheEntity? =
      rows[providerId to trackKey]

    override suspend fun getByMetadataKey(providerId: String, metadataKey: String): LyricsCacheEntity? =
      rows.values
        .filter { it.providerId == providerId && it.metadataKey == metadataKey }
        .maxByOrNull(LyricsCacheEntity::fetchedAtEpochMs)

    override suspend fun upsert(entity: LyricsCacheEntity) {
      rows[entity.providerId to entity.trackKey] = entity
    }

    override suspend fun delete(providerId: String, trackKey: String): Int =
      if (rows.remove(providerId to trackKey) != null) 1 else 0

    override suspend fun deleteByMetadataKey(providerId: String, metadataKey: String): Int {
      val before = rows.size
      rows.entries.removeAll { it.value.providerId == providerId && it.value.metadataKey == metadataKey }
      return before - rows.size
    }

    override suspend fun deleteExpired(nowEpochMs: Long): Int {
      val before = rows.size
      rows.entries.removeAll { it.value.expiresAtEpochMs < nowEpochMs }
      return before - rows.size
    }

    override suspend fun trimToNewest(keep: Int): Int {
      val retained = rows.values
        .sortedByDescending(LyricsCacheEntity::fetchedAtEpochMs)
        .take(keep)
        .map { it.providerId to it.trackKey }
        .toSet()
      val before = rows.size
      rows.entries.removeAll { it.key !in retained }
      return before - rows.size
    }

    override suspend fun clear(): Int = rows.size.also { rows.clear() }
  }

  private companion object {
    const val HOUR_MS = 60L * 60L * 1_000L
    const val DAY_MS = 24L * HOUR_MS
    const val WORD_TTML =
      "<tt timing=\"Word\"><body><p begin=\"1s\" end=\"2s\"><span begin=\"1s\" end=\"2s\">Timed words</span></p></body></tt>"
    val TRACK = TrackIdentity(
      uri = "spotify:track:0YJ9FWWHn9EfnN0lHwbzvV",
      title = "Jigsaw Falling Into Place",
      artists = listOf("Radiohead"),
      album = "In Rainbows",
      durationMs = 248_000L,
    )

    fun lineTtml(text: String) =
      "<tt><body><p begin=\"1s\" end=\"2s\">$text</p></body></tt>"

    fun response(
      code: Int,
      body: String = "",
      headers: Map<String, String> = emptyMap(),
    ) = Response(code, Headers(headers), body.encodeToByteArray())

  }
}
