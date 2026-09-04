package com.icy.lyrics.core.lyrics.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class LyricsModelsTest {
  @Test
  fun `local URI remains an exact collision-free persistence key`() {
    val first = TrackIdentity("spotify:local:Artist:Album:One:180000")
    val second = TrackIdentity("spotify:local:Artist:Album:Two:180000")

    assertTrue(first.isSpotifyLocal)
    assertEquals(first.uri, first.exactStorageKey)
    assertTrue(first.exactStorageKey != second.exactStorageKey)
    assertNull(first.spotifyTrackId)
    assertEquals("abc123", TrackIdentity("spotify:track:abc123").spotifyTrackId)
  }

  @Test
  fun `sealed normalized document is serializable`() {
    val source: LyricsDocument = SyllableLyrics(
      metadata = LyricsMetadata(
        trackUri = "spotify:track:serialization",
        source = LyricsSource.SPICY,
        songwriters = listOf("Writer"),
        hasTransliterations = true,
      ),
      lines = listOf(
        SyllableLyricLine(
          lead = VocalLine(
            100,
            500,
            listOf(LyricToken("hello", 100, 500, transliteratedText = "hallo")),
          ),
        ),
      ),
    )
    val json = Json { classDiscriminator = "kind" }
    val encoded = json.encodeToString(source)
    val decoded = json.decodeFromString<LyricsDocument>(encoded)

    assertEquals(source, decoded)
  }

  @Test
  fun `source aliases map to stable codes`() {
    assertEquals(LyricsSource.LOCAL_TTML, LyricsSource.fromCode("local_ttml"))
    assertEquals(LyricsSource.SPICY, LyricsSource.fromCode("Spicy Lyrics"))
    assertEquals(LyricsSource.APPLE_MUSIC, LyricsSource.fromCode("aml"))
    assertEquals(LyricsSource.UNKNOWN, LyricsSource.fromCode("future-provider"))
  }
}
