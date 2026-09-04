package com.icy.lyrics.parity

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Test inputs measured from a real native capture; never used by the application. */
internal data class NativeViewportProfile(
  val id: String,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val fontScale: Float,
  val insets: List<Int>,
) {
  fun matches(metrics: ComposeCaptureMetrics): Boolean =
    abs(metrics.density - density) < 0.00001f && abs(metrics.fontScale - fontScale) < 0.00001f &&
      metrics.insets == insets

  /** The parent transforms dispatch before Compose's own child listener sees it. */
  fun installInsets(activity: ComponentActivity) {
    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(parent) { _, osInsets ->
      WindowInsetsCompat.Builder(osInsets)
        .setDisplayCutout(null)
        .setInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), Insets.NONE)
        .setVisible(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), false)
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(insets[0], insets[1], insets[2], insets[3]))
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(insets[0], insets[1], insets[2], insets[3]))
        .setVisible(WindowInsetsCompat.Type.systemBars(), true)
        .build()
    }
    ViewCompat.requestApplyInsets(parent)
  }

  companion object {
    fun from(arguments: Bundle): NativeViewportProfile? {
      val id = arguments.getString("viewportProfileId") ?: return null
      fun number(name: String) = requireNotNull(arguments.getString(name)) { "Missing $name" }
      return NativeViewportProfile(id, number("viewportWidthPx").toInt(), number("viewportHeightPx").toInt(),
        number("viewportDensity").toFloat(), number("viewportFontScale").toFloat(),
        listOf("Left", "Top", "Right", "Bottom").map { number("viewportInset$it").toInt() }).also {
        require(it.id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")))
        require(it.widthPx > 0 && it.heightPx > 0)
        require(it.density.isFinite() && it.density > 0 && it.fontScale.isFinite() && it.fontScale > 0)
        require(it.insets.all { inset -> inset >= 0 })
        require(it.insets[0].toLong() + it.insets[2] < it.widthPx && it.insets[1].toLong() + it.insets[3] < it.heightPx)
      }
    }
  }
}

internal data class ComposeCaptureMetrics(
  val density: Float,
  val fontScale: Float,
  val insets: List<Int>,
  val spToPx: Map<String, Float>,
) {
  fun asJson() = JSONObject().put("density", density).put("fontScale", fontScale)
    .put("safeDrawingInsetsPx", JSONArray(insets)).put("spToPx", JSONObject(spToPx))
    .put("fontScalingPolicy", "native Android LocalDensity; no replacement Density")
}

/** Observe actual Compose values, including Android's nonlinear accessibility scaling. */
@Composable internal fun CaptureComposeMetrics(onMetrics: (ComposeCaptureMetrics) -> Unit) {
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val safeDrawing = WindowInsets.safeDrawing
  val measured = ComposeCaptureMetrics(density.density, density.fontScale,
    listOf(safeDrawing.getLeft(density, direction), safeDrawing.getTop(density),
      safeDrawing.getRight(density, direction), safeDrawing.getBottom(density)),
    listOf(12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64).associate { size ->
      size.toString() to with(density) { size.sp.toPx() }
    })
  SideEffect { onMetrics(measured) }
}
