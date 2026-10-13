package com.icy.lyrics.overlay

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.SyllableLyricLine
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import com.icy.lyrics.core.lyrics.model.VocalLine
import com.icy.lyrics.media.NowPlayingSnapshot
import com.icy.lyrics.ui.IcyLyricsUiState
import com.icy.lyrics.ui.LyricsUiStatus
import com.icy.lyrics.ui.IcyUiPlatform
import com.icy.lyrics.ui.LocalIcyUiPlatform
import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsOverlayRendererTest {
  @get:Rule val compose = createComposeRule()

  @Test fun timedLyricsRenderWithAndWithoutOverlayArtworkAndTitles() {
    // Lyrics and animated artwork intentionally maintain a frame loop even with a paused song.
    // Auto-advancing to idle would never finish; drive deterministic fixture frames explicitly.
    compose.mainClock.autoAdvance = false
    val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.rgb(48, 128, 210))
    }.asImageBitmap()
    val snapshot = NowPlayingSnapshot(
      packageName = "com.spotify.music", title = "Overlay rendering fixture", artist = "Icy Lyrics",
      album = "Local test", durationMs = 30_000, positionMs = 3_500, playbackSpeed = 1f,
      playbackState = 2, artwork = artwork, capturedAtElapsedMs = SystemClock.elapsedRealtime(),
      rawMediaId = null, rawUri = null, extras = emptyMap(), availableActions = 0,
    )
    fun line(start: Long, text: String): SyllableLyricLine {
      val words = text.split(' ')
      return SyllableLyricLine(VocalLine(start, start + words.size * 750L,
        words.mapIndexed { index, word -> LyricToken(word, start + index * 750, start + (index + 1) * 750) }))
    }
    val state = IcyLyricsUiState(notificationAccess = true, nowPlaying = snapshot,
      lyrics = LyricsUiStatus.Ready(SyllableLyrics(lines = listOf(
        line(0, "Previous words fade away"), line(3_000, "Current words follow the music"),
        line(7_000, "Next words wait their turn"),
      ))))
    val options = mutableStateOf(OverlayOptions())
    compose.setContent {
      val androidPlatform = rememberAndroidIcyUiPlatform()
      val fixturePlatform = remember(androidPlatform) {
        object : IcyUiPlatform by androidPlatform {
          override val fixedFrameTimeNanos: Long = 0L
        }
      }
      CompositionLocalProvider(LocalIcyUiPlatform provides fixturePlatform) {
        Box(Modifier.size(350.dp, 560.dp).testTag("overlay-fixture")) {
          LyricsOverlayPanel(state, options.value, {}, {}, {}, {})
        }
      }
    }
    settle()
    compose.onNodeWithText("Overlay rendering fixture").assertExists()
    compose.onNodeWithContentDescription("Album artwork").assertExists()
    capture("word-synced-artwork.png")
    compose.runOnIdle { options.value = options.value.copy(backgroundEnabled = false, showArtwork = false, showTitles = false) }
    settle()
    compose.onNodeWithText("Overlay rendering fixture").assertDoesNotExist()
    compose.onNodeWithContentDescription("Album artwork").assertDoesNotExist()
    capture("word-synced-lyrics-only.png")
  }

  @Test fun resizeHandleOnlySendsVerticalDeltasWithoutSeekingOrMovingWindow() {
    compose.mainClock.autoAdvance = false
    val deltas = mutableListOf<Float>()
    var moves = 0
    var seeks = 0
    compose.setContent {
      val androidPlatform = rememberAndroidIcyUiPlatform()
      val fixturePlatform = remember(androidPlatform) {
        object : IcyUiPlatform by androidPlatform {
          override val fixedFrameTimeNanos: Long = 0L
        }
      }
      CompositionLocalProvider(LocalIcyUiPlatform provides fixturePlatform) {
        Box(Modifier.size(350.dp, 560.dp)) {
          LyricsOverlayPanel(
            state = IcyLyricsUiState(notificationAccess = true),
            options = OverlayOptions(backgroundEnabled = false),
            onSeek = { seeks++ }, onMove = { moves++ }, onCollapse = {}, onClose = {},
            onResizeHeight = { deltas += it },
          )
        }
      }
    }
    settle()
    val handle = compose.onNodeWithContentDescription("Resize lyrics window height")
    handle.performTouchInput {
      swipe(Offset(width * .1f, center.y), Offset(width * .9f, center.y), durationMillis = 300)
    }
    compose.runOnIdle { assertTrue("Horizontal dragging must not resize", deltas.isEmpty()) }
    handle.performTouchInput {
      swipe(Offset(center.x, height * .1f), Offset(center.x, height * .9f), durationMillis = 300)
    }
    compose.runOnIdle {
      assertTrue("Dragging down must increase height", deltas.sum() > 0f)
      assertEquals(0, moves)
      assertEquals(0, seeks)
      deltas.clear()
    }
    handle.performTouchInput {
      swipe(Offset(center.x, height * .9f), Offset(center.x, height * .1f), durationMillis = 300)
    }
    compose.runOnIdle { assertTrue("Dragging up must decrease height", deltas.sum() < 0f) }
    val actions = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions]
    compose.runOnIdle {
      deltas.clear()
      assertTrue(actions.single { it.label == "Increase window height" }.action())
      assertTrue(deltas.single() > 0f)
      deltas.clear()
      assertTrue(actions.single { it.label == "Decrease window height" }.action())
      assertTrue(deltas.single() < 0f)
    }
  }

  private fun settle() {
    compose.mainClock.advanceTimeBy(2_000)
    compose.waitForIdle()
  }

  private fun capture(name: String) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(context.getExternalFilesDir(null), "overlay").apply { mkdirs() }
    compose.onNodeWithTag("overlay-fixture").captureToImage().asAndroidBitmap().let { bitmap ->
      File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
