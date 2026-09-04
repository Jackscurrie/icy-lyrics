package com.icy.lyrics.ui

/**
 * Presents one bundled TTC face as a standalone SFNT for the Apple font loader.
 * Skia m144's SkTypeface_Mac::MakeFromStream rejects nonzero collection indices.
 * Every table is copied verbatim except head.checkSumAdjustment, which must describe
 * the new container. Glyphs, shaping, names, metrics and variable axes stay untouched.
 * https://learn.microsoft.com/en-us/typography/opentype/spec/otff#font-collections
 */
internal fun standaloneFontCollectionFace(collection: ByteArray, faceIndex: Int): ByteArray {
  fun range(offset: Long, length: Long): Int {
    require(offset >= 0 && length >= 0 && offset + length <= collection.size.toLong()) {
      "Truncated font collection"
    }
    return offset.toInt()
  }
  fun u32(offset: Int): Long {
    range(offset.toLong(), 4)
    return (0..3).fold(0L) { value, byte -> (value shl 8) or (collection[offset + byte].toLong() and 255) }
  }
  fun u16(offset: Int): Int {
    range(offset.toLong(), 2)
    return ((collection[offset].toInt() and 255) shl 8) or (collection[offset + 1].toInt() and 255)
  }
  require(u32(0) == 0x74746366L) { "Expected a TrueType/OpenType collection" }
  require(u32(4) in listOf(0x00010000L, 0x00020000L)) { "Unsupported font collection version" }
  val faceCount = u32(8)
  range(12, faceCount * 4)
  require(faceIndex >= 0 && faceIndex.toLong() < faceCount) { "Font collection face index is out of range" }
  val directory = range(u32(12 + faceIndex * 4), 12)
  require(u32(directory) in listOf(0x00010000L, 0x4f54544fL, 0x74727565L)) { "Unsupported SFNT face" }
  val tableCount = u16(directory + 4)
  require(tableCount > 0) { "Empty font face" }
  val directorySize = 12 + tableCount * 16
  range(directory.toLong(), directorySize.toLong())
  data class Table(val tag: Long, val offset: Int, val length: Int)
  val tables = List(tableCount) { index ->
    val record = directory + 12 + index * 16
    val length = u32(record + 12)
    Table(u32(record), range(u32(record + 8), length), length.toInt())
  }
  require(tables.map { it.tag }.toSet().size == tableCount) { "Duplicate SFNT table" }
  require(tables.none { it.tag == 0x44534947L }) { "Signed SFNT repacking is unsupported" }
  require(tables.singleOrNull { it.tag == 0x68656164L }?.length?.let { it >= 54 } == true) { "Missing or invalid head table" }
  val totalSize = tables.fold(directorySize.toLong()) { size, table -> size + ((table.length.toLong() + 3) and -4L) }
  require(totalSize <= Int.MAX_VALUE) { "Font face is too large" }
  val output = ByteArray(totalSize.toInt())
  fun writeU32(offset: Int, value: Long) {
    repeat(4) { byte -> output[offset + byte] = (value ushr (24 - byte * 8)).toByte() }
  }
  fun checksum(offset: Int, length: Int): Long {
    var sum = 0L
    for (word in 0 until length step 4) {
      var value = 0L
      repeat(4) { byte -> value = (value shl 8) or
        (if (word + byte < length) output[offset + word + byte].toLong() and 255 else 0) }
      sum = (sum + value) and 0xffffffffL
    }
    return sum
  }
  collection.copyInto(output, 0, directory, directory + directorySize)
  var destination = directorySize
  var headOffset = 0
  tables.forEachIndexed { index, table ->
    collection.copyInto(output, destination, table.offset, table.offset + table.length)
    if (table.tag == 0x68656164L) {
      headOffset = destination
      writeU32(destination + 8, 0)
    }
    writeU32(12 + index * 16 + 4, checksum(destination, table.length))
    writeU32(12 + index * 16 + 8, destination.toLong())
    destination += ((table.length.toLong() + 3) and -4L).toInt()
  }
  writeU32(headOffset + 8, (0xb1b0afbaL - checksum(0, output.size)) and 0xffffffffL)
  return output
}
