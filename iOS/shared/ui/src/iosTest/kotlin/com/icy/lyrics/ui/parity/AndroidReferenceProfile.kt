package com.icy.lyrics.ui.parity

import com.icy.lyrics.ui.IcyParityFixtures
import kotlinx.serialization.Serializable

/** Measured Android API 36 references; these are deliberately not iPhone screen profiles. */
@Serializable
internal data class AndroidReferenceProfile(
  val id: String,
  val orientation: String,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float = 2.625f,
  val fontScale: Float = 1f,
  val safeDrawingInsetsPx: List<Int> = listOf(0, 63, 0, 63),
  val safeDrawingInsetsDp: List<Float> = listOf(0f, 24f, 0f, 24f),
) {
  val scenarioIds: List<String>
    get() = if (orientation == "portrait") IcyParityFixtures.portraitIds else IcyParityFixtures.landscapeIds

  companion object {
    val Portrait = AndroidReferenceProfile("android36-420dpi-portrait-v1", "portrait", 1080, 2400)
    val Landscape = AndroidReferenceProfile("android36-420dpi-landscape-v1", "landscape", 2400, 1080)
  }
}
