package com.icy.lyrics.ui

import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle

/**
 * Android ShaderBrushSpan leaves the existing TextPaint alpha alone when its
 * alpha is unspecified. Skiko resets an unspecified shader alpha to one.
 * Preserve Android's inherited, eight-bit paint alpha for the lyric canvas's
 * flat shader spans. Explicit span alpha continues to replace the paint alpha.
 * Overlapping foreground overrides need their own ordered paint-state model;
 * this adapter deliberately leaves those unrelated cases unchanged.
 */
internal fun androidInheritedShaderAlpha(text: AnnotatedString, style: TextStyle): AnnotatedString {
  if (!style.color.isSpecified || style.brush != null) return text
  val inherited = ((style.color.toArgb() ushr 24) and 255) / 255f
  if (inherited == 1f) return text
  var builder: AnnotatedString.Builder? = null
  text.spanStyles.forEachIndexed { index, range ->
    val span = range.item
    if (span.brush !is ShaderBrush || !span.alpha.isNaN() || range.start == range.end) return@forEachIndexed
    val hasForegroundOverlap = text.spanStyles.withIndex().any { (otherIndex, other) ->
      otherIndex != index && other.start < range.end && range.start < other.end &&
        (other.item.color.isSpecified || other.item.brush != null)
    }
    if (!hasForegroundOverlap) {
      if (builder == null) builder = AnnotatedString.Builder(text)
      builder!!.addStyle(SpanStyle(brush = span.brush, alpha = inherited), range.start, range.end)
    }
  }
  return builder?.toAnnotatedString() ?: text
}
