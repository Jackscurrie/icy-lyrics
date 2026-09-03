package com.icy.lyrics.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.icy.lyrics.core.lyrics.animation.FocusRole
import com.icy.lyrics.core.lyrics.animation.FocusTransitionRole
import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricsSceneEngine
import com.icy.lyrics.core.lyrics.animation.LyricsSceneOptions
import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricsMetadata
import com.icy.lyrics.core.lyrics.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCanvasPresentationTest {
  @Test
  fun `fullscreen settles to previous current and next lead lines`() {
    val scene = LyricsSceneEngine().frame(
      document = timedLyrics(lineCount = 5),
      lyricsPositionMs = 2_500L,
      options = LyricsSceneOptions(fullscreenFocus = true),
    )

    val leads = visibleLyricsForPresentation(scene, focusPresentation = true)
      .filter { it.kind == LyricSceneLineKind.VOCAL }

    assertEquals(listOf("line 1", "line 2", "line 3"), leads.map { it.text })
    assertEquals(listOf(FocusRole.PREVIOUS, FocusRole.CURRENT, FocusRole.NEXT), leads.map { it.focusRole })
  }

  @Test
  fun `normal synced presentation keeps a bounded playback window`() {
    val scene = LyricsSceneEngine().frame(
      document = timedLyrics(lineCount = 40),
      lyricsPositionMs = 20_500L,
    )

    val visible = visibleLyricsForPresentation(scene, focusPresentation = false)

    assertEquals(13, visible.size)
    assertTrue(visible.any { it.text == "line 20" })
    assertFalse(visible.any { it.text == "line 0" })
    assertFalse(visible.any { it.text == "line 39" })

    val preserved = visibleLyricsForPresentation(
      scene = scene,
      focusPresentation = false,
      preserveUserViewport = true,
    )
    assertEquals(40, preserved.size)
  }

  @Test
  fun `focus font follows desktop responsive clamp`() {
    assertEquals(44.8f, responsiveFocusFontSp(320f), 0f)
    assertEquals(49.6f, responsiveFocusFontSp(800f), 0.0001f)
    assertEquals(124.8f, responsiveFocusFontSp(4_000f), 0f)
    assertEquals(
      responsiveFocusFontSp(800f) * 1.3f * 1.15f,
      responsiveFocusInterludeFontSp(800f),
      0.0001f,
    )
    assertEquals(0.07f, FOCUS_HORIZONTAL_INSET_FRACTION, 0f)
  }

  @Test
  fun `wrapped focus rows stay separated and background starts below its lead`() {
    val metrics = listOf(
      FocusLineLayoutMetric(0, 0, LyricSceneLineKind.VOCAL, desiredCenterY = 90f, visualHeight = 72f),
      FocusLineLayoutMetric(1, 1, LyricSceneLineKind.VOCAL, desiredCenterY = 300f, visualHeight = 180f),
      FocusLineLayoutMetric(2, 1, LyricSceneLineKind.BACKGROUND, desiredCenterY = 330f, visualHeight = 44f),
      FocusLineLayoutMetric(3, 2, LyricSceneLineKind.VOCAL, desiredCenterY = 560f, visualHeight = 72f),
    )

    val centers = dynamicFocusLineCenters(
      metrics = metrics,
      viewportHeight = 680f,
      safeInset = 24f,
      lineGap = 20f,
      backgroundGap = 12f,
    )

    val currentBottom = centers.getValue(1) + 90f
    val backgroundTop = centers.getValue(2) - 22f
    val backgroundBottom = centers.getValue(2) + 22f
    val nextTop = centers.getValue(3) - 36f
    assertEquals(12f, backgroundTop - currentBottom, 0.001f)
    assertTrue(nextTop - backgroundBottom >= 20f)
    assertTrue(centers.getValue(0) - 36f >= 24f)
    assertTrue(centers.getValue(3) + 36f <= 656f)
  }

  @Test
  fun `focus transition keeps complete blocks inside the viewport`() {
    val metrics = listOf(
      FocusLineLayoutMetric(4, 4, LyricSceneLineKind.VOCAL, desiredCenterY = -30f, visualHeight = 100f),
      FocusLineLayoutMetric(5, 4, LyricSceneLineKind.BACKGROUND, desiredCenterY = 10f, visualHeight = 30f),
    )

    val centers = dynamicFocusLineCenters(
      metrics = metrics,
      viewportHeight = 400f,
      safeInset = 20f,
      lineGap = 16f,
      backgroundGap = 8f,
    )

    assertEquals(70f, centers.getValue(4), 0f)
    assertEquals(143f, centers.getValue(5), 0f)
  }

  @Test
  fun `conveyor edge rows move through the clip without squeezing middle rows`() {
    val metrics = listOf(
      FocusLineLayoutMetric(
        0,
        0,
        LyricSceneLineKind.VOCAL,
        desiredCenterY = -70f,
        visualHeight = 100f,
        focusRole = FocusRole.PREVIOUS,
        transitionRole = FocusTransitionRole.DEPARTING,
      ),
      FocusLineLayoutMetric(
        1,
        1,
        LyricSceneLineKind.VOCAL,
        desiredCenterY = 120f,
        visualHeight = 100f,
        focusRole = FocusRole.PREVIOUS,
        transitionRole = FocusTransitionRole.OUTGOING,
      ),
      FocusLineLayoutMetric(
        2,
        2,
        LyricSceneLineKind.VOCAL,
        desiredCenterY = 280f,
        visualHeight = 100f,
        focusRole = FocusRole.CURRENT,
        transitionRole = FocusTransitionRole.INCOMING,
      ),
      FocusLineLayoutMetric(
        3,
        3,
        LyricSceneLineKind.VOCAL,
        desiredCenterY = 470f,
        visualHeight = 100f,
        focusRole = FocusRole.NEXT,
        transitionRole = FocusTransitionRole.ENTERING,
      ),
    )

    val placements = dynamicFocusLinePlacements(
      metrics = metrics,
      viewportHeight = 400f,
      safeInset = 20f,
      lineGap = 16f,
      backgroundGap = 8f,
    )

    assertEquals(-70f, placements.getValue(0).centerY, 0f)
    assertEquals(120f, placements.getValue(1).centerY, 0f)
    assertEquals(280f, placements.getValue(2).centerY, 0f)
    assertEquals(470f, placements.getValue(3).centerY, 0f)
    assertEquals(1f, placements.getValue(1).fitScale, 0f)
    assertEquals(1f, placements.getValue(2).fitScale, 0f)
  }

  @Test
  fun `too-tall focus blocks scale to desktop role caps and remain separated`() {
    val metrics = listOf(
      FocusLineLayoutMetric(
        renderIndex = 0,
        groupIndex = 0,
        kind = LyricSceneLineKind.VOCAL,
        desiredCenterY = 100f,
        visualHeight = 400f,
        focusRole = FocusRole.PREVIOUS,
      ),
      FocusLineLayoutMetric(
        renderIndex = 1,
        groupIndex = 1,
        kind = LyricSceneLineKind.VOCAL,
        desiredCenterY = 200f,
        visualHeight = 700f,
        focusRole = FocusRole.CURRENT,
      ),
      FocusLineLayoutMetric(
        renderIndex = 2,
        groupIndex = 1,
        kind = LyricSceneLineKind.BACKGROUND,
        desiredCenterY = 240f,
        visualHeight = 400f,
        focusRole = FocusRole.CURRENT,
      ),
      FocusLineLayoutMetric(
        renderIndex = 3,
        groupIndex = 2,
        kind = LyricSceneLineKind.VOCAL,
        desiredCenterY = 300f,
        visualHeight = 400f,
        focusRole = FocusRole.NEXT,
      ),
    )

    val placements = dynamicFocusLinePlacements(
      metrics = metrics,
      viewportHeight = 400f,
      safeInset = 20f,
      lineGap = 16f,
      backgroundGap = 8f,
    )
    val bounds = metrics.associate { metric ->
      val placement = placements.getValue(metric.renderIndex)
      val halfHeight = metric.visualHeight * placement.fitScale / 2f
      metric.renderIndex to (placement.centerY - halfHeight..placement.centerY + halfHeight)
    }

    assertTrue(bounds.getValue(0).start >= 20f)
    assertTrue(bounds.getValue(0).endInclusive < bounds.getValue(1).start)
    assertTrue(bounds.getValue(1).endInclusive < bounds.getValue(2).start)
    assertTrue(bounds.getValue(2).endInclusive < bounds.getValue(3).start)
    assertTrue(bounds.getValue(3).endInclusive <= 380f)
    assertTrue(placements.values.all { it.fitScale in 0f..1f })
  }

  @Test
  fun `manual scroll clamps to document and exposes recenter policy`() {
    val viewport = TimedLyricsScrollViewportState()
    viewport.updateGeometry(
      minimumOffsetPx = -220f,
      maximumOffsetPx = 310f,
      recenterOffsetPx = -45f,
      activeLineVisible = false,
    )

    assertEquals(-220f, viewport.clampOffset(-500f), 0f)
    assertEquals(310f, viewport.clampOffset(500f), 0f)
    assertEquals(-45f, viewport.recenterOffsetPx, 0f)
    assertFalse(viewport.activeLineVisible.value)
    assertFalse(shouldScheduleLyricsAutoFollow(true, false, false, false))
    assertFalse(shouldScheduleLyricsAutoFollow(true, true, true, false))
    assertFalse(shouldScheduleLyricsAutoFollow(true, false, true, true))
    assertTrue(shouldScheduleLyricsAutoFollow(true, false, true, false))
  }

  @Test
  fun `auto follow visibility requires five pixels of real overlap`() {
    assertFalse(hasMinimumVisibleOverlap(-10f, 20f, 100f, 5f))
    assertFalse(hasMinimumVisibleOverlap(-6f, 20f, 100f, 5f))
    assertTrue(hasMinimumVisibleOverlap(-5f, 20f, 100f, 5f))
    assertTrue(hasMinimumVisibleOverlap(50f, 20f, 100f, 5f))
    assertTrue(hasMinimumVisibleOverlap(105f, 20f, 100f, 5f))
    assertFalse(hasMinimumVisibleOverlap(106f, 20f, 100f, 5f))
  }

  @Test
  fun `fractional playback anchor interpolates measured row centers`() {
    assertEquals(175f, interpolatedLineCenter(floatArrayOf(50f, 150f, 250f), 1.25f), 0f)
  }

  @Test
  fun `line taps resolve the seek time in normal and focus geometry`() {
    val regions = LyricHitRegions()
    val normal = lyricHitBounds(
      centerX = 100f,
      centerY = 80f,
      contentWidth = 120f,
      contentHeight = 30f,
      scale = 1f,
      minimumTargetSize = 48f,
      horizontalPadding = 12f,
      viewportWidth = 240f,
    )
    val focus = lyricHitBounds(
      centerX = 120f,
      centerY = 180f,
      contentWidth = 130f,
      contentHeight = 34f,
      scale = 1.18f,
      minimumTargetSize = 48f,
      horizontalPadding = 12f,
      viewportWidth = 240f,
    )
    regions.replace(
      listOf(
        LyricHitRegion(normal, startMs = 1_000L),
        LyricHitRegion(focus, startMs = 2_000L),
      ),
    )

    assertEquals(1_000L, regions.startMsAt(Offset(100f, 80f)))
    assertEquals(2_000L, regions.startMsAt(Offset(120f, 180f)))
    assertNull(regions.startMsAt(Offset(239f, 20f)))
  }

  @Test
  fun `adjacent lyric tap bands split at center midpoint without widening`() {
    val separated = nonOverlappingLyricHitRegions(
      listOf(
        LyricHitRegion(Rect(left = 40f, top = 16f, right = 160f, bottom = 64f), startMs = 1_000L),
        LyricHitRegion(Rect(left = 55f, top = 46f, right = 145f, bottom = 94f), startMs = 2_000L),
      ),
    )
    val regions = LyricHitRegions().apply { replace(separated) }

    assertEquals(40f, separated[0].bounds.left, 0f)
    assertEquals(160f, separated[0].bounds.right, 0f)
    assertEquals(55f, separated[0].bounds.bottom, 0f)
    assertEquals(55f, separated[1].bounds.top, 0f)
    assertEquals(1_000L, regions.startMsAt(Offset(100f, 54f)))
    assertEquals(2_000L, regions.startMsAt(Offset(100f, 56f)))
  }

  @Test
  fun `opposite lane tap targets keep full height when horizontally disjoint`() {
    val original = listOf(
      LyricHitRegion(Rect(left = 0f, top = 0f, right = 45f, bottom = 48f), startMs = 1_000L),
      LyricHitRegion(Rect(left = 55f, top = 30f, right = 100f, bottom = 78f), startMs = 2_000L),
    )

    val separated = nonOverlappingLyricHitRegions(original)
    val regions = LyricHitRegions().apply { replace(separated) }

    assertEquals(original, separated)
    assertEquals(1_000L, regions.startMsAt(Offset(20f, 40f)))
    assertEquals(2_000L, regions.startMsAt(Offset(80f, 40f)))
  }

  @Test
  fun `canvas render window rejects distant rows`() {
    assertTrue(isInsideLyricRenderWindow(centerY = 50f, viewportHeight = 100f))
    assertTrue(isInsideLyricRenderWindow(centerY = -25f, viewportHeight = 100f))
    assertFalse(isInsideLyricRenderWindow(centerY = -26f, viewportHeight = 100f))
    assertFalse(isInsideLyricRenderWindow(centerY = 126f, viewportHeight = 100f))
  }

  @Test
  fun `portrait lyric viewport reserves a clipped safe inset below transport`() {
    assertEquals(LyricsViewportPadding(topDp = 16, bottomDp = 8), lyricsViewportPadding(false))
    assertEquals(LyricsViewportPadding(topDp = 12, bottomDp = 12), lyricsViewportPadding(true))
  }

  private fun timedLyrics(lineCount: Int) = LineLyrics(
    metadata = LyricsMetadata(),
    lines = (0 until lineCount).map { index ->
      TimedLyricLine(
        text = "line $index",
        startMs = index * 1_000L,
        endMs = (index + 1) * 1_000L,
      )
    },
  )
}
