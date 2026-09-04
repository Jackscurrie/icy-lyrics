package com.icy.lyrics.core.lyrics.animation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class TimedElementStatus {
  NOT_SUNG,
  ACTIVE,
  SUNG,
}

fun timedElementStatus(positionMs: Long, startMs: Long, endMs: Long): TimedElementStatus = when {
  positionMs < startMs -> TimedElementStatus.NOT_SUNG
  positionMs >= endMs -> TimedElementStatus.SUNG
  else -> TimedElementStatus.ACTIVE
}

/** Half-open timing, matching desktop: the exact end belongs to SUNG. */
fun timedElementProgress(positionMs: Long, startMs: Long, endMs: Long): Double {
  if (endMs <= startMs) return if (positionMs >= endMs) 1.0 else 0.0
  return ((positionMs - startMs).toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0)
}

@Serializable
data class AnimationPoint(val time: Double, val value: Double)

/** Port of the desktop `cubic-spline` package's natural derivative spline. */
class NaturalCubicSpline(points: List<AnimationPoint>) {
  private val xs = points.map(AnimationPoint::time).toDoubleArray()
  private val ys = points.map(AnimationPoint::value).toDoubleArray()
  private val derivatives: DoubleArray

  init {
    require(points.size >= 2) { "A spline requires at least two points" }
    require(points.zipWithNext().all { (left, right) -> right.time > left.time }) {
      "Spline times must be finite and strictly increasing"
    }
    require(points.all { it.time.isFinite() && it.value.isFinite() }) {
      "Spline points must be finite"
    }
    derivatives = solveDerivatives()
  }

  fun at(rawTime: Double): Double {
    val time = rawTime.coerceIn(xs.first(), xs.last())
    var low = 0
    var high = xs.lastIndex
    while (high - low > 1) {
      val middle = (low + high) ushr 1
      if (xs[middle] <= time) low = middle else high = middle
    }
    val next = (low + 1).coerceAtMost(xs.lastIndex)
    if (next == low) return ys[low]
    val width = xs[next] - xs[low]
    val ratio = (time - xs[low]) / width
    val delta = ys[next] - ys[low]
    val a = derivatives[low] * width - delta
    val b = -derivatives[next] * width + delta
    return (1 - ratio) * ys[low] + ratio * ys[next] +
      ratio * (1 - ratio) * (a * (1 - ratio) + b * ratio)
  }

  private fun solveDerivatives(): DoubleArray {
    val size = xs.size
    val lower = DoubleArray(size)
    val diagonal = DoubleArray(size)
    val upper = DoubleArray(size)
    val rhs = DoubleArray(size)

    fun width(index: Int) = xs[index + 1] - xs[index]
    diagonal[0] = 2.0 / width(0)
    upper[0] = 1.0 / width(0)
    rhs[0] = 3.0 * (ys[1] - ys[0]) / width(0).pow(2)
    for (index in 1 until size - 1) {
      val previousWidth = width(index - 1)
      val nextWidth = width(index)
      lower[index] = 1.0 / previousWidth
      diagonal[index] = 2.0 * (1.0 / previousWidth + 1.0 / nextWidth)
      upper[index] = 1.0 / nextWidth
      rhs[index] = 3.0 * (
        (ys[index] - ys[index - 1]) / previousWidth.pow(2) +
          (ys[index + 1] - ys[index]) / nextWidth.pow(2)
        )
    }
    val lastWidth = width(size - 2)
    lower[size - 1] = 1.0 / lastWidth
    diagonal[size - 1] = 2.0 / lastWidth
    rhs[size - 1] = 3.0 * (ys[size - 1] - ys[size - 2]) / lastWidth.pow(2)

    for (index in 1 until size) {
      val factor = lower[index] / diagonal[index - 1]
      diagonal[index] -= factor * upper[index - 1]
      rhs[index] -= factor * rhs[index - 1]
    }
    val result = DoubleArray(size)
    result[size - 1] = rhs[size - 1] / diagonal[size - 1]
    for (index in size - 2 downTo 0) {
      result[index] = (rhs[index] - upper[index] * result[index + 1]) / diagonal[index]
    }
    return result
  }
}

/**
 * Exact analytic spring used by the desktop renderer. dt is seconds. A caller
 * can reset on a seek and step once per rendered frame without wall-clock CSS.
 */
class DesktopSpring(
  startPosition: Double,
  frequencyHz: Double,
  dampingRatio: Double,
  goal: Double = startPosition,
) {
  var dampingRatio: Double = dampingRatio
  var frequencyHz: Double = frequencyHz
  var goal: Double = goal
    private set
  var position: Double = startPosition
    private set
  var velocity: Double = 0.0
    private set

  fun setGoal(value: Double, replacePosition: Boolean = false) {
    require(value.isFinite()) { "Spring goal must be finite" }
    goal = value
    if (replacePosition) {
      position = value
      velocity = 0.0
    }
  }

  fun step(rawDtSeconds: Double): Double {
    val dt = rawDtSeconds.coerceAtLeast(0.0)
    val damping = dampingRatio
    val frequency = frequencyHz * (2 * PI)
    val target = goal
    var nextPosition = position
    var nextVelocity = velocity
    if (dt == 0.0 || frequency == 0.0) return position

    when {
      damping == 1.0 -> {
        val q = exp(-frequency * dt)
        val w = dt * q
        val c0 = q + w * frequency
        val c2 = q - w * frequency
        val c3 = w * frequency * frequency
        val offset = nextPosition - target
        nextPosition = offset * c0 + nextVelocity * w + target
        nextVelocity = nextVelocity * c2 - offset * c3
      }

      damping < 1.0 -> {
        val q = exp(-damping * frequency * dt)
        val c = sqrt(1 - damping * damping)
        val i = cos(dt * frequency * c)
        val j = sin(dt * frequency * c)
        val z = if (c > EPSILON) {
          j / c
        } else {
          val a = dt * frequency
          a + ((a * a) * (c * c) * (c * c) / 20 - c * c) * (a * a * a) / 6
        }
        val y = if (frequency * c > EPSILON) {
          j / (frequency * c)
        } else {
          val b = frequency * c
          dt + ((dt * dt) * (b * b) * (b * b) / 20 - b * b) * (dt * dt * dt) / 6
        }
        val offset = nextPosition - target
        nextPosition = (offset * (i + z * damping) + nextVelocity * y) * q + target
        nextVelocity = (nextVelocity * (i - z * damping) - offset * (z * frequency)) * q
      }

      else -> {
        val c = sqrt(damping * damping - 1)
        val r1 = -frequency * (damping + c)
        val r2 = -frequency * (damping - c)
        val e1 = exp(r1 * dt)
        val e2 = exp(r2 * dt)
        val offset = nextPosition - target
        val coefficient2 = (nextVelocity - offset * r1) / (2 * frequency * c)
        val coefficient1 = e1 * (offset - coefficient2)
        nextPosition = coefficient1 + coefficient2 * e2 + target
        nextVelocity = coefficient1 * r1 + coefficient2 * e2 * r2
      }
    }
    position = nextPosition
    velocity = nextVelocity
    return nextPosition
  }

  fun canSleep(): Boolean = velocity * velocity <= SLEEP_VELOCITY_SQUARED &&
    (position - goal) * (position - goal) <= SLEEP_OFFSET_SQUARED

  companion object {
    private const val EPSILON = 1e-5
    private val SLEEP_OFFSET_SQUARED = (1.0 / 3840).pow(2)
    private val SLEEP_VELOCITY_SQUARED = 1e-4
  }
}

/** easeSinOut from d3-ease. */
fun easeSinOut(value: Double): Double = sin(value.coerceIn(0.0, 1.0) * PI / 2)

internal fun Double.closeTo(other: Double, epsilon: Double = 1e-9): Boolean = abs(this - other) <= epsilon
