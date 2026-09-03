package com.icy.lyrics.core.platform.provider

import com.icy.lyrics.core.lyrics.provider.LyricsOrchestrator
import com.icy.lyrics.core.lyrics.provider.LyricsRequest
import com.icy.lyrics.core.lyrics.provider.LyricsResolution
import com.icy.lyrics.core.lyrics.provider.LyricsResolutionPolicy
import com.icy.lyrics.core.lyrics.provider.LyricsSelectionMode
import com.icy.lyrics.core.lyrics.provider.ProviderAttempt
import com.icy.lyrics.core.lyrics.provider.ProviderAttemptOutcome
import com.icy.lyrics.core.platform.diagnostics.DiagnosticInput
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSeverity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.settings.SourceSelectionMode

/** Adds settings and redacted persistence around the canonical core orchestrator. */
class PlatformLyricsResolver(
  private val orchestrator: LyricsOrchestrator,
  private val settings: SettingsRepository,
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
  suspend fun resolve(request: LyricsRequest): LyricsResolution {
    val currentSettings = settings.current()
    val mode = when (currentSettings.sourceSelectionMode) {
      SourceSelectionMode.STRICT_PRIORITY -> LyricsSelectionMode.STRICT_ORDER
      SourceSelectionMode.BETTER_SYNC -> LyricsSelectionMode.BETTER_SYNC
    }
    return orchestrator.resolve(request, LyricsResolutionPolicy(mode = mode)).also { resolution ->
      resolution.attempts.forEach { attempt -> record(request, attempt, currentSettings.debugEnabled) }
    }
  }

  suspend fun resolve(
    request: LyricsRequest,
    policy: LyricsResolutionPolicy,
  ): LyricsResolution {
    val debugEnabled = settings.current().debugEnabled
    return orchestrator.resolve(request, policy).also { resolution ->
      resolution.attempts.forEach { attempt -> record(request, attempt, debugEnabled) }
    }
  }

  private suspend fun record(
    request: LyricsRequest,
    attempt: ProviderAttempt,
    debugEnabled: Boolean,
  ) {
    val severity = when (attempt.outcome) {
      ProviderAttemptOutcome.FAILED,
      ProviderAttemptOutcome.SOURCE_MISMATCH,
      -> DiagnosticSeverity.ERROR
      ProviderAttemptOutcome.QUEUED -> DiagnosticSeverity.WARNING
      else -> DiagnosticSeverity.INFO
    }
    // Errors remain visible at all times; debug mode adds the full provider trace.
    if (severity == DiagnosticSeverity.INFO && !debugEnabled) return
    diagnostics.record(
      DiagnosticInput(
        severity = severity,
        component = "lyrics-orchestrator",
        code = attempt.outcome.name.lowercase(),
        provider = attempt.provider,
        trackKey = request.track.exactStorageKey,
        httpStatus = attempt.httpStatus,
        message = attempt.message ?: attempt.outcome.name,
        details = buildMap {
          attempt.source?.let { put("source", it.name) }
          attempt.syncKind?.let { put("syncKind", it) }
          attempt.retryAfterMs?.let { put("retryAfterMs", it.toString()) }
          put("fromCache", attempt.fromCache.toString())
        },
      ),
    )
  }
}
