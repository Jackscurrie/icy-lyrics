package com.icy.lyrics.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.icy.lyrics.core.lyrics.model.*
import com.icy.lyrics.media.NowPlayingSnapshot

/** Original, offline-only data shared by Android and iOS screenshot tests. */
object IcyParityFixtures {
  const val FRAME_TIME_NANOS = 0L
  const val POSITION_MS = 26_500L
  val portraitIds = listOf("onboarding", "empty", "portrait", "portrait-long", "portrait-failed",
    "background-static", "background-disabled", "reduced-motion", "settings", "library", "library-empty", "legal", "diagnostics")
  val landscapeIds = listOf("landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
    "landscape-mixed-right", "multilingual", "syllables")
  fun scenarioIds(): List<String> = portraitIds + landscapeIds
  fun isLandscape(id: String): Boolean = id in landscapeIds
  fun reducedMotion(id: String): Boolean = id == "reduced-motion"

  fun state(id: String): IcyLyricsUiState {
    require(id in scenarioIds()) { "Unknown parity fixture: $id" }
    val snapshot = NowPlayingSnapshot(
      packageName = "com.spotify.music", title = "Glass & Northern Lights", artist = "Icy Fixture Ensemble",
      album = "Parity Sessions", durationMs = 192_000L, positionMs = POSITION_MS,
      playbackSpeed = 0f, playbackState = 2, artwork = artwork(), capturedAtElapsedMs = 0L,
      rawMediaId = "spotify:track:0000000000000000000001", rawUri = null, extras = emptyMap(), availableActions = 0L,
    )
    val base = IcyLyricsUiState(
      notificationAccess = true, bluetoothPermissionGranted = true, nowPlaying = snapshot,
      lyrics = LyricsUiStatus.Ready(lineLyrics()),
      settings = AppSettings(backgroundStyle = BackgroundStyle.STATIC_BLURRED),
      spotifyAuthAvailable = true, spotifyConnected = true,
    )
    return when (id) {
      "onboarding" -> base.copy(notificationAccess = false)
      "empty" -> base.copy(nowPlaying = null)
      "portrait-long" -> base.copy(nowPlaying = snapshot.copy(
        title = "An exceptionally long song title across several lines of a narrow phone display",
        artist = "The Northern Lights Ensemble with Another Very Long Featured Artist Name"))
      "portrait-failed" -> base.copy(lyrics = LyricsUiStatus.Failed("No synced lyrics were found. Import a local TTML file or try again."))
      "background-disabled" -> base.copy(settings = base.settings.copy(backgroundEnabled = false))
      "reduced-motion" -> base.copy(settings = base.settings.copy(backgroundStyle = BackgroundStyle.ANIMATED))
      "settings" -> base.copy(destination = AppDestination.SETTINGS, settings = base.settings.copy(
        activeBluetoothDeviceName = "Icy Headphones", activeBluetoothDeviceId = "fixture-device", activeBluetoothTimingOffsetMs = 120))
      "library", "library-empty" -> base.copy(destination = AppDestination.LIBRARY,
        library = if (id == "library-empty") emptyList() else listOf(
          SavedLyricsUi("fixture:1", "Glass & Northern Lights", "Icy Fixture Ensemble", 1788436800000L),
          SavedLyricsUi("fixture:2", "A Longer Song Title Preserved in the Local Library", "Another Artist", 1788436800000L)))
      "legal" -> base.copy(destination = AppDestination.ABOUT_LEGAL)
      "diagnostics" -> base.copy(destination = AppDestination.DEBUG, diagnostics = LyricsDiagnosticsUi(
        selectedSource = LyricsSource.LOCAL_TTML, selectedSyncKind = LyricsSyncKind.LINE, fromCache = true,
        attempts = listOf(ProviderAttemptUi("Local TTML", "Success", "LOCAL_TTML", "LINE", true, elapsedMs = 12)),
        events = listOf(DiagnosticEventUi(1788436800000L, "INFO", "Lyrics", message = "Loaded the deterministic local fixture."))))
      "landscape-artwork" -> base.copy(landscapeMode = LandscapeMode.ARTWORK_ONLY, artworkControlsVisible = true)
      "landscape-titles" -> base.copy(landscapeMode = LandscapeMode.ARTWORK_TITLES)
      "landscape-mixed-right" -> base.copy(landscapeMode = LandscapeMode.MIXED, settings = base.settings.copy(mixedMediaSide = MixedMediaSide.RIGHT))
      "landscape-lyrics" -> base.copy(landscapeMode = LandscapeMode.LYRICS)
      "multilingual" -> base.copy(landscapeMode = LandscapeMode.LYRICS, lyrics = LyricsUiStatus.Ready(StaticLyrics(
        metadata = metadata(), lines = listOf(
          StaticLyricLine("English · Café · Ελληνικά · Кириллица"),
          StaticLyricLine("日本語の歌詞 · 中文歌词 · 한국어 가사"),
          StaticLyricLine("العربية: تحت ضوء القمر"),
          StaticLyricLine("हिन्दी: चाँदनी में संगीत"),
          StaticLyricLine("Winter light ❄️ 🎵 💙"),
        ))))
      "syllables" -> base.copy(landscapeMode = LandscapeMode.LYRICS, lyrics = LyricsUiStatus.Ready(syllables()))
      else -> base
    }
  }

  private fun metadata() = LyricsMetadata(source = LyricsSource.LOCAL_TTML, sourceLabel = "Local TTML")
  private fun lineLyrics() = LineLyrics(metadata(), listOf(
    TimedLyricLine("A quiet light across the snow", 0, 12_000),
    TimedLyricLine("We trace the colors as they glow", 12_000, 24_000),
    TimedLyricLine("The northern sky is ours tonight", 24_000, 36_000),
    TimedLyricLine("And every word returns to light", 36_000, 48_000),
    TimedLyricLine("The music follows where we go", 48_000, 60_000),
  ))
  private fun syllables() = SyllableLyrics(metadata(), listOf(
    SyllableLyricLine(VocalLine(12_000, 24_000, listOf(LyricToken("A quiet light", 12_000, 24_000)))),
    SyllableLyricLine(VocalLine(24_000, 36_000, listOf(
      LyricToken("The", 24_000, 25_000), LyricToken("northern", 25_000, 27_000),
      LyricToken("sky", 27_000, 30_000), LyricToken("is ours tonight", 30_000, 36_000))),
      background = listOf(VocalLine(25_000, 34_000, listOf(LyricToken("Ours tonight", 25_000, 34_000))))),
    SyllableLyricLine(VocalLine(36_000, 48_000, listOf(LyricToken("Returns to light", 36_000, 48_000))), oppositeAligned = true),
  ))

  fun artwork(): ImageBitmap = ImageBitmap(256, 256).also { image ->
    val canvas = Canvas(image)
    val paint = Paint()
    paint.color = Color(0xff143a63); canvas.drawRect(Rect(0f, 0f, 256f, 256f), paint)
    paint.color = Color(0xffa1e6ee); canvas.drawCircle(Offset(84f, 83f), 67f, paint)
    paint.color = Color(0xff81558c); canvas.drawCircle(Offset(210f, 224f), 130f, paint)
    paint.color = Color(0xffecce95); canvas.drawRect(Rect(0f, 183f, 256f, 207f), paint)
    paint.color = Color(0xff2b6980); canvas.drawCircle(Offset(145f, 130f), 48f, paint)
  }
}
