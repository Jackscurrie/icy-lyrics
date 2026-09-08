package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.platform.database.LyricsCacheDao
import com.icy.lyrics.core.platform.database.LyricsCacheEntity
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.network.OkHttpTransport
import com.icy.lyrics.core.platform.storage.CachedLyrics
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

class LrclibReliabilityTest {
  private lateinit var server: MockWebServer
  private lateinit var cacheDao: ReliabilityLyricsCacheDao
  private lateinit var cache: LyricsCacheRepository
  private var now = POLICY_EPOCH_MS + 1_000L

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    cacheDao = ReliabilityLyricsCacheDao()
    cache = LyricsCacheRepository(cacheDao) { now }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun exactLookupRemovesSpotifyLosslessBadgeAndIdentifiesTheClient() = runTest {
    server.enqueue(success(entry(lyricsFile = WORD_TTML)))

    val result = provider().fetch(LyricsRequest(RADIOHEAD.copy(artists = listOf("Radiohead • Lossless"))))

    assertTrue(result is ProviderResult.Found)
    val request = server.takeRequest()
    assertEquals("Radiohead", request.requestUrl?.queryParameter("artist_name"))
    assertEquals("IcyLyricsReliabilityTest/1 (+https://jackscurrie.com)", request.getHeader("User-Agent"))
  }

  @Test
  fun synchronizedLrcWinsWhenLyricsfileRepresentationIsOnlyStatic() = runTest {
    server.enqueue(success(entry(lyricsFile = "Static words", synced = "[00:01.00]Timed words")))
    repeat(4) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSyncKind.LINE, result.document.syncKind)
    assertEquals("lrc", result.rawFormat)
    assertEquals(5, server.requestCount)
  }

  @Test
  fun albumSearchMissFallsBackToArtistSearchWithSequentialSpacing() = runTest {
    val waits = mutableListOf<Long>()
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(success("[]"))
    server.enqueue(
      success(
        "[${entry(artist = "Radiohead;RADIOHEAD", album = "In Rainbows\u001FIn Rainbows", lyricsFile = WORD_TTML)}]",
      ),
    )
    repeat(2) { server.enqueue(success("[]")) }

    val result = provider(spacingMs = 250L, wait = { waits += it }).fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(listOf(250L, 250L, 250L, 250L), waits)
    val exact = server.takeRequest().requestUrl!!
    val albumSearch = server.takeRequest().requestUrl!!
    val artistSearch = server.takeRequest().requestUrl!!
    assertEquals("/api/get", exact.encodedPath)
    assertEquals("In Rainbows", albumSearch.queryParameter("album_name"))
    assertNull(artistSearch.queryParameter("album_name"))
    assertEquals("Radiohead", artistSearch.queryParameter("artist_name"))
  }

  @Test
  fun structuredMissesReachLocallyValidatedBroadSearch() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(success("[]"))
    server.enqueue(success("[]"))
    server.enqueue(success("[${entry(lyricsFile = WORD_TTML)}]"))

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    repeat(3) { server.takeRequest() }
    val broad = server.takeRequest().requestUrl!!
    assertEquals("Jigsaw Falling Into Place Radiohead", broad.queryParameter("q"))
    assertNull(broad.queryParameter("track_name"))
    assertNull(broad.queryParameter("artist_name"))
  }

  @Test
  fun exactInstrumentalRecordDoesNotHideASynchronizedSearchResult() = runTest {
    server.enqueue(success(entry(instrumental = true, synced = null, plain = null)))
    server.enqueue(success("[${entry()}]"))
    repeat(3) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.LINE, (result as ProviderResult.Found).document.syncKind)
    assertEquals(5, server.requestCount)
  }

  @Test
  fun rejectedIdentityCandidatesAreNotNegativeCached() = runTest {
    val wrong = entry(artist = "The Wrong Artist")
    server.enqueue(MockResponse().setResponseCode(404))
    repeat(4) { server.enqueue(success("[$wrong]")) }

    val first = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(first is ProviderResult.NotFound)
    assertTrue((first as ProviderResult.NotFound).message.orEmpty().contains("exact track identity"))
    assertNull(cache.get(LyricsProviderId.LRCLIB, RADIOHEAD, allowExpired = true))
    assertEquals(5, server.requestCount)
  }

  @Test
  fun titleOnlySearchRecoversFromDecoratedArtistMetadata() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    repeat(3) { server.enqueue(success("[]")) }
    server.enqueue(
      success("[${entry(artist = "Radiohead, Guest", lyricsFile = WORD_TTML)}]"),
    )

    val result = provider().fetch(
      LyricsRequest(RADIOHEAD.copy(artists = listOf("Radiohead, Guest Appearance"))),
    )

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.SYLLABLE, (result as ProviderResult.Found).document.syncKind)
    repeat(4) { server.takeRequest() }
    val titleOnly = server.takeRequest().requestUrl!!
    assertEquals("Jigsaw Falling Into Place", titleOnly.queryParameter("track_name"))
    assertNull(titleOnly.queryParameter("artist_name"))
  }

  @Test
  fun reorderedArtistCreditsMatchAsAnUnorderedSet() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(
      success("[${entry(artist = "Radiohead & Thom Yorke", lyricsFile = WORD_TTML)}]"),
    )
    repeat(3) { server.enqueue(success("[]")) }

    val result = provider().fetch(
      LyricsRequest(RADIOHEAD.copy(artists = listOf("Thom Yorke", "Radiohead"))),
    )

    assertTrue(result is ProviderResult.Found)
    assertEquals(5, server.requestCount)
  }

  @Test
  fun featuredTitleSuffixFallsBackToBaseTitleSearch() = runTest {
    val featured = RADIOHEAD.copy(title = "Jigsaw Falling Into Place (feat. Thom Yorke)")
    server.enqueue(MockResponse().setResponseCode(404))
    repeat(4) { server.enqueue(success("[]")) }
    server.enqueue(success("[${entry(lyricsFile = WORD_TTML)}]"))

    val result = provider().fetch(LyricsRequest(featured))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.SYLLABLE, (result as ProviderResult.Found).document.syncKind)
    repeat(5) { server.takeRequest() }
    val baseTitle = server.takeRequest().requestUrl!!
    assertEquals("Jigsaw Falling Into Place", baseTitle.queryParameter("track_name"))
    assertNull(baseTitle.queryParameter("artist_name"))
  }

  @Test
  fun ordinaryTitleEndingInWithPhraseIsNotTreatedAsAFeatureCredit() = runTest {
    val titled = RADIOHEAD.copy(title = "Come with Me")
    server.enqueue(success(entry(title = "Come with Me", synced = "[00:01.00]Exact line")))
    repeat(5) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(titled))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.LINE, (result as ProviderResult.Found).document.syncKind)
    assertEquals(5, server.requestCount)
  }

  @Test
  fun sharedFeaturedArtistWithoutPrimaryOrAlbumMatchIsRejected() = runTest {
    val requested = RADIOHEAD.copy(
      title = "Shared Title",
      artists = listOf("Lead A", "Guest"),
      album = "Requested Album",
      durationMs = 180_000L,
    )
    val wrong = entry(
      title = "Shared Title",
      artist = "Lead B, Guest",
      album = "Other Album",
      duration = 180.0,
      lyricsFile = WORD_TTML,
    )
    server.enqueue(MockResponse().setResponseCode(404))
    repeat(4) { server.enqueue(success("[$wrong]")) }

    val result = provider().fetch(LyricsRequest(requested))

    assertTrue(result is ProviderResult.NotFound)
    assertTrue((result as ProviderResult.NotFound).message.orEmpty().contains("exact track identity"))
    assertNull(cache.get(LyricsProviderId.LRCLIB, requested, allowExpired = true))
  }

  @Test
  fun mismatchedExactInstrumentalIsRejectedBeforeNegativeCaching() = runTest {
    server.enqueue(
      success(entry(artist = "Wrong Artist", instrumental = true, synced = null, plain = null)),
    )
    repeat(4) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.NotFound)
    assertTrue((result as ProviderResult.NotFound).message.orEmpty().contains("exact track identity"))
    assertNull(cache.get(LyricsProviderId.LRCLIB, RADIOHEAD, allowExpired = true))
  }

  @Test
  fun legacyStaticPositiveCacheIsRefreshedForWordTiming() = runTest {
    cache.put(
      LyricsProviderId.LRCLIB,
      RADIOHEAD,
      com.icy.lyrics.core.lyrics.model.StaticLyrics(
        com.icy.lyrics.core.lyrics.model.LyricsMetadata(
          RADIOHEAD.uri,
          com.icy.lyrics.core.lyrics.model.LyricsSource.LRCLIB,
        ),
        listOf(com.icy.lyrics.core.lyrics.model.StaticLyricLine("Old static lyrics")),
      ),
      rawFormat = "plain",
    )
    server.enqueue(success(entry(lyricsFile = WORD_TTML)))

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertFalse(result.fromCache)
    assertEquals(LyricsSyncKind.SYLLABLE, result.document.syncKind)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun exactIdentityLineBeatsLowerConfidenceWordCandidate() = runTest {
    val exactLine = entry(synced = "[00:01.00]Exact line")
    val weakerWord = entry(
      artist = "Radiohead, Guest",
      album = "Other Edition",
      lyricsFile = WORD_TTML,
    )
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(success("[$exactLine,$weakerWord]"))
    repeat(3) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.LINE, (result as ProviderResult.Found).document.syncKind)
  }

  @Test
  fun laterEqualConfidenceWordTimingUpgradesAnExactLine() = runTest {
    server.enqueue(success(entry(synced = "[00:01.00]Exact line")))
    server.enqueue(success("[]"))
    server.enqueue(success("[${entry(lyricsFile = WORD_TTML)}]"))
    repeat(2) { server.enqueue(success("[]")) }

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.SYLLABLE, (result as ProviderResult.Found).document.syncKind)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun mismatchedExactResponseContinuesToValidatedSearch() = runTest {
    server.enqueue(success(entry(artist = "Wrong Artist")))
    server.enqueue(success("[${entry(lyricsFile = WORD_TTML)}]"))

    val result = provider().fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSyncKind.SYLLABLE, (result as ProviderResult.Found).document.syncKind)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun negativeFromOlderMatchingPolicyIsInvalidatedOnce() = runTest {
    now = 1_000L
    cache.putNegative(LyricsProviderId.LRCLIB, RADIOHEAD)
    now = 20_000L
    server.enqueue(success(entry(lyricsFile = WORD_TTML)))

    val result = provider(negativePolicyEpochMs = 10_000L).fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(1, server.requestCount)
    val cached = cache.get(LyricsProviderId.LRCLIB, RADIOHEAD)
    assertTrue(cached is CachedLyrics.Hit)
    assertFalse(cached is CachedLyrics.Negative)
  }

  @Test
  fun retryableServerFailureIsRetriedOnceWithoutWritingANegative() = runTest {
    val waits = mutableListOf<Long>()
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(success(entry(lyricsFile = WORD_TTML)))

    val result = provider(wait = { waits += it }).fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    assertEquals(listOf(500L), waits)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun exhaustedRateLimitStopsTheSearchLadderAndOpensACooldown() = runTest {
    val waits = mutableListOf<Long>()
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    val provider = provider(wait = { waits += it }, clock = { now })

    val first = provider.fetch(LyricsRequest(RADIOHEAD))
    val second = provider.fetch(LyricsRequest(RADIOHEAD))

    assertTrue(first is ProviderResult.Queued)
    assertEquals(2_000L, (first as ProviderResult.Queued).retryAfterMs)
    assertTrue(second is ProviderResult.Queued)
    assertEquals(2_000L, (second as ProviderResult.Queued).retryAfterMs)
    assertEquals(listOf(2_000L), waits)
    assertEquals(2, server.requestCount)

    now += 2_001L
    server.enqueue(success(entry(lyricsFile = WORD_TTML)))
    val recovered = provider.fetch(LyricsRequest(RADIOHEAD))

    assertTrue(recovered is ProviderResult.Found)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun exhaustedRateLimitKeepsEarlierLineResultAndStillOpensCooldown() = runTest {
    val waits = mutableListOf<Long>()
    server.enqueue(success(entry(synced = "[00:01.00]Exact line")))
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    val provider = provider(wait = { waits += it }, clock = { now })

    val result = provider.fetch(LyricsRequest(RADIOHEAD))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSyncKind.LINE, result.document.syncKind)
    assertFalse(result.fromCache)
    assertEquals(listOf(2_000L), waits)
    assertEquals(3, server.requestCount)
    val cached = cache.get(LyricsProviderId.LRCLIB, RADIOHEAD)
    assertTrue(cached is CachedLyrics.Hit)
    assertEquals(LyricsSyncKind.LINE, (cached as CachedLyrics.Hit).document.syncKind)

    val otherTrack = RADIOHEAD.copy(
      uri = "spotify:track:another",
      title = "Another Song",
    )
    val duringCooldown = provider.fetch(LyricsRequest(otherTrack))

    assertTrue(duringCooldown is ProviderResult.Queued)
    assertEquals(2_000L, (duringCooldown as ProviderResult.Queued).retryAfterMs)
    assertEquals(3, server.requestCount)
  }

  private fun provider(
    spacingMs: Long = 0L,
    negativePolicyEpochMs: Long = POLICY_EPOCH_MS,
    wait: suspend (Long) -> Unit = {},
    clock: () -> Long = { now },
  ) = LrclibProvider(
    client = OkHttpTransport(OkHttpClient()),
    cache = cache,
    config = LrclibConfig(
      baseUrl = server.url("/api/").toString().toHttpUrl(),
      userAgent = "IcyLyricsReliabilityTest/1 (+https://jackscurrie.com)",
      requestSpacingMs = spacingMs,
      allowInsecureForTests = true,
      negativeCachePolicyEpochMs = negativePolicyEpochMs,
    ),
    wait = wait,
    clock = clock,
  )

  private fun success(body: String) = MockResponse().setResponseCode(200).setBody(body)

  private fun entry(
    title: String = "Jigsaw Falling Into Place",
    artist: String = "Radiohead",
    album: String = "In Rainbows",
    duration: Double = 248.0,
    instrumental: Boolean = false,
    plain: String? = "Timed words",
    synced: String? = "[00:01.00]Timed words",
    lyricsFile: String? = null,
  ): String = """
    {
      "id": 34000,
      "trackName": ${Json.encodeToString(title)},
      "artistName": ${Json.encodeToString(artist)},
      "albumName": ${Json.encodeToString(album)},
      "duration": $duration,
      "instrumental": $instrumental,
      "plainLyrics": ${Json.encodeToString(plain)},
      "syncedLyrics": ${Json.encodeToString(synced)},
      "lyricsfile": ${Json.encodeToString(lyricsFile)}
    }
  """.trimIndent()

  private companion object {
    const val POLICY_EPOCH_MS = 1_788_595_200_000L
    const val WORD_TTML =
      "<tt timing=\"Word\"><body><p begin=\"1\" end=\"2\"><span begin=\"1\" end=\"2\">Timed words</span></p></body></tt>"
    val RADIOHEAD = TrackIdentity(
      uri = "spotify:track:0YJ9FWWHn9EfnN0lHwbzvV",
      title = "Jigsaw Falling Into Place",
      artists = listOf("Radiohead"),
      album = "In Rainbows",
      durationMs = 248_000L,
    )
  }
}

private class ReliabilityLyricsCacheDao : LyricsCacheDao {
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
