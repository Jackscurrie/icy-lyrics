package com.icy.lyrics.core.lyrics.provider

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import com.icy.lyrics.core.lyrics.model.VocalLine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsOrchestratorTest {
  @Test
  fun `strict order records queued source but continues to lower fallback`() = runTest {
    val orchestrator = LyricsOrchestrator(
      listOf(
        provider(LyricsProviderId.LOCAL_TTML, ProviderResult.NotFound()),
        provider(LyricsProviderId.SPICY, ProviderResult.Queued(800, "building")),
        provider(LyricsProviderId.LRCLIB, ProviderResult.Found(line(LyricsSource.LRCLIB))),
        provider(LyricsProviderId.APPLE_MUSIC, ProviderResult.Found(line(LyricsSource.APPLE_MUSIC))),
        provider(LyricsProviderId.SPOTIFY, ProviderResult.Found(line(LyricsSource.SPOTIFY))),
      ),
    )

    val result = orchestrator.resolve(REQUEST) as LyricsResolution.Found
    assertEquals(LyricsProviderId.LRCLIB, result.provider)
    assertEquals(
      listOf(ProviderAttemptOutcome.NOT_FOUND, ProviderAttemptOutcome.QUEUED, ProviderAttemptOutcome.FOUND),
      result.attempts.map { it.outcome },
    )
  }

  @Test
  fun `default strict order is local spicy lrclib apple spotify`() = runTest {
    val calls = mutableListOf<LyricsProviderId>()
    val orchestrator = LyricsOrchestrator(
      LyricsResolutionPolicy.DEFAULT_PROVIDER_ORDER.map { id ->
        recordingProvider(
          id = id,
          calls = calls,
          result = if (id == LyricsProviderId.SPOTIFY) {
            ProviderResult.Found(line(LyricsSource.SPOTIFY))
          } else {
            ProviderResult.NotFound()
          },
        )
      },
    )

    val result = orchestrator.resolve(REQUEST) as LyricsResolution.Found

    assertEquals(LyricsProviderId.SPOTIFY, result.provider)
    assertEquals(
      listOf(
        LyricsProviderId.LOCAL_TTML,
        LyricsProviderId.SPICY,
        LyricsProviderId.LRCLIB,
        LyricsProviderId.APPLE_MUSIC,
        LyricsProviderId.SPOTIFY,
      ),
      calls,
    )
  }

  @Test
  fun `strict returns first queue only after every fallback misses`() = runTest {
    val orchestrator = LyricsOrchestrator(
      listOf(
        provider(LyricsProviderId.LOCAL_TTML, ProviderResult.NotFound()),
        provider(LyricsProviderId.SPICY, ProviderResult.Queued(800)),
        provider(LyricsProviderId.SPOTIFY, ProviderResult.NotFound()),
      ),
    )

    val result = orchestrator.resolve(REQUEST) as LyricsResolution.Pending
    assertEquals(LyricsProviderId.SPICY, result.provider)
    assertEquals(800L, result.retryAfterMs)
    assertEquals(3, result.attempts.size)
  }

  @Test
  fun `better sync never displaces exact local lyrics`() = runTest {
    val local = static(LyricsSource.LOCAL_TTML)
    val result = LyricsOrchestrator(
      listOf(
        provider(LyricsProviderId.LOCAL_TTML, ProviderResult.Found(local)),
        provider(LyricsProviderId.SPICY, ProviderResult.Found(syllable(LyricsSource.SPICY))),
      ),
    ).resolve(REQUEST, LyricsResolutionPolicy(LyricsSelectionMode.BETTER_SYNC)) as LyricsResolution.Found

    assertEquals(LyricsProviderId.LOCAL_TTML, result.provider)
    assertEquals(local, result.document)
    assertEquals(1, result.attempts.size)
  }

  @Test
  fun `better sync ranks syllable over line over static and uses order for ties`() = runTest {
    val result = LyricsOrchestrator(
      listOf(
        provider(LyricsProviderId.LOCAL_TTML, ProviderResult.NotFound()),
        provider(LyricsProviderId.SPICY, ProviderResult.Found(line(LyricsSource.SPICY))),
        provider(LyricsProviderId.SPOTIFY, ProviderResult.Found(syllable(LyricsSource.SPOTIFY))),
        provider(LyricsProviderId.APPLE_MUSIC, ProviderResult.Found(static(LyricsSource.APPLE_MUSIC))),
      ),
    ).resolve(REQUEST, LyricsResolutionPolicy(LyricsSelectionMode.BETTER_SYNC)) as LyricsResolution.Found
    assertEquals(LyricsProviderId.SPOTIFY, result.provider)

    val tie = LyricsOrchestrator(
      listOf(
        provider(LyricsProviderId.SPICY, ProviderResult.Found(syllable(LyricsSource.SPICY))),
        provider(LyricsProviderId.SPOTIFY, ProviderResult.Found(syllable(LyricsSource.SPOTIFY))),
      ),
    ).resolve(REQUEST, LyricsResolutionPolicy(LyricsSelectionMode.BETTER_SYNC)) as LyricsResolution.Found
    assertEquals(LyricsProviderId.SPICY, tie.provider)
  }

  @Test
  fun `rejects forged source and captures thrown provider failure`() = runTest {
    val forged = provider(LyricsProviderId.SPICY, ProviderResult.Found(line(LyricsSource.SPOTIFY)))
    val throwing = object : LyricsProvider {
      override val id = LyricsProviderId.SPOTIFY
      override suspend fun fetch(request: LyricsRequest): ProviderResult = error("boom")
    }
    val result = LyricsOrchestrator(listOf(forged, throwing)).resolve(REQUEST) as LyricsResolution.Missing

    assertEquals(ProviderAttemptOutcome.SOURCE_MISMATCH, result.attempts[0].outcome)
    assertEquals(ProviderAttemptOutcome.FAILED, result.attempts[1].outcome)
    assertTrue(result.attempts[1].message.orEmpty().contains("boom"))
  }

  private fun provider(id: LyricsProviderId, result: ProviderResult) = object : LyricsProvider {
    override val id = id
    override suspend fun fetch(request: LyricsRequest): ProviderResult = result
  }

  private fun recordingProvider(
    id: LyricsProviderId,
    calls: MutableList<LyricsProviderId>,
    result: ProviderResult,
  ) = object : LyricsProvider {
    override val id = id
    override suspend fun fetch(request: LyricsRequest): ProviderResult {
      calls += id
      return result
    }
  }

  private fun metadata(source: LyricsSource) = LyricsMetadata(TRACK_URI, source)

  private fun static(source: LyricsSource): LyricsDocument =
    StaticLyrics(metadata(source), listOf(StaticLyricLine("static")))

  private fun line(source: LyricsSource): LyricsDocument =
    LineLyrics(metadata(source), listOf(TimedLyricLine("line", 0, 1_000)))

  private fun syllable(source: LyricsSource): LyricsDocument = SyllableLyrics(
    metadata(source),
    listOf(SyllableLyricLine(VocalLine(0, 1_000, listOf(LyricToken("word", 0, 1_000))))),
  )

  companion object {
    private const val TRACK_URI = "spotify:track:provider"
    private val REQUEST = LyricsRequest(TrackIdentity(TRACK_URI))
  }
}
