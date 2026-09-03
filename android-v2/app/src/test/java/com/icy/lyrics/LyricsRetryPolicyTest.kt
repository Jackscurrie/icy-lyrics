package com.icy.lyrics

import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRetryPolicyTest {
  @Test
  fun queuedSourceBeforeVisibleFallbackIsPromotedInBackground() {
    val queued = ProviderAttempt(
      provider = LyricsProviderId.SPICY,
      outcome = ProviderAttemptOutcome.QUEUED,
      retryAfterMs = 2_000L,
    )
    val found = resolution(
      selected = LyricsProviderId.SPOTIFY,
      attempts = listOf(queued, foundAttempt(LyricsProviderId.SPOTIFY)),
    )

    assertEquals(queued, found.queuedHigherPriorityAttempt())
  }

  @Test
  fun queuedSourceAfterSelectedProviderDoesNotTriggerPromotion() {
    val found = resolution(
      selected = LyricsProviderId.SPICY,
      attempts = listOf(
        foundAttempt(LyricsProviderId.SPICY),
        ProviderAttempt(LyricsProviderId.SPOTIFY, ProviderAttemptOutcome.QUEUED),
      ),
    )

    assertNull(found.queuedHigherPriorityAttempt())
  }

  @Test
  fun retryDelayUsesDesktopExponentialCadenceForever() {
    assertEquals(
      listOf(2_000L, 3_000L, 4_500L, 6_750L, 10_000L, 10_000L),
      (0..5).map(::lyricsQueueRetryDelayMs),
    )
    assertEquals(2_000L, lyricsQueueRetryDelayMs(-1))
    assertEquals(10_000L, lyricsQueueRetryDelayMs(Int.MAX_VALUE))
  }

  @Test
  fun retryAttemptSaturatesWithoutEndingTheQueueLoop() {
    assertEquals(
      listOf(1, 2, 3, 4, 4, 4),
      generateSequence(nextLyricsQueueRetryAttempt(0), ::nextLyricsQueueRetryAttempt).take(6).toList(),
    )
    assertEquals(1, nextLyricsQueueRetryAttempt(-1))
    assertEquals(4, nextLyricsQueueRetryAttempt(Int.MAX_VALUE))
  }

  private fun resolution(
    selected: LyricsProviderId,
    attempts: List<ProviderAttempt>,
  ) = LyricsResolution.Found(
    document = StaticLyrics(
      metadata = LyricsMetadata(source = selected.expectedSource),
      lines = listOf(StaticLyricLine("fallback")),
    ),
    provider = selected,
    attempts = attempts,
  )

  private fun foundAttempt(provider: LyricsProviderId) = ProviderAttempt(
    provider = provider,
    outcome = ProviderAttemptOutcome.FOUND,
    source = provider.expectedSource,
  )
}
