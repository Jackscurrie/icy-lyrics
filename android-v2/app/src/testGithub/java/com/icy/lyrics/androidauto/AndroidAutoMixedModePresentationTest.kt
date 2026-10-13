package com.icy.lyrics.androidauto

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidAutoMixedModePresentationTest {
  @Test
  fun timelineSpansFromPhysicalLeftEdgeToLyricsWithoutChangingTheirEdge() {
    val layout = androidAutoMixedPaneGeometry(
      viewportWidth = 800.dp,
      viewportHeight = 480.dp,
      lyricsEdge = 384.dp,
    )

    assertEquals(12.dp, layout.timelineStart)
    assertEquals(372.dp, layout.timelineWidth)
    assertEquals(384.dp, layout.timelineStart + layout.timelineWidth)
    assertEquals(384.dp, layout.mediaWidth)
    assertEquals(76.dp, layout.timelineBlockHeight)
  }

  @Test
  fun lyricEdgeIsClampedToTheViewportForDefensiveLayout() {
    val afterViewport = androidAutoMixedPaneGeometry(800.dp, 480.dp, 900.dp)
    val beforeViewport = androidAutoMixedPaneGeometry(800.dp, 480.dp, (-40).dp)

    assertEquals(800.dp, afterViewport.mediaWidth)
    assertEquals(12.dp, afterViewport.timelineStart)
    assertEquals(788.dp, afterViewport.timelineWidth)
    assertEquals(0.dp, beforeViewport.mediaWidth)
    assertEquals(0.dp, beforeViewport.timelineStart)
    assertEquals(0.dp, beforeViewport.timelineWidth)
  }

  @Test
  fun reservedTimelineBandNeverExceedsTheViewport() {
    val layout = androidAutoMixedPaneGeometry(800.dp, 52.dp, 384.dp)

    assertEquals(52.dp, layout.timelineBlockHeight)
    assertTrue(layout.horizontalContentPadding <= layout.mediaWidth / 4f)
  }
}
