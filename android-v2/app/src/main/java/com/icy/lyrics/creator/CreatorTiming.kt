package com.icy.lyrics.creator

enum class CreatorTimingAction {
  START,
  END,
  END_AND_NEXT,
}

data class CreatorTimingTarget(
  val lineIndex: Int,
  val tokenIndex: Int,
  val fragmentIndex: Int,
  val isBackground: Boolean,
  val isSecondSpeaker: Boolean,
  val fragment: CreatorFragment,
  val label: String,
)

data class CreatorTimingResult(
  val project: CreatorProject,
  val targetIndex: Int,
)

data class CreatorTimingOptions(
  /** Positive output latency heard by the user. It is subtracted when capturing. */
  val headphoneDelayMs: Long = 0L,
  val ignoreBackground: Boolean = false,
)

enum class CreatorTimingTargetStatus {
  UNTIMED,
  PARTIAL,
  INVALID,
  OVERLAP,
  TIMED,
}

data class CreatorPlaybackActivity(
  val fragmentIds: Set<String>,
  val tokenIds: Set<String>,
  val lineIds: Set<String>,
)

fun creatorTimingTargets(
  project: CreatorProject,
  ignoreBackground: Boolean = false,
): List<CreatorTimingTarget> = buildList {
  project.lines.forEachIndexed { lineIndex, line ->
    if (ignoreBackground && line.isBackground) return@forEachIndexed
    line.tokens.forEachIndexed { tokenIndex, token ->
      token.fragments.forEachIndexed { fragmentIndex, fragment ->
        add(
          CreatorTimingTarget(
            lineIndex = lineIndex,
            tokenIndex = tokenIndex,
            fragmentIndex = fragmentIndex,
            isBackground = line.isBackground,
            isSecondSpeaker = line.isSecondSpeaker,
            fragment = fragment,
            label = fragment.text.ifEmpty { "Empty fragment" },
          ),
        )
      }
    }
  }
}

/**
 * Converts the raw MediaSession playhead to the canonical media timestamp.
 * If headphones render audio late, the playhead is ahead when the user taps,
 * so a positive delay must be subtracted rather than added.
 */
fun creatorCapturePosition(playbackPositionMs: Long, headphoneDelayMs: Long): Long =
  (playbackPositionMs.coerceAtLeast(0L) - headphoneDelayMs.coerceAtLeast(0L)).coerceAtLeast(0L)

fun applyCreatorTimingAction(
  project: CreatorProject,
  targetIndex: Int,
  action: CreatorTimingAction,
  playbackPositionMs: Long,
  options: CreatorTimingOptions = CreatorTimingOptions(),
): CreatorTimingResult {
  val targets = creatorTimingTargets(project, options.ignoreBackground)
  if (targets.isEmpty()) return CreatorTimingResult(project, 0)

  val index = targetIndex.coerceIn(targets.indices)
  val time = creatorCapturePosition(playbackPositionMs, options.headphoneDelayMs)
  var nextProject = updateCreatorFragment(project, targets[index]) { fragment ->
    when (action) {
      CreatorTimingAction.START -> fragment.copy(
        startTimeMs = time,
        endTimeMs = fragment.endTimeMs?.takeIf { it >= time },
      )

      CreatorTimingAction.END,
      CreatorTimingAction.END_AND_NEXT,
      -> fragment.copy(endTimeMs = maxOf(time, fragment.startTimeMs ?: time))
    }
  }

  if (action != CreatorTimingAction.END_AND_NEXT) {
    return CreatorTimingResult(nextProject, index)
  }

  val nextIndex = (index + 1).coerceAtMost(targets.lastIndex)
  if (nextIndex != index) {
    val refreshedTarget = creatorTimingTargets(nextProject, options.ignoreBackground)[nextIndex]
    nextProject = updateCreatorFragment(nextProject, refreshedTarget) { fragment ->
      fragment.copy(
        startTimeMs = time,
        endTimeMs = fragment.endTimeMs?.takeIf { it >= time },
      )
    }
  }
  return CreatorTimingResult(nextProject, nextIndex)
}

private fun updateCreatorFragment(
  project: CreatorProject,
  target: CreatorTimingTarget,
  update: (CreatorFragment) -> CreatorFragment,
): CreatorProject {
  val line = project.lines.getOrNull(target.lineIndex) ?: return project
  val token = line.tokens.getOrNull(target.tokenIndex) ?: return project
  if (target.fragmentIndex !in token.fragments.indices) return project

  val fragments = token.fragments.toMutableList().apply {
    this[target.fragmentIndex] = update(this[target.fragmentIndex])
  }
  val tokens = line.tokens.toMutableList().apply {
    this[target.tokenIndex] = token.copy(fragments = fragments)
  }
  val lines = project.lines.toMutableList().apply {
    this[target.lineIndex] = line.copy(tokens = tokens).synchronizeTiming()
  }
  return project.copy(lines = lines)
}

fun classifyCreatorTimingTarget(
  targets: List<CreatorTimingTarget>,
  targetIndex: Int,
): CreatorTimingTargetStatus {
  val target = targets.getOrNull(targetIndex) ?: return CreatorTimingTargetStatus.UNTIMED
  val start = target.fragment.startTimeMs
  val end = target.fragment.endTimeMs
  if (start == null && end == null) return CreatorTimingTargetStatus.UNTIMED
  if (start == null || end == null) return CreatorTimingTargetStatus.PARTIAL
  if (start < 0L || end <= start) return CreatorTimingTargetStatus.INVALID

  val previous = targets.getOrNull(targetIndex - 1)
  if (
    previous?.lineIndex == target.lineIndex &&
    previous.fragment.endTimeMs?.let { start < it } == true
  ) {
    return CreatorTimingTargetStatus.OVERLAP
  }
  return CreatorTimingTargetStatus.TIMED
}

fun creatorTimingErrorIndexes(
  project: CreatorProject,
  ignoreBackground: Boolean = false,
): Set<Int> {
  val targets = creatorTimingTargets(project, ignoreBackground)
  return targets.indices.filterTo(linkedSetOf()) { index ->
    classifyCreatorTimingTarget(targets, index) in setOf(
      CreatorTimingTargetStatus.PARTIAL,
      CreatorTimingTargetStatus.INVALID,
      CreatorTimingTargetStatus.OVERLAP,
    )
  }
}

fun isCreatorPlaybackIntervalActive(startMs: Long?, endMs: Long?, positionMs: Long): Boolean =
  startMs != null && endMs != null && startMs >= 0L && endMs > startMs &&
    positionMs >= startMs && positionMs < endMs

fun creatorPlaybackActivity(project: CreatorProject, positionMs: Long): CreatorPlaybackActivity {
  val fragments = linkedSetOf<String>()
  val tokens = linkedSetOf<String>()
  val lines = linkedSetOf<String>()
  if (positionMs < 0L) return CreatorPlaybackActivity(fragments, tokens, lines)

  project.lines.forEach { line ->
    line.tokens.forEach { token ->
      token.fragments.forEach { fragment ->
        if (isCreatorPlaybackIntervalActive(fragment.startTimeMs, fragment.endTimeMs, positionMs)) {
          fragments += fragment.id
          tokens += token.id
          lines += line.id
        }
      }
    }
  }
  return CreatorPlaybackActivity(fragments, tokens, lines)
}
