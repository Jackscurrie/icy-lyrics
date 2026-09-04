package com.icy.lyrics.core.platform.diagnostics

import com.icy.lyrics.core.platform.runtime.epochMillis
import com.icy.lyrics.core.lyrics.provider.LyricsProviderId
import com.icy.lyrics.core.platform.database.DiagnosticEventDao
import com.icy.lyrics.core.platform.database.DiagnosticEventEntity
import com.icy.lyrics.core.platform.storage.PlatformJson
import com.icy.lyrics.core.platform.storage.TrackKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class DiagnosticSeverity {
  DEBUG,
  INFO,
  WARNING,
  ERROR,
}

@Serializable
data class DiagnosticEvent(
  val id: Long = 0L,
  val createdAtEpochMs: Long,
  val severity: DiagnosticSeverity,
  val component: String,
  val code: String,
  val provider: LyricsProviderId? = null,
  val trackKeyHash: String? = null,
  val httpStatus: Int? = null,
  val message: String,
  val details: String? = null,
)

data class DiagnosticInput(
  val severity: DiagnosticSeverity,
  val component: String,
  val code: String,
  val provider: LyricsProviderId? = null,
  val trackKey: String? = null,
  val httpStatus: Int? = null,
  val message: String,
  val details: Map<String, String> = emptyMap(),
)

fun interface DiagnosticSink {
  suspend fun record(input: DiagnosticInput)

  companion object {
    val NONE = DiagnosticSink { }
  }
}

object SecretRedactor {
  private val namedSecret = Regex(
    """(?i)(authorization|spicylyrics-webauth|access[_-]?token|refresh[_-]?token|client[_-]?secret|code[_-]?verifier|cookie)(\s*[:=]\s*)(bearer\s+)?([^\s,;&}\]]+)""",
  )
  private val bearer = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
  private val sensitiveQuery = Regex(
    """(?i)([?&](?:code|token|access_token|refresh_token|state)=)[^&#\s]+""",
  )

  fun redact(value: String): String {
    return value
      .replace(namedSecret) { match -> "${match.groupValues[1]}${match.groupValues[2]}[redacted]" }
      .replace(bearer, "Bearer [redacted]")
      .replace(sensitiveQuery) { match -> "${match.groupValues[1]}[redacted]" }
  }
}

class DiagnosticRepository(
  private val dao: DiagnosticEventDao,
  private val clock: () -> Long = ::epochMillis,
) : DiagnosticSink {
  private val writeMutex = Mutex()

  override suspend fun record(input: DiagnosticInput) {
    val now = clock()
    val message = SecretRedactor.redact(input.message).take(MAX_MESSAGE_CHARS)
    val details = input.details
      .mapValues { (_, value) -> SecretRedactor.redact(value).take(MAX_DETAIL_VALUE_CHARS) }
      .takeIf { it.isNotEmpty() }
      ?.let { PlatformJson.encodeToString(it).take(MAX_DETAILS_CHARS) }

    writeMutex.withLock {
      dao.insert(
        DiagnosticEventEntity(
          createdAtEpochMs = now,
          severity = input.severity.name,
          component = input.component.take(MAX_COMPONENT_CHARS),
          code = input.code.take(MAX_CODE_CHARS),
          providerId = input.provider?.name,
          trackKeyHash = input.trackKey?.let(TrackKeys::privacyHash),
          httpStatus = input.httpStatus,
          message = message,
          details = details,
        ),
      )
      dao.deleteOlderThan(now - RETENTION_MS)
      dao.trimToNewest(MAX_EVENTS)
    }
  }

  suspend fun recent(limit: Int = MAX_EVENTS): List<DiagnosticEvent> {
    val cutoff = clock() - RETENTION_MS
    return dao.recent(limit.coerceIn(1, MAX_EVENTS))
      .filter { it.createdAtEpochMs >= cutoff }
      .map { it.toModel() }
  }

  fun observeRecent(limit: Int = MAX_EVENTS): Flow<List<DiagnosticEvent>> {
    return dao.observeRecent(limit.coerceIn(1, MAX_EVENTS))
      .map { events ->
        val cutoff = clock() - RETENTION_MS
        events.filter { it.createdAtEpochMs >= cutoff }.map { it.toModel() }
      }
  }

  /** Enforces both retention limits even after a quiet relaunch with no new events. */
  suspend fun prune() = writeMutex.withLock {
    dao.deleteOlderThan(clock() - RETENTION_MS)
    dao.trimToNewest(MAX_EVENTS)
  }

  suspend fun clear() = dao.clear()

  private fun DiagnosticEventEntity.toModel(): DiagnosticEvent = DiagnosticEvent(
    id = id,
    createdAtEpochMs = createdAtEpochMs,
    severity = runCatching { DiagnosticSeverity.valueOf(severity) }
      .getOrDefault(DiagnosticSeverity.ERROR),
    component = component,
    code = code,
    provider = providerId?.let { runCatching { LyricsProviderId.valueOf(it) }.getOrNull() },
    trackKeyHash = trackKeyHash,
    httpStatus = httpStatus,
    message = message,
    details = details,
  )

  companion object {
    const val MAX_EVENTS = 200
    const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MAX_MESSAGE_CHARS = 1_000
    private const val MAX_DETAILS_CHARS = 4_000
    private const val MAX_DETAIL_VALUE_CHARS = 1_000
    private const val MAX_COMPONENT_CHARS = 80
    private const val MAX_CODE_CHARS = 80
  }
}
