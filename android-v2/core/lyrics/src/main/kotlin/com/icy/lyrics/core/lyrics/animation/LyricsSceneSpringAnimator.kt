package com.icy.lyrics.core.lyrics.animation

/**
 * Retains the analytic desktop spring state that cannot be represented by a
 * playback-position-only [LyricsScene]. Keep one instance per rendered lyrics
 * document and call [animate] from the display frame clock.
 *
 * This class is intentionally platform independent and thread-confined. It
 * returns immutable scene copies, leaving the target scene untouched.
 */
class LyricsSceneSpringAnimator {
  private val tokenSprings = mutableMapOf<TokenKey, TokenSprings>()
  private val letterSprings = mutableMapOf<LetterKey, LetterSprings>()
  private val lineGlowSprings = mutableMapOf<LineKey, DesktopSpring>()
  private var lastFrameTimeNanos: Long? = null
  private var topologyHash: Int? = null

  /** True while at least one retained spring still needs display frames. */
  var needsFrames: Boolean = false
    private set

  fun reset() {
    tokenSprings.clear()
    letterSprings.clear()
    lineGlowSprings.clear()
    lastFrameTimeNanos = null
    topologyHash = null
    needsFrames = false
  }

  /**
   * Resolves all spring goal channels for this display frame.
   *
   * [frameTimeNanos] should come from the platform display clock (for Compose,
   * `withFrameNanos`). Set [snap] for reduced-motion rendering or an explicit
   * renderer reset; seeking alone does not snap desktop word springs.
   */
  fun animate(scene: LyricsScene, frameTimeNanos: Long, snap: Boolean = false): LyricsScene {
    require(frameTimeNanos >= 0L) { "Frame time must not be negative" }

    val currentTopologyHash = scene.topologyHash()
    if (topologyHash != null && topologyHash != currentTopologyHash) reset()
    topologyHash = currentTopologyHash

    val previousFrameTimeNanos = lastFrameTimeNanos
    val dtSeconds = if (previousFrameTimeNanos == null || frameTimeNanos <= previousFrameTimeNanos) {
      0.0
    } else {
      (frameTimeNanos - previousFrameTimeNanos) / NANOS_PER_SECOND
    }
    lastFrameTimeNanos = frameTimeNanos

    val liveTokenKeys = mutableSetOf<TokenKey>()
    val liveLetterKeys = mutableSetOf<LetterKey>()
    val liveLineKeys = mutableSetOf<LineKey>()
    val resolvedLines = scene.lines.map { line ->
      val lineKey = LineKey(line.key, line.startMs, line.endMs, line.kind)
      liveLineKeys += lineKey
      val lineSpring = lineGlowSprings.getOrPut(lineKey) {
        spring(0.0, scene.animationPresets.lineGlow)
      }.also {
        it.applyPreset(scene.animationPresets.lineGlow)
        it.setGoal(line.lineGlowGoal, replacePosition = snap)
      }
      val resolvedLineGlow = lineSpring.step(dtSeconds)

      val resolvedTokens = line.tokens.mapIndexed { tokenIndex, token ->
        val tokenKey = TokenKey(
          lineKey = lineKey,
          index = tokenIndex,
          text = token.text,
          startMs = token.startMs,
          endMs = token.endMs,
          isDot = token.isDot,
        )
        liveTokenKeys += tokenKey
        val springs = tokenSprings.getOrPut(tokenKey) {
          TokenSprings.create(token, scene.animationPresets)
        }
        springs.applyPresets(token, scene.animationPresets)
        val resolved = springs.step(token.animation, dtSeconds, snap)
        val resolvedLetters = token.letters.mapIndexed { letterIndex, letter ->
          val letterKey = LetterKey(
            tokenKey = tokenKey,
            index = letterIndex,
            text = letter.text,
            startMs = letter.startMs,
            endMs = letter.endMs,
          )
          liveLetterKeys += letterKey
          val retained = letterSprings.getOrPut(letterKey) {
            LetterSprings.create(scene.animationPresets)
          }
          retained.applyPresets(scene.animationPresets)
          letter.copy(animation = retained.step(letter.animation, dtSeconds, snap))
        }
        token.copy(animation = resolved, letters = resolvedLetters)
      }

      line.copy(lineGlow = resolvedLineGlow, tokens = resolvedTokens)
    }

    tokenSprings.keys.retainAll(liveTokenKeys)
    letterSprings.keys.retainAll(liveLetterKeys)
    lineGlowSprings.keys.retainAll(liveLineKeys)
    needsFrames = tokenSprings.values.any { !it.canSleep() } ||
      letterSprings.values.any { !it.canSleep() } ||
      lineGlowSprings.values.any { !it.canSleep() }
    return scene.copy(lines = resolvedLines)
  }

  private data class LineKey(
    val key: String,
    val startMs: Long?,
    val endMs: Long?,
    val kind: LyricSceneLineKind,
  )

  private data class TokenKey(
    val lineKey: LineKey,
    val index: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isDot: Boolean,
  )

  private data class LetterKey(
    val tokenKey: TokenKey,
    val index: Int,
    val text: String,
    val startMs: Double,
    val endMs: Double,
  )

  private data class TokenSprings(
    val scale: DesktopSpring,
    val yOffset: DesktopSpring,
    val glow: DesktopSpring,
    val opacity: DesktopSpring?,
  ) {
    fun applyPresets(token: LyricTokenScene, presets: LyricAnimationPresets) {
      scale.applyPreset(if (token.isDot) presets.dotScale else presets.scale)
      yOffset.applyPreset(if (token.isDot) presets.dotYOffset else presets.yOffset)
      glow.applyPreset(if (token.isDot) presets.dotGlow else presets.glow)
      opacity?.applyPreset(presets.dotOpacity)
    }

    fun step(target: TimedAnimationFrame, dtSeconds: Double, snap: Boolean): TimedAnimationFrame {
      scale.setGoal(target.scaleGoal, replacePosition = snap)
      yOffset.setGoal(target.yOffsetFontUnitsGoal, replacePosition = snap)
      glow.setGoal(target.glowGoal, replacePosition = snap)
      opacity?.setGoal(target.opacityGoal, replacePosition = snap)
      return target.copy(
        scale = scale.step(dtSeconds),
        yOffsetFontUnits = yOffset.step(dtSeconds),
        glow = glow.step(dtSeconds),
        opacity = opacity?.step(dtSeconds) ?: target.opacityGoal,
      )
    }

    fun canSleep(): Boolean = scale.canSleep() && yOffset.canSleep() && glow.canSleep() &&
      (opacity?.canSleep() != false)

    companion object {
      fun create(token: LyricTokenScene, presets: LyricAnimationPresets): TokenSprings {
        val isDot = token.isDot
        return TokenSprings(
          scale = spring(if (isDot) DOT_INITIAL_SCALE else WORD_INITIAL_SCALE,
            if (isDot) presets.dotScale else presets.scale),
          yOffset = spring(if (isDot) DOT_INITIAL_Y_OFFSET else WORD_INITIAL_Y_OFFSET,
            if (isDot) presets.dotYOffset else presets.yOffset),
          glow = spring(INITIAL_GLOW, if (isDot) presets.dotGlow else presets.glow),
          opacity = if (isDot) spring(DOT_INITIAL_OPACITY, presets.dotOpacity) else null,
        )
      }
    }
  }

  private data class LetterSprings(
    val scale: DesktopSpring,
    val yOffset: DesktopSpring,
    val glow: DesktopSpring,
  ) {
    fun applyPresets(presets: LyricAnimationPresets) {
      scale.applyPreset(presets.scale)
      yOffset.applyPreset(presets.yOffset)
      glow.applyPreset(presets.glow)
    }

    fun step(target: TimedAnimationFrame, dtSeconds: Double, snap: Boolean): TimedAnimationFrame {
      scale.setGoal(target.scaleGoal, replacePosition = snap)
      yOffset.setGoal(target.yOffsetFontUnitsGoal, replacePosition = snap)
      glow.setGoal(target.glowGoal, replacePosition = snap)
      return target.copy(
        scale = scale.step(dtSeconds),
        yOffsetFontUnits = yOffset.step(dtSeconds),
        glow = glow.step(dtSeconds),
      )
    }

    fun canSleep(): Boolean = scale.canSleep() && yOffset.canSleep() && glow.canSleep()

    companion object {
      fun create(presets: LyricAnimationPresets) = LetterSprings(
        scale = spring(LETTER_INITIAL_SCALE, presets.scale),
        yOffset = spring(LETTER_INITIAL_Y_OFFSET, presets.yOffset),
        glow = spring(INITIAL_GLOW, presets.glow),
      )
    }
  }

  companion object {
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val WORD_INITIAL_SCALE = 0.95
    private const val WORD_INITIAL_Y_OFFSET = 0.01
    private const val LETTER_INITIAL_SCALE = 0.95
    private const val LETTER_INITIAL_Y_OFFSET = 0.01
    private const val DOT_INITIAL_SCALE = 0.75
    private const val DOT_INITIAL_Y_OFFSET = 0.0
    private const val DOT_INITIAL_OPACITY = 0.35
    private const val INITIAL_GLOW = 0.0

    private fun spring(position: Double, preset: SpringPreset) = DesktopSpring(
      startPosition = position,
      frequencyHz = preset.frequencyHz,
      dampingRatio = preset.dampingRatio,
    )
  }
}

private fun DesktopSpring.applyPreset(preset: SpringPreset) {
  frequencyHz = preset.frequencyHz
  dampingRatio = preset.dampingRatio
}

private fun LyricsScene.topologyHash(): Int {
  var hash = syncKind.hashCode()
  lines.forEach { line ->
    hash = 31 * hash + line.key.hashCode()
    hash = 31 * hash + line.kind.hashCode()
    hash = 31 * hash + line.text.hashCode()
    hash = 31 * hash + (line.startMs?.hashCode() ?: 0)
    hash = 31 * hash + (line.endMs?.hashCode() ?: 0)
    line.tokens.forEach { token ->
      hash = 31 * hash + token.text.hashCode()
      hash = 31 * hash + token.startMs.hashCode()
      hash = 31 * hash + token.endMs.hashCode()
      hash = 31 * hash + token.isDot.hashCode()
      token.letters.forEach { letter ->
        hash = 31 * hash + letter.text.hashCode()
        hash = 31 * hash + letter.startMs.hashCode()
        hash = 31 * hash + letter.endMs.hashCode()
      }
    }
  }
  return hash
}
