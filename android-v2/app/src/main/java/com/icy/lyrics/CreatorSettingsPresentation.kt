package com.icy.lyrics

import android.app.Activity
import android.content.Intent
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.icy.lyrics.creator.LyricCreatorActivity
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.DesktopMixedLayout
import com.icy.lyrics.ui.IcyText
import com.icy.lyrics.ui.MixedMediaSide
import com.icy.lyrics.ui.OptionalMixedModePresentation

/** Public phone presentation that contributes the shared Lyric Creator entry. */
internal class CreatorSettingsPresentation(
  private val activity: ComponentActivity,
) : OptionalMixedModePresentation {
  override val edgeNavigationBottomInset: Dp = 0.dp

  @Composable
  override fun isActive(mediaSide: MixedMediaSide): Boolean = false

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
  ) = Unit

  @Composable
  override fun SettingsEntry(
    snapshot: NowPlayingSnapshot?,
    onLyricsChanged: () -> Unit,
  ) {
    LyricCreatorSettingsEntry(activity, snapshot, onLyricsChanged)
  }
}

@Composable
internal fun LyricCreatorSettingsEntry(
  activity: ComponentActivity,
  snapshot: NowPlayingSnapshot?,
  onLyricsChanged: () -> Unit,
) {
  if (!shouldShowLyricCreatorSettings(activity.display?.displayId ?: Display.DEFAULT_DISPLAY)) return

  val creatorLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) onLyricsChanged()
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = Color.White.copy(alpha = 0.07f),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      IcyText(
        text = "Lyric Creator",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
      )
      HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
      IcyText(
        text = snapshot?.let {
          "Create or refine word-synced TTML for ${it.displayTitle}."
        } ?: "Create, time, preview, and save word-synced TTML on your phone.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.58f),
      )
      Button(
        onClick = {
          creatorLauncher.launch(Intent(activity, LyricCreatorActivity::class.java))
        },
      ) {
        IcyText("Open Lyric Creator")
      }
    }
  }
}

internal fun shouldShowLyricCreatorSettings(displayId: Int): Boolean =
  displayId == Display.DEFAULT_DISPLAY
