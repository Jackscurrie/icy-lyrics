package com.icy.lyrics.core.lyrics.parser

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.LyricsSource
import com.icy.lyrics.core.lyrics.model.StaticLyricLine
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.core.lyrics.model.VocalLine
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Converts the desktop Spicy/Icy wire schema (seconds, PascalCase) into the core model. */
object IcyLyricsJsonParser {
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }

  fun parse(rawJson: String, trackUri: String, sourceOverride: LyricsSource? = null): LyricsDocument {
    val root = json.parseToJsonElement(rawJson).objectOrThrow()
    val payload = (root["Result"] as? JsonObject) ?: root
    val type = payload.string("Type")?.lowercase()
      ?: throw IllegalArgumentException("Lyrics JSON has no Type")
    val source = sourceOverride ?: LyricsSource.fromCode(
      payload.string("source") ?: payload.string("Source")
        ?: payload.string("provider") ?: payload.string("Provider")
    )
    val metadata = LyricsMetadata(
      trackUri = trackUri,
      source = source,
      sourceLabel = payload.string("sourceLabel"),
      songwriters = payload.array("SongWriters").strings(),
      language = payload.string("Language") ?: payload.string("language"),
      hasTransliterations = payload.boolean("HasTransliterations") ||
        payload.boolean("IncludesRomanization"),
    )
    return when (type) {
      "static" -> StaticLyrics(metadata, payload.array("Lines").objects().mapNotNull { line ->
        line.string("Text")?.takeIf(String::isNotBlank)?.let {
          StaticLyricLine(it, line.transliteration())
        }
      }.ifEmpty { throw IllegalArgumentException("Static lyrics contain no lines") })
      "line" -> LineLyrics(metadata, payload.array("Content").objects().mapNotNull { line ->
        if (line.string("Type")?.equals("Vocal", true) == false) return@mapNotNull null
        val text = line.string("Text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val start = line.timeMs("StartTime") ?: return@mapNotNull null
        val end = line.timeMs("EndTime")?.coerceAtLeast(start + 1L) ?: return@mapNotNull null
        TimedLyricLine(text, start, end, line.transliteration(), line.boolean("OppositeAligned"))
      }.ifEmpty { throw IllegalArgumentException("Line lyrics contain no timed vocals") })
      "syllable" -> SyllableLyrics(metadata, payload.array("Content").objects().mapNotNull { group ->
        if (group.string("Type")?.equals("Vocal", true) == false) return@mapNotNull null
        val lead = group.obj("Lead")?.toVocal(group.boolean("OppositeAligned")) ?: return@mapNotNull null
        val background = group.array("Background").objects().mapNotNull {
          it.toVocal(it.boolean("OppositeAligned"))
        }
        SyllableLyricLine(lead, background, group.boolean("OppositeAligned"))
      }.ifEmpty { throw IllegalArgumentException("Syllable lyrics contain no vocals") })
      else -> throw IllegalArgumentException("Unsupported lyrics Type: $type")
    }
  }

  private fun JsonObject.toVocal(opposite: Boolean): VocalLine? {
    val tokens = array("Syllables").objects().mapNotNull { token ->
      val text = token.string("Text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val start = token.timeMs("StartTime") ?: return@mapNotNull null
      val end = token.timeMs("EndTime")?.coerceAtLeast(start + 1L) ?: return@mapNotNull null
      LyricToken(text, start, end, token.boolean("IsPartOfWord"), token.transliteration())
    }
    if (tokens.isEmpty()) return null
    val start = timeMs("StartTime") ?: tokens.minOf(LyricToken::startMs)
    val end = (timeMs("EndTime") ?: tokens.maxOf(LyricToken::endMs)).coerceAtLeast(start + 1L)
    return VocalLine(start, end, tokens, opposite, transliteration())
  }

  private fun JsonElement.objectOrThrow() = this as? JsonObject
    ?: throw IllegalArgumentException("Lyrics JSON root must be an object")
  private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
  private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull() ?: false
  private fun JsonObject.obj(key: String) = get(key) as? JsonObject
  private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
  private fun JsonArray.objects() = mapNotNull { it as? JsonObject }
  private fun JsonArray.strings() = mapNotNull { it.jsonPrimitive.contentOrNull }.filter(String::isNotBlank)
  private fun JsonObject.timeMs(key: String): Long? = get(key)?.jsonPrimitive?.doubleOrNull
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.times(1_000.0)
    ?.roundToLong()
  private fun JsonObject.transliteration() =
    string("TransliteratedText")?.takeIf(String::isNotBlank)
      ?: string("RomanizedText")?.takeIf(String::isNotBlank)
}
