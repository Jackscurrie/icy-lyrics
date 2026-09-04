package com.icy.lyrics.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Locale data measured from the original Android java.text MEDIUM/SHORT API, not Apple styles. */
internal class AndroidDateProfiles(json: String) {
  private val document = Json.parseToJsonElement(json).jsonObject
  private val profiles: List<Profile>
  private val localeProfiles: Map<String, Int>
  private val aliases: Map<String, String>

  init {
    require(document.getValue("schemaVersion").jsonPrimitive.int == 1)
    require(document.getValue("calendar").jsonPrimitive.content == "gregory")
    profiles = document.getValue("profiles").jsonArray.map { value ->
      val item = value.jsonObject
      Profile(item.getValue("pattern").jsonPrimitive.content,
        item.getValue("shortMonths").jsonArray.map { it.jsonPrimitive.content },
        item.getValue("amPm").jsonArray.map { it.jsonPrimitive.content },
        item.getValue("zeroDigit").jsonPrimitive.content.single())
    }
    localeProfiles = document.getValue("localeProfiles").jsonObject.mapValues { it.value.jsonPrimitive.int }
    require(localeProfiles.values.all { it in profiles.indices })
    aliases = document.getValue("aliases").jsonObject.mapValues { it.value.jsonPrimitive.content }
    require(aliases.values.all { it in localeProfiles })
    require("en" in localeProfiles)
  }

  /** Exact measured tags and two measured Chinese aliases; then language fallback, then English. */
  fun resolvedLocale(identifier: String): String {
    val parts = identifier.substringBefore('@').replace('_', '-').split('-')
      .takeWhile { it.length != 1 }.filter(String::isNotEmpty)
    val tag = parts.mapIndexed { index, part -> when {
      index == 0 -> part.lowercase()
      part.length == 4 -> part.lowercase().replaceFirstChar(Char::uppercaseChar)
      part.length == 2 || part.length == 3 -> part.uppercase()
      else -> part
    } }.joinToString("-")
    if (tag in localeProfiles) return tag
    aliases[tag]?.let { return it }
    // A region-specific unmeasured script combination must not silently select another region.
    // Language fallback is explicit; arbitrary locale extensions remain outside measured parity.
    val language = tag.substringBefore('-')
    return language.takeIf { it in localeProfiles } ?: "en"
  }

  fun format(identifier: String, fields: AndroidDateFields): String =
    profiles[localeProfiles.getValue(resolvedLocale(identifier))].format(fields)

  private class Profile(pattern: String, val months: List<String>, val amPm: List<String>, val zero: Char) {
    private val tokens = tokenize(pattern)
    init { require(months.size == 12 && amPm.size == 2) }

    fun format(fields: AndroidDateFields): String = buildString {
      require(fields.year > 0 && fields.month in 1..12 && fields.day in 1..31)
      require(fields.hour in 0..23 && fields.minute in 0..59)
      tokens.forEach { token ->
        val field = token.field
        if (field == null) append(token.literal)
        else when {
          field == 'a' -> append(amPm[if (fields.hour < 12) 0 else 1])
          field == 'M' && token.count == 3 -> append(months[fields.month - 1])
          else -> {
            val number = when (field) {
              'y' -> if (token.count == 2) fields.year % 100 else fields.year
              'M' -> fields.month
              'd' -> fields.day
              'H' -> fields.hour
              'h' -> (fields.hour % 12).let { if (it == 0) 12 else it }
              'm' -> fields.minute
              else -> error("Unsupported measured date field: $field")
            }
            number.toString().padStart(token.count, '0').forEach { append((zero.code + it.code - '0'.code).toChar()) }
          }
        }
      }
    }
  }

  private data class Token(val field: Char? = null, val count: Int = 0, val literal: String = "")
  private companion object {
    fun tokenize(pattern: String): List<Token> = buildList {
      var cursor = 0
      var quoted = false
      val literal = StringBuilder()
      fun flush() { if (literal.isNotEmpty()) { add(Token(literal = literal.toString())); literal.clear() } }
      while (cursor < pattern.length) {
        val character = pattern[cursor]
        if (character == '\'') {
          if (pattern.getOrNull(cursor + 1) == '\'') { literal.append('\''); cursor += 2 }
          else { quoted = !quoted; cursor++ }
        } else if (!quoted && (character in 'A'..'Z' || character in 'a'..'z')) {
          flush()
          require(character in "yMdHhma") { "Unsupported Android date pattern: $pattern" }
          var end = cursor + 1
          while (pattern.getOrNull(end) == character) end++
          require(character != 'M' || end - cursor <= 3) { "Unmeasured full/standalone month pattern: $pattern" }
          add(Token(field = character, count = end - cursor)); cursor = end
        } else { literal.append(character); cursor++ }
      }
      require(!quoted) { "Unclosed date pattern quote: $pattern" }
      flush()
    }
  }
}

internal data class AndroidDateFields(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int)
