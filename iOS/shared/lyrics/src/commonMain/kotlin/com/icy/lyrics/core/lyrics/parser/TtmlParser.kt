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

/** Security or structural failure while reading an imported TTML document. */
open class TtmlParseException(message: String, cause: Throwable? = null) :
  IllegalArgumentException(message, cause)

class UnsafeTtmlException(message: String) : TtmlParseException(message)

/**
 * A local, deterministic TTML parser for Apple Music/AMLL and ordinary TTML.
 * The parser never resolves a DTD, external entity, XInclude, schema or network resource.
 */
object TtmlParser {
  private const val MAX_INPUT_CHARS = 2_000_000
  private const val MAX_ELEMENTS = 50_000
  private const val MAX_DEPTH = 64
  private const val DEFAULT_LINE_DURATION_MS = 3_000L
  private const val MIN_DURATION_MS = 1L
  private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"
  private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
  private val prohibitedDeclaration = Regex("<![ \\t\\n\\x0B\\f\\r]*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
  private val whitespace = Regex("[ \\t\\n\\x0B\\f\\r]+")

  fun parse(
    rawTtml: String,
    trackUri: String,
    source: LyricsSource = LyricsSource.LOCAL_TTML,
  ): LyricsDocument {
    require(trackUri.isNotBlank()) { "An exact track URI is required" }
    val document = parseXml(rawTtml)
    val root = document.documentElement
      ?: throw TtmlParseException("TTML has no document element")
    if (!root.localTagEquals("tt")) throw TtmlParseException("Expected a TTML <tt> root")

    validateTree(root)
    val timing = TimingContext.from(root, document)
    val paragraphs = document.elements("p")
    if (paragraphs.isEmpty()) throw TtmlParseException("TTML contains no lyric paragraphs")

    val metadata = LyricsMetadata(
      trackUri = trackUri,
      source = source,
      sourceLabel = sourceLabel(source),
      songwriters = parseSongwriters(document),
      language = root.attribute(XML_NS, "lang") ?: root.attribute(null, "lang"),
      hasTransliterations = document.elements("span").any {
        it.role() == "x-roman" || it.firstAttribute("romanWord", "romanLyric") != null
      } || document.elements("p").any { it.firstAttribute("romanLyric") != null } ||
        document.elements("transliteration").isNotEmpty(),
    )

    val sidecar = parseRomanizationSidecar(document, timing)
    val raw = paragraphs.map { parseParagraph(it, timing, sidecar) }
    val wordTimed = timingMode(document, root).equals("word", ignoreCase = true) ||
      raw.any { it.leadTokens.size > 1 || it.background.any { vocal -> vocal.tokens.isNotEmpty() } }

    return if (wordTimed) {
      buildSyllable(raw, metadata)
    } else if (raw.any { it.startMs != null || it.endMs != null }) {
      buildLine(raw, metadata)
    } else {
      val lines = raw.mapNotNull { paragraph ->
        paragraph.leadText.takeIf(String::isNotBlank)?.let {
          StaticLyricLine(text = it, transliteratedText = paragraph.romanLine)
        }
      }
      if (lines.isEmpty()) throw TtmlParseException("TTML contains no displayable lyrics")
      StaticLyrics(metadata = metadata.copy(hasTransliterations = lines.any { it.transliteratedText != null }), lines = lines)
    }
  }

  fun parseTimeToMilliseconds(value: String, frameRate: Double = 30.0, tickRate: Double = 1.0): Long {
    val text = value.trim()
    if (text.isEmpty()) throw TtmlParseException("Empty TTML time expression")

    val offset = OFFSET_TIME.matchEntire(text)
    if (offset != null) {
      val amount = offset.groupValues[1].toDoubleOrNull()
        ?: throw TtmlParseException("Invalid TTML time: $value")
      val milliseconds = when (offset.groupValues[2].lowercase()) {
        "h" -> amount * 3_600_000.0
        "m" -> amount * 60_000.0
        "s" -> amount * 1_000.0
        "ms" -> amount
        "f" -> amount * 1_000.0 / frameRate.coerceAtLeast(0.001)
        "t" -> amount * 1_000.0 / tickRate.coerceAtLeast(0.001)
        else -> error("unreachable")
      }
      return finiteNonNegative(milliseconds, value)
    }

    val parts = text.split(':')
    val milliseconds = when (parts.size) {
      3 -> {
        val hours = parts[0].toDoubleOrNull()
        val minutes = parts[1].toDoubleOrNull()
        val seconds = parts[2].toDoubleOrNull()
        if (hours == null || minutes == null || seconds == null || minutes !in 0.0..<60.0 || seconds !in 0.0..<60.0) {
          throw TtmlParseException("Invalid TTML clock time: $value")
        }
        (hours * 3_600.0 + minutes * 60.0 + seconds) * 1_000.0
      }
      2 -> {
        val minutes = parts[0].toDoubleOrNull()
        val seconds = parts[1].toDoubleOrNull()
        if (minutes == null || seconds == null || seconds !in 0.0..<60.0) {
          throw TtmlParseException("Invalid TTML clock time: $value")
        }
        (minutes * 60.0 + seconds) * 1_000.0
      }
      1 -> parts[0].toDoubleOrNull()?.times(1_000.0)
        ?: throw TtmlParseException("Invalid TTML time: $value")
      else -> throw TtmlParseException("Invalid TTML time: $value")
    }
    return finiteNonNegative(milliseconds, value)
  }

  private fun parseXml(raw: String): Document {
    if (raw.isBlank()) throw TtmlParseException("TTML is empty")
    if (raw.length > MAX_INPUT_CHARS) throw UnsafeTtmlException("TTML exceeds the size limit")
    if (prohibitedDeclaration.containsMatchIn(raw)) {
      throw UnsafeTtmlException("DOCTYPE and entity declarations are not allowed")
    }

    return try {
      readTtmlXml(raw, MAX_ELEMENTS, MAX_DEPTH)
    } catch (error: TtmlParseException) {
      throw error
    } catch (error: Exception) {
      throw TtmlParseException("Invalid TTML XML", error)
    }
  }

  private fun validateTree(root: Element) {
    var count = 0
    fun visit(node: Node, depth: Int) {
      if (depth > MAX_DEPTH) throw UnsafeTtmlException("TTML nesting exceeds the safety limit")
      if (node.nodeType == Node.ENTITY_REFERENCE_NODE || node.nodeType == Node.DOCUMENT_TYPE_NODE) {
        throw UnsafeTtmlException("TTML entity and document type nodes are not allowed")
      }
      if (node.nodeType == Node.ELEMENT_NODE && ++count > MAX_ELEMENTS) {
        throw UnsafeTtmlException("TTML contains too many elements")
      }
      val children = node.childNodes
      for (index in 0 until children.length) visit(children.item(index), depth + 1)
    }
    visit(root, 0)
  }

  private fun parseParagraph(
    paragraph: Element,
    timing: TimingContext,
    sidecar: Map<String, RomanContent>,
  ): RawParagraph {
    val pStart = paragraph.timeAttribute("begin", timing)
      ?: paragraph.timeAttribute("start", timing)
    val pEnd = paragraph.timeAttribute("end", timing)
      ?: paragraph.timeAttribute("dur", timing)?.let { duration -> (pStart ?: 0L) + duration }
    val paragraphRole = paragraph.role()
    val paragraphId = paragraph.attribute(XML_NS, "id")
      ?: paragraph.attribute(null, "id")
      ?: paragraph.firstAttribute("key", "itunes:key")
    val roman = paragraphId?.let(sidecar::get)

    val backgroundRoots = paragraph.descendants("span").filter { it.role() == "x-bg" &&
      it.ancestorsUntil(paragraph).none { ancestor -> ancestor.role() == "x-bg" } }
    val leadTimedElements = paragraph.descendants("span").filter { element ->
      element.hasOwnTiming() &&
        element.role() !in NON_LEAD_ROLES &&
        element.ancestorsUntil(paragraph).none { ancestor -> ancestor.role() in NON_LEAD_ROLES }
    }
    val leadTokens = tokenElementsToTokens(
      elements = leadTimedElements,
      fallbackStart = pStart,
      fallbackEnd = pEnd,
      timing = timing,
      romanTokens = roman?.leadTokens.orEmpty(),
    )
    val leadText = when {
      leadTokens.isNotEmpty() -> joinTokenText(leadTokens)
      else -> textExcludingRoles(paragraph, NON_LEAD_ROLES)
    }

    val backgrounds = backgroundRoots.mapNotNull { root ->
      val rootStart = root.timeAttribute("begin", timing) ?: pStart
      val rootEnd = root.timeAttribute("end", timing)
        ?: root.timeAttribute("dur", timing)?.let { (rootStart ?: 0L) + it }
        ?: pEnd
      val timed = root.descendantsOrSelf("span").filter {
        it !== root && it.hasOwnTiming() && it.role() !in NON_TEXT_ROLES
      }.ifEmpty { listOf(root).filter { it.hasOwnTiming() } }
      val tokens = tokenElementsToTokens(
        elements = timed,
        fallbackStart = rootStart,
        fallbackEnd = rootEnd,
        timing = timing,
        romanTokens = roman?.backgroundTokens.orEmpty(),
      ).ifEmpty {
        val text = textExcludingRoles(root, NON_TEXT_ROLES)
        if (text.isBlank()) emptyList() else listOf(
          LyricToken(text, rootStart ?: pStart ?: 0L, rootEnd ?: pEnd ?: ((rootStart ?: pStart ?: 0L) + DEFAULT_LINE_DURATION_MS))
        )
      }
      if (tokens.isEmpty()) return@mapNotNull null
      RawVocal(
        tokens = tokens,
        startMs = tokens.minOf { it.startMs },
        endMs = tokens.maxOf { it.endMs },
        oppositeAligned = (root.agent() ?: paragraph.agent()) == "v2",
        romanLine = root.firstAttribute("romanLyric", "roman") ?: roman?.backgroundLine,
      )
    }

    return RawParagraph(
      leadText = leadText,
      leadTokens = leadTokens,
      startMs = pStart ?: leadTokens.minOfOrNull { it.startMs },
      endMs = pEnd ?: leadTokens.maxOfOrNull { it.endMs },
      oppositeAligned = paragraph.agent() == "v2",
      isBackgroundParagraph = paragraphRole == "x-bg",
      background = backgrounds,
      romanLine = roman?.leadLine
        ?: paragraph.firstAttribute("romanLyric", "roman")
        ?: inlineRomanization(paragraph),
    )
  }

  private fun buildStaticMetadata(metadata: LyricsMetadata, hasRoman: Boolean) =
    metadata.copy(hasTransliterations = metadata.hasTransliterations || hasRoman)

  private fun buildLine(raw: List<RawParagraph>, metadata: LyricsMetadata): LyricsDocument {
    val leads = raw.filterNot { it.isBackgroundParagraph }.filter { it.leadText.isNotBlank() }
    val lines = leads.mapIndexed { index, line ->
      val start = line.startMs ?: 0L
      val nextStart = leads.getOrNull(index + 1)?.startMs
      val end = (line.endMs ?: nextStart ?: start + DEFAULT_LINE_DURATION_MS)
        .coerceAtLeast(start + MIN_DURATION_MS)
      TimedLyricLine(
        text = line.leadText,
        startMs = start,
        endMs = end,
        transliteratedText = line.romanLine,
        oppositeAligned = line.oppositeAligned,
      )
    }
    if (lines.isEmpty()) throw TtmlParseException("TTML contains no displayable line lyrics")
    return LineLyrics(
      metadata = buildStaticMetadata(metadata, lines.any { it.transliteratedText != null }),
      lines = lines,
    )
  }

  private fun buildSyllable(raw: List<RawParagraph>, metadata: LyricsMetadata): LyricsDocument {
    val result = mutableListOf<SyllableLyricLine>()
    raw.forEachIndexed { index, paragraph ->
      val start = paragraph.startMs ?: paragraph.leadTokens.minOfOrNull { it.startMs } ?: 0L
      val nextStart = raw.drop(index + 1).firstOrNull { !it.isBackgroundParagraph }?.startMs
      val end = (paragraph.endMs ?: paragraph.leadTokens.maxOfOrNull { it.endMs } ?: nextStart
        ?: start + DEFAULT_LINE_DURATION_MS).coerceAtLeast(start + MIN_DURATION_MS)
      val tokens = paragraph.leadTokens.ifEmpty {
        paragraph.leadText.takeIf(String::isNotBlank)?.let { text ->
          listOf(LyricToken(text, start, end, transliteratedText = paragraph.romanLine))
        }.orEmpty()
      }

      val asVocal = tokens.takeIf(List<LyricToken>::isNotEmpty)?.let {
        VocalLine(
          startMs = it.minOf { token -> token.startMs }.coerceAtMost(start),
          endMs = it.maxOf { token -> token.endMs }.coerceAtLeast(end),
          tokens = it,
          oppositeAligned = paragraph.oppositeAligned,
          transliteratedText = paragraph.romanLine,
        )
      }

      if (paragraph.isBackgroundParagraph) {
        val background = asVocal ?: paragraph.background.firstOrNull()?.toVocalLine()
        if (background != null && result.isNotEmpty()) {
          val previous = result.removeAt(result.lastIndex)
          result += previous.copy(background = previous.background + background)
        }
      } else if (asVocal != null) {
        result += SyllableLyricLine(
          lead = asVocal,
          background = paragraph.background.map(RawVocal::toVocalLine),
          oppositeAligned = paragraph.oppositeAligned,
        )
      }
    }
    if (result.isEmpty()) throw TtmlParseException("TTML contains no displayable word-timed lyrics")
    val hasRoman = result.any { line ->
      line.lead.transliteration != null || line.background.any { it.transliteration != null }
    }
    return SyllableLyrics(metadata = buildStaticMetadata(metadata, hasRoman), lines = result)
  }

  private fun tokenElementsToTokens(
    elements: List<Element>,
    fallbackStart: Long?,
    fallbackEnd: Long?,
    timing: TimingContext,
    romanTokens: List<String>,
  ): List<LyricToken> {
    if (elements.isEmpty()) return emptyList()
    val rawTexts = elements.map { textExcludingRoles(it, NON_TEXT_ROLES, normalize = false) }
    return elements.mapIndexedNotNull { index, element ->
      val rawText = rawTexts[index]
      val cleanText = normalizeText(rawText)
      if (cleanText.isBlank()) return@mapIndexedNotNull null
      val start = element.timeAttribute("begin", timing)
        ?: element.timeAttribute("start", timing)
        ?: fallbackStart
        ?: return@mapIndexedNotNull null
      val end = element.timeAttribute("end", timing)
        ?: element.timeAttribute("dur", timing)?.let { start + it }
        ?: fallbackEnd
        ?: start + MIN_DURATION_MS
      val directRoman = element.firstAttribute("romanWord", "roman", "transliteration")
      LyricToken(
        text = cleanText,
        startMs = start,
        endMs = end.coerceAtLeast(start + MIN_DURATION_MS),
        isPartOfWord = !rawText.endsWithWhitespace() &&
          rawTexts.getOrNull(index + 1)?.startsWithWhitespace() != true,
        transliteratedText = directRoman ?: romanTokens.getOrNull(index)?.takeIf(String::isNotBlank),
      )
    }
  }

  private fun parseRomanizationSidecar(
    document: Document,
    timing: TimingContext,
  ): Map<String, RomanContent> {
    val result = mutableMapOf<String, RomanContent>()
    document.elements("transliteration").forEach { container ->
      container.descendants("text").forEach { text ->
        val id = text.attribute(null, "for") ?: return@forEach
        val backgroundRoot = text.descendants("span").firstOrNull { it.role() == "x-bg" }
        val leadElements = text.descendants("span").filter {
          it.hasOwnTiming() && it.ancestorsUntil(text).none { ancestor -> ancestor.role() == "x-bg" }
        }
        val bgElements = backgroundRoot?.descendantsOrSelf("span")
          ?.filter { it !== backgroundRoot && it.hasOwnTiming() }
          .orEmpty()
        val leadWords = leadElements.map { normalizeText(it.textContent.orEmpty()) }.filter(String::isNotBlank)
        val bgWords = bgElements.map { normalizeText(it.textContent.orEmpty()) }.filter(String::isNotBlank)
        result[id] = RomanContent(
          leadLine = textExcludingRoles(text, setOf("x-bg")),
          leadTokens = leadWords,
          backgroundLine = backgroundRoot?.let { normalizeText(it.textContent.orEmpty()) },
          backgroundTokens = bgWords,
        )
      }
    }
    return result
  }

  private fun inlineRomanization(paragraph: Element): String? = paragraph.descendants("span")
    .firstOrNull { it.role() == "x-roman" }
    ?.textContent
    ?.let(::normalizeText)
    ?.takeIf(String::isNotBlank)

  private fun parseSongwriters(document: Document): List<String> {
    val explicit = document.elements("songwriter").mapNotNull {
      normalizeText(it.textContent.orEmpty()).takeIf(String::isNotBlank)
    }
    if (explicit.isNotEmpty()) return explicit.distinct()

    val metas = document.elements("meta").filter {
      it.firstAttribute("key", "name")?.equals("songwriters", ignoreCase = true) == true
    }.flatMap { meta ->
      (meta.firstAttribute("value", "content") ?: meta.textContent.orEmpty())
        .split(',', ';')
        .map(::normalizeText)
        .filter(String::isNotBlank)
    }
    return metas.distinct()
  }

  private fun timingMode(document: Document, root: Element): String? {
    root.firstAttribute("timing", "itunes:timing")?.let { return it }
    document.elements("timing").firstOrNull()?.textContent?.trim()?.let { return it }
    return document.elements("meta").firstNotNullOfOrNull { meta ->
      if (meta.firstAttribute("key", "name")?.equals("timingMode", ignoreCase = true) == true) {
        meta.firstAttribute("value", "content") ?: meta.textContent?.trim()
      } else null
    }
  }

  private fun textExcludingRoles(
    root: Element,
    roles: Set<String>,
    normalize: Boolean = true,
  ): String {
    val builder = StringBuilder()
    fun visit(node: Node) {
      if (node.nodeType == Node.ELEMENT_NODE && (node as Element).role() in roles) return
      if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
        builder.append(node.nodeValue.orEmpty())
      }
      val children = node.childNodes
      for (index in 0 until children.length) visit(children.item(index))
    }
    visit(root)
    return if (normalize) normalizeText(builder.toString()) else builder.toString()
  }

  private fun joinTokenText(tokens: List<LyricToken>): String = buildString {
    tokens.forEachIndexed { index, token ->
      if (index > 0 && !tokens[index - 1].isPartOfWord) append(' ')
      append(token.text)
    }
  }.trim()

  private fun normalizeText(value: String): String = value.trim().replace(whitespace, " ")

  private fun String.endsWithWhitespace(): Boolean = lastOrNull()?.isWhitespace() == true
  private fun String.startsWithWhitespace(): Boolean = firstOrNull()?.isWhitespace() == true

  private fun Element.hasOwnTiming(): Boolean =
    listOf("begin", "start", "end", "dur").any { hasAttribute(it) }

  private fun Element.timeAttribute(name: String, timing: TimingContext): Long? =
    attribute(null, name)?.let(timing::parse)

  private fun Element.agent(): String? =
    attribute(TTM_NS, "agent") ?: firstAttribute("ttm:agent", "agent")

  private fun Element.role(): String? =
    (attribute(TTM_NS, "role") ?: firstAttribute("ttm:role", "role"))?.lowercase()

  private fun Element.attribute(namespace: String?, name: String): String? {
    val value = if (namespace == null) getAttribute(name) else getAttributeNS(namespace, name)
    return value.takeIf(String::isNotBlank)
  }

  private fun Element.firstAttribute(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    val direct = getAttribute(name).takeIf(String::isNotBlank)
    direct ?: attributes?.let { attrs ->
      (0 until attrs.length).asSequence()
        .map(attrs::item)
        .firstOrNull { it.localName.equals(name.substringAfter(':'), ignoreCase = true) }
        ?.nodeValue
        ?.takeIf(String::isNotBlank)
    }
  }

  private fun Document.elements(localName: String): List<Element> {
    val namespaced = getElementsByTagNameNS("*", localName)
    return buildList {
      for (index in 0 until namespaced.length) (namespaced.item(index) as? Element)?.let(::add)
      if (isEmpty()) {
        val plain = getElementsByTagName(localName)
        for (index in 0 until plain.length) (plain.item(index) as? Element)?.let(::add)
      }
    }
  }

  private fun Element.descendants(localName: String): List<Element> {
    val nodes = getElementsByTagNameNS("*", localName)
    return buildList { for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.let(::add) }
  }

  private fun Element.descendantsOrSelf(localName: String): List<Element> = buildList {
    if (localTagEquals(localName)) add(this@descendantsOrSelf)
    addAll(descendants(localName))
  }

  private fun Element.ancestorsUntil(stopExclusive: Element): Sequence<Element> = sequence {
    var current = parentNode
    while (current is Element && current !== stopExclusive) {
      yield(current)
      current = current.parentNode
    }
  }

  private fun Element.localTagEquals(name: String): Boolean =
    (localName ?: tagName.substringAfter(':')).equals(name, ignoreCase = true)

  private fun finiteNonNegative(milliseconds: Double, source: String): Long {
    if (!milliseconds.isFinite() || milliseconds < 0.0 || milliseconds > Long.MAX_VALUE.toDouble()) {
      throw TtmlParseException("Invalid TTML time: $source")
    }
    return milliseconds.roundToLong()
  }

  private fun sourceLabel(source: LyricsSource): String = when (source) {
    LyricsSource.LOCAL_TTML -> "Local TTML"
    LyricsSource.SPICY -> "Spicy Lyrics"
    LyricsSource.SPOTIFY -> "Spotify"
    LyricsSource.APPLE_MUSIC -> "Apple Music"
    LyricsSource.LRCLIB -> "LRCLIB"
    LyricsSource.UNKNOWN -> "Unknown"
  }

  private data class TimingContext(
    val frameRate: Double,
    val tickRate: Double,
    val unitlessIsMilliseconds: Boolean,
  ) {
    fun parse(value: String): Long {
      val text = value.trim()
      if (unitlessIsMilliseconds && UNITLESS_TIME.matches(text)) {
        return finiteNonNegative(
          text.toDoubleOrNull() ?: throw TtmlParseException("Invalid TTML time: $value"),
          value,
        )
      }
      return parseTimeToMilliseconds(text, frameRate, tickRate)
    }

    companion object {
      fun from(root: Element, document: Document): TimingContext {
        val namespaceValues = root.attributes?.let { attributes ->
          (0 until attributes.length).map { attributes.item(it).nodeValue.orEmpty().lowercase() }
        }.orEmpty()
        val isAppleOrAmll = namespaceValues.any {
          "music.apple.com" in it || "apple.com/lyric" in it || "amll" in it
        }
        val mode = timingMode(document, root)
        return TimingContext(
          frameRate = root.firstAttribute("frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0,
          tickRate = root.firstAttribute("tickRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
          unitlessIsMilliseconds = isAppleOrAmll && mode.equals("word", ignoreCase = true),
        )
      }
    }
  }

  private data class RawParagraph(
    val leadText: String,
    val leadTokens: List<LyricToken>,
    val startMs: Long?,
    val endMs: Long?,
    val oppositeAligned: Boolean,
    val isBackgroundParagraph: Boolean,
    val background: List<RawVocal>,
    val romanLine: String?,
  )

  private data class RawVocal(
    val tokens: List<LyricToken>,
    val startMs: Long,
    val endMs: Long,
    val oppositeAligned: Boolean,
    val romanLine: String?,
  ) {
    fun toVocalLine() = VocalLine(startMs, endMs, tokens, oppositeAligned, romanLine)
  }

  private data class RomanContent(
    val leadLine: String?,
    val leadTokens: List<String>,
    val backgroundLine: String?,
    val backgroundTokens: List<String>,
  )

  private val OFFSET_TIME = Regex("([+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))(h|m|s|ms|f|t)", RegexOption.IGNORE_CASE)
  private val UNITLESS_TIME = Regex("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
  private val NON_LEAD_ROLES = setOf("x-bg", "x-roman", "x-translation")
  private val NON_TEXT_ROLES = setOf("x-roman", "x-translation")
}
