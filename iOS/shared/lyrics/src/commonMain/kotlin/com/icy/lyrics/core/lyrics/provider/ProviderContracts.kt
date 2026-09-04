package com.icy.lyrics.core.lyrics.provider

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.TrackIdentity
import kotlinx.serialization.Serializable

@Serializable
enum class LyricsProviderId(val expectedSource: LyricsSource) {
  LOCAL_TTML(LyricsSource.LOCAL_TTML),
  SPICY(LyricsSource.SPICY),
  SPOTIFY(LyricsSource.SPOTIFY),
  APPLE_MUSIC(LyricsSource.APPLE_MUSIC),
  LRCLIB(LyricsSource.LRCLIB),
}

@Serializable
data class LyricsRequest(
  val track: TrackIdentity,
  val allowCached: Boolean = true,
  val requestedSource: LyricsSource? = null,
  val requestId: Long = 0L,
)

/** Implemented by storage and network adapters in core:platform. */
interface LyricsProvider {
  val id: LyricsProviderId

  suspend fun fetch(request: LyricsRequest): ProviderResult
}

sealed interface ProviderResult {
  data class Found(
    val document: LyricsDocument,
    val fromCache: Boolean = false,
    val rawFormat: String? = null,
    val message: String? = null,
    /**
     * Allows a provider route to validate a document whose upstream source is
     * intentionally more specific than the route itself. The Spicy route uses
     * this for the desktop-compatible automatic query while retaining `aml` or
     * `spt` in [document] for diagnostics and source display.
     */
    val validatedForProvider: LyricsProviderId? = null,
  ) : ProviderResult

  data class NotFound(val message: String? = null) : ProviderResult

  data class Queued(
    val retryAfterMs: Long? = null,
    val message: String? = null,
  ) : ProviderResult

  data class Unavailable(
    val reason: ProviderUnavailableReason,
    val message: String? = null,
  ) : ProviderResult

  data class Failure(
    val category: ProviderFailureCategory,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
  ) : ProviderResult
}

@Serializable
enum class ProviderUnavailableReason {
  DISABLED,
  AUTH_REQUIRED,
  UNSUPPORTED_TRACK,
  OFFLINE,
  NOT_CONFIGURED,
}

@Serializable
enum class ProviderFailureCategory {
  NETWORK,
  HTTP,
  PARSE,
  SECURITY,
  STORAGE,
  UNKNOWN,
}

@Serializable
data class ProviderAttempt(
  val provider: LyricsProviderId,
  val outcome: ProviderAttemptOutcome,
  val source: LyricsSource? = null,
  val syncKind: String? = null,
  val fromCache: Boolean = false,
  val httpStatus: Int? = null,
  val retryAfterMs: Long? = null,
  val message: String? = null,
)

@Serializable
enum class ProviderAttemptOutcome {
  FOUND,
  NOT_FOUND,
  QUEUED,
  UNAVAILABLE,
  SOURCE_MISMATCH,
  FAILED,
}
