package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.platform.database.TrackAliasDao
import com.icy.lyrics.core.platform.database.TrackAliasEntity
import com.icy.lyrics.core.platform.storage.TrackAliasRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyTrackResolverTest {
  private lateinit var server: MockWebServer
  private lateinit var aliasDao: FakeTrackAliasDao

  @Before
  fun setUp() {
    server = MockWebServer().also { it.start() }
    aliasDao = FakeTrackAliasDao()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun currentlyPlayingExactMatchIsPersistedWithoutSearch() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(currentJson(trackJson(TRACK_ID))))

    val resolved = resolver().resolve(TRACK)

    assertEquals("spotify:track:$TRACK_ID", resolved)
    assertEquals(resolved, TrackAliasRepository(aliasDao).resolve(TRACK))
    assertEquals(1, server.requestCount)
    val request = server.takeRequest()
    assertEquals("/v1/me/player/currently-playing", request.requestUrl?.encodedPath)
    assertEquals("Bearer catalog-token", request.getHeader("Authorization"))
  }

  @Test
  fun currentlyPlayingCorrelationCanResolveWhenVersionMetadataIsMissing() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(currentJson(trackJson(TRACK_ID))))

    val sparse = TRACK.copy(album = "", durationMs = null)

    assertEquals("spotify:track:$TRACK_ID", resolver().resolve(sparse))
    assertEquals(1, server.requestCount)
  }

  @Test
  fun searchRepairsMissingMediaSessionIdWhenCurrentItemDoesNotMatch() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(200).setBody(searchJson(trackJson(TRACK_ID))))

    assertEquals("spotify:track:$TRACK_ID", resolver().resolve(TRACK))

    val search = server.takeRequest().let { server.takeRequest() }
    assertEquals("/v1/search", search.requestUrl?.encodedPath)
    assertEquals("track", search.requestUrl?.queryParameter("type"))
    assertTrue(search.requestUrl?.queryParameter("q").orEmpty().contains("Test Song"))
  }

  @Test
  fun searchStillWorksWhenTokenLacksCurrentlyPlayingScope() = runTest {
    server.enqueue(MockResponse().setResponseCode(403))
    server.enqueue(MockResponse().setResponseCode(200).setBody(searchJson(trackJson(TRACK_ID))))

    assertEquals("spotify:track:$TRACK_ID", resolver().resolve(TRACK))
    assertEquals(2, server.requestCount)
  }

  @Test
  fun ambiguousSearchDoesNotCreateAlias() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        searchJson(trackJson(TRACK_ID), trackJson(SECOND_TRACK_ID)),
      ),
    )

    assertNull(resolver().resolve(TRACK))
    assertNull(TrackAliasRepository(aliasDao).resolve(TRACK))
  }

  @Test
  fun durationMismatchIsRejected() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        searchJson(trackJson(TRACK_ID, durationMs = 240_000L)),
      ),
    )

    assertNull(resolver().resolve(TRACK))
  }

  @Test
  fun missingSpotifyUriIsRejected() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(200).setBody(searchJson(trackJson(""))))

    assertNull(resolver().resolve(TRACK))
  }

  @Test
  fun bearerTokenIsNeverForwardedAcrossRedirects() = runTest {
    val redirectTarget = MockWebServer().also { it.start() }
    try {
      server.enqueue(
        MockResponse().setResponseCode(307).addHeader("Location", redirectTarget.url("/capture")),
      )
      assertNull(resolver().resolve(TRACK))
      assertEquals(0, redirectTarget.requestCount)
      assertEquals(1, server.requestCount)
    } finally {
      redirectTarget.shutdown()
    }
  }

  @Test
  fun tokenFailureLeavesMetadataTrackUnresolvedWithoutNetworkWork() = runTest {
    val resolver = SpotifyTrackResolver(
      client = OkHttpClient(),
      tokenSource = SpotifyAccessTokenSource { error("refresh failed") },
      aliases = TrackAliasRepository(aliasDao),
      config = SpotifyCatalogConfig(
        baseUrl = server.url("/v1/"),
        allowInsecureForTests = true,
      ),
    )

    assertNull(resolver.resolve(TRACK))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun currentEndpointServerFailureDoesNotRepeatAgainstSearch() = runTest {
    server.enqueue(MockResponse().setResponseCode(500))

    assertNull(resolver().resolve(TRACK))
    assertEquals(1, server.requestCount)
  }

  private fun resolver() = SpotifyTrackResolver(
    client = OkHttpClient(),
    tokenSource = SpotifyAccessTokenSource { "catalog-token" },
    aliases = TrackAliasRepository(aliasDao),
    config = SpotifyCatalogConfig(
      baseUrl = server.url("/v1/"),
      allowInsecureForTests = true,
    ),
  )

  private fun currentJson(track: String) = """{"item":$track}"""

  private fun searchJson(vararg tracks: String) =
    """{"tracks":{"items":[${tracks.joinToString(",")}]}}"""

  private fun trackJson(id: String, durationMs: Long = 180_000L) = """
    {
      "uri": "spotify:track:$id",
      "name": "Test Song",
      "duration_ms": $durationMs,
      "album": {"name": "Test Album"},
      "artists": [{"name": "Test Artist"}],
      "external_ids": {"isrc": "USAAA0000001"}
    }
  """.trimIndent()

  private companion object {
    const val TRACK_ID = "1234567890123456789012"
    const val SECOND_TRACK_ID = "abcdefghijklmnopqrstuv"
    val TRACK = TrackIdentity(
      uri = "metadata:test-song",
      title = "Test Song",
      artists = listOf("Test Artist"),
      album = "Test Album",
      durationMs = 180_000L,
    )
  }
}

private class FakeTrackAliasDao : TrackAliasDao {
  private val rows = linkedMapOf<String, TrackAliasEntity>()

  override suspend fun get(aliasKey: String): TrackAliasEntity? = rows[aliasKey]

  override suspend fun upsert(entity: TrackAliasEntity) {
    rows[entity.aliasKey] = entity
  }

  override suspend fun delete(aliasKey: String): Int =
    if (rows.remove(aliasKey) != null) 1 else 0
}
