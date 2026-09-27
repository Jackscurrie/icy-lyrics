package com.icy.lyrics.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import com.icy.lyrics.ui.IcyText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class AppEntryExperiencePhase { ONBOARDING, SUCCESS, STARTUP, CONTENT }

internal const val PERMISSION_SUCCESS_DURATION_MS = 820L
internal const val STARTUP_EXPERIENCE_DURATION_MS = 1_180L
internal const val REDUCED_MOTION_SUCCESS_DURATION_MS = 650L
internal const val REDUCED_MOTION_STARTUP_DURATION_MS = 260L

internal data class StartupWordmarkMotion(
  val scale: Float,
  val alpha: Float,
  val outwardDp: Float,
)

internal fun startupWordmarkMotion(
  arrival: Float,
  departure: Float,
  reducedMotion: Boolean,
): StartupWordmarkMotion {
  val entered = arrival.coerceIn(0f, 1f)
  val leaving = departure.coerceIn(0f, 1f)
  return if (reducedMotion) {
    StartupWordmarkMotion(
      scale = 1f,
      alpha = 1f - leaving,
      outwardDp = 0f,
    )
  } else {
    StartupWordmarkMotion(
      scale = (0.58f + entered * 0.42f) * (1f + leaving * 0.52f),
      alpha = (0.16f + entered * 0.84f) * (1f - leaving),
      outwardDp = leaving * 20f,
    )
  }
}

internal fun initialAppEntryExperiencePhase(
  notificationAccess: Boolean,
  launchExperienceEnabled: Boolean,
): AppEntryExperiencePhase = when {
  !launchExperienceEnabled -> AppEntryExperiencePhase.CONTENT
  !notificationAccess -> AppEntryExperiencePhase.ONBOARDING
  else -> AppEntryExperiencePhase.STARTUP
}

internal fun shouldShowPermissionSuccess(
  previousNotificationAccess: Boolean,
  notificationAccess: Boolean,
  initialAccessObserved: Boolean,
  notificationSettingsRequestPending: Boolean = false,
): Boolean = notificationAccess && (
  notificationSettingsRequestPending || (initialAccessObserved && !previousNotificationAccess)
)

internal fun appEntryPhaseDurationMs(
  phase: AppEntryExperiencePhase,
  reducedMotion: Boolean,
): Long = when (phase) {
  AppEntryExperiencePhase.SUCCESS -> if (reducedMotion) {
    REDUCED_MOTION_SUCCESS_DURATION_MS
  } else {
    PERMISSION_SUCCESS_DURATION_MS
  }
  AppEntryExperiencePhase.STARTUP -> if (reducedMotion) {
    REDUCED_MOTION_STARTUP_DURATION_MS
  } else {
    STARTUP_EXPERIENCE_DURATION_MS
  }
  AppEntryExperiencePhase.ONBOARDING,
  AppEntryExperiencePhase.CONTENT,
  -> 0L
}

@Composable
internal fun PermissionSuccessExperience(reducedMotion: Boolean) {
  val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
  LaunchedEffect(reducedMotion) {
    if (reducedMotion) {
      progress.snapTo(1f)
    } else {
      progress.animateTo(
        targetValue = 1f,
        animationSpec = tween(
          durationMillis = 720,
          easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
        ),
      )
    }
  }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    IcyLaunchGradient(animated = !reducedMotion)
    if (!reducedMotion) {
      Confetti(progress = progress.value)
    }
    Text(
      text = "Success! 🎉",
      modifier = Modifier
        .graphicsLayer {
          alpha = 0.72f + progress.value * 0.28f
          scaleX = 0.9f + progress.value * 0.1f
          scaleY = scaleX
        }
        .semantics { heading() },
      color = Color(0xFFF4F1EA),
      fontSize = 42.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.displaySmall,
    )
  }
}

@Composable
internal fun StartupExperienceOverlay(reducedMotion: Boolean) {
  val arrival = remember { Animatable(if (reducedMotion) 1f else 0f) }
  val departure = remember { Animatable(0f) }
  LaunchedEffect(reducedMotion) {
    if (reducedMotion) {
      arrival.snapTo(1f)
      departure.animateTo(1f, tween(durationMillis = 220, easing = LinearEasing))
    } else {
      arrival.animateTo(
        1f,
        tween(durationMillis = 650, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
      )
      kotlinx.coroutines.delay(160)
      departure.animateTo(
        1f,
        tween(durationMillis = 350, easing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)),
      )
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .graphicsLayer { alpha = 1f - departure.value }
      .blur((departure.value * 22f).dp)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
          }
        }
      }
      .clearAndSetSemantics { contentDescription = "Icy Lyrics" },
  ) {
    IcyLaunchGradient(animated = !reducedMotion)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      val titleSizeValue = (minOf(maxWidth.value, maxHeight.value) * 0.30f)
        .coerceIn(84f, 150f)
      val titleSize = titleSizeValue.sp
      val lineHeight = (titleSizeValue * 1.04f).sp
      val lineGap = (titleSizeValue * 0.04f).dp
      val groupWidth = minOf(maxWidth - 32.dp, (titleSizeValue * 4.1f).dp)
      val motion = startupWordmarkMotion(
        arrival = arrival.value,
        departure = departure.value,
        reducedMotion = reducedMotion,
      )
      Column(
        modifier = Modifier
          .width(groupWidth)
          .graphicsLayer {
            alpha = motion.alpha
            scaleX = motion.scale
            scaleY = scaleX
          },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(lineGap),
      ) {
        Text(
          text = "Icy",
          modifier = Modifier
            .fillMaxWidth()
            .offset(
              x = (-motion.outwardDp).dp,
              y = (-motion.outwardDp * 0.35f).dp,
            ),
          color = Color(0xFFF4F1EA),
          fontSize = titleSize,
          fontWeight = FontWeight.Bold,
          letterSpacing = (-titleSizeValue * 0.09f).sp,
          lineHeight = lineHeight,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
        Text(
          text = "Lyrics",
          modifier = Modifier
            .fillMaxWidth()
            .offset(
              x = motion.outwardDp.dp,
              y = (motion.outwardDp * 0.35f).dp,
            ),
          color = Color(0xFFF4F1EA),
          fontSize = titleSize,
          fontWeight = FontWeight.Bold,
          letterSpacing = (-titleSizeValue * 0.09f).sp,
          lineHeight = lineHeight,
          textAlign = TextAlign.Center,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun IcyLaunchGradient(animated: Boolean) {
  val phase = if (animated) {
    val transition = rememberInfiniteTransition(label = "Icy launch gradient")
    val animatedPhase by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 2_300, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "Icy launch gradient movement",
    )
    animatedPhase
  } else {
    0.42f
  }

  Canvas(Modifier.fillMaxSize()) {
    drawRect(
      brush = Brush.linearGradient(
        colors = listOf(Color(0xFF251125), Color(0xFF40200D), Color(0xFF202700)),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
      ),
    )
    val travel = sin(phase * PI).toFloat()
    val redCenter = androidx.compose.ui.geometry.Offset(
      size.width * (0.68f + travel * 0.08f),
      size.height * (0.17f + phase * 0.08f),
    )
    val tealCenter = androidx.compose.ui.geometry.Offset(
      size.width * (0.13f + phase * 0.07f),
      size.height * (0.88f - travel * 0.09f),
    )
    val oliveCenter = androidx.compose.ui.geometry.Offset(
      size.width * (0.9f - phase * 0.05f),
      size.height * (0.84f + travel * 0.05f),
    )
    val glowRadius = maxOf(size.width, size.height) * 0.67f
    drawCircle(
      brush = Brush.radialGradient(
        listOf(Color(0xFFA71E0D), Color(0x007D180C)), redCenter, glowRadius,
      ),
      radius = glowRadius,
      center = redCenter,
    )
    drawCircle(
      brush = Brush.radialGradient(
        listOf(Color(0xFF093C42), Color(0x00162C29)), tealCenter, glowRadius * 0.92f,
      ),
      radius = glowRadius * 0.92f,
      center = tealCenter,
    )
    drawCircle(
      brush = Brush.radialGradient(
        listOf(Color(0xB3596900), Color(0x00596900)), oliveCenter, glowRadius * 0.7f,
      ),
      radius = glowRadius * 0.7f,
      center = oliveCenter,
    )
    drawRect(
      brush = Brush.linearGradient(
        listOf(Color(0x52100B14), Color.Transparent),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height),
      ),
    )
  }
}

@Composable
private fun Confetti(progress: Float) {
  val colors = remember {
    listOf(
      Color(0xFF8FD7FF),
      Color(0xFFF4F1EA),
      Color(0xFFFF7D66),
      Color(0xFF74E2CF),
      Color(0xFFFFD166),
      Color(0xFFC7A7FF),
    )
  }
  Canvas(Modifier.fillMaxSize()) {
    repeat(44) { index ->
      val delayed = ((progress - (index % 6) * 0.025f) / 0.875f).coerceIn(0f, 1f)
      if (delayed <= 0f) return@repeat
      val seed = ((index * 37) % 101) / 100f
      val angle = (-0.93f * PI + seed * 0.86f * PI).toFloat()
      val speed = 0.62f + ((index * 19) % 31) / 100f
      val distance = minOf(size.width, size.height) * speed * delayed
      val x = size.width * 0.5f + cos(angle) * distance
      val y = size.height * 0.5f + sin(angle) * distance + size.height * 0.58f * delayed * delayed
      val turn = angle + delayed * (2f + index % 5) * PI.toFloat()
      val halfLength = 4f + index % 4
      val dx = cos(turn) * halfLength
      val dy = sin(turn) * halfLength
      drawLine(
        color = colors[index % colors.size].copy(
          alpha = ((1f - delayed) / 0.28f).coerceIn(0f, 1f),
        ),
        start = androidx.compose.ui.geometry.Offset(x - dx, y - dy),
        end = androidx.compose.ui.geometry.Offset(x + dx, y + dy),
        strokeWidth = 3f + index % 3,
      )
    }
  }
}
