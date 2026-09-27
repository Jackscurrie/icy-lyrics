package com.icy.lyrics.ui

import com.icy.lyrics.core.lyrics.animation.LyricLineScene
import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricsScene
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvRemoteLyricSelectionTest {
  @Test
  fun initialSelectionStartsImmediatelyAboveOrBelowThePlaybackAnchor() {
    val scene = scene(
      anchorRenderIndex = 3,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, 1_000L),
        line(1, LyricSceneLineKind.BACKGROUND, 1_200L),
        line(2, LyricSceneLineKind.INTERLUDE, 2_000L),
        line(3, LyricSceneLineKind.VOCAL, 3_000L),
        line(4, LyricSceneLineKind.VOCAL, 5_000L),
      ),
    )

    assertEquals(0, remoteLyricSelectionTarget(scene, selectedRenderIndex = null, direction = -1))
    assertEquals(4, remoteLyricSelectionTarget(scene, selectedRenderIndex = null, direction = 1))
  }

  @Test
  fun repeatedStepsMoveOnlyAmongTimedPrimaryRows() {
    val scene = scene(
      anchorRenderIndex = 0,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, 1_000L),
        line(1, LyricSceneLineKind.BACKGROUND, 1_100L),
        line(2, LyricSceneLineKind.INTERLUDE, 2_000L),
        line(3, LyricSceneLineKind.VOCAL, startMs = null),
        line(4, LyricSceneLineKind.VOCAL, 4_000L),
        line(5, LyricSceneLineKind.STATIC, 5_000L),
      ),
    )

    assertEquals(4, remoteLyricSelectionTarget(scene, selectedRenderIndex = 0, direction = 1))
    assertEquals(5, remoteLyricSelectionTarget(scene, selectedRenderIndex = 4, direction = 1))
    assertEquals(4, remoteLyricSelectionTarget(scene, selectedRenderIndex = 5, direction = -1))
  }

  @Test
  fun focusVisibilityDoesNotLimitFullListRemoteBrowsing() {
    val scene = scene(
      anchorRenderIndex = 0,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, 1_000L),
        line(1, LyricSceneLineKind.VOCAL, 2_000L).copy(visible = false, preHidden = true),
        line(2, LyricSceneLineKind.VOCAL, 3_000L).copy(visible = false),
      ),
    )

    assertEquals(1, remoteLyricSelectionTarget(scene, selectedRenderIndex = 0, direction = 1))
    assertEquals(2, remoteLyricSelectionTarget(scene, selectedRenderIndex = 1, direction = 1))
  }

  @Test
  fun steppingPastEitherBoundaryClampsToTheEndpoint() {
    val scene = scene(
      anchorRenderIndex = 2,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, 1_000L),
        line(2, LyricSceneLineKind.VOCAL, 3_000L),
        line(4, LyricSceneLineKind.VOCAL, 5_000L),
      ),
    )

    assertEquals(0, remoteLyricSelectionTarget(scene, selectedRenderIndex = 0, direction = -1))
    assertEquals(4, remoteLyricSelectionTarget(scene, selectedRenderIndex = 4, direction = 1))
  }

  @Test
  fun initialSelectionAlsoClampsWhenPlaybackIsAtAPlaylistEndpoint() {
    val first = scene(
      anchorRenderIndex = 0,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, 1_000L),
        line(1, LyricSceneLineKind.VOCAL, 2_000L),
      ),
    )
    val last = first.copy(anchorRenderIndex = 1)

    assertEquals(0, remoteLyricSelectionTarget(first, selectedRenderIndex = null, direction = -1))
    assertEquals(1, remoteLyricSelectionTarget(last, selectedRenderIndex = null, direction = 1))
  }

  @Test
  fun selectionIsUnavailableWhenTheSceneHasNoTimedPrimaryRows() {
    val scene = scene(
      anchorRenderIndex = 1,
      lines = listOf(
        line(0, LyricSceneLineKind.VOCAL, startMs = null),
        line(1, LyricSceneLineKind.INTERLUDE, 1_000L),
        line(2, LyricSceneLineKind.BACKGROUND, 1_100L),
      ),
    )

    assertNull(remoteLyricSelectionTarget(scene, selectedRenderIndex = null, direction = 1))
  }

  @Test
  fun selectionTimeoutIsExactlyThreeSeconds() {
    assertEquals(3_000L, TV_REMOTE_SELECTION_TIMEOUT_MS)
  }

  private fun scene(
    anchorRenderIndex: Int?,
    lines: List<LyricLineScene>,
  ) = LyricsScene(
    positionMs = 3_000L,
    rawPositionMs = 3_000L,
    syncKind = LyricsSyncKind.LINE,
    anchorRenderIndex = anchorRenderIndex,
    lines = lines,
  )

  private fun line(
    renderIndex: Int,
    kind: LyricSceneLineKind,
    startMs: Long?,
  ) = LyricLineScene(
    key = "line-$renderIndex",
    renderIndex = renderIndex,
    sourceLineIndex = renderIndex,
    groupIndex = renderIndex,
    kind = kind,
    text = "line $renderIndex",
    startMs = startMs,
    endMs = startMs?.plus(900L),
  )
}
