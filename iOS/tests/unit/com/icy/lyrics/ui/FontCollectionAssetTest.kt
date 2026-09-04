package com.icy.lyrics.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FontCollectionAssetTest {
  private val source get() = File("../../iOS/shared/ui/assets/font/NotoSansCJK-Regular.ttc").readBytes()
  private fun ByteArray.u32(offset: Int) = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
  private fun ByteArray.tables(directory: Int): Map<String, ByteArray> {
    val count = ((this[directory + 4].toInt() and 255) shl 8) or (this[directory + 5].toInt() and 255)
    return (0 until count).associate { index ->
      val record = directory + 12 + index * 16
      val tag = copyOfRange(record, record + 4).decodeToString()
      val offset = u32(record + 8).toInt()
      val length = u32(record + 12).toInt()
      tag to copyOfRange(offset, offset + length).also {
        if (tag == "head") it.fill(0, 8, 12) // The container checksum is the only allowed table change.
      }
    }
  }

  @Test fun allFiveOriginalFacesRetainEveryGlyphMetricLayoutAndVariationTable() {
    val original = source
    val untouched = original.copyOf()
    assertEquals(5, original.u32(8).toInt())
    repeat(5) { face ->
      val output = standaloneFontCollectionFace(original, face)
      val expected = original.tables(original.u32(12 + face * 4).toInt())
      val actual = output.tables(0)
      assertEquals(expected.keys, actual.keys)
      assertTrue(setOf("CFF2", "GPOS", "GSUB", "HVAR", "fvar", "name", "cmap").all { it in actual })
      expected.forEach { (tag, bytes) -> assertContentEquals(bytes, actual[tag], "face=$face table=$tag") }
      assertEquals(0xb1b0afbaL, (output.indices step 4).fold(0L) { total, offset ->
        (total + output.u32(offset)) and 0xffffffffL
      })
      val directorySize = 12 + actual.size * 16
      repeat(actual.size) { table ->
        val offset = output.u32(12 + table * 16 + 8)
        assertEquals(0L, offset % 4)
        assertTrue(offset >= directorySize)
      }
    }
    assertContentEquals(untouched, original)
  }

  @Test fun malformedCollectionsAndFaceIndicesAreRejectedBeforeLoadingNativeFonts() {
    val original = source
    for (index in listOf(-1, 5, Int.MAX_VALUE)) {
      assertFailsWith<IllegalArgumentException> { standaloneFontCollectionFace(original, index) }
    }
    for (bytes in listOf(ByteArray(0), original.copyOf(11), original.copyOf(20),
      original.copyOf().apply { fill(0, 0, 4) },
      original.copyOf().apply { fill(0x7f, 8, 12) },
      original.copyOf().apply { fill(0x7f, 12, 16) })) {
      assertFailsWith<IllegalArgumentException> { standaloneFontCollectionFace(bytes, 0) }
    }
  }
}
