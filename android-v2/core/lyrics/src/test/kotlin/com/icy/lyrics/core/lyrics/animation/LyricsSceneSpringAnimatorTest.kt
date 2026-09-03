package com.icy.lyrics.core.lyrics.animation

import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.VocalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSceneSpringAnimatorTest {
  @Test
  fun `first display frame starts from desktop resting values then settles analytically`() {
    val target = LyricsSceneEngine().frame(lyrics(), 1_200)
    val targetAnimation = target.lines.single().tokens.single().animation
    val animator = LyricsSceneSpringAnimator()

    val first = animator.animate(target, frameTimeNanos = 1_000_000_000)
      .lines.single().tokens.single().animation
    assertEquals(0.95, first.scale, 0.0)
    assertEquals(0.01, first.yOffsetFontUnits, 0.0)
    assertEquals(0.0, first.glow, 0.0)
    assertNotEquals(targetAnimation.scaleGoal, first.scale, 1e-9)
    assertTrue(animator.needsFrames)

    val next = animator.animate(target, frameTimeNanos = 1_016_666_667)
      .lines.single().tokens.single().animation
    assertTrue(next.scale > first.scale)
    assertTrue(next.glow > first.glow)

    val settled = animator.animate(target, frameTimeNanos = 11_016_666_667)
      .lines.single().tokens.single().animation
    assertEquals(targetAnimation.scaleGoal, settled.scale, 1e-6)
    assertEquals(targetAnimation.yOffsetFontUnitsGoal, settled.yOffsetFontUnits, 1e-6)
    assertEquals(targetAnimation.glowGoal, settled.glow, 1e-6)
    assertFalse(animator.needsFrames)
  }

  @Test
  fun `snap resolves every spring channel immediately`() {
    val target = LyricsSceneEngine().frame(lyrics(), 1_200)
    val resolved = LyricsSceneSpringAnimator().animate(target, 5, snap = true)
    val expected = target.lines.single().tokens.single().animation
    val actual = resolved.lines.single().tokens.single().animation

    assertEquals(expected.scaleGoal, actual.scale, 0.0)
    assertEquals(expected.yOffsetFontUnitsGoal, actual.yOffsetFontUnits, 0.0)
    assertEquals(expected.glowGoal, actual.glow, 0.0)
    assertEquals(expected.opacityGoal, actual.opacity, 0.0)
    assertEquals(target.lines.single().lineGlowGoal, resolved.lines.single().lineGlow, 0.0)
    assertFalse(LyricsSceneSpringAnimator().also { it.animate(target, 5, snap = true) }.needsFrames)
  }

  @Test
  fun `dot opacity has retained spring state`() {
    val target = LyricsSceneEngine().frame(
      SyllableLyrics(
        LyricsMetadata(),
        listOf(
          SyllableLyricLine(
            VocalLine(0, 1_000, listOf(LyricToken("•", 0, 1_000))),
          ),
        ),
      ),
      600,
      options = LyricsSceneOptions(synthesizeInterludes = false),
    )
    val animator = LyricsSceneSpringAnimator()
    val first = animator.animate(target, 100).lines.single().tokens.single().animation
    val next = animator.animate(target, 16_666_767).lines.single().tokens.single().animation

    assertEquals(0.35, first.opacity, 0.0)
    assertTrue(next.opacity > first.opacity)
    assertTrue(next.opacity < target.lines.single().tokens.single().animation.opacityGoal)
  }

  @Test
  fun `letter emphasis has independent retained desktop springs`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          VocalLine(1_000, 2_400, listOf(LyricToken("hello", 1_000, 2_400))),
        ),
      ),
    )
    val target = LyricsSceneEngine().frame(lyrics, 1_200, options = LyricsSceneOptions(synthesizeInterludes = false))
    val targetLetter = target.lines.single().tokens.single().letters.first().animation
    val animator = LyricsSceneSpringAnimator()

    val first = animator.animate(target, 1_000_000_000)
      .lines.single().tokens.single().letters.first().animation
    assertEquals(0.95, first.scale, 0.0)
    assertEquals(0.01, first.yOffsetFontUnits, 0.0)
    assertEquals(0.0, first.glow, 0.0)

    val next = animator.animate(target, 1_016_666_667)
      .lines.single().tokens.single().letters.first().animation
    assertTrue(next.scale > first.scale)
    assertTrue(next.glow > first.glow)

    val snapped = LyricsSceneSpringAnimator().animate(target, 5, snap = true)
      .lines.single().tokens.single().letters.first().animation
    assertEquals(targetLetter.scaleGoal, snapped.scale, 0.0)
    assertEquals(targetLetter.yOffsetFontUnitsGoal, snapped.yOffsetFontUnits, 0.0)
    assertEquals(targetLetter.glowGoal, snapped.glow, 0.0)
  }

  private fun lyrics() = SyllableLyrics(
    LyricsMetadata(),
    listOf(
      SyllableLyricLine(
        VocalLine(
          1_000,
          1_400,
          listOf(LyricToken("hello", 1_000, 1_400)),
        ),
      ),
    ),
  )
}
