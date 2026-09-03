package com.icy.lyrics.core.lyrics.provider

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable

/**
 * STRICT_ORDER preserves source precedence. BETTER_SYNC still gives an exact
 * local document absolute priority, then chooses the best remote timing level.
 */
@Serializable
enum class LyricsSelectionMode {
  STRICT_ORDER,
  BETTER_SYNC,
}

@Serializable
data class LyricsResolutionPolicy(
  val mode: LyricsSelectionMode = LyricsSelectionMode.STRICT_ORDER,
  val providerOrder: List<LyricsProviderId> = DEFAULT_PROVIDER_ORDER,
) {
  init {
    require(providerOrder.distinct().size == providerOrder.size) {
      "Provider order must not contain duplicates"
    }
  }

  companion object {
    /** Canonical strict priority shared by resolution, diagnostics, and retry promotion. */
    val DEFAULT_PROVIDER_ORDER = listOf(
      LyricsProviderId.LOCAL_TTML,
      LyricsProviderId.SPICY,
      LyricsProviderId.LRCLIB,
      LyricsProviderId.APPLE_MUSIC,
      LyricsProviderId.SPOTIFY,
    )
  }
}

@Serializable
sealed interface LyricsResolution {
  val attempts: List<ProviderAttempt>

  @Serializable
  data class Found(
    val document: LyricsDocument,
    val provider: LyricsProviderId,
    override val attempts: List<ProviderAttempt>,
  ) : LyricsResolution

  /** A higher-priority source is preparing a result and should be retried. */
  @Serializable
  data class Pending(
    val provider: LyricsProviderId,
    val retryAfterMs: Long? = null,
    val message: String? = null,
    override val attempts: List<ProviderAttempt>,
  ) : LyricsResolution

  /** All enabled providers completed without a usable, source-valid document. */
  @Serializable
  data class Missing(
    override val attempts: List<ProviderAttempt>,
  ) : LyricsResolution
}

/** Pure provider selection; persistence, HTTP, auth and retries stay in platform adapters. */
class LyricsOrchestrator(
  providers: Iterable<LyricsProvider>,
) {
  private val providersById = providers.associateBy(LyricsProvider::id)

  suspend fun resolve(
    request: LyricsRequest,
    policy: LyricsResolutionPolicy = LyricsResolutionPolicy(),
  ): LyricsResolution {
    val selected = policy.providerOrder
      .filter { id -> request.requestedSource == null || id == LyricsProviderId.LOCAL_TTML || id.expectedSource == request.requestedSource }
      .mapNotNull(providersById::get)

    return when (policy.mode) {
      LyricsSelectionMode.STRICT_ORDER -> resolveStrict(request, selected)
      LyricsSelectionMode.BETTER_SYNC -> resolveBestSync(request, selected)
    }
  }

  private suspend fun resolveStrict(
    request: LyricsRequest,
    selected: List<LyricsProvider>,
  ): LyricsResolution {
    val attempts = mutableListOf<ProviderAttempt>()
    var firstQueued: Pair<LyricsProviderId, ProviderResult.Queued>? = null
    for (provider in selected) {
      when (val result = fetchSafely(provider, request)) {
        is ProviderResult.Found -> {
          val attempt = result.toAttempt(provider.id)
          attempts += attempt
          if (attempt.outcome == ProviderAttemptOutcome.FOUND) {
            return LyricsResolution.Found(result.document, provider.id, attempts.toList())
          }
        }

        is ProviderResult.Queued -> {
          attempts += result.toAttempt(provider.id)
          if (firstQueued == null) firstQueued = provider.id to result
        }

        else -> attempts += result.toAttempt(provider.id)
      }
    }
    firstQueued?.let { (provider, queued) ->
      return LyricsResolution.Pending(
        provider = provider,
        retryAfterMs = queued.retryAfterMs,
        message = queued.message,
        attempts = attempts.toList(),
      )
    }
    return LyricsResolution.Missing(attempts)
  }

  private suspend fun resolveBestSync(
    request: LyricsRequest,
    selected: List<LyricsProvider>,
  ): LyricsResolution {
    val local = selected.firstOrNull { it.id == LyricsProviderId.LOCAL_TTML }
    val attemptsById = mutableMapOf<LyricsProviderId, ProviderAttempt>()
    var firstQueued: Pair<LyricsProviderId, ProviderResult.Queued>? = null

    if (local != null) {
      val localResult = fetchSafely(local, request)
      attemptsById[local.id] = localResult.toAttempt(local.id)
      if (localResult is ProviderResult.Found && localResult.hasExpectedSource(local.id)) {
        return LyricsResolution.Found(localResult.document, local.id, listOf(attemptsById.getValue(local.id)))
      }
      if (localResult is ProviderResult.Queued) {
        firstQueued = local.id to localResult
      }
    }

    val remotes = selected.filterNot { it.id == LyricsProviderId.LOCAL_TTML }
    val results = supervisorScope {
      remotes.map { provider -> async { provider to fetchSafely(provider, request) } }.awaitAll()
    }
    results.forEach { (provider, result) -> attemptsById[provider.id] = result.toAttempt(provider.id) }
    val attempts = selected.mapNotNull { attemptsById[it.id] }

    val winner = results
      .filter { (provider, result) -> result is ProviderResult.Found && result.hasExpectedSource(provider.id) }
      .maxByOrNull { (provider, result) ->
        syncScore((result as ProviderResult.Found).document.syncKind) * 1_000 - selected.indexOf(provider)
      }
    if (winner != null) {
      val (provider, result) = winner
      return LyricsResolution.Found((result as ProviderResult.Found).document, provider.id, attempts)
    }

    // A queue is useful only when no already-complete provider supplied lyrics.
    if (firstQueued == null) {
      results.firstOrNull { it.second is ProviderResult.Queued }?.let { (provider, queued) ->
        firstQueued = provider.id to (queued as ProviderResult.Queued)
      }
    }
    firstQueued?.let { (provider, queued) ->
      return LyricsResolution.Pending(provider, queued.retryAfterMs, queued.message, attempts)
    }
    return LyricsResolution.Missing(attempts)
  }

  private suspend fun fetchSafely(provider: LyricsProvider, request: LyricsRequest): ProviderResult =
    try {
      provider.fetch(request)
    } catch (error: Throwable) {
      // Cancellation must retain structured-concurrency semantics.
      if (error is kotlinx.coroutines.CancellationException) throw error
      ProviderResult.Failure(
        category = ProviderFailureCategory.UNKNOWN,
        message = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName ?: "Provider failed",
      )
    }

  private fun ProviderResult.hasExpectedSource(provider: LyricsProviderId): Boolean =
    this is ProviderResult.Found &&
      (document.metadata.source == provider.expectedSource || validatedForProvider == provider)

  private fun ProviderResult.toAttempt(provider: LyricsProviderId): ProviderAttempt = when (this) {
    is ProviderResult.Found -> {
      val matches = hasExpectedSource(provider)
      ProviderAttempt(
        provider = provider,
        outcome = if (matches) ProviderAttemptOutcome.FOUND else ProviderAttemptOutcome.SOURCE_MISMATCH,
        source = document.metadata.source,
        syncKind = document.syncKind.name,
        fromCache = fromCache,
        message = if (matches) message else "Expected ${provider.expectedSource}, received ${document.metadata.source}",
      )
    }

    is ProviderResult.NotFound -> ProviderAttempt(provider, ProviderAttemptOutcome.NOT_FOUND, message = message)
    is ProviderResult.Queued -> ProviderAttempt(
      provider,
      ProviderAttemptOutcome.QUEUED,
      retryAfterMs = retryAfterMs,
      message = message,
    )
    is ProviderResult.Unavailable -> ProviderAttempt(
      provider,
      ProviderAttemptOutcome.UNAVAILABLE,
      message = listOfNotNull(reason.name, message).joinToString(": "),
    )
    is ProviderResult.Failure -> ProviderAttempt(
      provider,
      ProviderAttemptOutcome.FAILED,
      httpStatus = httpStatus,
      retryAfterMs = retryAfterMs,
      message = listOf(category.name, message).joinToString(": "),
    )
  }

  private fun syncScore(kind: LyricsSyncKind): Int = when (kind) {
    LyricsSyncKind.SYLLABLE -> 3
    LyricsSyncKind.LINE -> 2
    LyricsSyncKind.STATIC -> 1
  }
}
