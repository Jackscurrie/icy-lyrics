package com.icy.lyrics.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import com.icy.lyrics.ui.IcyText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icy.lyrics.core.lyrics.animation.FocusRole
import com.icy.lyrics.core.lyrics.animation.FocusTransitionRole
import com.icy.lyrics.core.lyrics.animation.LyricGradientFrame
import com.icy.lyrics.core.lyrics.animation.LyricHorizontalAlignment
import com.icy.lyrics.core.lyrics.animation.LyricLineScene
import com.icy.lyrics.core.lyrics.animation.LyricSceneLineKind
import com.icy.lyrics.core.lyrics.animation.LyricTextDirection
import com.icy.lyrics.core.lyrics.animation.LyricsScene
import com.icy.lyrics.core.lyrics.animation.LyricsSceneEngine
import com.icy.lyrics.core.lyrics.animation.LyricsSceneOptions
import com.icy.lyrics.core.lyrics.animation.LyricsSceneSpringAnimator
import com.icy.lyrics.core.lyrics.animation.TimedElementStatus
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.timing.PlaybackClock
import com.icy.lyrics.core.lyrics.timing.PlaybackClockFrame
import com.icy.lyrics.media.NowPlayingSnapshot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/**
 * Shared frame clock for both transport and lyrics. PlaybackClock owns the one
 * +100 ms perceptual lead; its raw position remains free of lyric offsets.
 */
@Composable
fun rememberPlaybackFrame(
  snapshot: NowPlayingSnapshot?,
  timingOffsetMs: Int,
): State<PlaybackClockFrame?> {
  val platform = LocalIcyUiPlatform.current
  val clock = remember(snapshot?.identity?.exactStorageKey) { PlaybackClock() }
  val frame = remember(snapshot?.identity?.exactStorageKey) {
    mutableStateOf(
      snapshot?.let {
        clock.update(it.asPlaybackSample())
        clock.frameAt(platform.monotonicTimeMs(), timingOffsetMs)
      },
    )
  }
  LaunchedEffect(
    snapshot?.identity?.exactStorageKey,
    snapshot?.positionMs,
    snapshot?.capturedAtElapsedMs,
    snapshot?.playbackState,
    snapshot?.playbackSpeed,
    snapshot?.durationMs,
    timingOffsetMs,
  ) {
    if (snapshot == null) {
      clock.reset()
      frame.value = null
      return@LaunchedEffect
    }
    clock.update(snapshot.asPlaybackSample())
    frame.value = clock.frameAt(platform.monotonicTimeMs(), timingOffsetMs)
    if (!snapshot.isPlaying || platform.fixedFrameTimeNanos != null) return@LaunchedEffect
    while (isActive) {
      withFrameNanos {
        frame.value = clock.frameAt(platform.monotonicTimeMs(), timingOffsetMs)
      }
    }
  }
  return frame
}

/**
 * Canvas front-end for the shared desktop-parity scene engine. Timing state,
 * Reveal, interludes, opposite lanes, word curves, transitions and outro all
 * come from LyricsSceneEngine; this file only measures and paints its scene.
 */
@Composable
fun LyricsCanvas(
  document: LyricsDocument,
  positionMs: Long,
  rawPositionMs: Long,
  durationMs: Long?,
  reveal: Boolean,
  focusPresentation: Boolean,
  modifier: Modifier = Modifier,
  onSeek: (Long) -> Unit = {},
) {
  if (document is StaticLyrics) {
    StaticLyricsList(document, modifier)
    return
  }

  val reducedMotion = rememberReducedMotionEnabled()
  val engine = remember(document) { LyricsSceneEngine() }
  val targetScene = engine.frame(
    document = document,
    lyricsPositionMs = positionMs.coerceAtLeast(0L),
    rawPositionMs = rawPositionMs.coerceAtLeast(0L),
    options = LyricsSceneOptions(
      fullscreenFocus = focusPresentation,
      reveal = reveal,
      outroEnabled = focusPresentation,
      durationMs = durationMs,
      reducedMotion = reducedMotion,
    ),
  )
  val scene = rememberAnimatedLyricsScene(document, targetScene, reducedMotion)
  var userScrollOffsetPx by remember(document) { mutableFloatStateOf(0f) }
  var userHasScrolled by remember(document) { mutableStateOf(false) }
  var isUserDragging by remember(document) { mutableStateOf(false) }
  val scrollViewport = remember(document) { TimedLyricsScrollViewportState() }
  val visibleLines = visibleLyricsForPresentation(
    scene = scene,
    focusPresentation = focusPresentation,
    preserveUserViewport = userHasScrolled,
  )
  if (visibleLines.isEmpty()) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No lyric lines to show", color = Color.White.copy(alpha = 0.72f))
    }
    return
  }

  val activeText = scene.lines
    .getOrNull(scene.anchorRenderIndex ?: -1)
    ?.accessibilityText
    .orEmpty()
  val textMeasurer = rememberIcyTextMeasurer(cacheSize = 48)
  val hitRegions = remember(document, focusPresentation) { LyricHitRegions() }
  val currentOnSeek = rememberUpdatedState(onSeek)
  val layoutCache = remember(document, focusPresentation) { LyricLayoutCache() }
  val scrollAnchorIndex = scene.playbackAnchorIndex()
  val scrollAnchor = remember(document) { Animatable(scrollAnchorIndex.toFloat()) }
  val currentScrollAnchorIndex = rememberUpdatedState(scrollAnchorIndex)
  var previousRawPositionMs by remember(document) { mutableLongStateOf(rawPositionMs) }
  val drasticPositionChange = abs(rawPositionMs - previousRawPositionMs) > 1_000L
  SideEffect { previousRawPositionMs = rawPositionMs }
  LaunchedEffect(focusPresentation) {
    if (focusPresentation) {
      isUserDragging = false
      userHasScrolled = false
      userScrollOffsetPx = 0f
    }
  }
  LaunchedEffect(scrollAnchorIndex, focusPresentation, reducedMotion, userHasScrolled) {
    if (userHasScrolled && !focusPresentation) return@LaunchedEffect
    val target = scrollAnchorIndex.toFloat()
    if (focusPresentation || reducedMotion || drasticPositionChange) {
      scrollAnchor.snapTo(target)
    } else {
      scrollAnchor.animateTo(
        targetValue = target,
        animationSpec = tween(durationMillis = 800, easing = DesktopScrollEasing),
      )
    }
  }
  LaunchedEffect(focusPresentation, userHasScrolled, isUserDragging, reducedMotion) {
    if (!shouldScheduleLyricsAutoFollow(userHasScrolled, isUserDragging, true, focusPresentation)) {
      return@LaunchedEffect
    }
    scrollViewport.activeLineVisible.collectLatest { activeLineVisible ->
      if (!shouldScheduleLyricsAutoFollow(userHasScrolled, isUserDragging, activeLineVisible, focusPresentation)) {
        return@collectLatest
      }
      delay(AUTO_FOLLOW_DELAY_MS)
      val targetOffset = scrollViewport.recenterOffsetPx
      if (reducedMotion) {
        userScrollOffsetPx = targetOffset
      } else {
        animate(
          initialValue = userScrollOffsetPx,
          targetValue = targetOffset,
          animationSpec = tween(durationMillis = AUTO_FOLLOW_RECENTER_MS, easing = DesktopScrollEasing),
        ) { value, _ ->
          userScrollOffsetPx = value
        }
      }
      scrollAnchor.snapTo(currentScrollAnchorIndex.value.toFloat())
      userScrollOffsetPx = 0f
      userHasScrolled = false
    }
  }
  val dragState = rememberDraggableState { delta ->
    userScrollOffsetPx = scrollViewport.clampOffset(userScrollOffsetPx + delta)
  }

  Canvas(
    modifier = modifier
      .fillMaxSize()
      .padding(
        top = lyricsViewportPadding(focusPresentation).topDp.dp,
        bottom = lyricsViewportPadding(focusPresentation).bottomDp.dp,
      )
      // Keep large glyphs and spring glow inside the lyric viewport; this is
      // intentionally inside padding so portrait lyrics cannot paint over transport.
      .clipToBounds()
      .semantics {
        contentDescription = activeText
        liveRegion = LiveRegionMode.Polite
      }
      .draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        enabled = !focusPresentation,
        onDragStarted = {
          isUserDragging = true
          userHasScrolled = true
        },
        onDragStopped = {
          isUserDragging = false
        },
      )
      .pointerInput(document, focusPresentation) {
        detectTapGestures { offset ->
          hitRegions.startMsAt(offset)?.let(currentOnSeek.value)
        }
      },
  ) {
    val measuredHitRegions = mutableListOf<LyricHitRegion>()
    val viewportWidthDp = size.width / density
    val normalHeights = if (focusPresentation) null else FloatArray(scene.lines.size)
    val normalCenters = if (focusPresentation) {
      null
    } else {
      FloatArray(scene.lines.size).also { centers ->
        var cursor = 0f
        scene.lines.forEachIndexed { index, line ->
          val participatesInLayout = line.kind != LyricSceneLineKind.INTERLUDE ||
            line.status == TimedElementStatus.ACTIVE
          if (!participatesInLayout) {
            centers[index] = cursor
            return@forEachIndexed
          }
          val baseFontSize = line.baseFontSize(focusPresentation = false, viewportWidthDp)
          val inset = size.width * line.horizontalInsetFraction(focusPresentation = false)
          val maxWidth = (size.width - inset * 2f).toInt().coerceAtLeast(1)
          val layout = layoutCache.measure(
            line,
            baseFontSize,
            maxWidth,
            line.composeTextAlign(),
            textMeasurer,
          )
          centers[index] = cursor + layout.size.height / 2f
          normalHeights?.set(index, layout.size.height.toFloat())
          cursor += layout.size.height + line.verticalSpacingPx(document.syncKind, size.width)
        }
      }
    }
    val focusLayouts = if (focusPresentation) {
      visibleLines.associate { line ->
        line.renderIndex to measureCanvasLine(
          line = line,
          focusPresentation = true,
          viewportWidthPx = size.width,
          viewportWidthDp = viewportWidthDp,
          textMeasurer = textMeasurer,
          layoutCache = layoutCache,
        )
      }
    } else {
      emptyMap()
    }
    val focusPlacements = if (focusPresentation) {
      dynamicFocusLinePlacements(
        metrics = visibleLines.mapNotNull { line ->
          focusLayouts[line.renderIndex]?.let { layout ->
            FocusLineLayoutMetric(
              renderIndex = line.renderIndex,
              groupIndex = line.groupIndex,
              kind = line.kind,
              focusRole = line.focusRole,
              desiredCenterY = size.height / 2f + line.focusTransform.yViewportFraction.toFloat() * size.height,
              visualHeight = layout.measured.size.height * layout.lineScale,
              transitionRole = line.transitionRole,
            )
          }
        },
        viewportHeight = size.height,
        safeInset = 8.dp.toPx(),
        lineGap = 16.dp.toPx(),
        backgroundGap = 8.dp.toPx(),
      )
    } else {
      null
    }
    val focusCenters = focusPlacements?.mapValues { it.value.centerY }

    if (normalCenters != null && normalHeights != null && normalCenters.isNotEmpty()) {
      val layoutAnchorCenter = interpolatedLineCenter(normalCenters, scrollAnchor.value)
      val playbackAnchor = scene.playbackAnchorIndex().coerceIn(normalCenters.indices)
      val playbackCenter = normalCenters[playbackAnchor]
      val playbackHalfHeight = normalHeights[playbackAnchor] / 2f
      val playbackCenterY = size.height / 2f + playbackCenter - layoutAnchorCenter + userScrollOffsetPx
      val firstIndex = normalHeights.indexOfFirst { it > 0f }.takeIf { it >= 0 } ?: 0
      val lastIndex = normalHeights.indexOfLast { it > 0f }.takeIf { it >= 0 } ?: normalHeights.lastIndex
      scrollViewport.updateGeometry(
        minimumOffsetPx = layoutAnchorCenter - normalCenters[lastIndex],
        maximumOffsetPx = layoutAnchorCenter - normalCenters[firstIndex],
        recenterOffsetPx = layoutAnchorCenter - playbackCenter,
        activeLineVisible = hasMinimumVisibleOverlap(
          centerY = playbackCenterY,
          contentHeight = playbackHalfHeight * 2f,
          viewportHeight = size.height,
          minimumOverlap = 5.dp.toPx(),
        ),
      )
    }

    visibleLines.forEach { line ->
      val lineCenterY = lineCenterY(
        line = line,
        scene = scene,
        height = size.height,
        focusPresentation = focusPresentation,
        normalCenters = normalCenters,
        animatedAnchorIndex = scrollAnchor.value,
        focusCenters = focusCenters,
        userScrollOffsetPx = userScrollOffsetPx,
      )
      if (!isInsideLyricRenderWindow(lineCenterY, size.height)) return@forEach

      val lineLayout = focusLayouts[line.renderIndex] ?: measureCanvasLine(
        line = line,
        focusPresentation = false,
        viewportWidthPx = size.width,
        viewportWidthDp = viewportWidthDp,
        textMeasurer = textMeasurer,
        layoutCache = layoutCache,
      )
      val baseFontSize = lineLayout.baseFontSize
      val lineOpacity = lineLayout.lineOpacity
      if (lineOpacity <= 0.001f) return@forEach

      val horizontalInset = lineLayout.horizontalInset
      val maxTextWidth = lineLayout.maxTextWidth
      val measured = lineLayout.measured
      val x = when (line.horizontalAlignment) {
        LyricHorizontalAlignment.START -> horizontalInset
        LyricHorizontalAlignment.CENTER -> center.x - measured.size.width / 2f
        LyricHorizontalAlignment.END -> size.width - horizontalInset - measured.size.width
      }
      val topLeft = Offset(x, lineCenterY - measured.size.height / 2f)
      val lineScale = lineLayout.lineScale *
        (focusPlacements?.get(line.renderIndex)?.fitScale ?: 1f)
      withTransform({
        scale(lineScale, lineScale, pivot = Offset(x + measured.size.width / 2f, lineCenterY))
      }) {
        if (line.tokens.isEmpty()) {
          drawText(measured, topLeft = topLeft)
        } else {
          drawFixedLayoutTokens(
            line = line,
            layout = measured,
            topLeft = topLeft,
            baseFontSize = baseFontSize,
            lineOpacity = lineOpacity,
            maxTextWidth = maxTextWidth,
            textMeasurer = textMeasurer,
          )
        }
      }
      line.startMs?.takeIf { line.kind != LyricSceneLineKind.INTERLUDE }?.let { startMs ->
        val centerX = x + measured.size.width / 2f
        measuredHitRegions += LyricHitRegion(
          bounds = lyricHitBounds(
            centerX = centerX,
            centerY = lineCenterY,
            contentWidth = measured.size.width.toFloat(),
            contentHeight = measured.size.height.toFloat(),
            scale = lineScale,
            minimumTargetSize = 48.dp.toPx(),
            horizontalPadding = 12.dp.toPx(),
            viewportWidth = size.width,
          ),
          startMs = startMs,
        )
      }
    }
    hitRegions.replace(nonOverlappingLyricHitRegions(measuredHitRegions))
  }
}

/**
 * Mirrors the desktop virtualizer: normal synced lyrics keep a small mounted
 * window around the playback anchor, while focus presentation trusts the
 * scene engine's Previous / Current / Next role assignments.
 */
internal fun visibleLyricsForPresentation(
  scene: LyricsScene,
  focusPresentation: Boolean,
  preserveUserViewport: Boolean = false,
): List<LyricLineScene> {
  val presentationVisible = scene.lines.filter { line ->
    line.visible && !line.preHidden &&
      (focusPresentation || line.kind != LyricSceneLineKind.INTERLUDE ||
        line.status == TimedElementStatus.ACTIVE)
  }
  if (focusPresentation || preserveUserViewport || presentationVisible.size <= NORMAL_RENDER_WINDOW_SIZE) {
    return presentationVisible
  }
  val anchor = scene.playbackAnchorIndex()
  val window = (anchor - NORMAL_RENDER_WINDOW_RADIUS)..(anchor + NORMAL_RENDER_WINDOW_RADIUS)
  return presentationVisible.filter { it.renderIndex in window }
}

internal fun LyricsScene.playbackAnchorIndex(): Int = anchorRenderIndex
  ?: lines.indexOfLast { it.status == TimedElementStatus.SUNG }.takeIf { it >= 0 }
  ?: 0

internal fun isInsideLyricRenderWindow(centerY: Float, viewportHeight: Float): Boolean =
  viewportHeight > 0f && centerY in -viewportHeight * 0.25f..viewportHeight * 1.25f

internal data class LyricsViewportPadding(val topDp: Int, val bottomDp: Int)

internal fun lyricsViewportPadding(focusPresentation: Boolean): LyricsViewportPadding =
  if (focusPresentation) LyricsViewportPadding(topDp = 12, bottomDp = 12)
  else LyricsViewportPadding(topDp = 16, bottomDp = 8)

private data class CanvasLineLayout(
  val baseFontSize: TextUnit,
  val lineOpacity: Float,
  val lineScale: Float,
  val horizontalInset: Float,
  val maxTextWidth: Int,
  val measured: TextLayoutResult,
)

private fun measureCanvasLine(
  line: LyricLineScene,
  focusPresentation: Boolean,
  viewportWidthPx: Float,
  viewportWidthDp: Float,
  textMeasurer: IcyTextMeasurer,
  layoutCache: LyricLayoutCache,
): CanvasLineLayout {
  val transform = line.focusTransform
  val fontScale = if (focusPresentation) transform.fontScale.toFloat() else 1f
  val baseFontSize = line.baseFontSize(focusPresentation, viewportWidthDp) * fontScale
  val lineOpacity = if (focusPresentation) transform.opacity.toFloat() else line.contentOpacity.toFloat()
  val lineScale = if (focusPresentation) transform.scale.toFloat() else 1f
  val horizontalInset = viewportWidthPx * line.horizontalInsetFraction(focusPresentation)
  val maxTextWidth = (viewportWidthPx - horizontalInset * 2f).toInt().coerceAtLeast(1)
  val align = line.composeTextAlign()
  val measured = if (line.tokens.isEmpty()) {
    textMeasurer.measure(
      text = line.toLineAnnotatedString(baseFontSize, lineOpacity),
      style = TextStyle(
        color = Color.White.copy(alpha = lineOpacity),
        fontSize = baseFontSize,
        lineHeight = baseFontSize * DESKTOP_LINE_HEIGHT,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        textDirection = line.composeTextDirection(),
        shadow = Shadow(
          color = Color.White.copy(alpha = (line.lineGlow * 0.25 * lineOpacity).toFloat()),
          offset = Offset.Zero,
          blurRadius = (line.lineGlow * 14 + line.blurRadiusPx).toFloat(),
        ),
      ),
      constraints = Constraints(maxWidth = maxTextWidth),
    )
  } else {
    layoutCache.measure(line, baseFontSize, maxTextWidth, align, textMeasurer)
  }
  return CanvasLineLayout(
    baseFontSize = baseFontSize,
    lineOpacity = lineOpacity,
    lineScale = lineScale,
    horizontalInset = horizontalInset,
    maxTextWidth = maxTextWidth,
    measured = measured,
  )
}

@Composable
private fun rememberAnimatedLyricsScene(
  document: LyricsDocument,
  target: LyricsScene,
  reducedMotion: Boolean,
): LyricsScene {
  val animator = remember(document) { LyricsSceneSpringAnimator() }
  val latestTarget = rememberUpdatedState(target)
  val wakeups = remember(document) { Channel<Unit>(capacity = Channel.CONFLATED) }
  var animated by remember(document) { mutableStateOf(target) }
  SideEffect { wakeups.trySend(Unit) }
  LaunchedEffect(animator, reducedMotion) {
    while (isActive) {
      wakeups.receive()
      do {
        withFrameNanos { frameTimeNanos ->
          animated = animator.animate(
            scene = latestTarget.value,
            frameTimeNanos = frameTimeNanos,
            snap = reducedMotion,
          )
        }
      } while (animator.needsFrames && isActive)
    }
  }
  return animated
}

@Composable
private fun StaticLyricsList(document: StaticLyrics, modifier: Modifier) {
  LazyColumn(
    modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    items(document.lines) { line ->
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = line.text,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
          color = Color.White.copy(alpha = 0.9f),
        )
        line.transliteratedText?.let {
          Text(it, color = Color.White.copy(alpha = 0.58f), textAlign = TextAlign.Center)
        }
      }
    }
  }
}

private fun lineCenterY(
  line: LyricLineScene,
  scene: LyricsScene,
  height: Float,
  focusPresentation: Boolean,
  normalCenters: FloatArray?,
  animatedAnchorIndex: Float,
  focusCenters: Map<Int, Float>?,
  userScrollOffsetPx: Float,
): Float {
  if (focusPresentation) {
    return focusCenters?.get(line.renderIndex)
      ?: height / 2f + line.focusTransform.yViewportFraction.toFloat() * height
  }
  if (normalCenters == null || normalCenters.isEmpty()) return height / 2f
  val lineCenter = normalCenters.getOrElse(line.renderIndex) { 0f }
  val anchorCenter = interpolatedLineCenter(normalCenters, animatedAnchorIndex)
  return height / 2f + lineCenter - anchorCenter + userScrollOffsetPx
}

internal fun interpolatedLineCenter(centers: FloatArray, anchorIndex: Float): Float {
  if (centers.isEmpty()) return 0f
  val clampedAnchor = anchorIndex.coerceIn(0f, centers.lastIndex.toFloat())
  val lower = floor(clampedAnchor).toInt()
  val upper = ceil(clampedAnchor).toInt().coerceAtMost(centers.lastIndex)
  val fraction = clampedAnchor - lower
  return centers[lower] + (centers[upper] - centers[lower]) * fraction
}

private fun LyricLineScene.baseFontSize(
  focusPresentation: Boolean,
  viewportWidthDp: Float,
): TextUnit = when (kind) {
  // Desktop focus uses one responsive base size and scales secondary vocals to
  // 0.62em. Giving background rows the same base avoids applying that factor twice.
  LyricSceneLineKind.BACKGROUND -> if (focusPresentation) responsiveFocusFontSp(viewportWidthDp).sp else 18.sp
  LyricSceneLineKind.INTERLUDE -> if (focusPresentation) {
    responsiveFocusInterludeFontSp(viewportWidthDp).sp
  } else {
    28.sp
  }
  else -> if (focusPresentation) responsiveFocusFontSp(viewportWidthDp).sp else 25.sp
}

internal fun responsiveFocusFontSp(viewportWidthDp: Float): Float =
  (viewportWidthDp * 0.062f).coerceIn(44.8f, 124.8f)

internal fun responsiveFocusInterludeFontSp(viewportWidthDp: Float): Float =
  responsiveFocusFontSp(viewportWidthDp) * 1.3f * 1.15f

private fun LyricLineScene.horizontalInsetFraction(focusPresentation: Boolean): Float =
  if (focusPresentation) FOCUS_HORIZONTAL_INSET_FRACTION else laneInsetFraction.toFloat()

internal data class FocusLineLayoutMetric(
  val renderIndex: Int,
  val groupIndex: Int?,
  val kind: LyricSceneLineKind,
  val desiredCenterY: Float,
  val visualHeight: Float,
  val focusRole: FocusRole = FocusRole.NONE,
  val transitionRole: FocusTransitionRole = FocusTransitionRole.NONE,
)

internal data class FocusLinePlacement(
  val centerY: Float,
  /** Additional scale applied after the desktop role transform. */
  val fitScale: Float,
)

private data class FittedFocusLine(
  val metric: FocusLineLayoutMetric,
  val roleScale: Float,
) {
  val visualHeight: Float = metric.visualHeight.coerceAtLeast(0f) * roleScale
}

private data class FocusLayoutBlock(
  val primary: FittedFocusLine,
  val backgrounds: List<FittedFocusLine>,
  val backgroundGap: Float,
) {
  val topExtent: Float = primary.visualHeight / 2f
  val bottomExtent: Float
    get() {
      if (backgrounds.isEmpty()) return primary.visualHeight / 2f
      return primary.visualHeight / 2f + attachedBackgroundHeight
    }

  private val attachedBackgroundHeight: Float
    get() = backgrounds.sumOf { it.visualHeight.toDouble() }.toFloat() +
      backgroundGap.coerceAtLeast(0f) +
      (backgrounds.size - 1).coerceAtLeast(0) * backgroundGap.coerceAtLeast(0f) / 2f
}

/**
 * Keeps the desktop Previous / Current / Next targets when they fit, then
 * resolves collisions caused by wrapped lines. Background rows are laid out as
 * part of their foreground block so they always begin below its measured bottom.
 */
internal fun dynamicFocusLineCenters(
  metrics: List<FocusLineLayoutMetric>,
  viewportHeight: Float,
  safeInset: Float,
  lineGap: Float,
  backgroundGap: Float,
): Map<Int, Float> = dynamicFocusLinePlacements(
  metrics = metrics,
  viewportHeight = viewportHeight,
  safeInset = safeInset,
  lineGap = lineGap,
  backgroundGap = backgroundGap,
).mapValues { it.value.centerY }

/**
 * Returns both the collision-free center and any extra scale required to keep
 * the complete wrapped text inside the focus stage. Desktop caps Previous,
 * Current, Next and background rows at roughly 24/44/24/18% of the viewport;
 * scaling rather than clipping preserves every lyric word on smaller screens.
 */
internal fun dynamicFocusLinePlacements(
  metrics: List<FocusLineLayoutMetric>,
  viewportHeight: Float,
  safeInset: Float,
  lineGap: Float,
  backgroundGap: Float,
): Map<Int, FocusLinePlacement> {
  if (metrics.isEmpty()) return emptyMap()
  val safeTop = safeInset.coerceAtLeast(0f)
  val safeBottom = (viewportHeight - safeInset.coerceAtLeast(0f)).coerceAtLeast(safeTop)
  val availableHeight = (safeBottom - safeTop).coerceAtLeast(0f)
  val fittedLines = metrics.associate { metric ->
    val height = metric.visualHeight.coerceAtLeast(0f)
    val cap = focusRoleHeightCapFraction(metric, viewportHeight) * viewportHeight.coerceAtLeast(0f)
    val roleScale = if (height > 0f && cap.isFinite()) minOf(1f, cap / height) else 1f
    metric.renderIndex to FittedFocusLine(metric, roleScale.coerceIn(0f, 1f))
  }
  val backgroundByGroup = fittedLines.values
    .filter { it.metric.kind == LyricSceneLineKind.BACKGROUND && it.metric.groupIndex != null }
    .groupBy { it.metric.groupIndex }
  val attachedBackgrounds = mutableSetOf<Int>()
  val primaryBlocks = fittedLines.values
    .filter { it.metric.kind != LyricSceneLineKind.BACKGROUND }
    .map { primary ->
      val backgrounds = if (primary.metric.kind == LyricSceneLineKind.VOCAL) {
        backgroundByGroup[primary.metric.groupIndex].orEmpty().sortedBy { it.metric.renderIndex }
      } else {
        emptyList()
      }
      attachedBackgrounds += backgrounds.map { it.metric.renderIndex }
      FocusLayoutBlock(primary, backgrounds, backgroundGap)
    }
  val orphanBackgroundBlocks = fittedLines.values
    .filter {
      it.metric.kind == LyricSceneLineKind.BACKGROUND &&
        it.metric.renderIndex !in attachedBackgrounds
    }
    .map { FocusLayoutBlock(it, emptyList(), backgroundGap) }
  val blocks = (primaryBlocks + orphanBackgroundBlocks)
    .sortedWith(
      compareBy<FocusLayoutBlock> { it.primary.metric.desiredCenterY }
        .thenBy { it.primary.metric.renderIndex },
    )
  if (blocks.isEmpty()) {
    return emptyMap()
  }

  // The desktop conveyor deliberately lets the departing Previous and the
  // entering Next travel through the focus-stage clip. Including those two
  // transient edge rows in the collision stack clamps them back on-screen and
  // changes the global fit scale for the other rows, which produces a visible
  // bunch-and-snap at both ends of the 450 ms handoff. Keep the stable middle
  // rows collision-safe while the edge rows follow their frame-clock targets.
  val (edgeTransitionBlocks, constrainedBlocks) = blocks.partition { block ->
    block.primary.metric.transitionRole == FocusTransitionRole.DEPARTING ||
      block.primary.metric.transitionRole == FocusTransitionRole.ENTERING
  }

  val requestedLineGap = lineGap.coerceAtLeast(0f)
  val requestedBackgroundGap = backgroundGap.coerceAtLeast(0f)
  val requestedHeight = constrainedBlocks.sumOf { (it.topExtent + it.bottomExtent).toDouble() }.toFloat() +
    (constrainedBlocks.size - 1).coerceAtLeast(0) * requestedLineGap
  val globalScale = if (requestedHeight > availableHeight && requestedHeight > 0f) {
    (availableHeight / requestedHeight).coerceIn(0f, 1f)
  } else {
    1f
  }
  val effectiveLineGap = requestedLineGap * globalScale
  val effectiveBackgroundGap = requestedBackgroundGap * globalScale
  fun topExtent(block: FocusLayoutBlock) = block.topExtent * globalScale
  fun bottomExtent(block: FocusLayoutBlock): Float {
    val primaryHalf = block.primary.visualHeight * globalScale / 2f
    if (block.backgrounds.isEmpty()) return primaryHalf
    val attachedHeight = block.backgrounds.sumOf { (it.visualHeight * globalScale).toDouble() }.toFloat() +
      effectiveBackgroundGap +
      (block.backgrounds.size - 1).coerceAtLeast(0) * effectiveBackgroundGap / 2f
    return primaryHalf + attachedHeight
  }

  val blockCenters = FloatArray(constrainedBlocks.size) { index ->
    constrainedBlocks[index].primary.metric.desiredCenterY
  }
  if (constrainedBlocks.isNotEmpty()) {
    // Animated desktop targets are retained whenever they are already safe. If
    // they collide or leave the stage, project them onto the nearest safe stack.
    blockCenters[0] = maxOf(blockCenters[0], safeTop + topExtent(constrainedBlocks[0]))
    for (index in 1 until constrainedBlocks.size) {
      val minimum = blockCenters[index - 1] + bottomExtent(constrainedBlocks[index - 1]) +
        effectiveLineGap + topExtent(constrainedBlocks[index])
      blockCenters[index] = maxOf(blockCenters[index], minimum)
    }
    val last = constrainedBlocks.lastIndex
    if (blockCenters[last] + bottomExtent(constrainedBlocks[last]) > safeBottom) {
      blockCenters[last] = safeBottom - bottomExtent(constrainedBlocks[last])
      for (index in last - 1 downTo 0) {
        val maximum = blockCenters[index + 1] - topExtent(constrainedBlocks[index + 1]) -
          effectiveLineGap - bottomExtent(constrainedBlocks[index])
        blockCenters[index] = minOf(blockCenters[index], maximum)
      }
    }
  }

  val result = mutableMapOf<Int, FocusLinePlacement>()
  fun placeBlock(block: FocusLayoutBlock, primaryCenter: Float, blockScale: Float) {
    result[block.primary.metric.renderIndex] = FocusLinePlacement(
      centerY = primaryCenter,
      fitScale = block.primary.roleScale * blockScale,
    )
    var cursor = primaryCenter + block.primary.visualHeight * blockScale / 2f
    block.backgrounds.forEachIndexed { backgroundIndex, background ->
      val scaledBackgroundGap = requestedBackgroundGap * blockScale
      cursor += if (backgroundIndex == 0) scaledBackgroundGap else scaledBackgroundGap / 2f
      val visualHeight = background.visualHeight * blockScale
      val center = cursor + visualHeight / 2f
      result[background.metric.renderIndex] = FocusLinePlacement(
        centerY = center,
        fitScale = background.roleScale * blockScale,
      )
      cursor += visualHeight
    }
  }
  constrainedBlocks.forEachIndexed { index, block ->
    placeBlock(block, blockCenters[index], globalScale)
  }
  edgeTransitionBlocks.forEach { block ->
    placeBlock(block, block.primary.metric.desiredCenterY, 1f)
  }
  return result
}

private fun focusRoleHeightCapFraction(
  metric: FocusLineLayoutMetric,
  viewportHeight: Float,
): Float = when (metric.kind) {
  LyricSceneLineKind.BACKGROUND -> 0.18f
  LyricSceneLineKind.INTERLUDE -> 0.20f
  LyricSceneLineKind.STATIC -> 1f
  LyricSceneLineKind.VOCAL -> if (
    viewportHeight > 0f && (
      metric.transitionRole == FocusTransitionRole.OUTGOING ||
        metric.transitionRole == FocusTransitionRole.INCOMING
      )
  ) {
    // During a handoff the semantic role already names the destination, while
    // the row is still travelling from its source slot. Derive the height cap
    // from that continuously moving slot so a wrapped Current does not snap to
    // the smaller Previous cap on the first transition frame (and vice versa).
    val yViewportFraction = abs(metric.desiredCenterY / viewportHeight - 0.5f)
    val secondaryProgress = (
      (yViewportFraction - FOCUS_CURRENT_Y_FRACTION) /
        (FOCUS_SECONDARY_Y_FRACTION - FOCUS_CURRENT_Y_FRACTION)
      ).coerceIn(0f, 1f)
    FOCUS_CURRENT_HEIGHT_CAP_FRACTION +
      (FOCUS_SECONDARY_HEIGHT_CAP_FRACTION - FOCUS_CURRENT_HEIGHT_CAP_FRACTION) * secondaryProgress
  } else when (metric.focusRole) {
    FocusRole.PREVIOUS, FocusRole.NEXT -> FOCUS_SECONDARY_HEIGHT_CAP_FRACTION
    FocusRole.CURRENT -> FOCUS_CURRENT_HEIGHT_CAP_FRACTION
    FocusRole.NONE -> 1f
  }
}

internal fun hasMinimumVisibleOverlap(
  centerY: Float,
  contentHeight: Float,
  viewportHeight: Float,
  minimumOverlap: Float,
): Boolean {
  if (viewportHeight <= 0f || contentHeight <= 0f) return false
  val halfHeight = contentHeight / 2f
  val overlap = minOf(centerY + halfHeight, viewportHeight) - maxOf(centerY - halfHeight, 0f)
  return overlap >= minimumOverlap.coerceAtLeast(0f) && overlap > 0f
}

internal class TimedLyricsScrollViewportState {
  val activeLineVisible = MutableStateFlow(false)
  var recenterOffsetPx: Float = 0f
    private set
  private var minimumOffsetPx: Float = 0f
  private var maximumOffsetPx: Float = 0f

  fun updateGeometry(
    minimumOffsetPx: Float,
    maximumOffsetPx: Float,
    recenterOffsetPx: Float,
    activeLineVisible: Boolean,
  ) {
    this.minimumOffsetPx = minOf(minimumOffsetPx, maximumOffsetPx)
    this.maximumOffsetPx = maxOf(minimumOffsetPx, maximumOffsetPx)
    this.recenterOffsetPx = recenterOffsetPx.coerceIn(this.minimumOffsetPx, this.maximumOffsetPx)
    this.activeLineVisible.value = activeLineVisible
  }

  fun clampOffset(offsetPx: Float): Float = offsetPx.coerceIn(minimumOffsetPx, maximumOffsetPx)
}

internal fun shouldScheduleLyricsAutoFollow(
  userHasScrolled: Boolean,
  isUserDragging: Boolean,
  activeLineVisible: Boolean,
  focusPresentation: Boolean,
): Boolean = userHasScrolled && !isUserDragging && activeLineVisible && !focusPresentation

private fun LyricLineScene.verticalSpacingPx(syncKind: LyricsSyncKind, viewportWidth: Float): Float =
  when (kind) {
    LyricSceneLineKind.BACKGROUND -> viewportWidth * 0.005f
    LyricSceneLineKind.INTERLUDE -> viewportWidth * 0.01f
    else -> viewportWidth * if (syncKind == LyricsSyncKind.LINE) 0.04f else 0.02f
  }

private fun LyricLineScene.composeTextDirection(): TextDirection =
  if (textDirection == LyricTextDirection.RIGHT_TO_LEFT) TextDirection.Rtl else TextDirection.Ltr

private fun LyricLineScene.composeTextAlign(): TextAlign = when (horizontalAlignment) {
  LyricHorizontalAlignment.START -> TextAlign.Start
  LyricHorizontalAlignment.CENTER -> TextAlign.Center
  LyricHorizontalAlignment.END -> TextAlign.End
}

private class LyricLayoutCache {
  private val layouts = mutableMapOf<LayoutKey, TextLayoutResult>()

  fun measure(
    line: LyricLineScene,
    baseFontSize: TextUnit,
    maxWidth: Int,
    textAlign: TextAlign,
    textMeasurer: IcyTextMeasurer,
  ): TextLayoutResult {
    val key = LayoutKey(
      lineKey = line.key,
      maxWidth = maxWidth,
      fontSize = baseFontSize.value,
      direction = line.textDirection,
      alignment = line.horizontalAlignment,
    )
    return layouts.getOrPut(key) {
      textMeasurer.measure(
        text = line.toLayoutAnnotatedString(baseFontSize),
        style = TextStyle(
          fontSize = baseFontSize,
          lineHeight = baseFontSize * DESKTOP_LINE_HEIGHT,
          fontWeight = FontWeight.Bold,
          textDirection = line.composeTextDirection(),
          textAlign = textAlign,
        ),
        constraints = Constraints(maxWidth = maxWidth),
      )
    }
  }

  private data class LayoutKey(
    val lineKey: String,
    val maxWidth: Int,
    val fontSize: Float,
    val direction: LyricTextDirection,
    val alignment: LyricHorizontalAlignment,
  )
}

private fun LyricLineScene.toLayoutAnnotatedString(baseFontSize: TextUnit): AnnotatedString =
  buildAnnotatedString {
    append(text)
    transliteratedText?.takeIf(String::isNotBlank)?.let { transliteration ->
      append('\n')
      withStyle(
        SpanStyle(
          fontSize = baseFontSize * 0.62f,
          fontWeight = FontWeight.Medium,
        ),
      ) { append(transliteration) }
    }
  }

private val LyricLineScene.accessibilityText: String
  get() = if (kind == LyricSceneLineKind.INTERLUDE) {
    "Instrumental break"
  } else {
    listOfNotNull(text.takeIf(String::isNotBlank), transliteratedText?.takeIf(String::isNotBlank))
      .joinToString(". ")
  }

internal fun lyricHitBounds(
  centerX: Float,
  centerY: Float,
  contentWidth: Float,
  contentHeight: Float,
  scale: Float,
  minimumTargetSize: Float,
  horizontalPadding: Float,
  viewportWidth: Float,
): Rect {
  val safeScale = abs(scale)
  val halfWidth = maxOf(contentWidth * safeScale / 2f + horizontalPadding, minimumTargetSize / 2f)
  val halfHeight = maxOf(contentHeight * safeScale / 2f, minimumTargetSize / 2f)
  return Rect(
    left = (centerX - halfWidth).coerceAtLeast(0f),
    top = centerY - halfHeight,
    right = (centerX + halfWidth).coerceAtMost(viewportWidth.coerceAtLeast(0f)),
    bottom = centerY + halfHeight,
  )
}

internal data class LyricHitRegion(
  val bounds: Rect,
  val startMs: Long,
)

/**
 * Adjacent 48dp touch targets can overlap even when their glyphs do not. Split
 * that overlap at the midpoint between row centers so every rendered row owns
 * one deterministic vertical tap band without widening its measured text area.
 */
internal fun nonOverlappingLyricHitRegions(regions: List<LyricHitRegion>): List<LyricHitRegion> {
  if (regions.size < 2) return regions
  return regions.map { region ->
    val center = (region.bounds.top + region.bounds.bottom) / 2f
    val horizontallyOverlapping = regions.filter { candidate ->
      candidate !== region &&
        candidate.bounds.left < region.bounds.right &&
        candidate.bounds.right > region.bounds.left
    }
    val previousCenter = horizontallyOverlapping
      .map { (it.bounds.top + it.bounds.bottom) / 2f }
      .filter { it < center }
      .maxOrNull()
    val nextCenter = horizontallyOverlapping
      .map { (it.bounds.top + it.bounds.bottom) / 2f }
      .filter { it > center }
      .minOrNull()
    val top = previousCenter?.let { maxOf(region.bounds.top, (it + center) / 2f) } ?: region.bounds.top
    val bottom = nextCenter?.let { minOf(region.bounds.bottom, (center + it) / 2f) } ?: region.bounds.bottom
    region.copy(bounds = Rect(region.bounds.left, top, region.bounds.right, maxOf(top, bottom)))
  }
}

/** Draw and pointer dispatch both run on the UI thread; the snapshot avoids Compose writes in draw. */
internal class LyricHitRegions {
  private var regions: List<LyricHitRegion> = emptyList()

  fun replace(value: List<LyricHitRegion>) {
    regions = value
  }

  fun startMsAt(position: Offset): Long? = regions
    .asReversed()
    .firstOrNull { position in it.bounds }
    ?.startMs
}

private fun LyricLineScene.toLineAnnotatedString(
  baseFontSize: TextUnit,
  lineOpacity: Float,
): AnnotatedString =
  buildAnnotatedString {
    withStyle(
      SpanStyle(
        brush = progressBrush(gradient, lineOpacity),
        shadow = Shadow(
          Color.White.copy(alpha = (lineGlow * 0.2 * lineOpacity).toFloat()),
          Offset.Zero,
          (lineGlow * 12 + blurRadiusPx).toFloat(),
        ),
      ),
    ) { append(text) }
    transliteratedText?.takeIf(String::isNotBlank)?.let { transliteration ->
      append('\n')
      withStyle(
        SpanStyle(
          color = Color.White.copy(alpha = lineOpacity * 0.58f),
          fontSize = baseFontSize * 0.62f,
          fontWeight = FontWeight.Medium,
        ),
      ) { append(transliteration) }
    }
  }

private fun DrawScope.drawFixedLayoutTokens(
  line: LyricLineScene,
  layout: TextLayoutResult,
  topLeft: Offset,
  baseFontSize: TextUnit,
  lineOpacity: Float,
  maxTextWidth: Int,
  textMeasurer: IcyTextMeasurer,
) {
  line.tokenCharacterRanges().forEachIndexed { index, range ->
    val token = line.tokens[index]
    val animation = token.animation
    if (!animation.revealVisible || range.first >= layout.layoutInput.text.length) return@forEachIndexed
    val tokenOpacity = (
      lineOpacity * animation.opacity.toFloat() * animation.revealOpacity.toFloat()
      ).coerceIn(0f, 1f)
    if (tokenOpacity <= 0.001f) return@forEachIndexed
    val firstOffset = range.first.coerceIn(0, layout.layoutInput.text.length - 1)
    val tokenBounds = layout.boundsFor(range) ?: return@forEachIndexed
    val visualLine = layout.getLineForOffset(firstOffset)
    val tokenLayout = textMeasurer.measure(
      text = buildAnnotatedString {
        withStyle(
          SpanStyle(
            brush = progressBrush(token.gradient, tokenOpacity),
            shadow = Shadow(
              Color.White.copy(alpha = (animation.glow * 0.28 * tokenOpacity).toFloat()),
              Offset.Zero,
              (animation.glow * 15 + line.blurRadiusPx).toFloat(),
            ),
          ),
        ) { append(token.text) }
      },
      style = TextStyle(
        fontSize = baseFontSize,
        lineHeight = baseFontSize * DESKTOP_LINE_HEIGHT,
        fontWeight = FontWeight.Bold,
        textDirection = line.composeTextDirection(),
      ),
      constraints = Constraints(maxWidth = maxTextWidth),
      softWrap = false,
    )
    val tokenTopLeft = Offset(
      x = topLeft.x + tokenBounds.left,
      y = topLeft.y + layout.getLineTop(visualLine),
    )
    val pivot = Offset(
      tokenTopLeft.x + tokenLayout.size.width / 2f,
      tokenTopLeft.y + tokenLayout.size.height / 2f,
    )
    val tokenYOffsetPx = animation.yOffsetFontUnits.toFloat() * baseFontSize.toPx()
    withTransform({
      translate(0f, tokenYOffsetPx)
      scale(animation.scale.toFloat(), animation.scale.toFloat(), pivot)
    }) {
      if (token.letters.isEmpty()) {
        drawText(tokenLayout, topLeft = tokenTopLeft)
      } else {
        drawFixedLayoutLetters(
          line = line,
          tokenRange = range,
          layout = layout,
          topLeft = topLeft,
          baseFontSize = baseFontSize,
          tokenOpacity = tokenOpacity,
          maxTextWidth = maxTextWidth,
          textMeasurer = textMeasurer,
          token = token,
        )
      }
    }
  }

  line.transliteratedText?.takeIf(String::isNotBlank)?.let { transliteration ->
    val startOffset = (line.text.length + 1).coerceAtMost(layout.layoutInput.text.length - 1)
    val visualLine = layout.getLineForOffset(startOffset)
    val transliterationLayout = textMeasurer.measure(
      text = transliteration,
      style = TextStyle(
        color = Color.White.copy(alpha = lineOpacity * 0.58f),
        fontSize = baseFontSize * 0.62f,
        lineHeight = baseFontSize * DESKTOP_LINE_HEIGHT,
        fontWeight = FontWeight.Medium,
        textAlign = line.composeTextAlign(),
        textDirection = line.composeTextDirection(),
      ),
      constraints = Constraints(maxWidth = maxTextWidth),
    )
    drawText(
      transliterationLayout,
      topLeft = Offset(topLeft.x, topLeft.y + layout.getLineTop(visualLine)),
    )
  }
}

private fun DrawScope.drawFixedLayoutLetters(
  line: LyricLineScene,
  tokenRange: IntRange,
  layout: TextLayoutResult,
  topLeft: Offset,
  baseFontSize: TextUnit,
  tokenOpacity: Float,
  maxTextWidth: Int,
  textMeasurer: IcyTextMeasurer,
  token: com.icy.lyrics.core.lyrics.animation.LyricTokenScene,
) {
  val ranges = letterCharacterRanges(tokenRange.first, token.letters.map { it.text })
  token.letters.forEachIndexed { index, letter ->
    val range = ranges[index]
    if (range.first !in layout.layoutInput.text.indices || range.last > tokenRange.last) {
      return@forEachIndexed
    }
    val animation = letter.animation
    val letterOpacity = (
      tokenOpacity * animation.opacity.toFloat() * animation.revealOpacity.toFloat()
      ).coerceIn(0f, 1f)
    if (!animation.revealVisible || letterOpacity <= 0.001f) return@forEachIndexed
    val bounds = layout.boundsFor(range) ?: return@forEachIndexed
    val visualLine = layout.getLineForOffset(range.first)
    val letterLayout = textMeasurer.measure(
      text = buildAnnotatedString {
        withStyle(
          SpanStyle(
            brush = progressBrush(letter.gradient, letterOpacity),
            shadow = Shadow(
              Color.White.copy(
                alpha = (animation.glow * LETTER_GLOW_OPACITY_MULTIPLIER * letterOpacity)
                  .toFloat()
                  .coerceIn(0f, 1f),
              ),
              Offset.Zero,
              (LETTER_BASE_BLUR_PX + LETTER_GLOW_BLUR_PX * animation.glow + line.blurRadiusPx).toFloat(),
            ),
          ),
        ) { append(letter.text) }
      },
      style = TextStyle(
        fontSize = baseFontSize,
        lineHeight = baseFontSize * DESKTOP_LINE_HEIGHT,
        fontWeight = FontWeight.Bold,
        textDirection = line.composeTextDirection(),
      ),
      constraints = Constraints(maxWidth = maxTextWidth),
      softWrap = false,
    )
    val letterTopLeft = Offset(
      x = topLeft.x + bounds.left,
      y = topLeft.y + layout.getLineTop(visualLine),
    )
    val pivot = Offset(
      letterTopLeft.x + letterLayout.size.width / 2f,
      letterTopLeft.y + letterLayout.size.height / 2f,
    )
    val yOffsetPx = animation.yOffsetFontUnits.toFloat() * baseFontSize.toPx() * 2f
    withTransform({
      translate(0f, yOffsetPx)
      scale(animation.scale.toFloat(), animation.scale.toFloat(), pivot)
    }) {
      drawText(letterLayout, topLeft = letterTopLeft)
    }
  }
}

internal fun LyricLineScene.tokenCharacterRanges(): List<IntRange> {
  var cursor = 0
  return tokens.mapIndexed { index, token ->
    if (index > 0 && !tokens[index - 1].isPartOfWord) cursor += 1
    val start = cursor
    cursor += token.text.length
    start until cursor
  }
}

internal fun letterCharacterRanges(tokenStart: Int, letters: List<String>): List<IntRange> {
  var cursor = tokenStart
  return letters.map { letter ->
    val start = cursor
    cursor += letter.length
    start until cursor
  }
}

private fun TextLayoutResult.boundsFor(range: IntRange): Rect? {
  if (layoutInput.text.isEmpty() || range.isEmpty()) return null
  var left = Float.POSITIVE_INFINITY
  var top = Float.POSITIVE_INFINITY
  var right = Float.NEGATIVE_INFINITY
  var bottom = Float.NEGATIVE_INFINITY
  range.forEach { offset ->
    if (offset !in layoutInput.text.indices) return@forEach
    val box = getBoundingBox(offset)
    left = minOf(left, box.left)
    top = minOf(top, box.top)
    right = maxOf(right, box.right)
    bottom = maxOf(bottom, box.bottom)
  }
  return if (left.isFinite()) Rect(left, top, right, bottom) else null
}

private fun progressBrush(frame: LyricGradientFrame, opacity: Float): Brush {
  val start = frame.positionPercent
  val end = start + frame.transitionWidthPercent.coerceAtLeast(0.001)
  fun alphaAt(position: Double): Float {
    val value = when {
      position <= start -> frame.leadingAlpha
      position >= end -> frame.trailingAlpha
      else -> {
        val fraction = (position - start) / (end - start)
        frame.leadingAlpha + (frame.trailingAlpha - frame.leadingAlpha) * fraction
      }
    }
    return (value * opacity).toFloat().coerceIn(0f, 1f)
  }
  val positions = listOf(0.0, start, end, 100.0)
    .map { it.coerceIn(0.0, 100.0) }
    .distinct()
    .sorted()
  var stops = positions.map { position ->
    (position / 100.0).toFloat() to Color.White.copy(alpha = alphaAt(position))
  }
  if (frame.angleDegrees < 0.0) {
    stops = stops.map { (position, color) -> (1f - position) to color }.sortedBy { it.first }
  }
  val array = stops.toTypedArray()
  val vertical = abs(((frame.angleDegrees % 360.0) + 360.0) % 360.0 - 180.0) < 0.01
  return if (vertical) Brush.verticalGradient(colorStops = array)
  else Brush.horizontalGradient(colorStops = array)
}

/** Exact piecewise curve used by the desktop center-scroll helper. */
private val DesktopScrollEasing = Easing { progress ->
  when {
    progress < 0.4f -> 2.5f * progress * progress
    progress < 0.65f -> 0.7f + (progress - 0.4f) * 1.2f
    progress < 0.85f -> 1f + (progress - 0.65f) * 0.15f
    else -> 1.03f - (progress - 0.85f) * 0.2f
  }
}

private const val DESKTOP_LINE_HEIGHT = 1.1818181f
private const val LETTER_GLOW_OPACITY_MULTIPLIER = 1.85
private const val LETTER_BASE_BLUR_PX = 4.0
private const val LETTER_GLOW_BLUR_PX = 12.0
private const val NORMAL_RENDER_WINDOW_RADIUS = 6
private const val NORMAL_RENDER_WINDOW_SIZE = NORMAL_RENDER_WINDOW_RADIUS * 2 + 1
private const val AUTO_FOLLOW_DELAY_MS = 2_000L
private const val AUTO_FOLLOW_RECENTER_MS = 440
private const val FOCUS_CURRENT_Y_FRACTION = 0.035f
private const val FOCUS_SECONDARY_Y_FRACTION = 0.35f
private const val FOCUS_CURRENT_HEIGHT_CAP_FRACTION = 0.44f
private const val FOCUS_SECONDARY_HEIGHT_CAP_FRACTION = 0.24f
internal const val FOCUS_HORIZONTAL_INSET_FRACTION = 0.07f
