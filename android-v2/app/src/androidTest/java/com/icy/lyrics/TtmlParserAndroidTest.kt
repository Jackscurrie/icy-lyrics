package com.icy.lyrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.parser.TtmlParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Guards against Android's deliberately limited DocumentBuilderFactory feature set. */
@RunWith(AndroidJUnit4::class)
class TtmlParserAndroidTest {
  @Test
  fun parsesAppleWordTtmlOnAndroidRuntime() {
    val raw = """
      <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
          xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
          itunes:timing="Word">
        <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body><div>
          <p begin="00:00.100" end="00:01.500" ttm:agent="v1" itunes:key="L1">
            <span begin="00:00.100" end="00:00.700">Working </span>
            <span begin="00:00.700" end="00:01.500">lyrics</span>
          </p>
        </div></body>
      </tt>
    """.trimIndent()

    val lyrics = TtmlParser.parse(raw, "spotify:track:android-runtime") as SyllableLyrics

    assertEquals("Working lyrics", lyrics.lines.single().lead.text)
    assertEquals(100L, lyrics.lines.single().lead.startMs)
    assertEquals(1_500L, lyrics.lines.single().lead.endMs)
  }
}
