package com.icy.lyrics.ui

import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle

/**
 * CMP 1.11.1 forwards Shadow.blurRadius to SkParagraph's blurSigma unchanged.
 * Android Compose 1.11.0 applies correctBlurRadius, then Android Paint/HWUI
 * converts radius to sigma. Keep authored values intact until paragraph layout.
 */
private fun androidShadowForSkia(shadow: Shadow): Shadow {
  if (shadow == Shadow.None || !shadow.blurRadius.isFinite() || shadow.blurRadius < 0f) return shadow
  // Compose preserves a visible zero-radius shadow by passing Float.MIN_VALUE
  // to Android Paint, whose zero radius would otherwise clear the shadow layer.
  val radius = if (shadow.blurRadius == 0f) Float.MIN_VALUE else shadow.blurRadius
  return shadow.copy(blurRadius = radius * 0.57735f + 0.5f)
}

internal fun androidShadowStyleForLayout(style: TextStyle): TextStyle {
  val original = style.shadow ?: return style
  val converted = androidShadowForSkia(original)
  return if (converted == original) style else style.copy(shadow = converted)
}

internal fun androidSpanShadowsForLayout(text: AnnotatedString): AnnotatedString {
  if (text.spanStyles.none { it.item.shadow?.let(::androidShadowForSkia) != it.item.shadow }) return text
  // Replace in place rather than appending overriding spans: annotation order,
  // nested/overlapping shadows, explicit Shadow.None and unrelated styles survive.
  return text.mapAnnotations { range ->
    val span = range.item as? SpanStyle
    val shadow = span?.shadow
    if (shadow == null) range
    else AnnotatedString.Range(span.copy(shadow = androidShadowForSkia(shadow)), range.start, range.end, range.tag)
  }
}
