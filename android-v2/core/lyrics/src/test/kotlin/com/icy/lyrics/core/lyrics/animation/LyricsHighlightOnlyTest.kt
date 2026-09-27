package com.icy.lyrics.core.lyrics.animation

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import com.icy.lyrics.core.lyrics.model.VocalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsHighlightOnlyTest {
  private val options = LyricsSceneOptions(
    fullscreenFocus = true,
    reveal = true,
    highlightOnly = true,
    synthesizeInterludes = false,
  )

  @Test
  fun `highlight only uses exact word clocks and discrete paint values`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          VocalLine(
            1_000,
            4_000,
            listOf(
              LyricToken("hello", 1_000, 2_500),
              LyricToken("world", 2_500, 4_000),
            ),
          ),
        ),
      ),
    )
    val engine = LyricsSceneEngine()
    val before = engine.frame(lyrics, 999, options = options)
    val active = engine.frame(lyrics, 1_001, options = options)
    val line = active.lines.single()
    val first = line.tokens.first()
    val second = line.tokens.last()

    assertTrue(active.highlightOnly)
    assertEquals(-20.0, before.lines.single().tokens.first().gradient.positionPercent, 0.0)
    assertEquals(100.0, first.gradient.positionPercent, 0.0)
    assertEquals(-20.0, second.gradient.positionPercent, 0.0)
    assertEquals(1_000L, first.startMs)
    assertEquals(2_500L, first.endMs)
    assertTrue(first.letters.isEmpty())
    assertEquals(1.0, first.animation.scaleGoal, 0.0)
    assertEquals(1.0, first.animation.scale, 0.0)
    assertEquals(0.0, first.animation.yOffsetFontUnitsGoal, 0.0)
    assertEquals(0.0, first.animation.yOffsetFontUnits, 0.0)
    assertEquals(0.0, first.animation.glowGoal, 0.0)
    assertEquals(0.0, first.animation.glow, 0.0)
    assertEquals(1.0, first.animation.revealOpacity, 0.0)
    assertEquals(0.0, second.animation.revealOpacity, 0.0)
    assertEquals(0.0, line.lineGlowGoal, 0.0)
    assertEquals(0.0, line.lineGlow, 0.0)
    assertEquals(0.0, line.blurRadiusPx, 0.0)
  }

  @Test
  fun `highlight only snaps fullscreen line handoffs`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(
        TimedLyricLine("first", 0, 1_000),
        TimedLyricLine("second", 1_000, 2_000),
        TimedLyricLine("third", 2_000, 3_000),
      ),
    )
    val engine = LyricsSceneEngine()
    engine.frame(lyrics, 900, options = options)
    val next = engine.frame(lyrics, 1_100, options = options)

    assertEquals(1, next.anchorRenderIndex)
    assertFalse(next.transition.active)
    assertEquals(1, next.transition.anchorIndex)
    assertEquals(1, next.transition.toIndex)
    assertTrue(next.lines.all { it.transitionRole == FocusTransitionRole.NONE })
    assertFalse(next.outro.active)
  }

  @Test
  fun `spring animator bypasses highlight only scenes`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          VocalLine(1_000, 2_500, listOf(LyricToken("hello", 1_000, 2_500))),
        ),
      ),
    )
    val target = LyricsSceneEngine().frame(lyrics, 1_200, options = options)
    val animator = LyricsSceneSpringAnimator()
    val resolved = animator.animate(target, frameTimeNanos = 1_000_000_000)

    assertTrue(resolved === target)
    assertFalse(animator.needsFrames)
  }
}
