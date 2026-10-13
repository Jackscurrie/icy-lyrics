package com.icy.lyrics.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
  @Test fun `bubble remains reachable after rotation or dragging past an edge`() {
    assertEquals(OverlayPosition(0, 480), clampOverlayPosition(
      OverlayPosition(-42, 900), OverlaySize(60, 60), OverlaySize(960, 540),
    ))
    assertEquals(OverlayPosition(480, 0), clampOverlayPosition(
      OverlayPosition(800, -30), OverlaySize(60, 60), OverlaySize(540, 960),
    ))
  }

  @Test fun `oversized window still clamps safely during display resize`() {
    assertEquals(OverlayPosition(0, 0), clampOverlayPosition(
      OverlayPosition(100, 100), OverlaySize(600, 600), OverlaySize(400, 300),
    ))
  }

  @Test fun `panel respects small landscape and large portrait displays`() {
    assertEquals(OverlaySize(420, 336), overlayPanelSize(OverlaySize(800, 360), 1f))
    assertEquals(OverlaySize(1008, 1680), overlayPanelSize(OverlaySize(1080, 2200), 3f))
    assertEquals(OverlaySize(1, 1), overlayPanelSize(OverlaySize(1, 1), 3f))
  }

  @Test fun `vertical resize clamps between minimum height and reachable bottom edge`() {
    val available = OverlaySize(1080, 2200)
    assertEquals(840, clampOverlayHeight(100, 200, available, 3f))
    assertEquals(1100, clampOverlayHeight(1100, 200, available, 3f))
    assertEquals(1964, clampOverlayHeight(Int.MAX_VALUE, 200, available, 3f))
  }

  @Test fun `minimum height shrinks to fit short screens or low window positions`() {
    assertEquals(208, clampOverlayHeight(500, 140, OverlaySize(800, 360), 1f))
    assertEquals(158, clampOverlayHeight(0, 190, OverlaySize(800, 360), 1f))
    assertEquals(1, clampOverlayHeight(560, 900, OverlaySize(800, 360), 1f))
    assertEquals(1, clampOverlayHeight(560, 0, OverlaySize(1, 1), 3f))
  }

  @Test fun `resize height ignores display width and tolerates a negative window position`() {
    assertEquals(548, clampOverlayHeight(900, -200, OverlaySize(800, 560), 1f))
    assertEquals(
      clampOverlayHeight(480, 10, OverlaySize(400, 800), 2f),
      clampOverlayHeight(480, 10, OverlaySize(1000, 800), 2f),
    )
  }

  @Test fun `overlay is opt in with settings independent of the player`() {
    val defaults = OverlayOptions()
    assertFalse(defaults.enabled)
    assertFalse(defaults.bannerSeen)
    assertTrue(defaults.backgroundEnabled)
    assertTrue(defaults.showArtwork)
    assertTrue(defaults.showTitles)
    assertTrue(defaults.copy(backgroundEnabled = false).showArtwork)
  }
}
