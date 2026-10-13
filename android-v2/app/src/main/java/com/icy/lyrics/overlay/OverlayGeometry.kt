package com.icy.lyrics.overlay

import kotlin.math.roundToInt

internal data class OverlayPosition(val x: Int, val y: Int)
internal data class OverlaySize(val width: Int, val height: Int)

/** Window bounds, not the entire display, keep handles reachable after rotation/multi-window. */
internal fun clampOverlayPosition(position: OverlayPosition, window: OverlaySize, available: OverlaySize) =
  OverlayPosition(
    position.x.coerceIn(0, (available.width - window.width).coerceAtLeast(0)),
    position.y.coerceIn(0, (available.height - window.height).coerceAtLeast(0)),
  )

internal fun overlayPanelSize(available: OverlaySize, density: Float): OverlaySize {
  val margin = (24 * density).roundToInt()
  val usableWidth = (available.width - margin).coerceAtLeast(1)
  val usableHeight = (available.height - margin).coerceAtLeast(1)
  return OverlaySize(
    width = usableWidth.coerceAtMost((420 * density).roundToInt().coerceAtLeast(1)),
    height = usableHeight.coerceAtMost((560 * density).roundToInt().coerceAtLeast(1)),
  )
}

/** Resize only height, leaving enough space to reach the bottom handle on short displays. */
internal fun clampOverlayHeight(
  requestedHeight: Int,
  positionY: Int,
  available: OverlaySize,
  density: Float,
): Int {
  val bottomMargin = (12 * density).roundToInt().coerceAtLeast(0)
  val maximum = (available.height - positionY.coerceAtLeast(0) - bottomMargin).coerceAtLeast(1)
  val minimum = (280 * density).roundToInt().coerceIn(1, maximum)
  return requestedHeight.coerceIn(minimum, maximum)
}
