package com.icy.lyrics.creator

enum class CreatorPreviewMode {
  EMPTY,
  STATIC,
  HYBRID,
  FULLY_TIMED,
}

/** A project is previewable as soon as it contains visible lyric text. */
fun creatorPreviewMode(project: CreatorProject): CreatorPreviewMode {
  val fragments = project.lines
    .flatMap(CreatorLine::tokens)
    .flatMap(CreatorToken::fragments)
    .filter { it.text.isNotBlank() }
  if (fragments.isEmpty()) return CreatorPreviewMode.EMPTY
  val validCount = fragments.count(::creatorFragmentHasValidTiming)
  return when (validCount) {
    0 -> CreatorPreviewMode.STATIC
    fragments.size -> CreatorPreviewMode.FULLY_TIMED
    else -> CreatorPreviewMode.HYBRID
  }
}

fun creatorFragmentHasValidTiming(fragment: CreatorFragment): Boolean {
  val start = fragment.startTimeMs ?: return false
  val end = fragment.endTimeMs ?: return false
  return start >= 0L && end > start
}

/**
 * Returns null for unfinished/invalid fragments so a preview can keep them
 * visible without pretending they have timing. Valid fragments progress from
 * zero to one with the media playhead.
 */
fun creatorFragmentPreviewProgress(fragment: CreatorFragment, positionMs: Long): Float? {
  if (!creatorFragmentHasValidTiming(fragment)) return null
  val start = requireNotNull(fragment.startTimeMs)
  val end = requireNotNull(fragment.endTimeMs)
  return ((positionMs - start).toDouble() / (end - start).toDouble())
    .coerceIn(0.0, 1.0)
    .toFloat()
}

fun CreatorTimingTargetStatus.isInvalidForCreatorDisplay(): Boolean =
  this != CreatorTimingTargetStatus.TIMED

fun CreatorTimingTargetStatus.creatorDisplayLabel(): String = when (this) {
  CreatorTimingTargetStatus.UNTIMED -> "Missing start and end"
  CreatorTimingTargetStatus.PARTIAL -> "Missing start or end"
  CreatorTimingTargetStatus.INVALID -> "Start/end invalid"
  CreatorTimingTargetStatus.OVERLAP -> "Overlaps previous word"
  CreatorTimingTargetStatus.TIMED -> "Timed"
}
