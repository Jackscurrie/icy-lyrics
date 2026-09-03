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

class LyricsSceneEngineTest {
  @Test
  fun `word frame exposes desktop gradient and spline goals`() {
    val scene = LyricsSceneEngine().frame(wordLyrics(), 1_200)
    val first = scene.lines.first().tokens.first().animation
    val second = scene.lines.first().tokens[1].animation

    assertEquals(TimedElementStatus.ACTIVE, first.status)
    assertEquals(0.5, first.progress, 1e-12)
    assertEquals(40.0, first.gradientPercent, 1e-12)
    assertTrue(first.scaleGoal > 1.0)
    assertEquals(TimedElementStatus.NOT_SUNG, second.status)
    assertEquals(-20.0, second.gradientPercent, 0.0)
  }

  @Test
  fun `eligible held syllable divides the shortened desktop emphasis window into letters`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          VocalLine(1_000, 2_200, listOf(LyricToken("held", 1_000, 2_200))),
        ),
      ),
    )
    val token = LyricsSceneEngine().frame(lyrics, 1_300).lines.single().tokens.single()

    assertEquals(1_000L, token.startMs)
    assertEquals(1_950L, token.endMs)
    assertEquals(listOf("h", "e", "l", "d"), token.letters.map(LyricLetterScene::text))
    assertEquals(1_000.0, token.letters[0].startMs, 0.0)
    assertEquals(1_237.5, token.letters[0].endMs, 0.0)
    assertEquals(1_237.5, token.letters[1].startMs, 0.0)
    assertEquals(1_950.0, token.letters.last().endMs, 0.0)

    val previous = token.letters[0].animation
    val active = token.letters[1].animation
    val future = token.letters[2].animation
    assertEquals(TimedElementStatus.SUNG, previous.status)
    assertEquals(TimedElementStatus.ACTIVE, active.status)
    assertEquals(TimedElementStatus.NOT_SUNG, future.status)
    assertEquals(100.0, previous.gradientPercent, 0.0)
    assertEquals(-20.0 + 120.0 * easeSinOut(62.5 / 237.5), active.gradientPercent, 1e-9)
    assertEquals(-20.0, future.gradientPercent, 0.0)
    assertTrue(active.scaleGoal > previous.scaleGoal)
    assertEquals(0.95 + (active.scaleGoal - 0.95) / 2.0, previous.scaleGoal, 1e-12)
    assertEquals(0.01 + (active.yOffsetFontUnitsGoal - 0.01) / 2.0, previous.yOffsetFontUnitsGoal, 1e-12)
    assertEquals(active.glowGoal / 1.9, previous.glowGoal, 1e-12)
    assertEquals(0.95, future.scaleGoal, 0.0)
    assertEquals(0.0, future.glowGoal, 0.0)
  }

  @Test
  fun `letter eligibility follows desktop duration simple length and RTL rules`() {
    fun scene(text: String, durationMs: Long, simple: Boolean = false) = LyricsSceneEngine().frame(
      SyllableLyrics(
        LyricsMetadata(),
        listOf(SyllableLyricLine(VocalLine(0, durationMs, listOf(LyricToken(text, 0, durationMs))))),
      ),
      lyricsPositionMs = 100,
      options = LyricsSceneOptions(simpleLyricsMode = simple, synthesizeInterludes = false),
    ).lines.single().tokens.single()

    assertTrue(scene("brief", 999).letters.isEmpty())
    assertTrue(scene("مرحبا", 2_000).letters.isEmpty())
    assertEquals(13, scene("abcdefghijklm", 2_000).letters.size)
    assertTrue(scene("abcdefghijklm", 2_000, simple = true).letters.isEmpty())
    assertEquals(
      listOf("A", "🙂", "B"),
      scene("A🙂B", 2_000).letters.map(LyricLetterScene::text),
    )

    val simple = scene("held", 2_000, simple = true)
    assertEquals(21L, simple.startMs)
    assertEquals(2_040L, simple.endMs)
    assertEquals(4, simple.letters.size)
    assertEquals(50.0, simple.letters.first().gradient.transitionWidthPercent, 0.0)
    assertEquals(1.0, simple.letters.first().gradient.leadingAlpha, 0.0)
    assertEquals(0.3, simple.letters.first().gradient.trailingAlpha, 0.0)
  }

  @Test
  fun `Reveal stages only current group and hides future words`() {
    val scene = LyricsSceneEngine().frame(
      wordLyrics(),
      1_200,
      options = LyricsSceneOptions(fullscreenFocus = true, reveal = true),
    )
    val visible = scene.lines.filter(LyricLineScene::visible)
    assertEquals(1, visible.size)
    assertTrue(visible.single().revealCurrent)
    assertTrue(visible.single().tokens[0].animation.revealVisible)
    assertFalse(visible.single().tokens[1].animation.revealVisible)
    assertEquals(0.0, visible.single().tokens[1].animation.revealOpacity, 0.0)
  }

  @Test
  fun `Reveal hides and fades line sync outside fullscreen focus`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(TimedLyricLine("first", 1_000, 2_000), TimedLyricLine("future", 3_000, 4_000)),
    )
    val engine = LyricsSceneEngine()

    val before = engine.frame(lyrics, 500, options = LyricsSceneOptions(reveal = true))
    assertEquals(0.0, before.lines[0].contentOpacity, 0.0)
    assertEquals(0.0, before.lines[1].contentOpacity, 0.0)

    val entering = engine.frame(lyrics, 1_100, options = LyricsSceneOptions(reveal = true))
    assertEquals(0.5, entering.lines[0].contentOpacity, 1e-9)
    assertEquals(0.0, entering.lines[1].contentOpacity, 0.0)

    val sung = engine.frame(lyrics, 2_100, options = LyricsSceneOptions(reveal = true))
    assertEquals(LyricsSceneEngine.SUNG_LINE_OPACITY, sung.lines[0].contentOpacity, 0.0)
  }

  @Test
  fun `synthesizes three padded dots and pre-hides final 500ms`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(TimedLyricLine("one", 0, 2_000), TimedLyricLine("two", 6_000, 7_000)),
    )
    val engine = LyricsSceneEngine()
    val active = engine.frame(lyrics, 3_000, options = LyricsSceneOptions(fullscreenFocus = true))
    val interlude = active.lines.single { it.kind == LyricSceneLineKind.INTERLUDE }
    assertEquals(3, interlude.tokens.size)
    assertEquals("• • •", interlude.text)
    assertEquals(listOf("•", "•", "•"), interlude.tokens.map(LyricTokenScene::text))
    assertEquals(listOf(false, false, false), interlude.tokens.map(LyricTokenScene::isPartOfWord))
    assertTrue(interlude.tokens.all(LyricTokenScene::isDot))
    assertEquals(5_450L, interlude.tokens.last().endMs)
    assertTrue(interlude.visible)
    assertEquals(FocusRole.CURRENT, interlude.focusRole)

    val late = engine.frame(lyrics, 5_600, options = LyricsSceneOptions(fullscreenFocus = true))
      .lines.single { it.kind == LyricSceneLineKind.INTERLUDE }
    assertTrue(late.preHidden)
    assertTrue(late.tokens.all { it.animation.status == TimedElementStatus.SUNG })
  }

  @Test
  fun `empty whitespace invisible and note-only timed rows become one interlude`() {
    val placeholders = listOf("", " \t ", "\u200B", "♪", "♫  🎵")

    placeholders.forEach { placeholder ->
      val lyrics = LineLyrics(
        LyricsMetadata(),
        listOf(
          TimedLyricLine("before", 0, 1_000),
          TimedLyricLine(placeholder, 1_250, 3_500),
          TimedLyricLine("after", 5_000, 6_000),
        ),
      )
      val scene = LyricsSceneEngine().frame(
        lyrics,
        2_500,
        options = LyricsSceneOptions(fullscreenFocus = true),
      )

      assertEquals(listOf("before", "• • •", "after"), scene.lines.map(LyricLineScene::text))
      val interlude = scene.lines.single { it.kind == LyricSceneLineKind.INTERLUDE }
      assertEquals(1_000L, interlude.startMs)
      assertEquals(5_000L, interlude.endMs)
      assertEquals(FocusRole.CURRENT, interlude.focusRole)
    }
  }

  @Test
  fun `music notes decorating real words remain lyric content`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(
        TimedLyricLine("before", 0, 1_000),
        TimedLyricLine("♪ sing this ♪", 1_250, 3_000),
        TimedLyricLine("after", 6_500, 7_500),
      ),
    )

    val scene = LyricsSceneEngine().frame(lyrics, 2_000)
    val decorated = scene.lines.single { it.text == "♪ sing this ♪" }
    assertEquals(LyricSceneLineKind.VOCAL, decorated.kind)
    assertEquals(TimedElementStatus.ACTIVE, decorated.status)
  }

  @Test
  fun `note-only background does not occupy interlude silence`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(0, 1_000, "before"),
          background = listOf(vocal(1_200, 3_200, "♪")),
        ),
        SyllableLyricLine(lead = vocal(5_000, 6_000, "after")),
      ),
    )

    val scene = LyricsSceneEngine().frame(lyrics, 2_500)
    assertTrue(scene.lines.none { it.kind == LyricSceneLineKind.BACKGROUND })
    assertEquals(1, scene.lines.count { it.kind == LyricSceneLineKind.INTERLUDE })
  }

  @Test
  fun `real background remains focusable when its lead is a note placeholder`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(1_000, 2_000, "♪"),
          background = listOf(vocal(1_100, 1_900, "(Louder)")),
        ),
      ),
    )

    val scene = LyricsSceneEngine().frame(
      lyrics,
      1_500,
      options = LyricsSceneOptions(fullscreenFocus = true),
    )
    val background = scene.lines.single()
    assertEquals(LyricSceneLineKind.BACKGROUND, background.kind)
    assertEquals("Louder", background.text)
    assertEquals(FocusRole.CURRENT, background.focusRole)
    assertTrue(background.visible)
  }

  @Test
  fun `background overlap prevents interlude synthesis in every presentation`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(0, 2_000, "lead"),
          background = listOf(vocal(2_100, 2_500, "background")),
        ),
        SyllableLyricLine(lead = vocal(6_000, 7_000, "next")),
      ),
    )
    val scene = LyricsSceneEngine().frame(
      lyrics,
      2_300,
    )
    assertTrue(scene.lines.none { it.kind == LyricSceneLineKind.INTERLUDE })
    assertEquals("background", scene.lines.single { it.kind == LyricSceneLineKind.BACKGROUND }.text)
  }

  @Test
  fun `background wrappers are removed only from presentation and still occupy silence`() {
    val wrappedBackground = VocalLine(
      startMs = 2_100,
      endMs = 2_500,
      tokens = listOf(LyricToken("((Louder))", 2_100, 2_500)),
      transliteratedText = "（Louder romanized）",
    )
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(0, 2_000, "lead"),
          background = listOf(wrappedBackground),
        ),
        SyllableLyricLine(lead = vocal(6_000, 7_000, "next")),
      ),
    )

    val scene = LyricsSceneEngine().frame(lyrics, 2_300)
    val background = scene.lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertEquals("Louder", background.text)
    assertEquals("Louder", background.tokens.single().text)
    assertEquals("Louder romanized", background.transliteratedText)
    assertEquals(2_100L, background.tokens.single().startMs)
    assertEquals(2_500L, background.tokens.single().endMs)
    assertTrue(scene.lines.none { it.kind == LyricSceneLineKind.INTERLUDE })

    assertEquals("((Louder))", wrappedBackground.text)
    assertEquals("((Louder))", wrappedBackground.tokens.single().text)
    assertEquals("（Louder romanized）", wrappedBackground.transliteratedText)
  }

  @Test
  fun `focus transition is a 450ms four-row conveyor`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(
        TimedLyricLine("zero", 0, 1_000),
        TimedLyricLine("one", 1_000, 2_000),
        TimedLyricLine("two", 2_000, 3_000),
        TimedLyricLine("three", 3_000, 4_000),
      ),
    )
    val engine = LyricsSceneEngine()
    engine.frame(lyrics, 900, options = LyricsSceneOptions(fullscreenFocus = true))
    engine.frame(lyrics, 1_100, options = LyricsSceneOptions(fullscreenFocus = true))
    val halfway = engine.frame(lyrics, 1_325, options = LyricsSceneOptions(fullscreenFocus = true))

    assertTrue(halfway.transition.active)
    assertEquals(0.5, halfway.transition.progress, 1e-12)
    assertEquals(FocusTransitionKind.LINE, halfway.transitionKind)
    assertTrue(halfway.lines.any { it.transitionRole == FocusTransitionRole.OUTGOING })
    assertTrue(halfway.lines.any { it.transitionRole == FocusTransitionRole.INCOMING })
    assertTrue(halfway.lines.any { it.transitionRole == FocusTransitionRole.ENTERING })
  }

  @Test
  fun `background gets attached focus role and exact secondary geometry`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(SyllableLyricLine(vocal(1_000, 2_000, "lead"), listOf(vocal(1_200, 1_800, "bg")))),
    )
    val scene = LyricsSceneEngine().frame(
      lyrics,
      1_300,
      options = LyricsSceneOptions(fullscreenFocus = true),
    )
    val background = scene.lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(background.visible)
    assertEquals(FocusRole.CURRENT, background.focusRole)
    assertEquals(0.185, background.focusTransform.yViewportFraction, 1e-12)
    assertEquals(0.58, background.focusTransform.opacity, 1e-12)
  }

  @Test
  fun `fullscreen background starts on its own clock and leaves with its foreground`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(1_000, 4_000, "lead"),
          background = listOf(vocal(1_500, 2_000, "background")),
        ),
        SyllableLyricLine(lead = vocal(4_000, 6_000, "next")),
      ),
    )
    val options = LyricsSceneOptions(fullscreenFocus = true, synthesizeInterludes = false)
    val engine = LyricsSceneEngine()

    val beforeBackground = engine.frame(lyrics, 1_400, options = options)
      .lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertFalse(beforeBackground.visible)
    assertEquals(FocusRole.NONE, beforeBackground.focusRole)

    val activeBackground = engine.frame(lyrics, 1_600, options = options)
      .lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(activeBackground.visible)
    assertEquals(FocusRole.CURRENT, activeBackground.focusRole)

    val completedBackground = engine.frame(lyrics, 3_900, options = options)
      .lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(completedBackground.visible)
    assertEquals(TimedElementStatus.SUNG, completedBackground.status)
    assertEquals(FocusRole.CURRENT, completedBackground.focusRole)

    val transitionStart = engine.frame(lyrics, 4_001, options = options)
    val outgoingBackground = transitionStart.lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(transitionStart.transition.active)
    assertTrue(outgoingBackground.visible)
    assertEquals(FocusRole.PREVIOUS, outgoingBackground.focusRole)
    assertEquals(FocusTransitionRole.OUTGOING, outgoingBackground.transitionRole)

    val transitionMiddle = engine.frame(lyrics, 4_226, options = options)
      .lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(transitionMiddle.visible)
    assertTrue(transitionMiddle.focusTransform.opacity in 0.0..<0.58)

    val settled = engine.frame(lyrics, 4_451, options = options)
      .lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertFalse(settled.visible)
    assertEquals(FocusRole.NONE, settled.focusRole)
  }

  @Test
  fun `fullscreen next row never previews future background lyrics`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(lead = vocal(0, 1_000, "current")),
        SyllableLyricLine(
          lead = vocal(1_000, 3_000, "next"),
          background = listOf(vocal(1_500, 2_000, "future background")),
        ),
      ),
    )

    val scene = LyricsSceneEngine().frame(
      lyrics,
      500,
      options = LyricsSceneOptions(fullscreenFocus = true, synthesizeInterludes = false),
    )

    val next = scene.lines.single { it.text == "next" }
    val background = scene.lines.single { it.kind == LyricSceneLineKind.BACKGROUND }
    assertTrue(next.visible)
    assertEquals(FocusRole.NEXT, next.focusRole)
    assertFalse(background.visible)
    assertEquals(FocusRole.NONE, background.focusRole)
  }

  @Test
  fun `scene exposes desktop RTL gradient direction duet lanes and focus centering`() {
    val lyrics = SyllableLyrics(
      LyricsMetadata(),
      listOf(
        SyllableLyricLine(
          lead = vocal(1_000, 2_000, "مرحبا"),
          oppositeAligned = true,
          background = listOf(vocal(1_100, 1_800, "صدى")),
        ),
      ),
    )
    val normal = LyricsSceneEngine().frame(lyrics, 1_300)
    val lead = normal.lines.first()
    val background = normal.lines.last()

    assertTrue(normal.hasDuetLines)
    assertTrue(normal.hasRtlLines)
    assertEquals(LyricTextDirection.RIGHT_TO_LEFT, lead.textDirection)
    assertEquals(LyricHorizontalAlignment.END, lead.horizontalAlignment)
    assertEquals(0.15, lead.laneInsetFraction, 0.0)
    assertEquals(-90.0, lead.tokens.single().gradient.angleDegrees, 0.0)
    assertEquals(0.85, lead.tokens.single().gradient.leadingAlpha, 0.0)
    assertEquals(0.5, lead.tokens.single().gradient.trailingAlpha, 0.0)
    assertEquals(0.6, background.tokens.single().gradient.leadingAlpha, 0.0)
    assertEquals(0.3, background.tokens.single().gradient.trailingAlpha, 0.0)

    val focused = LyricsSceneEngine().frame(
      lyrics,
      1_300,
      options = LyricsSceneOptions(fullscreenFocus = true),
    )
    assertTrue(focused.lines.all { it.horizontalAlignment == LyricHorizontalAlignment.CENTER })
    assertTrue(focused.lines.all { it.laneInsetFraction == 0.07 })
  }

  @Test
  fun `normal line opacity and line gradient use desktop constants`() {
    val lyrics = LineLyrics(
      LyricsMetadata(),
      listOf(
        TimedLyricLine("past", 0, 1_000),
        TimedLyricLine("current", 1_000, 2_000),
        TimedLyricLine("future", 2_000, 3_000),
      ),
    )
    val scene = LyricsSceneEngine().frame(lyrics, 1_500)

    assertEquals(0.497, scene.lines[0].contentOpacity, 0.0)
    assertEquals(1.0, scene.lines[1].contentOpacity, 0.0)
    assertEquals(0.51, scene.lines[2].contentOpacity, 0.0)
    assertEquals(180.0, scene.lines[1].gradient.angleDegrees, 0.0)
    assertEquals(50.0, scene.lines[1].gradient.positionPercent, 0.0)
    assertEquals(0.35, scene.lines[1].gradient.trailingAlpha, 0.0)
  }

  @Test
  fun `direction check skips desktop neutral prefix`() {
    assertEquals(LyricTextDirection.RIGHT_TO_LEFT, detectLyricTextDirection("123 -- مرحبا"))
    assertEquals(LyricTextDirection.LEFT_TO_RIGHT, detectLyricTextDirection("123 -- hello مرحبا"))
    assertEquals(LyricTextDirection.LEFT_TO_RIGHT, detectLyricTextDirection("123 --"))
  }

  @Test
  fun `outro is opt-in and disabled in v1 default scene`() {
    val lyrics = LineLyrics(LyricsMetadata(), listOf(TimedLyricLine("last", 0, 8_000)))
    val default = LyricsSceneEngine().frame(
      lyrics,
      lyricsPositionMs = 9_800,
      rawPositionMs = 9_800,
      options = LyricsSceneOptions(fullscreenFocus = true, durationMs = 10_000),
    )
    assertFalse(default.outro.active)

    val optedIn = LyricsSceneEngine().frame(
      lyrics,
      lyricsPositionMs = 9_800,
      rawPositionMs = 9_800,
      options = LyricsSceneOptions(fullscreenFocus = true, durationMs = 10_000, outroEnabled = true),
    )
    assertTrue(optedIn.outro.active)
  }

  private fun wordLyrics() = SyllableLyrics(
    LyricsMetadata(),
    listOf(
      SyllableLyricLine(
        VocalLine(
          1_000,
          2_000,
          listOf(
            LyricToken("hello", 1_000, 1_400),
            LyricToken("world", 1_400, 2_000),
          ),
        ),
      ),
    ),
  )

  private fun vocal(start: Long, end: Long, text: String) =
    VocalLine(start, end, listOf(LyricToken(text, start, end)))
}
