package com.icy.lyrics.core.lyrics.animation

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

@Serializable
enum class FullscreenView {
  ARTWORK_ONLY,
  ARTWORK_TITLES,
  MIXED,
  LYRICS,
}

fun FullscreenView.step(direction: Int): FullscreenView {
  require(direction == -1 || direction == 1) { "Direction must be -1 or 1" }
  val values = FullscreenView.entries
  return values[(ordinal + direction).coerceIn(values.indices)]
}

@Serializable
data class FullscreenViewNeighbours(
  val previous: FullscreenView?,
  val next: FullscreenView?,
)

fun FullscreenView.neighbours() = FullscreenViewNeighbours(
  previous = FullscreenView.entries.getOrNull(ordinal - 1),
  next = FullscreenView.entries.getOrNull(ordinal + 1),
)

@Serializable
enum class FocusRole {
  NONE,
  PREVIOUS,
  CURRENT,
  NEXT,
}

@Serializable
enum class FocusTransitionRole {
  NONE,
  DEPARTING,
  OUTGOING,
  INCOMING,
  ENTERING,
}

@Serializable
enum class FocusTransitionKind {
  LINE,
  ENTER_INTERLUDE,
  EXIT_INTERLUDE,
}

@Serializable
data class FocusTransform(
  /** Relative to the focus-stage viewport height (desktop cqh / 100). */
  val yViewportFraction: Double,
  val scale: Double,
  val opacity: Double,
  val fontScale: Double = 1.0,
)

/** Exact steady-state desktop focus geometry, including attached BG rows. */
fun focusTransform(
  role: FocusRole,
  isBackground: Boolean = false,
  backgroundIndex: Int = 0,
  isInterlude: Boolean = false,
  reveal: Boolean = false,
): FocusTransform {
  if (role == FocusRole.NONE) return FocusTransform(0.0, 1.0, 0.0)
  var y = when (role) {
    FocusRole.PREVIOUS -> -0.35
    FocusRole.CURRENT -> if (isInterlude) 0.0 else -0.035
    FocusRole.NEXT -> 0.35
    FocusRole.NONE -> 0.0
  }
  var scale = when (role) {
    FocusRole.CURRENT -> if (isInterlude || reveal) 1.0 else 1.18
    else -> 0.78
  }
  var opacity = when (role) {
    FocusRole.CURRENT -> if (isInterlude) 0.62 else 1.0
    else -> 0.3
  }
  var fontScale = if (reveal && role == FocusRole.CURRENT && !isInterlude) 1.3 else 1.0
  if (isBackground) {
    y += 0.22 + backgroundIndex.coerceAtLeast(0) * 0.052
    scale = 0.94
    opacity = 0.58
    fontScale *= 0.62
  }
  return FocusTransform(y, scale, opacity, fontScale)
}

@Serializable
data class FullscreenLineTransition(
  val anchorIndex: Int? = null,
  val active: Boolean = false,
  val fromIndex: Int? = null,
  val toIndex: Int? = null,
  val direction: Int = 0,
  val progress: Double = 1.0,
)

/** Playback-position-driven tracker; seek behavior matches the desktop helper. */
class FullscreenLineTransitionTracker(
  private val durationMs: Long = 450L,
  private val seekThresholdMs: Long = 1_000L,
) {
  private var anchorIndex: Int? = null
  private var fromIndex: Int? = null
  private var transitionStartedAtMs = 0L
  private var lastPositionMs: Long? = null

  fun update(
    nextAnchorIndex: Int?,
    positionMs: Long,
    retainThroughGap: Boolean = true,
  ): FullscreenLineTransition {
    val delta = lastPositionMs?.let { positionMs - it } ?: 0L
    val isSeek = lastPositionMs != null && (delta < -50L || abs(delta) > seekThresholdMs)
    if (nextAnchorIndex != null) {
      if (anchorIndex == null || isSeek) {
        anchorIndex = nextAnchorIndex
        fromIndex = null
      } else if (nextAnchorIndex != anchorIndex) {
        fromIndex = anchorIndex
        anchorIndex = nextAnchorIndex
        transitionStartedAtMs = positionMs
      }
    } else if (isSeek || !retainThroughGap) {
      anchorIndex = null
      fromIndex = null
    }
    if (isSeek && nextAnchorIndex == anchorIndex) fromIndex = null
    lastPositionMs = positionMs
    return snapshot(positionMs)
  }

  fun reset() {
    anchorIndex = null
    fromIndex = null
    transitionStartedAtMs = 0L
    lastPositionMs = null
  }

  private fun snapshot(positionMs: Long): FullscreenLineTransition {
    val anchor = anchorIndex ?: return FullscreenLineTransition()
    val from = fromIndex ?: return FullscreenLineTransition(anchorIndex = anchor, toIndex = anchor)
    val progress = timedElementProgress(positionMs, transitionStartedAtMs, transitionStartedAtMs + max(1L, durationMs))
    if (progress >= 1.0) {
      fromIndex = null
      return FullscreenLineTransition(anchorIndex = anchor, toIndex = anchor)
    }
    return FullscreenLineTransition(
      anchorIndex = anchor,
      active = true,
      fromIndex = from,
      toIndex = anchor,
      direction = (anchor - from).sign,
      progress = progress,
    )
  }
}

@Serializable
data class TimingInterval(val startMs: Long, val endMs: Long) {
  init {
    require(startMs >= 0) { "Interval start must not be negative" }
    require(endMs >= startMs) { "Interval end must not precede start" }
  }
}

fun vocalSilenceIntervals(
  interlude: TimingInterval,
  vocalIntervals: List<TimingInterval>,
  minimumDurationMs: Long,
): List<TimingInterval> {
  if (interlude.endMs <= interlude.startMs) return emptyList()
  val minimum = minimumDurationMs.coerceAtLeast(0L)
  val occupied = vocalIntervals.asSequence()
    .filter { it.endMs > it.startMs && it.endMs > interlude.startMs && it.startMs < interlude.endMs }
    .map { TimingInterval(max(interlude.startMs, it.startMs), min(interlude.endMs, it.endMs)) }
    .sortedWith(compareBy<TimingInterval> { it.startMs }.thenBy { it.endMs })
    .toList()
  val silence = mutableListOf<TimingInterval>()
  var cursor = interlude.startMs
  occupied.forEach { interval ->
    if (interval.startMs > cursor && interval.startMs - cursor >= minimum) {
      silence += TimingInterval(cursor, interval.startMs)
    }
    cursor = max(cursor, interval.endMs)
    if (cursor >= interlude.endMs) return@forEach
  }
  if (interlude.endMs > cursor && interlude.endMs - cursor >= minimum) {
    silence += TimingInterval(cursor, interlude.endMs)
  }
  return silence
}

fun isInterludeFullySilent(
  interlude: TimingInterval,
  vocalIntervals: List<TimingInterval>,
  minimumDurationMs: Long,
): Boolean = vocalSilenceIntervals(interlude, vocalIntervals, minimumDurationMs) == listOf(interlude)

@Serializable
data class FullscreenOutroFrame(
  val active: Boolean = false,
  val spinProgress: Double = 0.0,
  val popProgress: Double = 0.0,
  val rotationDegrees: Double = 0.0,
  val scale: Double = 1.0,
  val opacity: Double = 1.0,
)

fun guaranteedOutroStart(
  rawLineEndMs: Long,
  rawDurationMs: Long,
  popDurationMs: Long = FULLSCREEN_OUTRO_POP_DURATION_MS,
): Long {
  val guaranteedWindow = max(0L, rawDurationMs - min(max(1L, popDurationMs), rawDurationMs))
  return max(0L, min(rawLineEndMs, guaranteedWindow))
}

fun fullscreenOutroFrame(
  positionMs: Long,
  finalGroupEndMs: Long,
  durationMs: Long,
  popDurationMs: Long = FULLSCREEN_OUTRO_POP_DURATION_MS,
  startScale: Double = 1.0,
): FullscreenOutroFrame {
  if (durationMs <= finalGroupEndMs || positionMs < finalGroupEndMs) return FullscreenOutroFrame()
  val position = min(positionMs, durationMs)
  val safePopDuration = max(1L, min(popDurationMs, durationMs - finalGroupEndMs))
  val popStart = max(finalGroupEndMs, durationMs - safePopDuration)
  val spinEnd = if (popStart > finalGroupEndMs) popStart else durationMs
  val spinProgress = ((position - finalGroupEndMs).toDouble() / max(1L, spinEnd - finalGroupEndMs)).coerceIn(0.0, 1.0)
  val popProgress = if (position >= popStart) {
    ((position - popStart).toDouble() / max(1L, durationMs - popStart)).coerceIn(0.0, 1.0)
  } else 0.0
  val safeStartScale = startScale.takeIf(Double::isFinite) ?: 1.0
  val spunScale = safeStartScale + (0.78 - safeStartScale) * spinProgress
  val popScale = when {
    popProgress == 0.0 -> spunScale
    popProgress < 0.35 -> spunScale + (0.9 - spunScale) * (popProgress / 0.35)
    else -> 0.9 * (1 - (popProgress - 0.35) / 0.65)
  }
  return FullscreenOutroFrame(
    active = true,
    spinProgress = spinProgress,
    popProgress = popProgress,
    rotationDegrees = 90 * spinProgress,
    scale = max(0.0, popScale),
    opacity = max(0.0, (1 - 0.62 * spinProgress) * (1 - popProgress)),
  )
}

const val FULLSCREEN_OUTRO_POP_DURATION_MS = 375L
