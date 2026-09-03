// Forward lead added by GetProgress in both play and pause states. Keep
// consumers on this shared value so pausing freezes the rendered frame and
// end-of-track animation bounds use the same coordinate system.
export const PLAYBACK_CLOCK_FORWARD_LEAD_MS = 100;

/** Apply the user offset and perceptual lead to an unshifted audio position. */
export function getLyricsPlaybackClockPosition(
  rawPositionMs: number,
  playbackOffsetMs: number
): number {
  const safePosition = Number.isFinite(rawPositionMs) ? rawPositionMs : 0;
  const safeOffset = Number.isFinite(playbackOffsetMs) ? playbackOffsetMs : 0;
  return safePosition + PLAYBACK_CLOCK_FORWARD_LEAD_MS - safeOffset;
}

export function getPlaybackClockEnd(rawDurationMs: number, playbackOffsetMs: number): number {
  if (!Number.isFinite(rawDurationMs) || rawDurationMs <= 0) return 0;
  const safeOffset = Number.isFinite(playbackOffsetMs) ? playbackOffsetMs : 0;
  return Math.min(rawDurationMs, Math.max(0, getLyricsPlaybackClockPosition(rawDurationMs, safeOffset)));
}

/** Convert a processed lyric-animation timestamp back to raw audio time. */
export function getRawPlaybackTimeForLyricsAnimation(
  lyricAnimationTimeMs: number,
  playbackOffsetMs: number,
  animationClockAdjustmentMs = 0
): number {
  const safeOffset = Number.isFinite(playbackOffsetMs) ? playbackOffsetMs : 0;
  const safeAdjustment = Number.isFinite(animationClockAdjustmentMs)
    ? animationClockAdjustmentMs
    : 0;
  return lyricAnimationTimeMs - PLAYBACK_CLOCK_FORWARD_LEAD_MS + safeOffset - safeAdjustment;
}
