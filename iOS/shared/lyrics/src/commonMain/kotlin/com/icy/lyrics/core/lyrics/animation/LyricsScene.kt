package com.icy.lyrics.core.lyrics.animation

import com.icy.lyrics.core.lyrics.model.LineLyrics
import com.icy.lyrics.core.lyrics.model.LyricToken
import com.icy.lyrics.core.lyrics.model.LyricsDocument
import com.icy.lyrics.core.lyrics.model.LyricsSyncKind
import com.icy.lyrics.core.lyrics.model.StaticLyrics
import com.icy.lyrics.core.lyrics.model.SyllableLyrics
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

@Serializable
enum class LyricSceneLineKind {
  STATIC,
  VOCAL,
  BACKGROUND,
  INTERLUDE,
}

@Serializable
enum class LyricTextDirection {
  LEFT_TO_RIGHT,
  RIGHT_TO_LEFT,
}

@Serializable
enum class LyricHorizontalAlignment {
  START,
  CENTER,
  END,
}

@Serializable
data class LyricGradientFrame(
  /** CSS-compatible degrees: +90 LTR words, -90 RTL words, 180 line sync. */
  val angleDegrees: Double,
  val positionPercent: Double,
  val transitionWidthPercent: Double = 20.0,
  val leadingAlpha: Double = 0.85,
  val trailingAlpha: Double = 0.5,
)

@Serializable
data class SpringPreset(val frequencyHz: Double, val dampingRatio: Double)

@Serializable
data class LyricAnimationPresets(
  val scale: SpringPreset = SpringPreset(0.88, 0.64),
  val yOffset: SpringPreset = SpringPreset(1.45, 0.4),
  val glow: SpringPreset = SpringPreset(1.18, 0.56),
  val dotScale: SpringPreset = SpringPreset(0.7, 0.6),
  val dotYOffset: SpringPreset = SpringPreset(1.25, 0.4),
  val dotGlow: SpringPreset = SpringPreset(1.0, 0.5),
  val dotOpacity: SpringPreset = SpringPreset(1.0, 0.5),
  val lineGlow: SpringPreset = SpringPreset(1.0, 0.5),
)

@Serializable
data class TimedAnimationFrame(
  val status: TimedElementStatus,
  val progress: Double,
  /** Direct desktop gradient target, in percent. */
  val gradientPercent: Double,
  /** Spring goals; use [LyricsAnimationPresets] to reproduce the desktop settling. */
  val scaleGoal: Double,
  val yOffsetFontUnitsGoal: Double,
  val glowGoal: Double,
  val opacityGoal: Double = 1.0,
  /** Values after optional retained spring resolution. Initially equal their goals. */
  val scale: Double = scaleGoal,
  val yOffsetFontUnits: Double = yOffsetFontUnitsGoal,
  val glow: Double = glowGoal,
  val opacity: Double = opacityGoal,
  val revealVisible: Boolean = true,
  val revealOpacity: Double = 1.0,
)

@Serializable
data class LyricTokenScene(
  val text: String,
  val startMs: Long,
  val endMs: Long,
  val isPartOfWord: Boolean,
  val transliteratedText: String? = null,
  val isDot: Boolean = false,
  val gradient: LyricGradientFrame,
  val animation: TimedAnimationFrame,
  /** Desktop-style emphasis frames. Empty when this token is not letter-capable. */
  val letters: List<LyricLetterScene> = emptyList(),
)

@Serializable
data class LyricLetterScene(
  val text: String,
  /** Double precision preserves the desktop's evenly divided sub-millisecond boundaries. */
  val startMs: Double,
  val endMs: Double,
  val gradient: LyricGradientFrame,
  val animation: TimedAnimationFrame,
)

@Serializable
data class LyricLineScene(
  val key: String,
  val renderIndex: Int,
  val sourceLineIndex: Int?,
  val groupIndex: Int?,
  val kind: LyricSceneLineKind,
  val text: String,
  val transliteratedText: String? = null,
  val startMs: Long? = null,
  val endMs: Long? = null,
  val oppositeAligned: Boolean = false,
  val textDirection: LyricTextDirection = LyricTextDirection.LEFT_TO_RIGHT,
  val horizontalAlignment: LyricHorizontalAlignment = LyricHorizontalAlignment.START,
  /** 15% when duet lanes are present, otherwise desktop's 5% breathing room. */
  val laneInsetFraction: Double = 0.05,
  val status: TimedElementStatus? = null,
  val progress: Double = 0.0,
  val lineGradientPercent: Double = 100.0,
  val lineGlowGoal: Double = 0.0,
  val lineGlow: Double = lineGlowGoal,
  /** Normal list alpha. Fullscreen focus uses focusTransform.opacity instead. */
  val contentOpacity: Double = 1.0,
  val gradient: LyricGradientFrame = LyricGradientFrame(180.0, 100.0, trailingAlpha = 0.35),
  val blurRadiusPx: Double = 0.0,
  val tokens: List<LyricTokenScene> = emptyList(),
  val visible: Boolean = true,
  val focusRole: FocusRole = FocusRole.NONE,
  val transitionRole: FocusTransitionRole = FocusTransitionRole.NONE,
  val focusTransform: FocusTransform = FocusTransform(0.0, 1.0, 1.0),
  val revealCurrent: Boolean = false,
  val preHidden: Boolean = false,
)

@Serializable
data class LyricsScene(
  val positionMs: Long,
  val rawPositionMs: Long,
  val syncKind: LyricsSyncKind,
  val anchorRenderIndex: Int? = null,
  val lines: List<LyricLineScene>,
  val transition: FullscreenLineTransition = FullscreenLineTransition(),
  val transitionKind: FocusTransitionKind? = null,
  val outro: FullscreenOutroFrame = FullscreenOutroFrame(),
  val animationPresets: LyricAnimationPresets = LyricAnimationPresets(),
  val hasDuetLines: Boolean = false,
  val hasRtlLines: Boolean = false,
)

@Serializable
data class LyricsSceneOptions(
  val fullscreenFocus: Boolean = false,
  val reveal: Boolean = false,
  val simpleLyricsMode: Boolean = false,
  val minimalLyricsMode: Boolean = false,
  val synthesizeInterludes: Boolean = true,
  val outroEnabled: Boolean = false,
  val durationMs: Long? = null,
  val reducedMotion: Boolean = false,
)

/**
 * Converts normalized lyrics into renderer-only state. The only state retained
 * is the 450 ms fullscreen line handoff; every word/dot frame is a pure
 * function of playback position and is therefore pause/seek safe.
 */
class LyricsSceneEngine {
  private val transitionTracker = FullscreenLineTransitionTracker()
  private var activeDocument: LyricsDocument? = null
  private var prepared: List<PreparedLine> = emptyList()

  fun reset() {
    activeDocument = null
    prepared = emptyList()
    transitionTracker.reset()
  }

  fun frame(
    document: LyricsDocument,
    lyricsPositionMs: Long,
    rawPositionMs: Long = lyricsPositionMs,
    options: LyricsSceneOptions = LyricsSceneOptions(),
  ): LyricsScene {
    require(lyricsPositionMs >= 0L && rawPositionMs >= 0L) { "Playback positions must not be negative" }
    if (activeDocument !== document) {
      activeDocument = document
      prepared = prepare(document, options)
      transitionTracker.reset()
    } else if (preparedOptionsKey != options.preparationKey()) {
      prepared = prepare(document, options)
      transitionTracker.reset()
    }
    preparedOptionsKey = options.preparationKey()

    if (document is StaticLyrics) {
      val staticScenes = prepared.mapIndexed { index, line -> line.toStaticScene(index) }
      return LyricsScene(
        positionMs = lyricsPositionMs,
        rawPositionMs = rawPositionMs,
        syncKind = document.syncKind,
        lines = staticScenes,
        hasDuetLines = staticScenes.any(LyricLineScene::oppositeAligned),
        hasRtlLines = staticScenes.any { it.textDirection == LyricTextDirection.RIGHT_TO_LEFT },
      )
    }

    val effectivePosition = lyricsPositionMs - if (options.simpleLyricsMode) SIMPLE_LYRICS_DELAY_MS else 0.0
    val directAnchor = resolveFocusAnchor(prepared, effectivePosition, options.minimumInterludeMs())
    var transition = if (options.fullscreenFocus) {
      transitionTracker.update(directAnchor, effectivePosition.roundToLong(), retainThroughGap = true)
    } else {
      transitionTracker.reset()
      FullscreenLineTransition(anchorIndex = directAnchor, toIndex = directAnchor)
    }

    // When dots finish before a small untimed gap, promote the lyric that was
    // parked below them instead of leaving completed dots at center.
    if (options.fullscreenFocus && directAnchor == null && transition.anchorIndex != null) {
      val held = prepared.getOrNull(transition.anchorIndex!!)
      if (held?.kind == LyricSceneLineKind.INTERLUDE && effectivePosition >= held.endMs) {
        nearestLead(prepared, transition.anchorIndex!!, 1)?.let { next ->
          transition = transitionTracker.update(next, effectivePosition.roundToLong(), true)
        }
      }
    }
    val anchor = directAnchor ?: transition.anchorIndex
    val assignments = if (options.fullscreenFocus) {
      focusAssignments(prepared, anchor, transition, effectivePosition, options)
    } else emptyMap()

    val activeForBlur = getScrollLine(prepared, effectivePosition, includeInterludes = true)
    val hasDuetLines = prepared.any(PreparedLine::oppositeAligned)
    val scenes = prepared.mapIndexed { index, line ->
      val assignment = assignments[index]
      val status = status(effectivePosition, line.startMs, line.endMs)
      val progress = progress(effectivePosition, line.startMs, line.endMs)
      val textDirection = detectLyricTextDirection(line.text)
      val isBackground = line.kind == LyricSceneLineKind.BACKGROUND
      val tokenScenes = line.tokens.map { token ->
        tokenScene(token, effectivePosition, options, textDirection, isBackground)
      }
      val isLineTimed = line.tokens.isEmpty()
      val revealOpacity = if (isLineTimed) (progress * 5).coerceIn(0.0, 1.0) else 1.0
      val baseTransform = assignment?.let {
        focusTransform(
          role = it.role,
          isBackground = line.kind == LyricSceneLineKind.BACKGROUND,
          backgroundIndex = line.backgroundIndex,
          isInterlude = line.kind == LyricSceneLineKind.INTERLUDE,
          reveal = options.reveal,
        )
      } ?: FocusTransform(0.0, 1.0, if (options.fullscreenFocus) 0.0 else 1.0)
      val transformed = if (assignment != null && transition.active) {
        transitionTransform(baseTransform, assignment.transitionRole, transitionKind(prepared, transition), transition, line, options)
      } else baseTransform
      val finalTransform = if (options.reveal && assignment != null && line.kind != LyricSceneLineKind.INTERLUDE && isLineTimed) {
        transformed.copy(opacity = transformed.opacity * revealOpacity)
      } else transformed
      val blur = if (activeForBlur == null || status == TimedElementStatus.ACTIVE || activeForBlur == index) 0.0
      else min(BLUR_MAX_PX, BLUR_MULTIPLIER * abs(index - activeForBlur))
      LyricLineScene(
        key = line.key,
        renderIndex = index,
        sourceLineIndex = line.sourceLineIndex,
        groupIndex = line.groupIndex,
        kind = line.kind,
        text = line.text,
        transliteratedText = line.transliteratedText,
        startMs = line.startMs.roundToLong(),
        endMs = line.endMs.roundToLong(),
        oppositeAligned = line.oppositeAligned,
        textDirection = textDirection,
        horizontalAlignment = when {
          options.fullscreenFocus -> LyricHorizontalAlignment.CENTER
          line.oppositeAligned -> LyricHorizontalAlignment.END
          else -> LyricHorizontalAlignment.START
        },
        laneInsetFraction = when {
          options.fullscreenFocus -> FULLSCREEN_FOCUS_HORIZONTAL_INSET
          hasDuetLines -> DUET_LANE_INSET
          else -> DEFAULT_LANE_INSET
        },
        status = status,
        progress = progress,
        lineGradientPercent = when (status) {
          TimedElementStatus.NOT_SUNG -> -20.0
          TimedElementStatus.ACTIVE -> if (options.reveal) -20 + 120 * progress else 100 * progress
          TimedElementStatus.SUNG -> 100.0
        },
        lineGlowGoal = LINE_GLOW.at(progressForStatus(status, progress)),
        contentOpacity = (when (status) {
          TimedElementStatus.NOT_SUNG -> NOT_SUNG_LINE_OPACITY
          TimedElementStatus.ACTIVE -> ACTIVE_LINE_OPACITY
          TimedElementStatus.SUNG -> SUNG_LINE_OPACITY
        }) * if (options.reveal && isLineTimed) revealOpacity else 1.0,
        gradient = LyricGradientFrame(
          angleDegrees = 180.0,
          positionPercent = when (status) {
            TimedElementStatus.NOT_SUNG -> -20.0
            TimedElementStatus.ACTIVE -> if (options.reveal) -20 + 120 * progress else 100 * progress
            TimedElementStatus.SUNG -> 100.0
          },
          leadingAlpha = if (isBackground) BACKGROUND_LEADING_ALPHA else DEFAULT_LEADING_ALPHA,
          trailingAlpha = if (isBackground) BACKGROUND_TRAILING_ALPHA else LINE_TRAILING_ALPHA,
        ),
        blurRadiusPx = blur,
        tokens = tokenScenes,
        visible = !options.fullscreenFocus || assignment != null,
        focusRole = assignment?.role ?: FocusRole.NONE,
        transitionRole = assignment?.transitionRole ?: FocusTransitionRole.NONE,
        focusTransform = finalTransform,
        revealCurrent = options.reveal && assignment?.role == FocusRole.CURRENT && line.kind != LyricSceneLineKind.BACKGROUND,
        preHidden = line.kind == LyricSceneLineKind.INTERLUDE && effectivePosition > line.endMs - PRE_HIDDEN_INTERLUDE_MS,
      )
    }

    val transitionKind = transitionKind(prepared, transition)
    var outro = FullscreenOutroFrame()
    var finalScenes = scenes
    if (options.fullscreenFocus && options.outroEnabled && options.durationMs != null && prepared.isNotEmpty()) {
      val finalLeadIndex = prepared.indexOfLast { it.kind == LyricSceneLineKind.VOCAL }
      if (finalLeadIndex >= 0) {
        val finalGroupEnd = audibleGroupEnd(prepared, finalLeadIndex)
        val outroStart = guaranteedOutroStart(finalGroupEnd.roundToLong(), options.durationMs)
        outro = fullscreenOutroFrame(
          rawPositionMs,
          outroStart,
          options.durationMs,
          startScale = if (options.reveal) 1.0 else 1.18,
        )
        if (outro.active) {
          finalScenes = scenes.mapIndexed { index, scene ->
            if (index == finalLeadIndex) scene.copy(
              visible = true,
              focusRole = FocusRole.CURRENT,
              transitionRole = FocusTransitionRole.NONE,
              focusTransform = focusTransform(FocusRole.CURRENT, reveal = options.reveal).copy(
                scale = outro.scale,
                opacity = outro.opacity,
              ),
              revealCurrent = options.reveal,
            ) else scene.copy(visible = false, focusRole = FocusRole.NONE, focusTransform = scene.focusTransform.copy(opacity = 0.0))
          }
        }
      }
    }

    return LyricsScene(
      positionMs = lyricsPositionMs,
      rawPositionMs = rawPositionMs,
      syncKind = document.syncKind,
      anchorRenderIndex = anchor,
      lines = finalScenes,
      transition = if (outro.active) FullscreenLineTransition(anchorIndex = anchor, toIndex = anchor) else transition,
      transitionKind = if (outro.active) null else transitionKind,
      outro = outro,
      hasDuetLines = hasDuetLines,
      hasRtlLines = scenes.any { it.textDirection == LyricTextDirection.RIGHT_TO_LEFT },
    )
  }

  private var preparedOptionsKey: Int? = null

  private fun LyricsSceneOptions.preparationKey(): Int =
    31 * minimalLyricsMode.hashCode() + synthesizeInterludes.hashCode()

  private fun LyricsSceneOptions.minimumInterludeMs(): Long = if (minimalLyricsMode) 5_000L else 3_000L

  private fun prepare(document: LyricsDocument, options: LyricsSceneOptions): List<PreparedLine> {
    if (document is StaticLyrics) return document.lines.mapIndexed { index, line ->
      PreparedLine("static:$index", index, index, LyricSceneLineKind.STATIC, line.text, line.transliteratedText)
    }

    val base: List<PreparedLine> = when (document) {
      is LineLyrics -> document.lines.mapIndexedNotNull { index, line ->
        if (line.text.isNonLyricPlaceholder()) return@mapIndexedNotNull null
        PreparedLine(
          key = "lead:$index",
          sourceLineIndex = index,
          groupIndex = index,
          kind = LyricSceneLineKind.VOCAL,
          text = line.text,
          transliteratedText = line.transliteratedText,
          startMs = line.startMs.toDouble(),
          endMs = line.endMs.toDouble(),
          oppositeAligned = line.oppositeAligned,
        )
      }

      is SyllableLyrics -> buildList<PreparedLine> {
        document.lines.forEachIndexed { index, group ->
          val hasDisplayableLead = !group.lead.text.isNonLyricPlaceholder()
          if (hasDisplayableLead) {
            add(
              PreparedLine(
                key = "lead:$index",
                sourceLineIndex = index,
                groupIndex = index,
                kind = LyricSceneLineKind.VOCAL,
                text = group.lead.text,
                transliteratedText = group.lead.transliteration,
                startMs = group.lead.startMs.toDouble(),
                endMs = group.lead.endMs.toDouble(),
                oppositeAligned = group.oppositeAligned || group.lead.oppositeAligned,
                tokens = group.lead.tokens,
              ),
            )
          }
          group.background.forEachIndexed { backgroundIndex, background ->
            if (background.text.isNonLyricPlaceholder()) return@forEachIndexed
            val displayTokens = background.tokens.withoutBackgroundPresentationParentheses()
            if (displayTokens.joinedLyricText().isNonLyricPlaceholder()) return@forEachIndexed
            add(
              PreparedLine(
                key = "background:$index:$backgroundIndex",
                sourceLineIndex = index,
                groupIndex = index,
                kind = LyricSceneLineKind.BACKGROUND,
                text = displayTokens.joinedLyricText(),
                transliteratedText = background.transliteration?.withoutOuterPresentationParentheses(),
                startMs = background.startMs.toDouble(),
                endMs = background.endMs.toDouble(),
                oppositeAligned = background.oppositeAligned,
                backgroundIndex = backgroundIndex,
                tokens = displayTokens,
              ),
            )
          }
        }
      }

      is StaticLyrics -> error("handled above")
    }
    if (!options.synthesizeInterludes || base.isEmpty()) return base

    val leads = base.filter { it.kind == LyricSceneLineKind.VOCAL }
    // A valid provider group can contain only a background vocal after its
    // empty/note-only lead placeholder is removed. It remains renderable and
    // audible, but there are no lead-to-lead boundaries from which to create
    // a synthetic interlude.
    if (leads.isEmpty()) return base
    val minimum = options.minimumInterludeMs().toDouble()
    val vocalIntervals = base
      .filter { it.kind == LyricSceneLineKind.VOCAL || it.kind == LyricSceneLineKind.BACKGROUND }
      .map { TimingInterval(it.startMs.roundToLong(), it.endMs.roundToLong()) }
    val interludesByFollowingGroup = mutableMapOf<Int, PreparedLine>()
    fun addSilentInterlude(
      key: String,
      precedingGroup: Int?,
      followingGroup: Int,
      startMs: Double,
      endMs: Double,
      oppositeAligned: Boolean,
    ) {
      val interval = TimingInterval(startMs.roundToLong(), endMs.roundToLong())
      if (!isInterludeFullySilent(interval, vocalIntervals, minimum.roundToLong())) return
      interludesByFollowingGroup[followingGroup] = interlude(
        key = key,
        precedingGroup = precedingGroup,
        startMs = startMs,
        endMs = endMs,
        opposite = oppositeAligned,
      )
    }
    val first = leads.first()
    if (first.startMs >= minimum) {
      addSilentInterlude(
        key = "interlude:initial",
        precedingGroup = null,
        followingGroup = first.groupIndex!!,
        startMs = 0.0,
        endMs = first.startMs,
        oppositeAligned = first.oppositeAligned,
      )
    }
    leads.zipWithNext().forEach { (line, next) ->
      if (next.startMs - line.endMs >= minimum) {
        addSilentInterlude(
          key = "interlude:${line.groupIndex}:${next.groupIndex}",
          precedingGroup = line.groupIndex,
          followingGroup = next.groupIndex!!,
          startMs = line.endMs,
          endMs = next.startMs,
          oppositeAligned = next.oppositeAligned,
        )
      }
    }
    return buildList<PreparedLine> {
      base.forEach { line ->
        if (line.kind == LyricSceneLineKind.VOCAL) {
          line.groupIndex?.let(interludesByFollowingGroup::get)?.let(::add)
        }
        add(line)
      }
    }
  }

  private fun interlude(key: String, precedingGroup: Int?, startMs: Double, endMs: Double, opposite: Boolean): PreparedLine {
    val total = endMs - startMs
    val base = total / 3
    val padding = -INTERLUDE_END_PADDING_MS / 3
    val dot1End = max(startMs, startMs + base + padding)
    val dot2End = max(dot1End, startMs + base * 2 + padding * 2)
    val dot3End = max(dot2End, endMs - INTERLUDE_END_PADDING_MS)
    return PreparedLine(
      key = key,
      sourceLineIndex = null,
      groupIndex = precedingGroup,
      kind = LyricSceneLineKind.INTERLUDE,
      // Canvas token ranges use ordinary lyric joining semantics. Keep an
      // explicit separator between dots so all three ranges exist and retain
      // the desktop dot-group spacing instead of mapping the third dot past
      // the end of a compact three-character layout string.
      text = "• • •",
      startMs = startMs,
      endMs = endMs,
      oppositeAligned = opposite,
      tokens = listOf(
        LyricToken("•", startMs.roundToLong(), dot1End.roundToLong()),
        LyricToken("•", dot1End.roundToLong(), dot2End.roundToLong()),
        LyricToken("•", dot2End.roundToLong(), dot3End.roundToLong()),
      ),
    )
  }

  private fun tokenScene(
    token: LyricToken,
    position: Double,
    options: LyricsSceneOptions,
    textDirection: LyricTextDirection,
    isBackground: Boolean,
  ): LyricTokenScene {
    val isDot = token.text == "•"
    val emphasisUnits = if (isDot) emptyList() else token.text.emphasisUnits()
    val letterCapable = isLetterCapable(
      text = token.text,
      letterCount = emphasisUnits.size,
      durationMs = token.endMs - token.startMs,
      simpleLyricsMode = options.simpleLyricsMode,
    )
    val animationStartMs = if (letterCapable && options.simpleLyricsMode) {
      token.startMs + SIMPLE_EMPHASIS_START_SHIFT_MS
    } else {
      token.startMs
    }
    val animationEndMs = when {
      !letterCapable -> token.endMs
      options.simpleLyricsMode -> token.endMs + SIMPLE_EMPHASIS_END_SHIFT_MS
      else -> token.endMs - EMPHASIS_END_ADVANCE_MS
    }
    val status = status(position, animationStartMs.toDouble(), animationEndMs.toDouble())
    val progress = progress(position, animationStartMs.toDouble(), animationEndMs.toDouble())
    val curveProgress = progressForStatus(status, progress)
    val scale = if (isDot) DOT_SCALE.at(curveProgress) else WORD_SCALE.at(curveProgress)
    val yOffset = if (isDot) DOT_Y_OFFSET.at(curveProgress) else {
      (if (options.simpleLyricsMode) SIMPLE_WORD_Y_OFFSET else WORD_Y_OFFSET).at(curveProgress)
    }
    val glow = if (isDot) DOT_GLOW.at(curveProgress) else WORD_GLOW.at(curveProgress)
    val opacity = if (isDot) {
      (if (options.simpleLyricsMode) SIMPLE_DOT_OPACITY else DOT_OPACITY).at(curveProgress)
    } else 1.0
    val gradient = when (status) {
      TimedElementStatus.NOT_SUNG -> if (options.simpleLyricsMode) -50.0 else -20.0
      TimedElementStatus.SUNG -> 100.0
      TimedElementStatus.ACTIVE -> (if (options.simpleLyricsMode) -50.0 else -20.0) + 120 * progress
    }
    val letters = if (letterCapable) {
      letterScenes(
        units = emphasisUnits,
        wordStatus = status,
        wordStartMs = animationStartMs.toDouble(),
        wordEndMs = animationEndMs.toDouble(),
        position = position,
        simpleLyricsMode = options.simpleLyricsMode,
        isBackground = isBackground,
        textDirection = textDirection,
      )
    } else {
      emptyList()
    }
    return LyricTokenScene(
      text = token.text,
      startMs = animationStartMs,
      endMs = animationEndMs,
      isPartOfWord = token.isPartOfWord,
      transliteratedText = token.transliteratedText,
      isDot = isDot,
      gradient = LyricGradientFrame(
        angleDegrees = if (textDirection == LyricTextDirection.RIGHT_TO_LEFT) -90.0 else 90.0,
        positionPercent = gradient,
        leadingAlpha = if (isBackground) BACKGROUND_LEADING_ALPHA else DEFAULT_LEADING_ALPHA,
        trailingAlpha = if (isBackground) BACKGROUND_TRAILING_ALPHA else DEFAULT_TRAILING_ALPHA,
      ),
      animation = TimedAnimationFrame(
        status = status,
        progress = progress,
        gradientPercent = gradient,
        scaleGoal = scale,
        yOffsetFontUnitsGoal = yOffset,
        glowGoal = glow,
        opacityGoal = opacity,
        revealVisible = !options.reveal || status != TimedElementStatus.NOT_SUNG,
        revealOpacity = if (options.reveal) (progress * 5).coerceIn(0.0, 1.0) else 1.0,
      ),
      letters = letters,
    )
  }

  /** Port of desktop IsLetterCapable + Emphasize + the proximity section of LyricsAnimator. */
  private fun letterScenes(
    units: List<String>,
    wordStatus: TimedElementStatus,
    wordStartMs: Double,
    wordEndMs: Double,
    position: Double,
    simpleLyricsMode: Boolean,
    isBackground: Boolean,
    textDirection: LyricTextDirection,
  ): List<LyricLetterScene> {
    val duration = wordEndMs - wordStartMs
    val letterDuration = duration / units.size
    val timings = units.indices.map { index ->
      val start = wordStartMs + index * letterDuration
      start to start + letterDuration
    }
    val activeLetterIndex = if (wordStatus == TimedElementStatus.ACTIVE) {
      timings.indexOfFirst { (start, end) -> status(position, start, end) == TimedElementStatus.ACTIVE }
    } else {
      -1
    }
    val activeLetterProgress = activeLetterIndex.takeIf { it >= 0 }?.let { index ->
      val (start, end) = timings[index]
      progress(position, start, end)
    } ?: 0.0

    return units.mapIndexed { index, text ->
      val (start, end) = timings[index]
      val letterStatus = status(position, start, end)
      val letterProgress = progress(position, start, end)
      val restingScale = LETTER_SCALE.at(0.0)
      val restingYOffset = LETTER_Y_OFFSET.at(0.0)
      val restingGlow = WORD_GLOW.at(0.0)
      var scale = restingScale
      var yOffset = restingYOffset
      var glow = restingGlow

      when (wordStatus) {
        TimedElementStatus.NOT_SUNG -> Unit
        TimedElementStatus.SUNG -> {
          scale = LETTER_SCALE.at(1.0)
          yOffset = LETTER_Y_OFFSET.at(1.0)
          glow = WORD_GLOW.at(1.0)
        }
        TimedElementStatus.ACTIVE -> {
          if (activeLetterIndex >= 0) {
            val curveProgress = if (simpleLyricsMode) {
              progress(position, wordStartMs, wordEndMs)
            } else {
              activeLetterProgress
            }
            val simpleLong = duration > SIMPLE_EMPHASIS_LONG_DURATION_MS
            val scaleStrength = when {
              !simpleLyricsMode -> 1.0
              simpleLong -> SIMPLE_LONG_SCALE_STRENGTH
              else -> SIMPLE_SHORT_SCALE_STRENGTH
            }
            val yOffsetStrength = when {
              !simpleLyricsMode -> 1.0
              simpleLong -> SIMPLE_LONG_Y_OFFSET_STRENGTH
              else -> SIMPLE_SHORT_Y_OFFSET_STRENGTH
            }
            val glowStrength = when {
              !simpleLyricsMode -> 1.0
              simpleLong -> SIMPLE_LONG_GLOW_STRENGTH
              else -> SIMPLE_SHORT_GLOW_STRENGTH
            }
            val baseScale = (if (simpleLyricsMode) SIMPLE_LETTER_SCALE else LETTER_SCALE).at(curveProgress) * scaleStrength
            val baseYOffset = (if (simpleLyricsMode) SIMPLE_LETTER_Y_OFFSET else LETTER_Y_OFFSET).at(curveProgress) * yOffsetStrength
            val baseGlow = WORD_GLOW.at(curveProgress) * glowStrength
            val distance = abs(index - activeLetterIndex).toDouble()
            val scaleFalloff = 1.0 / (1.0 + distance.pow(LETTER_SCALE_FALLOFF_POWER))
            val glowFalloff = 1.0 / (1.0 + distance * LETTER_GLOW_FALLOFF)
            scale = restingScale + (baseScale - restingScale) * scaleFalloff
            yOffset = restingYOffset + (baseYOffset - restingYOffset) * scaleFalloff
            glow = restingGlow + (baseGlow - restingGlow) * glowFalloff
          }
          if (letterStatus == TimedElementStatus.NOT_SUNG && !simpleLyricsMode) {
            scale = restingScale
            yOffset = restingYOffset
            glow = restingGlow
          } else if (letterStatus == TimedElementStatus.SUNG && activeLetterIndex < 0) {
            glow = WORD_GLOW.at(SUNG_LETTER_GLOW_PROGRESS)
          }
        }
      }

      val gradientPosition = when (letterStatus) {
        TimedElementStatus.NOT_SUNG -> if (simpleLyricsMode) -50.0 else -20.0
        TimedElementStatus.SUNG -> 100.0
        TimedElementStatus.ACTIVE -> {
          val resting = if (simpleLyricsMode) -50.0 else -20.0
          if (index == activeLetterIndex) resting + 120 * easeSinOut(letterProgress) else resting
        }
      }
      LyricLetterScene(
        text = text,
        startMs = start,
        endMs = end,
        gradient = LyricGradientFrame(
          angleDegrees = if (textDirection == LyricTextDirection.RIGHT_TO_LEFT) -90.0 else 90.0,
          positionPercent = gradientPosition,
          transitionWidthPercent = if (simpleLyricsMode) SIMPLE_GRADIENT_WIDTH_PERCENT else DEFAULT_GRADIENT_WIDTH_PERCENT,
          leadingAlpha = when {
            isBackground -> BACKGROUND_LEADING_ALPHA
            simpleLyricsMode -> SIMPLE_LEADING_ALPHA
            else -> DEFAULT_LEADING_ALPHA
          },
          trailingAlpha = when {
            isBackground -> BACKGROUND_TRAILING_ALPHA
            simpleLyricsMode -> SIMPLE_TRAILING_ALPHA
            else -> DEFAULT_TRAILING_ALPHA
          },
        ),
        animation = TimedAnimationFrame(
          status = letterStatus,
          progress = letterProgress,
          gradientPercent = gradientPosition,
          scaleGoal = scale,
          yOffsetFontUnitsGoal = yOffset,
          glowGoal = glow,
        ),
      )
    }
  }

  private fun isLetterCapable(
    text: String,
    letterCount: Int,
    durationMs: Long,
    simpleLyricsMode: Boolean,
  ): Boolean {
    if (letterCount == 0 || detectLyricTextDirection(text) == LyricTextDirection.RIGHT_TO_LEFT) return false
    return if (simpleLyricsMode) {
      letterCount <= SIMPLE_EMPHASIS_MAX_LETTERS && durationMs >= SIMPLE_EMPHASIS_MIN_DURATION_MS
    } else {
      durationMs >= EMPHASIS_MIN_DURATION_MS
    }
  }

  private fun String.emphasisUnits(): List<String> = buildList {
    var offset = 0
    while (offset < length) {
      val codePoint = this@emphasisUnits.lyricCodePointAt(offset)
      val next = offset + codePoint.lyricCodePointCharCount()
      add(substring(offset, next))
      offset = next
    }
  }

  private fun resolveFocusAnchor(lines: List<PreparedLine>, position: Double, minimumInterludeMs: Long): Int? {
    val direct = getScrollLine(lines, position, includeInterludes = true) ?: return null
    val line = lines[direct]
    if (line.kind != LyricSceneLineKind.INTERLUDE) return resolveToLead(lines, direct)
    getScrollLine(lines, position, includeInterludes = false)?.let { return resolveToLead(lines, it) }
    val interval = TimingInterval(line.startMs.roundToLong(), line.endMs.roundToLong())
    val vocals = lines.filter { it.kind == LyricSceneLineKind.VOCAL || it.kind == LyricSceneLineKind.BACKGROUND }
      .map { TimingInterval(it.startMs.roundToLong(), it.endMs.roundToLong()) }
    return direct.takeIf {
      position >= line.startMs && position < line.endMs &&
        isInterludeFullySilent(interval, vocals, minimumInterludeMs)
    }
  }

  private fun getScrollLine(lines: List<PreparedLine>, position: Double, includeInterludes: Boolean): Int? {
    val active = lines.indices.filter { index ->
      val line = lines[index]
      line.kind != LyricSceneLineKind.STATIC &&
        (includeInterludes || line.kind != LyricSceneLineKind.INTERLUDE) &&
        line.startMs <= position && line.endMs >= position
    }
    if (active.isEmpty()) return null
    val anchor = resolveToLead(lines, active.first())
    val lookahead = lookaheadLine(lines, anchor)
    if (lookahead == null || groupEnd(lines, anchor) <= lines[lookahead].startMs) return anchor
    val selected = if (active.last() - active.first() <= 1) active.first() else active.last()
    return resolveToLead(lines, selected)
  }

  private fun resolveToLead(lines: List<PreparedLine>, index: Int): Int {
    if (lines[index].kind != LyricSceneLineKind.BACKGROUND) return index
    val group = lines[index].groupIndex
    // A provider can encode a background-only group with an empty or musical-
    // note placeholder in the lead lane. Keep that real background audible and
    // focusable after the placeholder is removed instead of crashing while
    // looking for a lead row that intentionally no longer exists.
    return (index downTo 0).firstOrNull {
      lines[it].kind == LyricSceneLineKind.VOCAL && lines[it].groupIndex == group
    } ?: index
  }

  private fun groupEnd(lines: List<PreparedLine>, leadIndex: Int): Double {
    var end = lines[leadIndex].endMs
    var index = leadIndex + 1
    while (index < lines.size && lines[index].kind == LyricSceneLineKind.BACKGROUND) {
      end = max(end, lines[index].endMs)
      index++
    }
    return end
  }

  private fun audibleGroupEnd(lines: List<PreparedLine>, leadIndex: Int): Double {
    var end = audibleEnd(lines[leadIndex])
    var index = leadIndex + 1
    while (index < lines.size && lines[index].kind == LyricSceneLineKind.BACKGROUND) {
      end = max(end, audibleEnd(lines[index]))
      index++
    }
    return end
  }

  private fun audibleEnd(line: PreparedLine): Double =
    line.tokens.maxOfOrNull { it.endMs }?.toDouble() ?: line.endMs

  private fun lookaheadLine(lines: List<PreparedLine>, leadIndex: Int): Int? {
    var remaining = 2
    for (index in leadIndex + 1 until lines.size) {
      if (lines[index].kind == LyricSceneLineKind.BACKGROUND) continue
      remaining--
      if (remaining == 0) return index
    }
    return null
  }

  private fun nearestLead(lines: List<PreparedLine>, index: Int, direction: Int): Int? {
    var current = index + direction
    while (current in lines.indices) {
      if (lines[current].kind == LyricSceneLineKind.VOCAL) return current
      current += direction
    }
    return null
  }

  private fun previousFocusLine(lines: List<PreparedLine>, index: Int): Int? {
    for (candidate in index - 1 downTo 0) {
      when (lines[candidate].kind) {
        LyricSceneLineKind.INTERLUDE -> return null
        LyricSceneLineKind.VOCAL -> return candidate
        else -> Unit
      }
    }
    return null
  }

  private fun persistentPrevious(lines: List<PreparedLine>, index: Int): Int? {
    previousFocusLine(lines, index)?.let { return it }
    var interludeIndex = index - 1
    while (interludeIndex >= 0 && lines[interludeIndex].kind == LyricSceneLineKind.BACKGROUND) interludeIndex--
    if (lines.getOrNull(interludeIndex)?.kind != LyricSceneLineKind.INTERLUDE) return null
    return previousFocusLine(lines, interludeIndex)
  }

  private fun focusAssignments(
    lines: List<PreparedLine>,
    anchor: Int?,
    transition: FullscreenLineTransition,
    position: Double,
    options: LyricsSceneOptions,
  ): Map<Int, Assignment> {
    val desired = linkedMapOf<Int, Assignment>()
    fun add(index: Int?, role: FocusRole, transitionRole: FocusTransitionRole = FocusTransitionRole.NONE) {
      if (index == null || index !in lines.indices) return
      desired[index] = Assignment(role, transitionRole)
      if (lines[index].kind != LyricSceneLineKind.VOCAL) return
      val attachStartedBackgrounds = role == FocusRole.CURRENT || transitionRole == FocusTransitionRole.OUTGOING
      var child = index + 1
      while (child < lines.size && lines[child].kind == LyricSceneLineKind.BACKGROUND) {
        if (attachStartedBackgrounds && position >= lines[child].startMs) {
          desired[child] = Assignment(role, transitionRole)
        }
        child++
      }
    }

    if (anchor != null) {
      if (!options.reveal) add(persistentPrevious(lines, anchor), FocusRole.PREVIOUS)
      add(anchor, FocusRole.CURRENT)
      if (!options.reveal) add(nearestLead(lines, anchor, 1), FocusRole.NEXT)
    }
    val outgoing = transition.fromIndex
    val incoming = transition.toIndex
    if (!transition.active || outgoing == null || incoming == null || outgoing !in lines.indices || incoming !in lines.indices) {
      return desired
    }
    val kind = transitionKind(lines, transition) ?: return desired
    when (kind) {
      FocusTransitionKind.ENTER_INTERLUDE -> {
        if (!options.reveal) add(persistentPrevious(lines, outgoing), FocusRole.PREVIOUS, FocusTransitionRole.DEPARTING)
        add(outgoing, if (options.reveal) FocusRole.CURRENT else FocusRole.PREVIOUS, FocusTransitionRole.OUTGOING)
        add(incoming, FocusRole.CURRENT, FocusTransitionRole.INCOMING)
      }

      FocusTransitionKind.EXIT_INTERLUDE -> {
        if (!options.reveal) add(persistentPrevious(lines, outgoing), FocusRole.PREVIOUS)
        if (position >= lines[outgoing].startMs && position < lines[outgoing].endMs) {
          add(outgoing, FocusRole.CURRENT, FocusTransitionRole.OUTGOING)
        }
        add(incoming, FocusRole.CURRENT, FocusTransitionRole.INCOMING)
        if (!options.reveal) add(nearestLead(lines, incoming, 1), FocusRole.NEXT, FocusTransitionRole.ENTERING)
      }

      FocusTransitionKind.LINE -> if (options.reveal) {
        add(outgoing, FocusRole.CURRENT, FocusTransitionRole.OUTGOING)
        add(incoming, FocusRole.CURRENT, FocusTransitionRole.INCOMING)
      } else {
        add(persistentPrevious(lines, outgoing), FocusRole.PREVIOUS, FocusTransitionRole.DEPARTING)
        add(outgoing, if (transition.direction > 0) FocusRole.PREVIOUS else FocusRole.NEXT, FocusTransitionRole.OUTGOING)
        add(incoming, FocusRole.CURRENT, FocusTransitionRole.INCOMING)
        add(nearestLead(lines, incoming, 1), FocusRole.NEXT, FocusTransitionRole.ENTERING)
      }
    }
    return desired
  }

  private fun transitionKind(lines: List<PreparedLine>, transition: FullscreenLineTransition): FocusTransitionKind? {
    if (!transition.active) return null
    val fromInterlude = lines.getOrNull(transition.fromIndex ?: -1)?.kind == LyricSceneLineKind.INTERLUDE
    val toInterlude = lines.getOrNull(transition.toIndex ?: -1)?.kind == LyricSceneLineKind.INTERLUDE
    return when {
      !fromInterlude && toInterlude -> FocusTransitionKind.ENTER_INTERLUDE
      fromInterlude && !toInterlude -> FocusTransitionKind.EXIT_INTERLUDE
      else -> FocusTransitionKind.LINE
    }
  }

  private fun transitionTransform(
    base: FocusTransform,
    role: FocusTransitionRole,
    kind: FocusTransitionKind?,
    transition: FullscreenLineTransition,
    line: PreparedLine,
    options: LyricsSceneOptions,
  ): FocusTransform {
    if (role == FocusTransitionRole.NONE || kind == null) return base
    val p = transition.progress.coerceIn(0.0, 1.0)
    val direction = if (transition.direction < 0) -1.0 else 1.0
    if (options.reducedMotion) {
      val quick = (p * 5).coerceIn(0.0, 1.0)
      val opacity = when (role) {
        FocusTransitionRole.INCOMING, FocusTransitionRole.ENTERING -> base.opacity * quick
        FocusTransitionRole.OUTGOING, FocusTransitionRole.DEPARTING -> base.opacity * (1 - quick)
        FocusTransitionRole.NONE -> base.opacity
      }
      return base.copy(yViewportFraction = focusTransform(baseRoleForReduced(role),
        isBackground = line.kind == LyricSceneLineKind.BACKGROUND,
        backgroundIndex = line.backgroundIndex,
        isInterlude = line.kind == LyricSceneLineKind.INTERLUDE,
        reveal = options.reveal).yViewportFraction, opacity = opacity)
    }
    if (options.reveal) return when (kind) {
      FocusTransitionKind.ENTER_INTERLUDE -> when (role) {
        FocusTransitionRole.OUTGOING -> base.copy(yViewportFraction = base.yViewportFraction - 0.42 * p, opacity = (1 - 1.8 * p).coerceIn(0.0, 1.0))
        FocusTransitionRole.INCOMING -> interludeIncoming(base, p)
        else -> base
      }
      FocusTransitionKind.EXIT_INTERLUDE -> when (role) {
        FocusTransitionRole.OUTGOING -> base.copy(opacity = 0.62 * (1 - p))
        FocusTransitionRole.INCOMING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * 0.12, opacity = p)
        else -> base
      }
      FocusTransitionKind.LINE -> when (role) {
        FocusTransitionRole.INCOMING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * direction * 0.06, opacity = p * if (line.kind == LyricSceneLineKind.BACKGROUND) 0.58 else 1.0)
        FocusTransitionRole.OUTGOING -> base.copy(yViewportFraction = base.yViewportFraction + p * direction * -0.04, opacity = (1 - p) * if (line.kind == LyricSceneLineKind.BACKGROUND) 0.58 else 1.0)
        else -> base
      }
    }
    val transformed = when (kind) {
      FocusTransitionKind.LINE -> when (role) {
        FocusTransitionRole.DEPARTING -> base.copy(yViewportFraction = base.yViewportFraction + p * direction * -0.38, opacity = 0.3 * (1 - p), scale = 0.78)
        FocusTransitionRole.INCOMING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * (0.035 + direction * 0.35), opacity = 0.3 + 0.7 * p, scale = 0.78 + 0.4 * p)
        FocusTransitionRole.OUTGOING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * (-0.035 + direction * 0.35), opacity = 0.3 + 0.7 * (1 - p), scale = 0.78 + 0.4 * (1 - p))
        FocusTransitionRole.ENTERING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * direction * 0.38, opacity = 0.3 * p, scale = 0.78)
        FocusTransitionRole.NONE -> base
      }
      FocusTransitionKind.ENTER_INTERLUDE -> when (role) {
        FocusTransitionRole.DEPARTING -> base.copy(yViewportFraction = base.yViewportFraction - 0.38 * p, opacity = 0.3 * (1 - p), scale = 0.78)
        FocusTransitionRole.OUTGOING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * 0.315, opacity = 0.3 + 0.7 * (1 - p), scale = 0.78 + 0.4 * (1 - p))
        FocusTransitionRole.INCOMING -> interludeIncoming(base, p)
        else -> base
      }
      FocusTransitionKind.EXIT_INTERLUDE -> when (role) {
        FocusTransitionRole.OUTGOING -> base.copy(opacity = 0.62 * (1 - p), scale = 1.0)
        FocusTransitionRole.INCOMING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * 0.385, opacity = 0.3 + 0.7 * p, scale = 0.78 + 0.4 * p)
        FocusTransitionRole.ENTERING -> base.copy(yViewportFraction = base.yViewportFraction + (1 - p) * 0.38, opacity = 0.3 * p, scale = 0.78)
        else -> base
      }
    }
    if (line.kind != LyricSceneLineKind.BACKGROUND) return transformed
    return when (role) {
      FocusTransitionRole.INCOMING, FocusTransitionRole.ENTERING -> transformed.copy(opacity = 0.58 * p)
      FocusTransitionRole.OUTGOING, FocusTransitionRole.DEPARTING -> transformed.copy(opacity = 0.58 * (1 - p))
      FocusTransitionRole.NONE -> transformed
    }
  }

  private fun interludeIncoming(base: FocusTransform, progress: Double): FocusTransform {
    val phase = ((progress - 0.42) * 1.724).coerceIn(0.0, 1.0)
    return base.copy(
      yViewportFraction = base.yViewportFraction + (1 - phase) * 0.1,
      opacity = phase * 0.62,
      scale = 0.88 + phase * 0.12,
    )
  }

  private fun baseRoleForReduced(role: FocusTransitionRole): FocusRole = when (role) {
    FocusTransitionRole.DEPARTING, FocusTransitionRole.OUTGOING -> FocusRole.PREVIOUS
    FocusTransitionRole.INCOMING -> FocusRole.CURRENT
    FocusTransitionRole.ENTERING -> FocusRole.NEXT
    FocusTransitionRole.NONE -> FocusRole.NONE
  }

  private fun status(position: Double, start: Double, end: Double): TimedElementStatus = when {
    position < start -> TimedElementStatus.NOT_SUNG
    position >= end -> TimedElementStatus.SUNG
    else -> TimedElementStatus.ACTIVE
  }

  private fun progress(position: Double, start: Double, end: Double): Double =
    if (end <= start) if (position >= end) 1.0 else 0.0 else ((position - start) / (end - start)).coerceIn(0.0, 1.0)

  private fun progressForStatus(status: TimedElementStatus, progress: Double): Double = when (status) {
    TimedElementStatus.NOT_SUNG -> 0.0
    TimedElementStatus.ACTIVE -> progress
    TimedElementStatus.SUNG -> 1.0
  }

  private data class Assignment(val role: FocusRole, val transitionRole: FocusTransitionRole)

  private data class PreparedLine(
    val key: String,
    val sourceLineIndex: Int?,
    val groupIndex: Int?,
    val kind: LyricSceneLineKind,
    val text: String,
    val transliteratedText: String? = null,
    val startMs: Double = 0.0,
    val endMs: Double = 0.0,
    val oppositeAligned: Boolean = false,
    val backgroundIndex: Int = 0,
    val tokens: List<LyricToken> = emptyList(),
  ) {
    fun toStaticScene(index: Int) = LyricLineScene(
      key = key,
      renderIndex = index,
      sourceLineIndex = sourceLineIndex,
      groupIndex = groupIndex,
      kind = kind,
      text = text,
      transliteratedText = transliteratedText,
      oppositeAligned = oppositeAligned,
      textDirection = detectLyricTextDirection(text),
      horizontalAlignment = if (oppositeAligned) LyricHorizontalAlignment.END else LyricHorizontalAlignment.START,
      contentOpacity = 1.0,
      gradient = LyricGradientFrame(
        angleDegrees = 180.0,
        positionPercent = 100.0,
        leadingAlpha = 1.0,
        trailingAlpha = 1.0,
      ),
    )
  }

  companion object {
    const val SIMPLE_LYRICS_DELAY_MS = 33.5
    const val PRE_HIDDEN_INTERLUDE_MS = 500.0
    const val INTERLUDE_END_PADDING_MS = 550.0
    const val BLUR_MULTIPLIER = 1.25
    const val BLUR_MAX_PX = BLUR_MULTIPLIER * 5 + BLUR_MULTIPLIER * 0.465
    const val NOT_SUNG_LINE_OPACITY = 0.51
    const val ACTIVE_LINE_OPACITY = 1.0
    const val SUNG_LINE_OPACITY = 0.497
    const val DEFAULT_LEADING_ALPHA = 0.85
    const val DEFAULT_TRAILING_ALPHA = 0.5
    const val LINE_TRAILING_ALPHA = 0.35
    const val BACKGROUND_LEADING_ALPHA = 0.6
    const val BACKGROUND_TRAILING_ALPHA = 0.3
    private const val DEFAULT_GRADIENT_WIDTH_PERCENT = 20.0
    private const val SIMPLE_GRADIENT_WIDTH_PERCENT = 50.0
    private const val SIMPLE_LEADING_ALPHA = 1.0
    private const val SIMPLE_TRAILING_ALPHA = 0.3
    const val DEFAULT_LANE_INSET = 0.05
    const val DUET_LANE_INSET = 0.15
    const val FULLSCREEN_FOCUS_HORIZONTAL_INSET = 0.07

    private const val EMPHASIS_MIN_DURATION_MS = 1_000L
    private const val EMPHASIS_END_ADVANCE_MS = 250L
    private const val SIMPLE_EMPHASIS_MIN_DURATION_MS = 1_050L
    private const val SIMPLE_EMPHASIS_MAX_LETTERS = 12
    private const val SIMPLE_EMPHASIS_START_SHIFT_MS = 21L
    private const val SIMPLE_EMPHASIS_END_SHIFT_MS = 40L
    private const val LETTER_SCALE_FALLOFF_POWER = 2.8
    private const val LETTER_GLOW_FALLOFF = 0.9
    private const val SUNG_LETTER_GLOW_PROGRESS = 0.2
    private const val SIMPLE_EMPHASIS_LONG_DURATION_MS = 1_500.0
    private const val SIMPLE_LONG_SCALE_STRENGTH = 1.103
    private const val SIMPLE_LONG_Y_OFFSET_STRENGTH = 0.45
    private const val SIMPLE_LONG_GLOW_STRENGTH = 0.4
    private const val SIMPLE_SHORT_SCALE_STRENGTH = 1.09
    private const val SIMPLE_SHORT_Y_OFFSET_STRENGTH = 0.1
    private const val SIMPLE_SHORT_GLOW_STRENGTH = 0.285

    private val WORD_SCALE = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.95), AnimationPoint(0.7, 1.0505), AnimationPoint(1.0, 1.0),
    ))
    private val WORD_Y_OFFSET = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.01), AnimationPoint(0.9, -1.0 / 60), AnimationPoint(1.0, 0.0),
    ))
    private val SIMPLE_WORD_Y_OFFSET = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.01), AnimationPoint(1.0, -0.033),
    ))
    private val WORD_GLOW = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.0), AnimationPoint(0.15, 1.0), AnimationPoint(0.6, 1.0), AnimationPoint(1.0, 0.0),
    ))
    private val LETTER_SCALE = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.95), AnimationPoint(0.7, 1.175), AnimationPoint(1.0, 1.0),
    ))
    private val SIMPLE_LETTER_SCALE = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.95), AnimationPoint(0.7, 1.07), AnimationPoint(1.0, 1.0),
    ))
    private val LETTER_Y_OFFSET = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.01), AnimationPoint(0.9, -1.0 / 56), AnimationPoint(1.0, 0.0),
    ))
    private val SIMPLE_LETTER_Y_OFFSET = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.01), AnimationPoint(0.9, -1.0 / 62), AnimationPoint(1.0, 0.0),
    ))
    private val DOT_SCALE = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.75), AnimationPoint(0.7, 1.05), AnimationPoint(1.0, 1.0),
    ))
    private val DOT_Y_OFFSET = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.0), AnimationPoint(0.9, -0.12), AnimationPoint(1.0, 0.0),
    ))
    private val DOT_GLOW = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.0), AnimationPoint(0.6, 1.0), AnimationPoint(1.0, 1.0),
    ))
    private val DOT_OPACITY = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.35), AnimationPoint(0.6, 1.0), AnimationPoint(1.0, 1.0),
    ))
    private val SIMPLE_DOT_OPACITY = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.27), AnimationPoint(0.6, 1.0), AnimationPoint(1.0, 1.0),
    ))
    private val LINE_GLOW = NaturalCubicSpline(listOf(
      AnimationPoint(0.0, 0.0), AnimationPoint(0.5, 1.0), AnimationPoint(1.0, 0.0),
    ))
  }
}

/**
 * Provider payloads sometimes contain a timed blank or a music-note marker in
 * place of an instrumental break. These rows are not lyrics: retaining them
 * would split the silence window and suppress the renderer's three dots.
 *
 * A note decorating real words is deliberately retained. Only an invisible
 * string, or a string made entirely from note glyphs plus punctuation/marks,
 * is considered a placeholder.
 */
private fun String.isNonLyricPlaceholder(): Boolean {
  var offset = 0
  var hasVisibleCodePoint = false
  var hasMusicNote = false
  while (offset < length) {
    val codePoint = lyricCodePointAt(offset)
    offset += codePoint.lyricCodePointCharCount()
    if (codePoint.isInvisibleLyricCodePoint()) continue
    hasVisibleCodePoint = true
    if (codePoint.isMusicNoteCodePoint()) {
      hasMusicNote = true
      continue
    }
    if (codePoint.isPlaceholderDecorationCodePoint()) continue
    return false
  }
  return !hasVisibleCodePoint || hasMusicNote
}

private fun String.isInvisibleLyricText(): Boolean {
  var offset = 0
  while (offset < length) {
    val codePoint = lyricCodePointAt(offset)
    if (!codePoint.isInvisibleLyricCodePoint()) return false
    offset += codePoint.lyricCodePointCharCount()
  }
  return true
}

private fun Int.isInvisibleLyricCodePoint(): Boolean = isInvisibleUnicodeScalar()

private fun Int.isMusicNoteCodePoint(): Boolean =
  this in 0x2669..0x266c || this in 0x1d100..0x1d1ff || this in 0x1f3b5..0x1f3b6

private fun Int.isPlaceholderDecorationCodePoint(): Boolean = isDecorationUnicodeScalar()

/** Keep provider/model text intact and remove wrappers only from scene tokens. */
private fun List<LyricToken>.withoutBackgroundPresentationParentheses(): List<LyricToken> {
  var result = this
  while (true) {
    val pair = result.joinedLyricText().outerPresentationParentheses() ?: return result
    val firstTokenIndex = result.indexOfFirst { token -> token.text.hasPresentationCharacter() }
    val lastTokenIndex = result.indexOfLast { token -> token.text.hasPresentationCharacter() }
    if (firstTokenIndex < 0 || lastTokenIndex < 0) return result

    val mutable = result.toMutableList()
    if (firstTokenIndex == lastTokenIndex) {
      var text = mutable[firstTokenIndex].text
      val closeIndex = text.indexOfLast { character -> !character.isPresentationWhitespace() }
      if (closeIndex < 0 || text[closeIndex] != pair.second) return result
      text = text.removeRange(closeIndex, closeIndex + 1)
      val openIndex = text.indexOfFirst { character -> !character.isPresentationWhitespace() }
      if (openIndex < 0 || text[openIndex] != pair.first) return result
      mutable[firstTokenIndex] = mutable[firstTokenIndex].copy(
        text = text.removeRange(openIndex, openIndex + 1),
      )
    } else {
      val first = mutable[firstTokenIndex]
      val openIndex = first.text.indexOfFirst { character -> !character.isPresentationWhitespace() }
      val last = mutable[lastTokenIndex]
      val closeIndex = last.text.indexOfLast { character -> !character.isPresentationWhitespace() }
      if (openIndex < 0 || closeIndex < 0 ||
        first.text[openIndex] != pair.first || last.text[closeIndex] != pair.second
      ) return result
      mutable[firstTokenIndex] = first.copy(text = first.text.removeRange(openIndex, openIndex + 1))
      mutable[lastTokenIndex] = last.copy(text = last.text.removeRange(closeIndex, closeIndex + 1))
    }
    result = mutable.filterNot { token -> token.text.isInvisibleLyricText() }
  }
}

private fun List<LyricToken>.joinedLyricText(): String = buildString {
  this@joinedLyricText.forEachIndexed { index, token ->
    if (index > 0 && !this@joinedLyricText[index - 1].isPartOfWord) append(' ')
    append(token.text)
  }
}.trim()

private fun String.withoutOuterPresentationParentheses(): String {
  var result = trim()
  while (true) {
    result.outerPresentationParentheses() ?: return result
    result = result.substring(1, result.length - 1).trim()
  }
}

private fun String.outerPresentationParentheses(): Pair<Char, Char>? {
  val value = trim()
  if (value.length < 2) return null
  val pair = when (value.first()) {
    '(' -> '(' to ')'
    '（' -> '（' to '）'
    else -> return null
  }
  if (value.last() != pair.second) return null
  var depth = 0
  value.forEachIndexed { index, character ->
    when (character) {
      pair.first -> depth++
      pair.second -> {
        depth--
        if (depth < 0 || (depth == 0 && index != value.lastIndex)) return null
      }
    }
  }
  return pair.takeIf { depth == 0 }
}

private fun String.hasPresentationCharacter(): Boolean = any { !it.isPresentationWhitespace() }

private fun Char.isPresentationWhitespace(): Boolean = code.isInvisibleUnicodeScalar()

/**
 * Mirrors the desktop renderer's first-strong-character direction check.
 * Digits, whitespace, and its common punctuation set are deliberately neutral.
 */
fun detectLyricTextDirection(text: String): LyricTextDirection {
  for (character in text) {
    if (character.isDigit() || character.isWhitespace() || character in DIRECTION_NEUTRAL_PUNCTUATION) continue
    return if (character.code in RTL_CODE_POINT_RANGES) {
      LyricTextDirection.RIGHT_TO_LEFT
    } else {
      LyricTextDirection.LEFT_TO_RIGHT
    }
  }
  return LyricTextDirection.LEFT_TO_RIGHT
}

private val DIRECTION_NEUTRAL_PUNCTUATION = setOf(
  ',', '.', ';', ':', '?', '!', '(', ')', '[', ']', '{', '}', '"', '\'', '\\', '/', '<', '>',
  '@', '#', '$', '%', '^', '&', '*', '_', '=', '+', '-',
)

private val RTL_CODE_POINT_RANGES = listOf(
  0x0590..0x05ff,
  0x0600..0x06ff,
  0x0750..0x077f,
  0x08a0..0x08ff,
  0xfb1d..0xfb4f,
  0xfb50..0xfdff,
  0xfe70..0xfeff,
)

private operator fun List<IntRange>.contains(codePoint: Int): Boolean = any { codePoint in it }
