package com.icy.lyrics.core.platform.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Defensive decoder for Spicy Lyrics X-mode 2 object-pack responses. */
internal class SpicyPayloadDecoder(
  private val limits: Limits = Limits(),
) {
  data class Limits(
    // Keep these defaults byte-for-byte equivalent to SLObjPack's desktop
    // limits so an X-mode 2 payload accepted there is accepted here too.
    val maxDepth: Int = 512,
    val maxArrayLength: Int = 1 shl 20,
    val maxObjectKeys: Int = 1 shl 16,
    val maxValuesLength: Int = 1 shl 22,
    val maxStreamLength: Int = 1 shl 24,
    val maxDecodeOperations: Int = 1 shl 22,
  )

  fun unpack(packed: JsonElement): JsonElement {
    val shell = packed as? JsonArray
      ?: throw IllegalArgumentException("Packed payload must be an array")
    require(shell.size == 2) { "Packed payload must contain values and stream" }
    val values = shell[0] as? JsonArray
      ?: throw IllegalArgumentException("Packed values must be an array")
    val stream = shell[1] as? JsonArray
      ?: throw IllegalArgumentException("Packed stream must be an array")
    require(values.size <= limits.maxValuesLength) { "Packed value table is too large" }
    require(stream.size <= limits.maxStreamLength) { "Packed stream is too large" }
    values.forEachIndexed { index, value ->
      require(value is JsonPrimitive) {
        "Packed value $index is not a primitive"
      }
      if (!value.isString && value.contentOrNull?.toDoubleOrNull()?.isFinite() == false) {
        throw IllegalArgumentException("Packed value $index is not finite")
      }
    }

    var cursor = 0
    fun read(): JsonElement {
      if (cursor >= stream.size) throw IllegalArgumentException("Packed stream ended unexpectedly")
      return stream[cursor++]
    }

    fun integer(element: JsonElement, label: String): Int {
      val primitive = element as? JsonPrimitive
        ?: throw IllegalArgumentException("Packed $label is not an integer")
      if (primitive.isString) throw IllegalArgumentException("Packed $label is not an integer")
      return primitive.intOrNull
        ?: throw IllegalArgumentException("Packed $label is not an integer")
    }

    fun pointer(element: JsonElement): JsonElement {
      val index = integer(element, "pointer")
      if (index !in values.indices) throw IllegalArgumentException("Packed pointer is out of range")
      return values[index]
    }

    fun readCount(max: Int, label: String): Int {
      val count = integer(read(), "$label count")
      require(count in 0..max) { "Packed $label count is out of range" }
      return count
    }

    fun readKey(): String {
      val primitive = pointer(read()) as? JsonPrimitive
        ?: throw IllegalArgumentException("Packed object key is not text")
      if (!primitive.isString) throw IllegalArgumentException("Packed object key is not text")
      val key = primitive.content
      require(key !in FORBIDDEN_KEYS) { "Packed object contains a forbidden key" }
      return key
    }

    lateinit var decode: (Int) -> JsonElement
    decode = fun(depth: Int): JsonElement {
      require(depth <= limits.maxDepth) { "Packed payload is nested too deeply" }
      val opcode = integer(read(), "opcode")
      if (opcode >= 0) return pointer(JsonPrimitive(opcode))
      return when (opcode) {
        -1 -> {
          val count = readCount(limits.maxObjectKeys, "object")
          require(stream.size - cursor >= count * 2) { "Packed object exceeds remaining stream" }
          val keys = List(count) { readKey() }
          JsonObject(keys.associateWith { decode(depth + 1) })
        }
        -2 -> {
          val count = readCount(limits.maxArrayLength, "array")
          require(stream.size - cursor >= count) { "Packed array exceeds remaining stream" }
          JsonArray(List(count) { decode(depth + 1) })
        }
        -3 -> {
          val itemCount = readCount(limits.maxArrayLength, "schema array")
          val keyCount = readCount(limits.maxObjectKeys, "schema key")
          val product = itemCount.toLong() * keyCount.toLong()
          require(product <= limits.maxDecodeOperations) { "Packed schema array budget exceeded" }
          require(stream.size.toLong() - cursor >= keyCount.toLong() + product) {
            "Packed schema array exceeds remaining stream"
          }
          val keys = List(keyCount) { readKey() }
          JsonArray(List(itemCount) {
            JsonObject(keys.associateWith { decode(depth + 1) })
          })
        }
        -4 -> JsonArray(emptyList())
        -5 -> JsonArray(listOf(decode(depth + 1)))
        -6 -> JsonObject(emptyMap())
        else -> throw IllegalArgumentException("Unknown packed opcode")
      }
    }

    val result = decode(0)
    require(cursor == stream.size) { "Packed payload contains trailing data" }
    return result
  }

  private companion object {
    val FORBIDDEN_KEYS = setOf("__proto__", "constructor", "prototype")
  }
}
