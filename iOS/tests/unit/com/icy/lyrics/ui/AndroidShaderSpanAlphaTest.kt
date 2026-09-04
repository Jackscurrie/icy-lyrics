package com.icy.lyrics.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AndroidShaderSpanAlphaTest {
  private val gradient = Brush.horizontalGradient(listOf(Color.White.copy(alpha = .4f), Color.White.copy(alpha = .4f)))
  private fun sample(alpha: Float = Float.NaN) = AnnotatedString.Builder("lyric\nreading").apply {
    addStyle(SpanStyle(brush = gradient, alpha = alpha), 0, 5)
    addStyle(SpanStyle(color = Color.White.copy(alpha = .29f)), 6, 13)
    addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 5)
    addStringAnnotation("track", "spotify:local:artist:album:title:123", 0, 13)
  }.toAnnotatedString()

  @Test fun inheritsAndroidPaintAlphaWithoutChangingGradientOtherSpansOrAnnotations() {
    val original = sample()
    val adapted = androidInheritedShaderAlpha(original, TextStyle(color = Color.White.copy(alpha = .5f)))
    assertEquals(original.text, adapted.text)
    assertEquals(original.getStringAnnotations(0, original.length), adapted.getStringAnnotations(0, adapted.length))
    assertEquals(original.spanStyles, adapted.spanStyles.take(original.spanStyles.size))
    val added = adapted.spanStyles.last()
    assertSame(gradient, added.item.brush)
    assertEquals(128f / 255f, added.item.alpha)
    assertEquals(0, added.start)
    assertEquals(5, added.end)
  }

  @Test fun explicitShaderAlphaReplacesInheritedPaintAlphaRatherThanMultiplyingIt() {
    val original = sample(.7f)
    assertSame(original, androidInheritedShaderAlpha(original, TextStyle(color = Color.White.copy(alpha = .5f))))
  }

  @Test fun opaqueUnspecifiedAndOverlappingForegroundCasesRemainUntouched() {
    val original = sample()
    assertSame(original, androidInheritedShaderAlpha(original, TextStyle(color = Color.White)))
    assertSame(original, androidInheritedShaderAlpha(original, TextStyle()))
    val overlap = AnnotatedString.Builder(original).apply {
      addStyle(SpanStyle(color = Color.Red), 1, 3)
    }.toAnnotatedString()
    assertSame(overlap, androidInheritedShaderAlpha(overlap, TextStyle(color = Color.White.copy(alpha = .5f))))
  }
}
