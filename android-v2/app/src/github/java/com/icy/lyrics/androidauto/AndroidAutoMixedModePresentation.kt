package com.icy.lyrics.androidauto

import android.view.Display
import androidx.activity.ComponentActivity
import androidx.car.app.connection.CarConnection
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.LyricCreatorSettingsEntry
import com.icy.lyrics.ui.Artwork
import com.icy.lyrics.ui.DesktopMixedLayout
import com.icy.lyrics.ui.IcyText
import com.icy.lyrics.ui.MixedMediaSide
import com.icy.lyrics.ui.OptionalMixedModePresentation
import com.icy.lyrics.ui.PlaybackTimeline
import com.icy.lyrics.ui.icons.Icons
import com.icy.lyrics.ui.icons.filled.Pause
import com.icy.lyrics.ui.icons.filled.PlayArrow
import com.icy.lyrics.ui.icons.filled.SkipNext
import com.icy.lyrics.ui.icons.filled.SkipPrevious

internal data class AndroidAutoMixedPaneGeometry(
  val mediaWidth: Dp,
  val timelineStart: Dp,
  val timelineWidth: Dp,
  val timelineBlockHeight: Dp,
  val horizontalContentPadding: Dp,
)

internal fun androidAutoMixedPaneGeometry(
  viewportWidth: Dp,
  viewportHeight: Dp,
  lyricsEdge: Dp,
): AndroidAutoMixedPaneGeometry {
  val safeWidth = viewportWidth.coerceAtLeast(0.dp)
  val safeHeight = viewportHeight.coerceAtLeast(0.dp)
  val mediaWidth = lyricsEdge.coerceIn(0.dp, safeWidth)
  val timelineStart = minOf(12.dp, mediaWidth)
  val horizontalContentPadding = minOf(
    (mediaWidth * 0.06f).coerceIn(16.dp, 44.dp),
    mediaWidth / 4f,
  )
  return AndroidAutoMixedPaneGeometry(
    mediaWidth = mediaWidth,
    timelineStart = timelineStart,
    timelineWidth = (mediaWidth - timelineStart).coerceAtLeast(0.dp),
    timelineBlockHeight = minOf(76.dp, safeHeight),
    horizontalContentPadding = horizontalContentPadding,
  )
}

/** GitHub-release renderer for the projected, parked Android Auto display. */
internal class AndroidAutoMixedModePresentation(
  private val activity: ComponentActivity,
) : OptionalMixedModePresentation {
  private val carConnection = CarConnection(activity)

  override val edgeNavigationBottomInset: Dp = 76.dp

  @Composable
  override fun SettingsEntry(
    snapshot: NowPlayingSnapshot?,
    onLyricsChanged: () -> Unit,
  ) {
    LyricCreatorSettingsEntry(activity, snapshot, onLyricsChanged)
  }

  @Composable
  override fun isActive(mediaSide: MixedMediaSide): Boolean {
    var connectionType by remember(carConnection) {
      mutableIntStateOf(
        carConnection.type.value ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED,
      )
    }
    DisposableEffect(carConnection, activity) {
      val observer = Observer<Int> { value ->
        connectionType = value ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
      }
      carConnection.type.observe(activity, observer)
      onDispose { carConnection.type.removeObserver(observer) }
    }

    val displayId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
    return mediaSide == MixedMediaSide.LEFT &&
      connectionType == CarConnection.CONNECTION_TYPE_PROJECTION &&
      displayId != Display.DEFAULT_DISPLAY
  }

  @Composable
  override fun Content(
    snapshot: NowPlayingSnapshot,
    playbackPositionMs: Long,
    layout: DesktopMixedLayout,
    lyricsEdge: Dp,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
  ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val geometry = androidAutoMixedPaneGeometry(maxWidth, maxHeight, lyricsEdge)
      val mediaStackHeight = (maxHeight - geometry.timelineBlockHeight).coerceAtLeast(0.dp)

      Column(
        modifier = Modifier
          .width(geometry.mediaWidth)
          .height(mediaStackHeight)
          .padding(
            start = geometry.horizontalContentPadding,
            top = 8.dp,
            end = geometry.horizontalContentPadding,
            bottom = 2.dp,
          ),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        BoxWithConstraints(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          val artworkSize = minOf(
            maxWidth,
            maxHeight,
            layout.artworkSize * 1.12f,
          ).coerceAtLeast(0.dp)
          Artwork(snapshot.artwork, Modifier.size(artworkSize))
        }
        Spacer(Modifier.height(3.dp))
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
          IcyText(
            text = snapshot.displayTitle,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            overflow = TextOverflow.Clip,
          )
          IcyText(
            text = snapshot.displayArtist,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.68f),
            textAlign = TextAlign.Center,
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            overflow = TextOverflow.Clip,
          )
        }
        Spacer(Modifier.height(3.dp))
        AndroidAutoPlaybackButtons(
          snapshot = snapshot,
          onPlayPause = onPlayPause,
          onPrevious = onPrevious,
          onNext = onNext,
        )
      }

      PlaybackTimeline(
        snapshot = snapshot,
        positionMs = playbackPositionMs,
        onSeek = onSeek,
        inlineTimeLabels = false,
        alwaysShowPlayhead = true,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .offset(x = geometry.timelineStart)
          .width(geometry.timelineWidth)
          .padding(bottom = 4.dp),
      )
    }
  }
}

@Composable
private fun AndroidAutoPlaybackButtons(
  snapshot: NowPlayingSnapshot,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    IconButton(onClick = onPrevious) {
      Icon(Icons.Default.SkipPrevious, "Previous track")
    }
    IconButton(
      onClick = onPlayPause,
      modifier = Modifier
        .width(96.dp)
        .height(48.dp)
        .border(1.5.dp, Color.White.copy(alpha = 0.72f), CircleShape),
    ) {
      Icon(
        imageVector = if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
        modifier = Modifier.size(30.dp),
      )
    }
    IconButton(onClick = onNext) {
      Icon(Icons.Default.SkipNext, "Next track")
    }
  }
}
