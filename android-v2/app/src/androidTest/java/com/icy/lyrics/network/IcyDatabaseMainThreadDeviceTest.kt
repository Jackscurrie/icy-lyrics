package com.icy.lyrics.network

import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.platform.database.LyricsCacheDao
import com.icy.lyrics.core.platform.database.LyricsCacheEntity
import com.icy.lyrics.core.platform.network.HttpUrl.Companion.toHttpUrl
import com.icy.lyrics.core.platform.provider.IcyLyricsDatabaseConfig
import com.icy.lyrics.core.platform.provider.IcyLyricsDatabaseProvider
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android StrictMode guard, using only a local HTTPS fixture (no production API traffic). */
@RunWith(AndroidJUnit4::class)
class IcyDatabaseMainThreadDeviceTest {
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    val certificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName("localhost")
      .addSubjectAlternativeName("127.0.0.1")
      .build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val clientCertificates = HandshakeCertificates.Builder()
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer().apply {
      useHttps(serverCertificates.sslSocketFactory(), false)
      start()
    }
    client = OkHttpClient.Builder()
      .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
      .build()
  }

  @After
  fun tearDown() {
    server.shutdown()
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun mainThreadLoadParsesWordTimingCachesAndReloadsWithStrictNetworkPolicy() = runBlocking {
    // Waiting after headers is essential: a tiny already-buffered body could otherwise hide the
    // original bug, where await() resumed the main coroutine before body.source().request(...).
    server.enqueue(ttmlResponse().setBodyDelay(250L, TimeUnit.MILLISECONDS))
    server.enqueue(ttmlResponse().setBodyDelay(250L, TimeUnit.MILLISECONDS))
    val provider = IcyLyricsDatabaseProvider(
      client = client,
      cache = LyricsCacheRepository(DeviceLyricsCacheDao()),
      config = IcyLyricsDatabaseConfig(endpoint = server.url("/api/ttml").toString().toHttpUrl()),
    )

    withContext(Dispatchers.Main.immediate) {
      assertSame(Looper.getMainLooper(), Looper.myLooper())
      val originalPolicy = StrictMode.getThreadPolicy()
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder(originalPolicy)
          .detectNetwork()
          .penaltyDeathOnNetwork() // Throws NetworkOnMainThreadException, not a process kill.
          .build(),
      )
      try {
        val first = provider.fetch(LyricsRequest(TRACK))
        assertTrue("Initial main-thread fetch failed: $first", first is ProviderResult.Found)
        first as ProviderResult.Found
        assertFalse(first.fromCache)
        assertEquals(LyricsSource.ICY_DATABASE, first.document.metadata.source)
        assertEquals(LyricsSyncKind.SYLLABLE, first.document.syncKind)

        val cached = provider.fetch(LyricsRequest(TRACK))
        assertTrue("Second fetch must use the loaded cache", cached is ProviderResult.Found && cached.fromCache)
        assertEquals(1, server.requestCount)

        val reloaded = provider.fetch(LyricsRequest(TRACK, allowCached = false))
        assertTrue("Explicit reload failed: $reloaded", reloaded is ProviderResult.Found && !reloaded.fromCache)
        assertEquals(2, server.requestCount)
      } finally {
        StrictMode.setThreadPolicy(originalPolicy)
      }
    }

    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("application/ttml+xml", request.getHeader("Accept"))
    assertEquals("{\"track\":{\"uri\":\"spotify:track:0YJ9FWWHn9EfnN0lHwbzvV\"}}", request.body.readUtf8())
  }

  private fun ttmlResponse() = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/ttml+xml")
    .setBody(
      """
        <tt xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
          <body><div><p begin="1s" end="3s">
            <span begin="1s" end="2s">Timed </span><span begin="2s" end="3s">lyrics</span>
          </p></div></body>
        </tt>
      """.trimIndent(),
    )

  private companion object {
    val TRACK = TrackIdentity(
      uri = "spotify:track:0YJ9FWWHn9EfnN0lHwbzvV",
      title = "Jigsaw Falling Into Place",
      artists = listOf("Radiohead"),
      album = "In Rainbows",
      durationMs = 248_000L,
    )
  }
}

internal class DeviceLyricsCacheDao : LyricsCacheDao {
  private val rows = linkedMapOf<Pair<String, String>, LyricsCacheEntity>()

  override suspend fun get(providerId: String, trackKey: String) = rows[providerId to trackKey]

  override suspend fun getByMetadataKey(providerId: String, metadataKey: String) = rows.values
    .filter { it.providerId == providerId && it.metadataKey == metadataKey }
    .maxByOrNull(LyricsCacheEntity::fetchedAtEpochMs)

  override suspend fun upsert(entity: LyricsCacheEntity) {
    rows[entity.providerId to entity.trackKey] = entity
  }

  override suspend fun delete(providerId: String, trackKey: String) =
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
      .take(keep).map { it.providerId to it.trackKey }.toSet()
    val before = rows.size
    rows.entries.removeAll { it.key !in retained }
    return before - rows.size
  }

  override suspend fun clear() = rows.size.also { rows.clear() }
}
