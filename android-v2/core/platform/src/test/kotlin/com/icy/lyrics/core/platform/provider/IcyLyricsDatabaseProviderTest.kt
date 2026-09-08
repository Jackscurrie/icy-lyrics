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
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.network.OkHttpTransport
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IcyLyricsDatabaseProviderTest {
  private lateinit var server: MockWebServer
  private lateinit var cacheDao: IcyProviderLyricsCacheDao
  private lateinit var cache: LyricsCacheRepository
  private var now = 1_800_000_000_000L

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    cacheDao = IcyProviderLyricsCacheDao()
    cache = LyricsCacheRepository(cacheDao) { now }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun postsOnlyTheCompleteUriAndParsesTtmlAsIcyDatabaseLyrics() = runTest {
    server.enqueue(ttmlResponse(WORD_TTML))

    val result = provider().fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSource.ICY_DATABASE, result.document.metadata.source)
    assertEquals("Icy Lyrics Database", result.document.metadata.sourceLabel)
    assertEquals("ttml", result.rawFormat)
    assertFalse(result.fromCache)

    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/ttml", request.path)
    assertEquals("application/ttml+xml", request.getHeader("Accept"))
    assertEquals("application/json", request.getHeader("Content-Type"))
    assertNull(request.getHeader("Authorization"))
    assertEquals(
      "{\"track\":{\"uri\":\"spotify:track:0YJ9FWWHn9EfnN0lHwbzvV\"}}",
      request.body.readUtf8(),
    )
  }

  @Test
  fun positiveResultIsPersistedForThirtyDaysAndAvoidsAnotherRequest() = runTest {
    server.enqueue(ttmlResponse(WORD_TTML))
    val provider = provider()

    val first = provider.fetch(LyricsRequest(TRACK))
    now += 29L * DAY_MS
    val cached = provider.fetch(LyricsRequest(TRACK))

    assertTrue(first is ProviderResult.Found)
    assertTrue(cached is ProviderResult.Found && cached.fromCache)
    assertEquals(1, server.requestCount)
    val row = cacheDao.get(LyricsProviderId.ICY_DATABASE.name, TRACK.uri)!!
    assertEquals(30L * DAY_MS, row.expiresAtEpochMs - row.fetchedAtEpochMs)
  }

  @Test
  fun concurrentNormalLoadsAreCoalescedByTheCacheRecheckInsideTheMutex() = runTest {
    server.enqueue(
      ttmlResponse(WORD_TTML).setBodyDelay(150L, TimeUnit.MILLISECONDS),
    )
    val provider = provider()

    val results = listOf(
      async { provider.fetch(LyricsRequest(TRACK)) },
      async { provider.fetch(LyricsRequest(TRACK)) },
    ).awaitAll()

    assertTrue(results.all { it is ProviderResult.Found })
    assertEquals(1, results.count { (it as ProviderResult.Found).fromCache })
    assertEquals(1, server.requestCount)
  }

  @Test
  fun explicitReloadBypassesThePositiveCache() = runTest {
    server.enqueue(ttmlResponse(lineTtml("First version")))
    server.enqueue(ttmlResponse(lineTtml("Reloaded version")))
    val provider = provider()

    provider.fetch(LyricsRequest(TRACK))
    val reloaded = provider.fetch(LyricsRequest(TRACK, allowCached = false))

    assertTrue(reloaded is ProviderResult.Found)
    reloaded as ProviderResult.Found
    assertEquals("Reloaded version", (reloaded.document as LineLyrics).lines.single().text)
    assertFalse(reloaded.fromCache)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun notFoundIsNegativeCachedForOneHour() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val provider = provider()

    val first = provider.fetch(LyricsRequest(TRACK))
    val cached = provider.fetch(LyricsRequest(TRACK))
    now += HOUR_MS + 1L
    server.enqueue(ttmlResponse(WORD_TTML))
    val afterExpiry = provider.fetch(LyricsRequest(TRACK))

    assertTrue(first is ProviderResult.NotFound)
    assertTrue(cached is ProviderResult.NotFound)
    assertTrue(afterExpiry is ProviderResult.Found)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun rateLimitIsFailureRatherThanQueuedSoTheControllerWillNotAutoRetry() = runTest {
    server.enqueue(
      MockResponse()
        .setResponseCode(429)
        .addHeader("Retry-After", "7"),
    )

    val result = provider().fetch(LyricsRequest(TRACK))

    assertFalse(result is ProviderResult.Queued)
    assertTrue(result is ProviderResult.Failure)
    result as ProviderResult.Failure
    assertEquals(ProviderFailureCategory.HTTP, result.category)
    assertEquals(429, result.httpStatus)
    assertEquals(7_000L, result.retryAfterMs)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun rejectsRedirectsAndIncompleteSpotifyUrisWithoutFollowingEither() = runTest {
    server.enqueue(
      MockResponse()
        .setResponseCode(302)
        .addHeader("Location", server.url("/elsewhere")),
    )
    val provider = provider()

    val redirect = provider.fetch(LyricsRequest(TRACK))
    val incomplete = provider.fetch(
      LyricsRequest(TRACK.copy(uri = "spotify:track:incomplete")),
    )

    assertTrue(
      redirect is ProviderResult.Failure &&
        redirect.category == ProviderFailureCategory.SECURITY,
    )
    assertTrue(
      incomplete is ProviderResult.Unavailable &&
        incomplete.reason == ProviderUnavailableReason.UNSUPPORTED_TRACK,
    )
    assertEquals(1, server.requestCount)
  }

  @Test
  fun keepsCompleteSpotifyLocalUrisPrivateToLocalStorage() = runTest {
    val local = TRACK.copy(uri = "spotify:local:Radiohead:In%20Rainbows:Jigsaw:248")

    val result = provider().fetch(LyricsRequest(local))

    assertTrue(
      result is ProviderResult.Unavailable &&
        result.reason == ProviderUnavailableReason.UNSUPPORTED_TRACK,
    )
    assertEquals(0, server.requestCount)
  }

  private fun provider() = IcyLyricsDatabaseProvider(
    client = OkHttpTransport(OkHttpClient()),
    cache = cache,
    config = IcyLyricsDatabaseConfig(
      endpoint = server.url("/api/ttml").toString().toHttpUrl(),
      userAgent = "IcyLyricsProviderTest/1.1.0",
      allowInsecureForTests = true,
    ),
  )

  private fun ttmlResponse(body: String) = MockResponse()
    .setResponseCode(200)
    .addHeader("Content-Type", "application/ttml+xml; charset=utf-8")
    .setBody(body)

  private fun lineTtml(text: String) =
    "<tt><body><p begin=\"1s\" end=\"2s\">$text</p></body></tt>"

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
  }
}

private class IcyProviderLyricsCacheDao : LyricsCacheDao {
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
    val retained = rows.values.sortedByDescending(LyricsCacheEntity::fetchedAtEpochMs)
      .take(keep)
      .map { it.providerId to it.trackKey }
      .toSet()
    val before = rows.size
    rows.entries.removeAll { it.key !in retained }
    return before - rows.size
  }

  override suspend fun clear(): Int = rows.size.also { rows.clear() }
}
