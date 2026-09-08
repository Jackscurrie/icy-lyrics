package com.icy.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.icy.lyrics.media.NowPlayingSnapshot

/**
 * Optional distribution-owned rendering hook for the non-lyric half of mixed
 * landscape mode. The shared lyric host remains owned and positioned by
 * [IcyLyricsApp], regardless of whether this hook is present.
 */
internal interface OptionalMixedModePresentation {
  @Composable
  fun isActive(mediaSide: MixedMediaSide): Boolean

  val edgeNavigationBottomInset: Dp

  @Composable
  fun Content(
    snapshot: NowPlayingSnapshot,
    playbackPositionMs: Long,
    layout: DesktopMixedLayout,
    lyricsEdge: Dp,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
  )
}

internal val LocalOptionalMixedModePresentation =
  staticCompositionLocalOf<OptionalMixedModePresentation?> { null }
