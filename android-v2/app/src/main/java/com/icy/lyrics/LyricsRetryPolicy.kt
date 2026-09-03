package com.icy.lyrics

import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Finds a queued source that strict ordering placed ahead of the source whose
 * fallback document is currently visible. Attempts retain provider order.
 */
internal fun LyricsResolution.Found.queuedHigherPriorityAttempt(): ProviderAttempt? {
  val selectedIndex = attempts.indexOfFirst { attempt ->
    attempt.provider == provider && attempt.outcome == ProviderAttemptOutcome.FOUND
  }
  if (selectedIndex <= 0) return null
  return attempts.subList(0, selectedIndex)
    .firstOrNull { it.outcome == ProviderAttemptOutcome.QUEUED }
}

/** Desktop queue cadence: 2s, 3s, 4.5s, 6.75s, then 10s indefinitely. */
internal fun lyricsQueueRetryDelayMs(completedRetries: Int): Long {
  val retryIndex = completedRetries.coerceIn(0, MAX_RETRY_EXPONENT)
  return (BASE_RETRY_DELAY_MS * RETRY_BACKOFF_FACTOR.pow(retryIndex.toDouble()))
    .roundToLong()
    .coerceAtMost(MAX_BACKOFF_DELAY_MS)
}

/** Saturation prevents overflow without imposing a retry limit. */
internal fun nextLyricsQueueRetryAttempt(completedRetries: Int): Int =
  if (completedRetries >= MAX_RETRY_EXPONENT) {
    MAX_RETRY_EXPONENT
  } else {
    completedRetries.coerceAtLeast(0) + 1
  }

private const val BASE_RETRY_DELAY_MS = 2_000L
private const val MAX_BACKOFF_DELAY_MS = 10_000L
private const val RETRY_BACKOFF_FACTOR = 1.5
private const val MAX_RETRY_EXPONENT = 4
