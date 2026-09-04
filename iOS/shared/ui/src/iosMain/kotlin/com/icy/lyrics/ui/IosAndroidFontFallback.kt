package com.icy.lyrics.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Typeface as SkTypeface

/** Explicit spans prevent CoreText choosing San Francisco, PingFang, or Apple emoji. */
internal class IosAndroidFontFallback(
  private val assetLoader: (String) -> ByteArray = ::readIcyAsset,
) {
  private val typefaces = mutableMapOf<Pair<String, Int>, SkTypeface>()
  private val families = mutableMapOf<Triple<String, Int, Int>, FontFamily>()

  fun apply(text: AnnotatedString, weight: FontWeight?): AnnotatedString {
    var builder: AnnotatedString.Builder? = null
    var i = 0
    while (i < text.length) {
      val first = text[i].code
      val count = if (first in 0xd800..0xdbff && i + 1 < text.length && text[i + 1].code in 0xdc00..0xdfff) 2 else 1
      val codePoint = if (count == 2) 0x10000 + ((first - 0xd800) shl 10) + text[i + 1].code - 0xdc00 else first
      val font = when {
        codePoint in 0x0600..0x06ff || codePoint in 0x0750..0x077f || codePoint in 0x08a0..0x08ff || codePoint in 0xfb50..0xfdff || codePoint in 0xfe70..0xfeff ->
          (if ((weight?.weight ?: 400) >= 600) "NotoNaskhArabicUI-Bold.ttf" else "NotoNaskhArabicUI-Regular.ttf") to 0
        codePoint in 0x0900..0x097f || codePoint in 0xa8e0..0xa8ff || codePoint in 0x1cd0..0x1cff -> "NotoSansDevanagariUI-VF.ttf" to 0
        codePoint in 0x3040..0x30ff -> "NotoSansCJK-Regular.ttc" to 0
        codePoint in 0x1100..0x11ff || codePoint in 0x3130..0x318f || codePoint in 0xac00..0xd7af -> "NotoSansCJK-Regular.ttc" to 1
        codePoint in 0x3000..0x303f || codePoint in 0xff00..0xffef || codePoint in 0x3400..0x9fff || codePoint in 0xf900..0xfaff || codePoint in 0x20000..0x2ffff -> "NotoSansCJK-Regular.ttc" to 2
        codePoint in 0x1f1e6..0x1f1ff -> "NotoColorEmojiFlags.ttf" to 0
        codePoint in 0x1f000..0x1faff || (codePoint in 0x2600..0x27bf && i + count < text.length && text[i + count] == '\ufe0f') -> "NotoColorEmoji.ttf" to 0
        else -> null
      }
      if (font != null) {
        if (builder == null) builder = AnnotatedString.Builder(text)
        var end = i + count
        // Keep emoji variation selectors and joining sequences in the same font run.
        while (end < text.length && (text[end] == '\ufe0f' || text[end] == '\u200d')) end++
        builder.addStyle(SpanStyle(fontFamily = family(font.first, font.second, weight?.weight ?: 400)), i, end)
      }
      i += count
    }
    return builder?.toAnnotatedString() ?: text
  }

  private fun family(file: String, index: Int, weight: Int): FontFamily = families.getOrPut(Triple(file, index, weight)) {
    val base = typefaces.getOrPut(file to index) {
      val original = assetLoader("font/$file")
      // CoreText's Skia adapter accepts only collection index zero. Select the
      // original face by repacking its tables, never by substituting a font.
      val bytes = if (file.endsWith(".ttc")) standaloneFontCollectionFace(original, index) else original
      val data = Data.makeFromBytes(bytes)
      try { requireNotNull(FontMgr.default.makeFromData(data, 0)) { "Cannot load Android fallback font $file/$index" } }
      finally { data.close() }
    }
    val variable = file.endsWith("-VF.ttf") || file.endsWith(".ttc")
    val variant = if (variable) base.makeClone(arrayOf(FontVariation("wght", weight.toFloat())), 0) else base
    FontFamily(Typeface(variant, "icy-android36-$file-$index-$weight"))
  }
}
