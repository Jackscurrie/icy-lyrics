package com.icy.lyrics.core.lyrics.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodeScalarsTest {
  @Test
  fun supplementarySymbolsStaySingleEmphasisUnits() {
    val text = "a\uD83C\uDFB5b"
    assertEquals(0x1F3B5, text.lyricCodePointAt(1))
    assertEquals(2, text.lyricCodePointAt(1).lyricCodePointCharCount())
    assertEquals(1, text.lyricCodePointAt(0).lyricCodePointCharCount())
    assertEquals(0xD800, "\uD800".lyricCodePointAt(0))
  }

  @Test
  fun formatCharactersAndCombiningMarksRetainAndroidPlaceholderSemantics() {
    assertTrue(0x200D.isInvisibleUnicodeScalar())
    assertTrue(0xE0001.isInvisibleUnicodeScalar())
    assertTrue(0xE0100.isDecorationUnicodeScalar())
    assertTrue(0x1D185.isDecorationUnicodeScalar())
    assertFalse(0x1F600.isInvisibleUnicodeScalar())
    assertFalse(0x1F600.isDecorationUnicodeScalar())
    assertFalse('A'.code.isInvisibleUnicodeScalar())
  }
}
