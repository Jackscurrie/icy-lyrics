package com.icy.lyrics.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeModeTest {
  @Test
  fun orderMatchesDesktopAndDoesNotWrap() {
    assertEquals(
      listOf(
        "Album art only",
        "Album art with titles",
        "Album art, titles and lyrics",
        "Lyrics only",
      ),
      LandscapeMode.entries.map { it.label },
    )
    assertEquals(LandscapeMode.ARTWORK_ONLY, LandscapeMode.ARTWORK_ONLY.step(-1))
    assertEquals(LandscapeMode.LYRICS, LandscapeMode.LYRICS.step(1))
    assertEquals(LandscapeMode.MIXED, LandscapeMode.ARTWORK_TITLES.step(1))
  }

  @Test
  fun mixedAndLyricsModesUseOneSharedLyricsHostGeometry() {
    val mixedLeft = landscapeLyricsLayout(LandscapeMode.MIXED, MixedMediaSide.LEFT)
    val mixedRight = landscapeLyricsLayout(LandscapeMode.MIXED, MixedMediaSide.RIGHT)
    val lyricsOnly = landscapeLyricsLayout(LandscapeMode.LYRICS, MixedMediaSide.LEFT)

    assertTrue(mixedLeft.visible)
    assertFalse(mixedLeft.focusPresentation)

    assertTrue(mixedRight.visible)
    assertFalse(mixedRight.focusPresentation)

    assertTrue(lyricsOnly.visible)
    assertTrue(lyricsOnly.focusPresentation)
    assertEquals(0f, lyricsOnly.startFraction)
    assertEquals(1f, lyricsOnly.widthFraction)
    assertEquals(0.dp, lyricsOnly.outerHorizontalInset)
    assertEquals(0.dp, lyricsOnly.innerHorizontalPadding)
    assertEquals(20.dp, lyricsOnly.verticalInset)
  }

  @Test
  fun artworkModesKeepLyricsHostOutOfHitTesting() {
    assertFalse(landscapeLyricsLayout(LandscapeMode.ARTWORK_ONLY, MixedMediaSide.LEFT).visible)
    assertFalse(landscapeLyricsLayout(LandscapeMode.ARTWORK_TITLES, MixedMediaSide.RIGHT).visible)
  }

  @Test
  fun mixedControlsArePersistentAndDoNotUseTheCenterTapOverlay() {
    val mixed = landscapeMediaControlsPolicy(LandscapeMode.MIXED)
    assertTrue(mixed.persistentPlaybackButtons)
    assertFalse(mixed.centerTapRevealEnabled)

    for (mode in listOf(LandscapeMode.ARTWORK_ONLY, LandscapeMode.ARTWORK_TITLES)) {
      val artwork = landscapeMediaControlsPolicy(mode)
      assertFalse(artwork.persistentPlaybackButtons)
      assertTrue(artwork.centerTapRevealEnabled)
    }
  }

  @Test
  fun desktopMixedGeometryScalesFromTheViewport() {
    val layout = desktopMixedLayout(1_920.dp, 1_080.dp, MixedMediaSide.LEFT)

    // These are the evaluated desktop CSS formulas, not screenshot-tuned
    // constants: min(30vw, 52vh) * 1.1 and its dependent offsets.
    assertEquals(617.76f, layout.artworkSize.value, 0.01f)
    assertEquals(187.80f, layout.mediaStart.value, 0.01f)
    assertEquals(153.90f, layout.mediaTop.value, 0.01f)
    assertEquals(27.80f, layout.timelineTopPadding.value, 0.01f)
    assertEquals(18.53f, layout.artworkTitleSpacing.value, 0.01f)
    assertEquals(48f, layout.playbackButtonsHeight.value, 0.01f)
    assertEquals(6.18f, layout.timelineHorizontalPadding.value, 0.01f)
    assertEquals(12.97f, layout.timelineTrackGap.value, 0.01f)
    assertEquals(49.42f, layout.timelineLabelWidth.value, 0.01f)
    assertEquals(913.67f, layout.lyricsStart.value, 0.01f)
    assertEquals(940.60f, layout.lyricsWidth.value, 0.01f)
  }

  @Test
  fun rightSideDesktopMixedGeometryIsAnExactMirror() {
    val left = desktopMixedLayout(1_920.dp, 1_080.dp, MixedMediaSide.LEFT)
    val right = desktopMixedLayout(1_920.dp, 1_080.dp, MixedMediaSide.RIGHT)

    assertEquals(1_920f - left.mediaStart.value - left.artworkSize.value, right.mediaStart.value, 0.01f)
    assertEquals(65.73f, right.lyricsStart.value, 0.01f)
    assertEquals(left.lyricsWidth.value, right.lyricsWidth.value, 0.01f)
  }

  @Test
  fun shortPhoneMixedGeometryKeepsTheWholeMediaStackVisible() {
    val layout = desktopMixedLayout(800.dp, 360.dp, MixedMediaSide.LEFT)
    val usableTrackWidth = layout.artworkSize - layout.timelineHorizontalPadding * 2 -
      layout.timelineLabelWidth * 2 - layout.timelineTrackGap * 2

    assertTrue(layout.inlineTimeLabels)
    assertTrue(layout.timelineLabelWidth >= 48.dp)
    assertTrue(usableTrackWidth >= 64.dp)
    assertTrue(layout.mediaTop >= 8.dp)
    assertTrue(layout.estimatedMediaBottom <= 352.dp)
  }

  @Test
  fun largeFontMixedGeometryFallsBackToStackedTimelineAndStillFits() {
    val layout = desktopMixedLayout(800.dp, 360.dp, MixedMediaSide.LEFT, fontScale = 2f)

    assertFalse(layout.inlineTimeLabels)
    assertTrue(layout.timelineLabelWidth >= 96.dp)
    assertTrue(layout.artworkSize > 0.dp)
    assertTrue(layout.estimatedMediaBottom <= 352.dp)
  }

  @Test
  fun measuredWrappedTitlesShrinkMixedArtworkBeforeTheStackCanOverflow() {
    val ordinary = desktopMixedLayout(800.dp, 360.dp, MixedMediaSide.LEFT)
    val wrapped = desktopMixedLayout(
      viewportWidth = 800.dp,
      viewportHeight = 360.dp,
      mediaSide = MixedMediaSide.LEFT,
      titleBlockHeight = 120.dp,
    )

    assertTrue(wrapped.artworkSize < ordinary.artworkSize)
    assertTrue(wrapped.estimatedMediaBottom <= 352.dp)
  }

  @Test
  fun portraitCompactBreakpointsReserveLyricsInsteadOfOverflowing() {
    val splitScreen = portraitPlayerLayout(360.dp, 360.dp)
    assertTrue(splitScreen.compact)
    assertFalse(splitScreen.showPlaybackButtons)
    assertTrue(splitScreen.artworkSize >= 72.dp)
    assertTrue(splitScreen.estimatedLyricsHeight >= 112.dp)

    val largeFont = portraitPlayerLayout(412.dp, 650.dp, fontScale = 1.6f)
    assertTrue(largeFont.compact)
    assertFalse(largeFont.showPlaybackButtons)
    assertTrue(largeFont.estimatedLyricsHeight >= 200.dp)
  }

  @Test
  fun portraitGeometryReservesTheMeasuredWrappedTitleBlock() {
    val ordinary = portraitPlayerLayout(360.dp, 650.dp)
    val wrapped = portraitPlayerLayout(
      viewportWidth = 360.dp,
      viewportHeight = 650.dp,
      titleBlockHeight = 120.dp,
    )

    assertTrue(wrapped.artworkSize < ordinary.artworkSize)
    assertTrue(wrapped.estimatedLyricsHeight >= 96.dp)
  }

  @Test
  fun edgeNavigationUsesASevenPercentGutterAndMixedLyricsStayOutsideIt() {
    val viewport = 800.dp
    val gutter = landscapeEdgeGutter(viewport)
    val desired = desktopMixedLayout(viewport, 360.dp, MixedMediaSide.LEFT)
    val safe = landscapeLyricsBounds(viewport, desired.lyricsStart, desired.lyricsWidth, gutter)

    assertEquals(56f, gutter.value, 0.01f)
    assertTrue(safe.start >= gutter)
    assertTrue(safe.start + safe.width <= viewport - gutter)
  }

  @Test
  fun presentationCrossfadeCompletesWithinTheBoundsTransition() {
    assertTrue(LANDSCAPE_PRESENTATION_FADE_OUT_MS > 0)
    assertTrue(LANDSCAPE_PRESENTATION_FADE_IN_MS > 0)
    assertTrue(
      LANDSCAPE_PRESENTATION_FADE_OUT_MS + LANDSCAPE_PRESENTATION_FADE_IN_MS <=
        LANDSCAPE_BOUNDS_TRANSITION_MS,
    )
  }

  @Test
  fun wavyTrackRailStartsBeyondThePlayedWaveAndMirrorsInRtl() {
    val ltr = wavyTrackGeometry(widthPx = 100f, fraction = 0.4f, isRtl = false, capClearancePx = 3.5f)
    val rtl = wavyTrackGeometry(widthPx = 100f, fraction = 0.4f, isRtl = true, capClearancePx = 3.5f)

    assertEquals(0f, ltr.activeStartPx, 0f)
    assertEquals(40f, ltr.activeEndPx, 0.0001f)
    assertEquals(43.5f, ltr.inactiveStartPx, 0.0001f)
    assertEquals(100f, ltr.inactiveEndPx, 0f)
    assertEquals(100f, rtl.activeStartPx, 0f)
    assertEquals(60f, rtl.activeEndPx, 0.0001f)
    assertEquals(0f, rtl.inactiveStartPx, 0f)
    assertEquals(56.5f, rtl.inactiveEndPx, 0.0001f)
    assertEquals(ltr.activeEndPx, 100f - rtl.activeEndPx, 0.0001f)
    assertEquals(ltr.inactiveStartPx, 100f - rtl.inactiveEndPx, 0.0001f)
  }

  @Test
  fun wavyTrackGeometryHandlesUnplayedAndFinishedEndpointsWithoutStrayRails() {
    val unplayed = wavyTrackGeometry(100f, 0f, isRtl = false, capClearancePx = 3.5f)
    assertEquals(0f, unplayed.activeLengthPx, 0f)
    assertEquals(0f, unplayed.inactiveStartPx, 0f)
    assertEquals(100f, unplayed.inactiveEndPx, 0f)
    assertTrue(unplayed.hasInactiveTrack)

    val finishedLtr = wavyTrackGeometry(100f, 1f, isRtl = false, capClearancePx = 3.5f)
    val finishedRtl = wavyTrackGeometry(100f, 1f, isRtl = true, capClearancePx = 3.5f)
    assertFalse(finishedLtr.hasInactiveTrack)
    assertFalse(finishedRtl.hasInactiveTrack)
  }

  @Test
  fun wavyTrackBackRemainsPhaseLiveWhileItsPlayheadJoinsTheCenterRail() {
    val back = flowingWavyTrackOffset(0f, 100f, 20f, 4f, 3f)
    val shiftedBack = flowingWavyTrackOffset(0f, 100f, 20f, 4f, 8f)
    assertEquals(wavyTrackOffset(0f, 20f, 4f, 3f), back, 0.0001f)
    assertTrue(kotlin.math.abs(back) > 0.1f)
    assertTrue(kotlin.math.abs(back - shiftedBack) > 0.1f)

    assertEquals(0f, flowingWavyTrackOffset(100f, 100f, 20f, 4f, 3f), 0.0001f)

    val taperStart = flowingWavyTrackOffset(85f, 100f, 20f, 4f, 3f)
    val insideTaper = flowingWavyTrackOffset(92.5f, 100f, 20f, 4f, 3f)
    assertTrue(kotlin.math.abs(insideTaper) < kotlin.math.abs(taperStart))

    // Advancing phase carries a crest toward the playhead rather than
    // pulling it backward toward the origin.
    assertEquals(
      wavyTrackOffset(5f, 20f, 4f, phasePx = 0f),
      wavyTrackOffset(10f, 20f, 4f, phasePx = 5f),
      0.0001f,
    )
  }

  @Test
  fun scrubPopupScalesAwayFromTheFingerWithLargeText() {
    assertEquals(-48f, scrubPopupVerticalOffset(1f).value, 0.01f)
    assertTrue(scrubPopupVerticalOffset(2f) < scrubPopupVerticalOffset(1f))
    assertEquals(112f, scrubPopupMinimumWidth(2f).value, 0.01f)
  }
}
