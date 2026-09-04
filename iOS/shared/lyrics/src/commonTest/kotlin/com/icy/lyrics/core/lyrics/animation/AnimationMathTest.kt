package com.icy.lyrics.core.lyrics.animation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class AnimationMathTest {
  @Test
  fun `timed state is half open and progress clamps`() {
    assertEquals(TimedElementStatus.NOT_SUNG, timedElementStatus(99, 100, 200))
    assertEquals(TimedElementStatus.ACTIVE, timedElementStatus(100, 100, 200))
    assertEquals(TimedElementStatus.SUNG, timedElementStatus(200, 100, 200))
    assertEquals(0.0, timedElementProgress(0, 100, 200), 0.0)
    assertEquals(0.5, timedElementProgress(150, 100, 200), 0.0)
    assertEquals(1.0, timedElementProgress(999, 100, 200), 0.0)
  }

  @Test
  fun `natural spline preserves desktop control points`() {
    val spline = NaturalCubicSpline(
      listOf(AnimationPoint(0.0, 0.95), AnimationPoint(0.7, 1.0505), AnimationPoint(1.0, 1.0)),
    )
    assertEquals(0.95, spline.at(0.0), 1e-12)
    assertEquals(1.0505, spline.at(0.7), 1e-12)
    assertEquals(1.0, spline.at(1.0), 1e-12)
    assertEquals(0.95, spline.at(-10.0), 1e-12)
    assertEquals(1.0, spline.at(10.0), 1e-12)
  }

  @Test
  fun `analytic desktop spring advances and settles at goal`() {
    val spring = DesktopSpring(0.95, frequencyHz = 0.88, dampingRatio = 0.64)
    spring.setGoal(1.05)
    val first = spring.step(1.0 / 60)
    assertTrue(first > 0.95)
    repeat(600) { spring.step(1.0 / 60) }
    assertEquals(1.05, spring.position, 1e-5)
    assertTrue(spring.canSleep())
  }

  @Test
  fun `four fullscreen modes step at edges without wrapping`() {
    assertEquals(
      listOf(FullscreenView.ARTWORK_ONLY, FullscreenView.ARTWORK_TITLES, FullscreenView.MIXED, FullscreenView.LYRICS),
      FullscreenView.entries,
    )
    assertEquals(FullscreenView.ARTWORK_ONLY, FullscreenView.ARTWORK_ONLY.step(-1))
    assertEquals(FullscreenView.ARTWORK_TITLES, FullscreenView.ARTWORK_ONLY.step(1))
    assertEquals(FullscreenView.LYRICS, FullscreenView.LYRICS.step(1))
    assertEquals(FullscreenView.MIXED, FullscreenView.LYRICS.neighbours().previous)
  }

  @Test
  fun `focus transforms match desktop cqh scale and alpha`() {
    assertEquals(FocusTransform(-0.35, 0.78, 0.3), focusTransform(FocusRole.PREVIOUS))
    assertEquals(FocusTransform(-0.035, 1.18, 1.0), focusTransform(FocusRole.CURRENT))
    assertEquals(FocusTransform(0.35, 0.78, 0.3), focusTransform(FocusRole.NEXT))
    assertEquals(FocusTransform(0.185, 0.94, 0.58, 0.62), focusTransform(FocusRole.CURRENT, isBackground = true))
    assertEquals(FocusTransform(0.0, 1.0, 0.62), focusTransform(FocusRole.CURRENT, isInterlude = true))
  }

  @Test
  fun `transition tracker uses playback position and snaps seeks`() {
    val tracker = FullscreenLineTransitionTracker()
    assertFalse(tracker.update(0, 1_000).active)
    val started = tracker.update(1, 1_100)
    assertTrue(started.active)
    assertEquals(0.0, started.progress, 0.0)
    assertEquals(0.5, tracker.update(1, 1_325).progress, 1e-12)
    assertFalse(tracker.update(1, 1_550).active)

    tracker.update(2, 1_600)
    assertFalse(tracker.update(0, 100).active)
  }

  @Test
  fun `silence merges every vocal lane and rejects a partial suffix`() {
    val candidate = TimingInterval(2_000, 6_000)
    val clean = vocalSilenceIntervals(candidate, emptyList(), 3_000)
    assertEquals(listOf(candidate), clean)
    assertTrue(isInterludeFullySilent(candidate, emptyList(), 3_000))

    val background = listOf(TimingInterval(2_100, 2_500))
    assertEquals(listOf(TimingInterval(2_500, 6_000)), vocalSilenceIntervals(candidate, background, 3_000))
    assertFalse(isInterludeFullySilent(candidate, background, 3_000))
  }

  @Test
  fun `outro is deterministic and hides final frame without overshoot flash`() {
    assertEquals(9_625L, guaranteedOutroStart(9_900, 10_000))
    val start = fullscreenOutroFrame(8_000, 8_000, 10_000, startScale = 1.18)
    assertTrue(start.active)
    assertEquals(1.18, start.scale, 1e-12)
    val end = fullscreenOutroFrame(10_125, 8_000, 10_000)
    assertTrue(end.active)
    assertEquals(0.0, end.opacity, 1e-12)
    assertEquals(0.0, end.scale, 1e-12)
  }
}
