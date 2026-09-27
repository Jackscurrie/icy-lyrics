package com.icy.lyrics.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidArtworkBackgroundMeshTest {
  @Test
  fun `mesh overscans every edge and keeps all coordinates finite`() {
    val width = 1_920f
    val height = 1_080f
    val vertices = FloatArray(LEGACY_KAWARP_VERTEX_FLOATS)

    populateLegacyKawarpMesh(width, height, timeSeconds = 3f, vertices)

    assertEquals(-124.8f, vertices[0], 0.01f)
    assertEquals(-70.2f, vertices[1], 0.01f)
    assertEquals(2_044.8f, vertices[vertices.lastIndex - 1], 0.01f)
    assertEquals(1_150.2f, vertices.last(), 0.01f)
    assertTrue(vertices.all(Float::isFinite))
  }

  @Test
  fun `animation changes interior vertices without moving overscan corners`() {
    val atStart = FloatArray(LEGACY_KAWARP_VERTEX_FLOATS)
    val later = FloatArray(LEGACY_KAWARP_VERTEX_FLOATS)

    populateLegacyKawarpMesh(1_280f, 720f, timeSeconds = 0f, atStart)
    populateLegacyKawarpMesh(1_280f, 720f, timeSeconds = 12f, later)

    assertEquals(atStart[0], later[0], 0.001f)
    assertEquals(atStart[1], later[1], 0.001f)
    assertEquals(atStart[atStart.lastIndex - 1], later[later.lastIndex - 1], 0.001f)
    assertEquals(atStart.last(), later.last(), 0.001f)
    assertTrue(atStart.indices.any { index -> abs(atStart[index] - later[index]) > 1f })
  }

  @Test
  fun `legacy animation is capped near thirty frames per second`() {
    assertFalse(shouldAdvanceLegacyKawarpFrame(16_666_667L))
    assertFalse(shouldAdvanceLegacyKawarpFrame(33_333_332L))
    assertTrue(shouldAdvanceLegacyKawarpFrame(33_333_333L))
    assertTrue(shouldAdvanceLegacyKawarpFrame(50_000_000L))
  }
}
