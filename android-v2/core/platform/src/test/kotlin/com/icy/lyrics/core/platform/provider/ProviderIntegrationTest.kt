package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricsSceneEngine
import com.icy.lyrics.core.lyrics.animation.LyricsSceneOptions
import com.icy.lyrics.core.lyrics.animation.TimedElementStatus
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.model.VocalLine
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.provider.LyricsOrchestrator
import com.icy.lyrics.core.lyrics.provider.LyricsProvider
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.LyricsResolutionPolicy
import com.icy.lyrics.core.lyrics.provider.LyricsSelectionMode
import com.icy.lyrics.core.lyrics.provider.ProviderFailureCategory
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.platform.database.LyricsCacheDao
import com.icy.lyrics.core.platform.database.LyricsCacheEntity
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderIntegrationTest {
  private lateinit var server: MockWebServer
  private lateinit var cacheDao: FakeLyricsCacheDao
  private lateinit var cache: LyricsCacheRepository
  private var now = 1_000L

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    cacheDao = FakeLyricsCacheDao()
    cache = LyricsCacheRepository(cacheDao) { now }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun lrclibExactSyncedHitHasVerifiedProvenance() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(lrclibEntry(synced = "[00:01.00]Hello")))
    val provider = lrclib()

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result.toString(), result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSource.LRCLIB, result.document.metadata.source)
    assertEquals("spotify:track:1234567890123456789012", result.document.metadata.trackUri)
    assertEquals("lrc", result.rawFormat)
    val request = server.takeRequest()
    assertEquals("/api/get", request.requestUrl?.encodedPath)
    assertEquals("IcyLyricsTest/1", request.getHeader("User-Agent"))
  }

  @Test
  fun lrclibExact404FallsBackToConfidentSearch() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(MockResponse().setResponseCode(200).setBody("[${lrclibEntry(synced = "[00:01.00]Hello")}]"))
    val provider = lrclib()

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result.toString(), result is ProviderResult.Found)
    assertEquals("/api/get", server.takeRequest().requestUrl?.encodedPath)
    assertEquals("/api/search", server.takeRequest().requestUrl?.encodedPath)
  }

  @Test
  fun lrclibSearchRejectsAnOtherwiseExactVersionWithWrongDuration() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        "[${lrclibEntry(synced = "[00:01.00]Wrong version").replace("\"duration\": 180.0", "\"duration\": 240.0")}]",
      ),
    )

    val result = lrclib().fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun lrclibExactInstrumentalDoesNotSearchForAnotherVersion() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        lrclibEntry(synced = "[00:01.00]Hello").replace(
          "\"instrumental\": false",
          "\"instrumental\": true",
        ),
      ),
    )

    val result = lrclib().fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun lrclibMalformedExactJsonIsAParseFailureAndIsNotNegativeCached() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("{"))

    val result = lrclib().fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(ProviderFailureCategory.PARSE, (result as ProviderResult.Failure).category)
    assertEquals(1, server.requestCount)
    assertEquals(null, cacheDao.get(LyricsProviderId.LRCLIB.name, TRACK.exactStorageKey))
  }

  @Test
  fun lrclibMalformedSearchJsonIsAParseFailure() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    server.enqueue(MockResponse().setResponseCode(200).setBody("["))

    val result = lrclib().fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(ProviderFailureCategory.PARSE, (result as ProviderResult.Failure).category)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun lrclibHonorsRetryAfterOnce() = runTest {
    val waits = mutableListOf<Long>()
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    server.enqueue(MockResponse().setResponseCode(200).setBody(lrclibEntry(synced = "[00:01.00]Hello")))
    val provider = lrclib(wait = { waits += it })

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(listOf(2_000L), waits)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun lrclibRateLimitWaitRemainsCancellable() = runTest {
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
    val provider = lrclib(wait = { throw CancellationException("cancelled") })

    val result = runCatching { provider.fetch(LyricsRequest(TRACK)) }
    assertTrue(result.exceptionOrNull() is CancellationException)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun lrclibUsesExpiredCacheWhileOffline() = runTest {
    val document = StaticLyrics(
      LyricsMetadata(TRACK.uri, LyricsSource.LRCLIB, "LRCLIB"),
      listOf(StaticLyricLine("Cached")),
    )
    cache.put(LyricsProviderId.LRCLIB, TRACK, document, ttlMs = 100L)
    now += 101L
    val provider = lrclib(online = { false })

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertTrue((result as ProviderResult.Found).fromCache)
    assertEquals(LyricsSource.LRCLIB, result.document.metadata.source)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun spicyPrimaryRequestMatchesNormalDesktopQueryContractExactly() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spl")))
    val provider = spicy(LyricsProviderId.SPICY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/query", request.requestUrl?.encodedPath)
    assertEquals("*/*", request.getHeader("Accept"))
    assertEquals("en-US,en;q=0.9", request.getHeader("Accept-Language"))
    assertEquals("application/json", request.getHeader("Content-Type"))
    assertEquals("139", request.getHeader("Content-Length"))
    assertEquals("https://xpui.app.spotify.com", request.getHeader("Origin"))
    assertEquals("https://xpui.app.spotify.com/", request.getHeader("Referer"))
    assertEquals("empty", request.getHeader("Sec-Fetch-Dest"))
    assertEquals("cors", request.getHeader("Sec-Fetch-Mode"))
    assertEquals("cross-site", request.getHeader("Sec-Fetch-Site"))
    assertEquals("6.3.12", request.getHeader("SpicyLyrics-Version"))
    assertEquals(
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Spotify/1.2.63 Chrome/132.0.6834.210 Electron/34.3.1 Safari/537.36",
      request.getHeader("User-Agent"),
    )
    assertEquals("2", request.getHeader("X-mode"))
    assertEquals("Bearer test-token", request.getHeader("SpicyLyrics-WebAuth"))
    assertNull(request.getHeader("Authorization"))
    val bodyBytes = request.body.readByteArray()
    val expectedBody =
      """{"queries":[{"operation":"lyrics","variables":{"id":"1234567890123456789012","auth":"SpicyLyrics-WebAuth"}}],"client":{"version":"6.3.12"}}"""
        .toByteArray(Charsets.UTF_8)
    assertTrue(expectedBody.contentEquals(bodyBytes))
    assertEquals(139, bodyBytes.size)
  }

  @Test
  fun spicyNeverSendsTheDesktopZeroTokenSentinel() = runTest {
    val provider = spicy(
      LyricsProviderId.SPICY,
      tokenSource = SpotifyAccessTokenSource { "0" },
    )

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Unavailable)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun spicyRecognizesHttp200InnerStatusAuthRejectionWithoutOperationId() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"result":{"status":401,"message":"Unauthorized"}}]}""",
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    result as ProviderResult.Failure
    assertEquals(ProviderFailureCategory.HTTP, result.category)
    assertEquals(401, result.httpStatus)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyRefreshesRejectedTokenAndRetriesOnlyOnceWithReplacement() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"operationId":"0","result":{"httpStatus":403}}]}""",
      ),
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spl")))
    var refreshCalls = 0
    val tokenSource = object : SpotifyAccessTokenSource {
      override suspend fun accessToken() = "rejected-token"

      override suspend fun refreshAfterRejection(rejectedToken: String): String {
        assertEquals("rejected-token", rejectedToken)
        refreshCalls += 1
        return "replacement-token"
      }
    }

    val result = spicy(LyricsProviderId.SPICY, tokenSource = tokenSource)
      .fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(1, refreshCalls)
    assertEquals("Bearer rejected-token", server.takeRequest().getHeader("SpicyLyrics-WebAuth"))
    assertEquals("Bearer replacement-token", server.takeRequest().getHeader("SpicyLyrics-WebAuth"))
    assertEquals(2, server.requestCount)
  }

  @Test
  fun spicyNeverRetriesAnAuthRejectionWithTheSameToken() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"result":{"Status":403}}]}""",
      ),
    )
    val tokenSource = object : SpotifyAccessTokenSource {
      override suspend fun accessToken() = "same-token"
      override suspend fun refreshAfterRejection(rejectedToken: String) = rejectedToken
    }

    val result = spicy(LyricsProviderId.SPICY, tokenSource = tokenSource)
      .fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertFalse(result is ProviderResult.NotFound)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyRetriesAtMostOnceWhenTheReplacementIsAlsoRejected() = runTest {
    repeat(2) {
      server.enqueue(
        MockResponse().setResponseCode(200).setBody(
          """{"queries":[{"result":{"status":401}}]}""",
        ),
      )
    }
    var refreshCalls = 0
    val tokenSource = object : SpotifyAccessTokenSource {
      override suspend fun accessToken() = "first-token"

      override suspend fun refreshAfterRejection(rejectedToken: String): String {
        refreshCalls += 1
        return "second-token"
      }
    }

    val result = spicy(LyricsProviderId.SPICY, tokenSource = tokenSource)
      .fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(401, (result as ProviderResult.Failure).httpStatus)
    assertEquals(1, refreshCalls)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun spicyReadsOnlyDesktopOperationIdZero() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """
          {
            "queries": [
              {"operationId":"1","result":{"data":{"Type":"Static","source":"aml","Lines":[{"Text":"Wrong"}]},"httpStatus":200,"format":"json"}},
              {"operationId":"0","result":{"data":{"Type":"Static","source":"spl","Lines":[{"Text":"Right"}]},"httpStatus":200,"format":"json"}}
            ]
          }
        """.trimIndent(),
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSource.SPICY, result.document.metadata.source)
    assertTrue((result.document as StaticLyrics).lines.single().text.contains("Right"))
  }

  @Test
  fun spicyMissingOperationIdZeroMatchesDesktopNotFoundPath() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"operationId":"1","result":{"data":{"Type":"Static","source":"spl","Lines":[{"Text":"Wrong slot"}]},"httpStatus":200,"format":"json"}}]}""",
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
  }

  @Test
  fun spicyMissingResultStatusIsRejectedLikeDesktop() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"operationId":"0","result":{"data":{"Type":"Static","source":"spl","Lines":[{"Text":"No status"}]},"format":"json"}}]}""",
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(ProviderFailureCategory.HTTP, (result as ProviderResult.Failure).category)
  }

  @Test
  fun spicyDoesNotTreatEnvelopeResultAliasAsData() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"operationId":"0","result":{"Result":{"Type":"Static","source":"spl","Lines":[{"Text":"Wrong field"}]},"httpStatus":200,"format":"json"}}]}""",
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
  }

  @Test
  fun spicyNeverForwardsSpotifyTokenAcrossRedirects() = runTest {
    val redirectTarget = MockWebServer().also { it.start() }
    try {
      redirectTarget.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spl")))
      server.enqueue(
        MockResponse()
          .setResponseCode(307)
          .addHeader("Location", redirectTarget.url("/capture")),
      )

      val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

      assertTrue(result is ProviderResult.Failure)
      assertEquals(ProviderFailureCategory.SECURITY, (result as ProviderResult.Failure).category)
      assertEquals(1, server.requestCount)
      assertEquals(0, redirectTarget.requestCount)
    } finally {
      redirectTarget.shutdown()
    }
  }

  @Test
  fun spotifyBackedSpicyQueryRequestsAndRequiresSptSource() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spt")))
    val provider = spicy(LyricsProviderId.SPOTIFY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSource.SPOTIFY, (result as ProviderResult.Found).document.metadata.source)
    assertTrue(server.takeRequest().body.readUtf8().contains("\"source\":\"spt\""))
  }

  @Test
  fun appleBackedSpicyQueryUsesDesktopCreatorSourceHint() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("aml")))

    val result = spicy(LyricsProviderId.APPLE_MUSIC).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSource.APPLE_MUSIC, (result as ProviderResult.Found).document.metadata.source)
    assertTrue(server.takeRequest().body.readUtf8().contains("\"source\":\"aml\""))
  }

  @Test
  fun appleBackedSpicyQueryRecognizesUppercaseDesktopProviderAlias() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        spicyEnvelope("aml").replace("\"source\": \"aml\"", "\"Provider\": \"aml\""),
      ),
    )

    val result = spicy(LyricsProviderId.APPLE_MUSIC).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(LyricsSource.APPLE_MUSIC, (result as ProviderResult.Found).document.metadata.source)
  }

  @Test
  fun spotifyBackedSpicyQueryTreatsSourceLessPayloadAsUnavailableInsteadOfSecurityFailure() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelopeWithoutSource()))
    val provider = spicy(LyricsProviderId.SPOTIFY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
    assertTrue((result as ProviderResult.NotFound).message.orEmpty().contains("did not identify"))
  }

  @Test
  fun spotifyBackedSpicyQueryTreatsMismatchedProviderAliasAsUnavailableWithoutRelabeling() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        spicyEnvelope("aml").replace("\"source\": \"aml\"", "\"Provider\": \"Apple Music\""),
      ),
    )

    val result = spicy(LyricsProviderId.SPOTIFY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.NotFound)
    result as ProviderResult.NotFound
    assertTrue(result.message.orEmpty().contains("Apple Music"))
  }

  @Test
  fun spicyPrimaryAcceptsSourceLessLyricsLikeNormalDesktopPlayback() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelopeWithoutSource()))
    val provider = spicy(LyricsProviderId.SPICY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSource.SPICY, result.document.metadata.source)
    assertEquals(LyricsProviderId.SPICY, result.validatedForProvider)
  }

  @Test
  fun spicyPrimaryReusesCurrentContractAutomaticResultWithLrclibProvenance() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("lrc")))
    val provider = spicy(LyricsProviderId.SPICY)

    val network = provider.fetch(LyricsRequest(TRACK))
    val cached = provider.fetch(LyricsRequest(TRACK))

    assertTrue(network is ProviderResult.Found)
    assertEquals(LyricsSource.LRCLIB, (network as ProviderResult.Found).document.metadata.source)
    assertEquals(LyricsProviderId.SPICY, network.validatedForProvider)
    assertTrue(cached is ProviderResult.Found)
    cached as ProviderResult.Found
    assertTrue(cached.fromCache)
    assertEquals(LyricsSource.LRCLIB, cached.document.metadata.source)
    assertEquals(LyricsProviderId.SPICY, cached.validatedForProvider)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyPrimaryRefreshesLegacyStaticCacheThenKeepsPackedWordTimingAndProvenance() = runTest {
    cache.put(
      LyricsProviderId.SPICY,
      TRACK,
      static(LyricsSource.LRCLIB),
      rawPayload = """{"Type":"Static","source":"lrc"}""",
      rawFormat = "json",
      sourceVerified = true,
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyPackedSyllableEnvelope()))
    val provider = spicy(LyricsProviderId.SPICY)

    val refreshed = provider.fetch(LyricsRequest(TRACK))
    val cached = provider.fetch(LyricsRequest(TRACK))

    assertTrue(refreshed is ProviderResult.Found)
    refreshed as ProviderResult.Found
    assertFalse(refreshed.fromCache)
    assertEquals(LyricsSource.APPLE_MUSIC, refreshed.document.metadata.source)
    assertTrue(refreshed.document is SyllableLyrics)
    val refreshedTokens = (refreshed.document as SyllableLyrics).lines.single().lead.tokens
    assertEquals(listOf("Hel", "lo", "world"), refreshedTokens.map(LyricToken::text))
    assertEquals(listOf(1_250L, 1_550L, 2_200L), refreshedTokens.map(LyricToken::startMs))
    assertEquals(listOf(1_550L, 2_200L, 2_800L), refreshedTokens.map(LyricToken::endMs))

    assertTrue(cached is ProviderResult.Found)
    cached as ProviderResult.Found
    assertTrue(cached.fromCache)
    assertEquals("json", cached.rawFormat)
    assertEquals(LyricsSource.APPLE_MUSIC, cached.document.metadata.source)
    assertTrue(cached.document is SyllableLyrics)
    assertEquals(refreshedTokens, (cached.document as SyllableLyrics).lines.single().lead.tokens)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyQueuedResultReturnsImmediatelyWithoutConsumingAnotherResponse() = runTest {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Retry-After", "86400")
        .setBody(spicyEnvelope("spl", status = 503)),
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("aml")))
    val provider = spicy(LyricsProviderId.SPICY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Queued)
    assertNull((result as ProviderResult.Queued).retryAfterMs)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyOuterHttp503IsFailureRatherThanDesktopQueueResult() = runTest {
    server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "86400"))
    val provider = spicy(LyricsProviderId.SPICY)

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    result as ProviderResult.Failure
    assertEquals(ProviderFailureCategory.HTTP, result.category)
    assertEquals(503, result.httpStatus)
    assertNull(result.retryAfterMs)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyHostFailureSkipsSiblingSourceRequestsDuringCooldown() = runTest {
    var now = 1_000L
    val circuit = SpicyHostCircuitBreaker(clock = { now }, coolDownMs = 5_000L)
    server.enqueue(MockResponse().setResponseCode(500))
    val first = spicy(LyricsProviderId.SPICY, hostCircuitBreaker = circuit)
    val sibling = spicy(LyricsProviderId.SPOTIFY, hostCircuitBreaker = circuit)

    assertTrue(first.fetch(LyricsRequest(TRACK)) is ProviderResult.Failure)
    assertTrue(sibling.fetch(LyricsRequest(TRACK)) is ProviderResult.Unavailable)
    assertEquals(1, server.requestCount)

    now += 5_001L
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spt")))
    assertTrue(sibling.fetch(LyricsRequest(TRACK)) is ProviderResult.Found)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun explicitSpotifyAdapterNeverUsesExpiredCacheFromTheWrongSource() = runTest {
    cache.put(
      LyricsProviderId.SPOTIFY,
      TRACK,
      static(LyricsSource.LRCLIB),
      ttlMs = 1L,
      sourceVerified = true,
    )
    now += 2L
    val provider = SpicyLyricsProvider(
      id = LyricsProviderId.SPOTIFY,
      client = okhttp3.OkHttpClient(),
      tokenSource = SpotifyAccessTokenSource { "test-token" },
      cache = cache,
      config = SpicyLyricsConfig(server.url("/query"), allowInsecureForTests = true),
      enabled = { true },
      tokenSharingConsent = { true },
      online = { false },
    )

    val result = provider.fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Unavailable)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun objectPackDecoderRejectsTrailingDataAndDecodesObject() {
    val json = Json
    val decoded = SpicyPayloadDecoder().unpack(json.parseToJsonElement("[[\"key\",\"value\"],[-1,1,0,1]]"))
    assertEquals("{\"key\":\"value\"}", decoded.toString())
    val failure = runCatching {
      SpicyPayloadDecoder().unpack(json.parseToJsonElement("[[\"x\"],[0,0]]"))
    }
    assertTrue(failure.isFailure)
    assertTrue(
      runCatching {
        SpicyPayloadDecoder().unpack(Json.parseToJsonElement("[[1,\"value\"],[-1,1,0,1]]"))
      }.isFailure,
    )
    assertTrue(
      runCatching {
        SpicyPayloadDecoder().unpack(Json.parseToJsonElement("[[\"key\"],[\"-6\"]]"))
      }.isFailure,
    )
  }

  @Test
  fun objectPackLimitsMatchDesktopSlObjPack() {
    assertEquals(
      SpicyPayloadDecoder.Limits(
        maxDepth = 512,
        maxArrayLength = 1 shl 20,
        maxObjectKeys = 1 shl 16,
        maxValuesLength = 1 shl 22,
        maxStreamLength = 1 shl 24,
        maxDecodeOperations = 1 shl 22,
      ),
      SpicyPayloadDecoder.Limits(),
    )
  }

  @Test
  fun spicyDecodesDesktopXModeTwoPackedPayload() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyPackedEnvelope()))

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    result as ProviderResult.Found
    assertEquals(LyricsSource.SPICY, result.document.metadata.source)
    assertEquals("Packed hello", (result.document as StaticLyrics).lines.single().text)
  }

  @Test
  fun spicyAutoPackedSyllablesWinBeforeLrclibAndKeepEveryWordTiming() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyPackedSyllableEnvelope()))
    val fallbackCalls = mutableListOf<LyricsProviderId>()
    val primary = spicy(LyricsProviderId.SPICY)
    val lrclib = fakeProvider(
      LyricsProviderId.LRCLIB,
      static(LyricsSource.LRCLIB),
      fallbackCalls,
    )
    val orchestrator = LyricsOrchestrator(listOf(primary, lrclib))

    val resolution = orchestrator.resolve(
      LyricsRequest(TRACK),
      LyricsResolutionPolicy(
        mode = LyricsSelectionMode.STRICT_ORDER,
        providerOrder = listOf(LyricsProviderId.SPICY, LyricsProviderId.LRCLIB),
      ),
    )

    assertTrue(resolution is LyricsResolution.Found)
    resolution as LyricsResolution.Found
    assertEquals(LyricsProviderId.SPICY, resolution.provider)
    assertEquals(LyricsSource.APPLE_MUSIC, resolution.document.metadata.source)
    assertTrue(resolution.document is SyllableLyrics)
    val line = (resolution.document as SyllableLyrics).lines.single()
    assertEquals("Hello world", line.lead.text)
    assertEquals(1_250L, line.lead.startMs)
    assertEquals(2_800L, line.lead.endMs)
    assertEquals(listOf("Hel", "lo", "world"), line.lead.tokens.map(LyricToken::text))
    assertEquals(listOf(1_250L, 1_550L, 2_200L), line.lead.tokens.map(LyricToken::startMs))
    assertEquals(listOf(1_550L, 2_200L, 2_800L), line.lead.tokens.map(LyricToken::endMs))
    assertEquals(listOf(true, false, false), line.lead.tokens.map(LyricToken::isPartOfWord))
    val scene = LyricsSceneEngine().frame(
      resolution.document,
      lyricsPositionMs = 1_400L,
      options = LyricsSceneOptions(synthesizeInterludes = false),
    )
    assertEquals(LyricsSyncKind.SYLLABLE, scene.syncKind)
    val animatedLead = scene.lines.single { it.kind == LyricSceneLineKind.VOCAL }
    assertEquals(listOf("Hel", "lo", "world"), animatedLead.tokens.map { it.text })
    assertEquals(TimedElementStatus.ACTIVE, animatedLead.tokens[0].animation.status)
    assertEquals(0.5, animatedLead.tokens[0].animation.progress, 0.000_001)
    assertEquals(TimedElementStatus.NOT_SUNG, animatedLead.tokens[1].animation.status)
    assertEquals("back", line.background.single().tokens.single().text)
    assertEquals(1_700L, line.background.single().tokens.single().startMs)
    assertEquals(2_400L, line.background.single().tokens.single().endMs)
    assertTrue(fallbackCalls.isEmpty())
    assertTrue(!server.takeRequest().body.readUtf8().contains("\"source\""))
  }

  @Test
  fun spicyIgnoresNegativeCacheWrittenByTheOldCreatorHintContract() = runTest {
    cache.putNegative(LyricsProviderId.SPICY, TRACK)
    server.enqueue(MockResponse().setResponseCode(200).setBody(spicyEnvelope("spl")))

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Found)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun spicyRejectsPlainTextThatDesktopPayloadDecoderCannotDecode() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"queries":[{"operationId":"0","result":{"data":"not JSON or TTML","httpStatus":200,"format":"text"}}]}""",
      ),
    )

    val result = spicy(LyricsProviderId.SPICY).fetch(LyricsRequest(TRACK))

    assertTrue(result is ProviderResult.Failure)
    assertEquals(ProviderFailureCategory.PARSE, (result as ProviderResult.Failure).category)
  }

  @Test
  fun betterSyncKeepsLocalAbsoluteAndDefaultOrderIsLocked() = runTest {
    val calls = mutableListOf<LyricsProviderId>()
    val local = fakeProvider(LyricsProviderId.LOCAL_TTML, static(LyricsSource.LOCAL_TTML), calls)
    val spicy = fakeProvider(LyricsProviderId.SPICY, syllable(LyricsSource.SPICY), calls)
    val orchestrator = LyricsOrchestrator(listOf(spicy, local))

    val result = orchestrator.resolve(
      LyricsRequest(TRACK),
      LyricsResolutionPolicy(mode = LyricsSelectionMode.BETTER_SYNC),
    )

    assertTrue(result is LyricsResolution.Found)
    assertEquals(LyricsProviderId.LOCAL_TTML, (result as LyricsResolution.Found).provider)
    assertEquals(listOf(LyricsProviderId.LOCAL_TTML), calls)
    assertEquals(
      listOf(
        LyricsProviderId.LOCAL_TTML,
        LyricsProviderId.SPICY,
        LyricsProviderId.LRCLIB,
        LyricsProviderId.APPLE_MUSIC,
        LyricsProviderId.SPOTIFY,
      ),
      LyricsResolutionPolicy.DEFAULT_PROVIDER_ORDER,
    )
  }

  @Test
  fun cacheDoesNotCrossFillDistinctSpotifyUrisWithMatchingMetadata() = runTest {
    cache.put(LyricsProviderId.LRCLIB, TRACK, static(LyricsSource.LRCLIB))
    val anotherSpotifyTrack = TRACK.copy(uri = "spotify:track:abcdefghijklmnopqrstuv")

    assertEquals(null, cache.get(LyricsProviderId.LRCLIB, anotherSpotifyTrack))
  }

  @Test
  fun cacheStartupPruneRemovesExpiredRows() = runTest {
    cache.put(LyricsProviderId.LRCLIB, TRACK, static(LyricsSource.LRCLIB), ttlMs = 1L)
    now += 2L

    assertEquals(1, cache.pruneExpired())
    assertEquals(null, cache.get(LyricsProviderId.LRCLIB, TRACK, allowExpired = true))
  }

  private fun lrclib(
    online: () -> Boolean = { true },
    wait: suspend (Long) -> Unit = {},
  ) = LrclibProvider(
    client = okhttp3.OkHttpClient(),
    cache = cache,
    config = LrclibConfig(
      baseUrl = server.url("/api/"),
      userAgent = "IcyLyricsTest/1",
      requestSpacingMs = 0,
      allowInsecureForTests = true,
    ),
    online = online,
    wait = wait,
  )

  private fun spicy(
    id: LyricsProviderId,
    hostCircuitBreaker: SpicyHostCircuitBreaker = SpicyHostCircuitBreaker(),
    tokenSource: SpotifyAccessTokenSource = SpotifyAccessTokenSource { "test-token" },
  ) = SpicyLyricsProvider(
    id = id,
    client = okhttp3.OkHttpClient(),
    tokenSource = tokenSource,
    cache = cache,
    config = SpicyLyricsConfig(server.url("/query"), allowInsecureForTests = true),
    enabled = { true },
    tokenSharingConsent = { true },
    hostCircuitBreaker = hostCircuitBreaker,
  )

  private fun fakeProvider(
    id: LyricsProviderId,
    document: com.icy.lyrics.core.lyrics.model.LyricsDocument,
    calls: MutableList<LyricsProviderId>,
  ) = object : LyricsProvider {
    override val id = id
    override suspend fun fetch(request: LyricsRequest): ProviderResult {
      calls += id
      return ProviderResult.Found(document)
    }
  }

  private fun static(source: LyricsSource) = StaticLyrics(
    LyricsMetadata(TRACK.uri, source),
    listOf(StaticLyricLine("Local")),
  )

  private fun syllable(source: LyricsSource) = SyllableLyrics(
    LyricsMetadata(TRACK.uri, source),
    listOf(
      SyllableLyricLine(
        VocalLine(0, 1000, listOf(LyricToken("Remote", 0, 1000))),
      ),
    ),
  )

  private fun lrclibEntry(synced: String) = """
    {
      "id": 1,
      "trackName": "Test Song",
      "artistName": "Test Artist",
      "albumName": "Test Album",
      "duration": 180.0,
      "instrumental": false,
      "plainLyrics": "Hello",
      "syncedLyrics": ${Json.encodeToString(synced)},
      "lyricsfile": null
    }
  """.trimIndent()

  private fun spicyEnvelope(source: String, status: Int = 200) = """
    {
      "queries": [{
        "operationId": "0",
        "result": {
          "data": {
            "Type": "Static",
            "source": "$source",
            "Lines": [{"Text": "Hello"}]
          },
          "httpStatus": $status,
          "format": "json"
        }
      }]
    }
  """.trimIndent()

  private fun spicyEnvelopeWithoutSource() = """
    {
      "queries": [{
        "operationId": "0",
        "result": {
          "data": {
            "Type": "Static",
            "Lines": [{"Text": "Hello"}]
          },
          "httpStatus": 200,
          "format": "json"
        }
      }]
    }
  """.trimIndent()

  private fun spicyPackedEnvelope() = """
    {
      "queries": [{
        "operationId": "0",
        "result": {
          "data": [
            ["Type", "source", "Lines", "Static", "spl", "Text", "Packed hello"],
            [-1, 3, 0, 1, 2, 3, 4, -5, -1, 1, 5, 6]
          ],
          "httpStatus": 200,
          "format": "json"
        }
      }]
    }
  """.trimIndent()

  /** Produced by the desktop SLObjPack.pack implementation. */
  private fun spicyPackedSyllableEnvelope() = """
    {
      "queries": [{
        "operationId": "0",
        "result": {
          "data": [
            ["StartTime","EndTime","Text","IsPartOfWord",false,"Type",1.25,2.8,"Syllables",1.55,2.2,1.7,2.4,"Syllable","source","aml","Content","Vocal","Lead","Hel",true,"lo","world","Background","back"],
            [-1,3,5,14,16,13,15,-5,-1,3,5,18,23,17,-1,3,0,1,8,6,7,-3,3,4,2,0,1,3,19,6,9,20,21,9,10,4,22,10,7,4,-5,-1,3,0,1,8,11,12,-5,-1,4,2,0,1,3,24,11,12,4]
          ],
          "httpStatus": 200,
          "format": "json"
        }
      }]
    }
  """.trimIndent()

  private companion object {
    val TRACK = TrackIdentity(
      uri = "spotify:track:1234567890123456789012",
      title = "Test Song",
      artists = listOf("Test Artist"),
      album = "Test Album",
      durationMs = 180_000,
    )
  }
}

private class FakeLyricsCacheDao : LyricsCacheDao {
  private val rows = linkedMapOf<Pair<String, String>, LyricsCacheEntity>()

  override suspend fun get(providerId: String, trackKey: String) = rows[providerId to trackKey]

  override suspend fun getByMetadataKey(providerId: String, metadataKey: String) = rows.values
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
