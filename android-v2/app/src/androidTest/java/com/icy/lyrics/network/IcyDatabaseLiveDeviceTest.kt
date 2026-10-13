package com.icy.lyrics.network

import android.os.Looper
import android.os.StrictMode
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.ProviderResult
import com.icy.lyrics.core.platform.provider.IcyLyricsDatabaseConfig
import com.icy.lyrics.core.platform.provider.IcyLyricsDatabaseProvider
import com.icy.lyrics.core.platform.storage.LyricsCacheRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: `-e liveIcy true`. Never part of the offline regression requirements. */
@RunWith(AndroidJUnit4::class)
class IcyDatabaseLiveDeviceTest {
  @Test
  fun liveWordTimedTtmlLoadsOnMainAndTheSecondFetchIsCached() {
    assumeTrue(
      "Live API smoke test requires instrumentation argument liveIcy=true",
      InstrumentationRegistry.getArguments().getString("liveIcy") == "true",
    )
    val networkRequests = AtomicInteger()
    val lastHttpStatus = AtomicInteger()
    val client = OkHttpClient.Builder()
      .connectTimeout(10L, TimeUnit.SECONDS)
      .readTimeout(20L, TimeUnit.SECONDS)
      .addNetworkInterceptor { chain ->
        networkRequests.incrementAndGet()
        chain.proceed(chain.request()).also { lastHttpStatus.set(it.code) }
      }
      .build()
    val provider = IcyLyricsDatabaseProvider(
      // Use the same Android provider factory / Happy Eyeballs policy as AppServices.
      client = client,
      cache = LyricsCacheRepository(DeviceLyricsCacheDao()),
      config = IcyLyricsDatabaseConfig(
        userAgent = "IcyLyricsAndroid/1.3.0-device-smoke (+https://jackscurrie.com/icy-lyrics)",
      ),
    )
    try {
      runBlocking {
        withContext(Dispatchers.Main.immediate) {
          assertSame(Looper.getMainLooper(), Looper.myLooper())
          val originalPolicy = StrictMode.getThreadPolicy()
          StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(originalPolicy).detectNetwork().penaltyDeathOnNetwork().build(),
          )
          try {
            // The second call below must be local cache-only. Never reload, retry or loop against
            // the live service: a deliberate opt-in run sends at most one public lyrics request.
            val first = provider.fetch(LyricsRequest(TRACK))
            val failureSummary = when (first) {
              is ProviderResult.Failure -> "${first.category}: ${first.message}"
              else -> first::class.simpleName.orEmpty()
            }
            assertTrue("Live Icy request failed: $failureSummary", first is ProviderResult.Found)
            first as ProviderResult.Found
            assertFalse(first.fromCache)
            assertEquals(LyricsSource.ICY_DATABASE, first.document.metadata.source)
            assertEquals(LyricsSyncKind.SYLLABLE, first.document.syncKind)
            assertEquals(200, lastHttpStatus.get())
            assertEquals(1, networkRequests.get())

            val second = provider.fetch(LyricsRequest(TRACK))
            assertTrue("Second fetch must be cache-only", second is ProviderResult.Found && second.fromCache)
            assertEquals(1, networkRequests.get())
            Log.i(
              "IcyDatabaseLiveTest",
              "source=${first.document.metadata.source} sync=${first.document.syncKind} " +
                "lines=${(first.document as SyllableLyrics).lines.size} " +
                "http=${lastHttpStatus.get()} networkRequests=${networkRequests.get()} secondFetchCached=true",
            )
          } finally {
            StrictMode.setThreadPolicy(originalPolicy)
          }
        }
      }
    } finally {
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdown()
    }
  }

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
