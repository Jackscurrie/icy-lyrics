package com.icy.lyrics.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.icy.lyrics.IcyLyricsViewModel
import com.icy.lyrics.ui.ArtworkBackground
import com.icy.lyrics.ui.BackgroundStyle
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.IcyLyricsUiState
import com.icy.lyrics.ui.LyricsCanvas
import com.icy.lyrics.ui.LyricsUiStatus
import com.icy.lyrics.ui.icyColors
import com.icy.lyrics.ui.icyTypography
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import com.icy.lyrics.ui.rememberPlaybackFrame

/** Reuses the phone's exact renderer, playback clock, offsets and already-loaded document. */
@Composable
internal fun LyricsOverlayContent(
  viewModel: IcyLyricsViewModel,
  preferences: OverlayPreferences,
  onMove: (Offset) -> Unit,
  onCollapse: () -> Unit,
  onClose: () -> Unit,
  onResizeHeight: (Float) -> Unit = {},
) {
  val state by viewModel.state.collectAsState()
  val options by preferences.options.collectAsState()
  val platform = rememberAndroidIcyUiPlatform()
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
    LyricsOverlayPanel(state, options, viewModel::seekTo, onMove, onCollapse, onClose, onResizeHeight)
  }
}

@Composable
internal fun LyricsOverlayPanel(
  state: IcyLyricsUiState,
  options: OverlayOptions,
  onSeek: (Long) -> Unit,
  onMove: (Offset) -> Unit,
  onCollapse: () -> Unit,
  onClose: () -> Unit,
  onResizeHeight: (Float) -> Unit = {},
) {
  val currentOnMove by rememberUpdatedState(onMove)
  MaterialTheme(colorScheme = icyColors(), typography = icyTypography()) {
      val snapshot = state.nowPlaying
      val playback by rememberPlaybackFrame(snapshot, state.settings.effectiveTimingOffsetMs)
      val shape = RoundedCornerShape(24.dp)
      Box(Modifier.fillMaxSize().clip(shape).border(1.dp, Color.White.copy(alpha = .24f), shape)) {
        ArtworkBackground(
          artwork = snapshot?.artwork,
          enabled = options.backgroundEnabled,
          // The overlay owns its background option; changing the main app never changes it.
          style = BackgroundStyle.ANIMATED,
          isPlaying = snapshot?.isPlaying == true,
          modifier = Modifier.fillMaxSize(),
        ) {
          Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .20f))) {
            Row(
              Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .28f)),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                Modifier.weight(1f).pointerInput(Unit) {
                  detectDragGestures { change, amount -> change.consume(); currentOnMove(amount) }
                }.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Default.DragHandle, "Drag lyrics window", tint = Color.White)
                Text("Icy Lyrics", color = Color.White, fontWeight = FontWeight.Bold)
              }
              IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowDown, "Collapse to floating button", tint = Color.White)
              }
              IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Turn off lyrics overlay", tint = Color.White)
              }
            }
            if (snapshot != null && (options.showArtwork || options.showTitles)) {
              Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                if (options.showArtwork && snapshot.artwork != null) {
                  Image(
                    snapshot.artwork, "Album artwork",
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                  )
                }
                if (options.showTitles) {
                  Column(Modifier.weight(1f)) {
                    Text(snapshot.displayTitle, color = Color.White, style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold, maxLines = 3)
                    Text(snapshot.displayArtist, color = Color.White.copy(alpha = .75f),
                      style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                  }
                }
              }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
              when (val lyrics = state.lyrics) {
                is LyricsUiStatus.Ready -> LyricsCanvas(
                  document = lyrics.document,
                  positionMs = playback?.lyricsPositionMs ?: 0L,
                  rawPositionMs = playback?.rawPositionMs ?: 0L,
                  durationMs = snapshot?.durationMs,
                  reveal = state.settings.revealEnabled,
                  focusPresentation = false,
                  modifier = Modifier.fillMaxSize(),
                  onSeek = onSeek,
                )
                else -> Text(
                  when {
                    !state.notificationAccess -> "Enable notification access in Icy Lyrics to see the current song."
                    snapshot == null -> "Play a song in Spotify to see its lyrics here."
                    lyrics is LyricsUiStatus.Loading -> "Finding lyrics…"
                    lyrics is LyricsUiStatus.Failed -> "Lyrics could not be loaded. Open Icy Lyrics for details or to reload."
                    lyrics is LyricsUiStatus.Empty -> lyrics.message
                    else -> "Waiting for lyrics…"
                  },
                  Modifier.padding(24.dp), color = Color.White.copy(alpha = .85f),
                  style = MaterialTheme.typography.bodyLarge,
                )
              }
            }
            // Keep this touch target outside the lyric viewport so resizing cannot seek a line.
            Box(
              Modifier.fillMaxWidth().height(48.dp),
              contentAlignment = Alignment.BottomEnd,
            ) {
              OverlayHeightResizeHandle(onResizeHeight)
            }
          }
        }
      }
  }
}

/** Vertical deltas are physical pixels, matching the overlay WindowManager layout parameters. */
@Composable
private fun OverlayHeightResizeHandle(onResizeHeight: (Float) -> Unit) {
  val currentOnResizeHeight by rememberUpdatedState(onResizeHeight)
  val accessibilityStep = with(LocalDensity.current) { 64.dp.toPx() }
  Box(
    Modifier.size(48.dp)
      .background(Color.Black.copy(alpha = .32f), RoundedCornerShape(topStart = 16.dp))
      .semantics {
        contentDescription = "Resize lyrics window height"
        customActions = listOf(
          CustomAccessibilityAction("Increase window height") { currentOnResizeHeight(accessibilityStep); true },
          CustomAccessibilityAction("Decrease window height") { currentOnResizeHeight(-accessibilityStep); true },
        )
      }
      .pointerInput(Unit) {
        detectVerticalDragGestures { change, amount ->
          change.consume()
          currentOnResizeHeight(amount)
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.size(24.dp)) {
      val unit = size.width / 24f
      // Two diagonal lines: a recognizable grab corner without horizontal-resize arrows.
      drawLine(Color.White.copy(alpha = .92f), Offset(7f * unit, 19f * unit),
        Offset(19f * unit, 7f * unit), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
      drawLine(Color.White.copy(alpha = .92f), Offset(14f * unit, 19f * unit),
        Offset(19f * unit, 14f * unit), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
  }
}
