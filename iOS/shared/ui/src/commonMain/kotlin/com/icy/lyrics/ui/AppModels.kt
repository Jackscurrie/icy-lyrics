package com.icy.lyrics.ui

import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.platform.diagnostics.SecretRedactor
import com.icy.lyrics.media.NowPlayingSnapshot

enum class LandscapeMode(val label: String) {
  ARTWORK_ONLY("Album art only"),
  ARTWORK_TITLES("Album art with titles"),
  MIXED("Album art, titles and lyrics"),
  LYRICS("Lyrics only");

  fun step(direction: Int): LandscapeMode {
    val next = (ordinal + direction.coerceIn(-1, 1)).coerceIn(0, entries.lastIndex)
    return entries[next]
  }
}

enum class MixedMediaSide(val label: String) {
  LEFT("Media on left"),
  RIGHT("Media on right"),
}

enum class BackgroundStyle(val label: String) {
  ANIMATED("Animated"),
  STATIC_BLURRED("Static blurred"),
}

enum class SourceStrategy(val label: String) {
  STRICT_PRIORITY("Strict priority"),
  PREFER_BETTER_SYNC("Prefer better sync"),
}

enum class AppDestination(val label: String) {
  PLAYER("Player"),
  SETTINGS("Settings"),
  LIBRARY("Local lyrics"),
  DEBUG("Debug"),
  ABOUT_LEGAL("About & legal"),
}

data class AppSettings(
  val useLocalTtml: Boolean = true,
  val globalTimingOffsetMs: Int = 0,
  val rememberBluetoothOffsets: Boolean = true,
  val activeBluetoothDeviceId: String? = null,
  val activeBluetoothDeviceName: String? = null,
  val activeBluetoothTimingOffsetMs: Int? = null,
  val mixedMediaSide: MixedMediaSide = MixedMediaSide.LEFT,
  val backgroundStyle: BackgroundStyle = BackgroundStyle.ANIMATED,
  val backgroundEnabled: Boolean = true,
  val keepScreenAwake: Boolean = true,
  val revealEnabled: Boolean = false,
  val sourceStrategy: SourceStrategy = SourceStrategy.STRICT_PRIORITY,
  val debugEnabled: Boolean = false,
  val spicyEnabled: Boolean = false,
  val spicyTokenSharingConsent: Boolean = false,
  val lrclibEnabled: Boolean = true,
) {
  val effectiveTimingOffsetMs: Int
    get() = activeBluetoothTimingOffsetMs ?: globalTimingOffsetMs
}

data class ProviderAttemptUi(
  val provider: String,
  val outcome: String,
  val source: String? = null,
  val syncKind: String? = null,
  val fromCache: Boolean = false,
  val message: String? = null,
  val httpStatus: Int? = null,
  val retryAfterMs: Long? = null,
  val elapsedMs: Long? = null,
)

data class DiagnosticEventUi(
  val createdAtEpochMs: Long,
  val severity: String,
  val component: String,
  val provider: String? = null,
  val message: String,
  val httpStatus: Int? = null,
)

data class LyricsDiagnosticsUi(
  val selectedSource: LyricsSource? = null,
  val selectedSyncKind: LyricsSyncKind? = null,
  val fromCache: Boolean = false,
  val trackUri: String? = null,
  val attempts: List<ProviderAttemptUi> = emptyList(),
  val events: List<DiagnosticEventUi> = emptyList(),
  val error: String? = null,
) {
  fun asText(): String = buildString {
    appendLine("Track key hash: ${trackUri?.safeDiagnosticText() ?: "none"}")
    appendLine("Source: ${selectedSource?.name ?: "none"}")
    appendLine("Sync: ${selectedSyncKind?.name ?: "none"}")
    appendLine("Cache: $fromCache")
    error?.let { appendLine("Error: ${it.safeDiagnosticText()}") }
    if (attempts.isNotEmpty()) {
      appendLine("Attempts:")
      attempts.forEachIndexed { index, attempt ->
        append("${index + 1}. ${attempt.provider}: ${attempt.outcome}")
        attempt.httpStatus?.let { append(" HTTP $it") }
        attempt.elapsedMs?.let { append(" ${it}ms") }
        attempt.source?.let { append(" source=${it.safeDiagnosticText()}") }
        attempt.syncKind?.let { append(" sync=${it.safeDiagnosticText()}") }
        append(" cache=${attempt.fromCache}")
        attempt.retryAfterMs?.let { append(" retryAfter=${it}ms") }
        attempt.message?.let { append(" - ${it.safeDiagnosticText()}") }
        appendLine()
      }
    }
    if (events.isNotEmpty()) {
      appendLine("Recent events:")
      events.forEach { event ->
        append("${event.severity.safeDiagnosticText()} ${event.component.safeDiagnosticText()}")
        event.provider?.let { append(" [$it]") }
        event.httpStatus?.let { append(" HTTP $it") }
        append(" - ${event.message.safeDiagnosticText()}")
        appendLine()
      }
    }
  }.trimEnd()
}

private fun String.safeDiagnosticText(): String = SecretRedactor.redact(this).take(4_000)

data class SavedLyricsUi(
  val trackUri: String,
  val title: String,
  val artist: String,
  val updatedAtEpochMs: Long,
)

sealed interface LyricsUiStatus {
  data object Idle : LyricsUiStatus
  data class Loading(val retainingPrevious: Boolean = false) : LyricsUiStatus
  data class Ready(val document: LyricsDocument) : LyricsUiStatus
  data class Empty(val message: String) : LyricsUiStatus
  data class Failed(val message: String) : LyricsUiStatus
}

data class IcyLyricsUiState(
  val notificationAccess: Boolean = false,
  val bluetoothPermissionGranted: Boolean = false,
  val nowPlaying: NowPlayingSnapshot? = null,
  val lyrics: LyricsUiStatus = LyricsUiStatus.Idle,
  val settings: AppSettings = AppSettings(),
  val diagnostics: LyricsDiagnosticsUi = LyricsDiagnosticsUi(),
  val library: List<SavedLyricsUi> = emptyList(),
  val spotifyAuthAvailable: Boolean = false,
  val spotifyConnected: Boolean = false,
  val spotifyAuthorizationInProgress: Boolean = false,
  val destination: AppDestination = AppDestination.PLAYER,
  val landscapeMode: LandscapeMode = LandscapeMode.MIXED,
  val artworkControlsVisible: Boolean = false,
  val transientMessage: String? = null,
)
