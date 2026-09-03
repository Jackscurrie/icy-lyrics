export {
  PLAYBACK_CLOCK_FORWARD_LEAD_MS,
  getLyricsPlaybackClockPosition,
  getPlaybackClockEnd as getOffsetAdjustedDuration,
  getRawPlaybackTimeForLyricsAnimation,
} from "./PlaybackClock.ts";

export type FullscreenHost = "closed" | "cinema" | "document";

export type FullscreenView = "artwork-only" | "artwork-titles" | "mixed" | "lyrics";

export const FULLSCREEN_VIEW_ORDER: readonly FullscreenView[] = [
  "artwork-only",
  "artwork-titles",
  "mixed",
  "lyrics",
] as const;

export const FULLSCREEN_VIEW_LABELS: Readonly<Record<FullscreenView, string>> = {
  "artwork-only": "Album art only",
  "artwork-titles": "Album art with titles",
  mixed: "Album art, titles and lyrics",
  lyrics: "Lyrics only",
};

export function isFullscreenView(value: string): value is FullscreenView {
  return FULLSCREEN_VIEW_ORDER.includes(value as FullscreenView);
}

export function stepFullscreenView(current: FullscreenView, direction: -1 | 1): FullscreenView {
  const index = FULLSCREEN_VIEW_ORDER.indexOf(current);
  const nextIndex = Math.max(0, Math.min(FULLSCREEN_VIEW_ORDER.length - 1, index + direction));
  return FULLSCREEN_VIEW_ORDER[nextIndex];
}

export function getFullscreenViewNeighbours(current: FullscreenView): {
  previous: FullscreenView | null;
  next: FullscreenView | null;
} {
  const index = FULLSCREEN_VIEW_ORDER.indexOf(current);
  return {
    previous: index > 0 ? FULLSCREEN_VIEW_ORDER[index - 1] : null,
    next: index < FULLSCREEN_VIEW_ORDER.length - 1 ? FULLSCREEN_VIEW_ORDER[index + 1] : null,
  };
}

export type FullscreenViewScrollHandoff = "reset-then-force" | "pin-then-smooth";

export type FullscreenLyricsContextTransition = "hide" | "show" | null;

/** The surrounding virtual-list rows stay visible while the three focus rows
 * cross the mixed/lyrics boundary. They fade and drift as a separate set of
 * compositor leaves, so the focus rows visibly leave or rejoin a living list. */
export function getFullscreenLyricsContextTransition(
  previous: FullscreenView,
  next: FullscreenView
): FullscreenLyricsContextTransition {
  if (previous !== "lyrics" && next === "lyrics") return "hide";
  if (previous === "lyrics" && next !== "lyrics") return "show";
  return null;
}

/**
 * A lyrics-focus exit already positions the real virtual list as part of its
 * FLIP preflight. Its scroll offset must remain pinned until the carriers land;
 * other view changes can use the renderer's ordinary forced recenter.
 */
export function getFullscreenViewScrollHandoff(
  previous: FullscreenView,
  next: FullscreenView
): FullscreenViewScrollHandoff {
  return previous === "lyrics" && next !== "lyrics" ? "pin-then-smooth" : "reset-then-force";
}

/** Keep every already-moving leaf when a rapid reversal also discovers a new
 * playback target. In-flight order wins so the compositor can rebase the exact
 * rendered frame; fresh leaves are appended once without duplicate carriers. */
export function mergeFullscreenTransitionTargets<T>(
  fresh: readonly T[],
  inFlight: readonly T[]
): T[] {
  return [...new Set([...inFlight, ...fresh])];
}

export type TimedElementStatus = "NotSung" | "Active" | "Sung";

export function timedElementStatus(
  currentTime: number,
  startTime: number,
  endTime: number
): TimedElementStatus {
  if (currentTime < startTime) return "NotSung";
  if (currentTime >= endTime) return "Sung";
  return "Active";
}

export function timedElementProgress(
  currentTime: number,
  startTime: number,
  endTime: number
): number {
  if (
    !Number.isFinite(currentTime) ||
    !Number.isFinite(startTime) ||
    !Number.isFinite(endTime) ||
    endTime <= startTime
  ) {
    return currentTime >= endTime ? 1 : 0;
  }
  return Math.max(0, Math.min(1, (currentTime - startTime) / (endTime - startTime)));
}

export const shouldHideTimedElementInReveal = (status: TimedElementStatus) => status === "NotSung";

export const resolveKawarpBlurPasses = (isFullscreen: boolean, fullscreenBlurEnabled: boolean) =>
  isFullscreen && !fullscreenBlurEnabled ? 0 : 8;

export interface FullscreenTimedLineLike {
  EndTime: number;
  Syllables?: {
    Lead?: ReadonlyArray<{ EndTime: number }>;
  };
}

/**
 * Syllable line bounds can include display padding that runs to track end. The
 * final audible token is the correct start for the fullscreen outro; line-sync
 * lyrics have no token clock and deliberately keep the line bound fallback.
 */
export function getFullscreenAudibleLineEnd(
  line: FullscreenTimedLineLike,
  useTokenTiming: boolean
): number {
  if (useTokenTiming) {
    const tokenEnds = (line.Syllables?.Lead ?? [])
      .map((token) => token.EndTime)
      .filter(Number.isFinite);
    if (tokenEnds.length > 0) return Math.max(...tokenEnds);
  }
  return line.EndTime;
}

/** Invalidates stale async transition completions after rapid view changes. */
export class FullscreenTransitionGate {
  private generation = 0;

  begin(): number {
    this.generation += 1;
    return this.generation;
  }

  isCurrent(token: number): boolean {
    return token === this.generation;
  }

  cancel(): void {
    this.generation += 1;
  }
}

/**
 * One-shot gate for layout work that must wait until a compositor transition
 * finishes. Re-deferring coalesces work, flush consumes it exactly once, and
 * cancel guarantees a rapid ownership reversal cannot leave stale work armed.
 */
export class FullscreenDeferredWorkGate {
  private pending = false;

  defer(): void {
    this.pending = true;
  }

  get isPending(): boolean {
    return this.pending;
  }

  flush(work: () => void): boolean {
    if (!this.pending) return false;
    this.pending = false;
    work();
    return true;
  }

  cancel(): void {
    this.pending = false;
  }
}

export interface FullscreenFlipRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface FullscreenFlipGeometry {
  deltaX: number;
  deltaY: number;
  scaleX: number;
  scaleY: number;
}

/** Compute a bounded FLIP inversion that is safe for briefly stale/zero DOM
 * measurements during rapid mode changes. */
export function computeFullscreenFlipGeometry(
  before: FullscreenFlipRect,
  after: FullscreenFlipRect,
  minScale = 0.25,
  maxScale = 4
): FullscreenFlipGeometry {
  const safeScale = (from: number, to: number) => {
    if (!Number.isFinite(from) || !Number.isFinite(to) || from <= 0 || to <= 0) return 1;
    return Math.max(minScale, Math.min(maxScale, from / to));
  };
  return {
    deltaX: Number.isFinite(before.left - after.left) ? before.left - after.left : 0,
    deltaY: Number.isFinite(before.top - after.top) ? before.top - after.top : 0,
    scaleX: safeScale(before.width, after.width),
    scaleY: safeScale(before.height, after.height),
  };
}

/** Scale a large Reveal line just enough to fit its stage-safe inline/block
 * bounds. This runs only when the focus role changes (or the viewport resizes),
 * never on lyric animation frames. */
export function computeFullscreenRevealFit(
  contentWidth: number,
  contentHeight: number,
  maxWidth: number,
  maxHeight: number
): number {
  const ratios = [
    contentWidth > 0 && maxWidth > 0 ? maxWidth / contentWidth : 1,
    contentHeight > 0 && maxHeight > 0 ? maxHeight / contentHeight : 1,
  ].filter(Number.isFinite);
  const fit = Math.min(1, ...ratios);
  return fit > 0 ? fit : 1;
}

export interface FullscreenLineTransitionState {
  anchorIndex: number | null;
  active: boolean;
  fromIndex: number | null;
  toIndex: number | null;
  direction: -1 | 0 | 1;
  progress: number;
}

export type FullscreenFocusLineRole = "FocusPreviousLine" | "FocusCurrentLine" | "FocusNextLine";

export type FullscreenLineTransitionKind = "line" | "enter-interlude" | "exit-interlude";

export function getFullscreenLineTransitionKind(
  fromIsInterlude: boolean,
  toIsInterlude: boolean
): FullscreenLineTransitionKind {
  if (!fromIsInterlude && toIsInterlude) return "enter-interlude";
  if (fromIsInterlude && !toIsInterlude) return "exit-interlude";
  return "line";
}

export interface FullscreenLineTransitionPlan {
  stageInterlude: boolean;
  showDepartingPrevious: boolean;
  showEnteringNext: boolean;
  keepCompletedLeadAsPrevious: boolean;
}

export function getFullscreenLineTransitionPlan(
  kind: FullscreenLineTransitionKind,
  reveal: boolean
): FullscreenLineTransitionPlan {
  return {
    stageInterlude: kind !== "line",
    showDepartingPrevious: !reveal && kind !== "exit-interlude",
    showEnteringNext: !reveal && kind !== "enter-interlude",
    // In the ordinary three-line focus layout an interlude replaces the
    // current row, not both context rows. The lyric that completed immediately
    // before the dots therefore remains in Previous while the upcoming lyric
    // stays parked in Next. Reveal deliberately keeps its active-only contract.
    keepCompletedLeadAsPrevious: !reveal && kind !== "line",
  };
}

/**
 * Once an interlude's own clock is complete, the lyric below it becomes the
 * focus anchor even if there is a small untimed gap before its first word.
 * This keeps that line parked below the dots for the whole interlude, then
 * promotes it exactly when the dots finish.
 */
export function shouldAdvanceCompletedFullscreenInterlude(
  positionMs: number,
  interludeEndMs: number,
  hasNextLine: boolean
): boolean {
  return (
    hasNextLine &&
    Number.isFinite(positionMs) &&
    Number.isFinite(interludeEndMs) &&
    positionMs >= interludeEndMs
  );
}

export interface FullscreenTimingInterval {
  startTime: number;
  endTime: number;
}

/**
 * Resolve the continuous, sufficiently-long pieces of a synthesized dot-line
 * window during which no vocal lane is active. Callers pass every normalized
 * lead, duet/second-speaker, and background row as `vocalIntervals`; merging
 * them here makes the result independent of row order and overlapping groups.
 */
export function getFullscreenVocalSilenceIntervals(
  interlude: FullscreenTimingInterval,
  vocalIntervals: readonly FullscreenTimingInterval[],
  minimumDurationMs: number
): FullscreenTimingInterval[] {
  const windowStart = interlude.startTime;
  const windowEnd = interlude.endTime;
  if (!Number.isFinite(windowStart) || !Number.isFinite(windowEnd) || windowEnd <= windowStart) {
    return [];
  }

  const minimum = Number.isFinite(minimumDurationMs) ? Math.max(0, minimumDurationMs) : 0;
  const occupied = vocalIntervals
    .filter(
      ({ startTime, endTime }) =>
        Number.isFinite(startTime) &&
        Number.isFinite(endTime) &&
        endTime > startTime &&
        endTime > windowStart &&
        startTime < windowEnd
    )
    .map(({ startTime, endTime }) => ({
      startTime: Math.max(windowStart, startTime),
      endTime: Math.min(windowEnd, endTime),
    }))
    .sort((a, b) => a.startTime - b.startTime || a.endTime - b.endTime);

  const silence: FullscreenTimingInterval[] = [];
  let cursor = windowStart;
  for (const interval of occupied) {
    if (interval.startTime > cursor && interval.startTime - cursor >= minimum) {
      silence.push({ startTime: cursor, endTime: interval.startTime });
    }
    cursor = Math.max(cursor, interval.endTime);
    if (cursor >= windowEnd) break;
  }

  if (windowEnd > cursor && windowEnd - cursor >= minimum) {
    silence.push({ startTime: cursor, endTime: windowEnd });
  }
  return silence;
}

/** Half-open interval lookup: at the exact start of the next vocal, dots are
 * already ineligible and the singer owns the focus row. */
export function getFullscreenVocalSilenceAtPosition(
  interlude: FullscreenTimingInterval,
  vocalIntervals: readonly FullscreenTimingInterval[],
  positionMs: number,
  minimumDurationMs: number
): FullscreenTimingInterval | null {
  if (!Number.isFinite(positionMs)) return null;
  return (
    getFullscreenVocalSilenceIntervals(interlude, vocalIntervals, minimumDurationMs).find(
      ({ startTime, endTime }) => positionMs >= startTime && positionMs < endTime
    ) ?? null
  );
}

/** A synthesized dot row owns one continuous three-dot animation. If any vocal
 * overlaps its interior, reject the whole row rather than starting the
 * original animation halfway through a later silent suffix. */
export function isFullscreenInterludeFullySilent(
  interlude: FullscreenTimingInterval,
  vocalIntervals: readonly FullscreenTimingInterval[],
  minimumDurationMs: number
): boolean {
  const silence = getFullscreenVocalSilenceIntervals(interlude, vocalIntervals, minimumDurationMs);
  return (
    silence.length === 1 &&
    silence[0].startTime === interlude.startTime &&
    silence[0].endTime === interlude.endTime
  );
}

export function resolveFullscreenLineTransitionRoles(
  reveal: boolean,
  direction: -1 | 1
): { outgoing: FullscreenFocusLineRole; incoming: FullscreenFocusLineRole } {
  return {
    outgoing: reveal ? "FocusCurrentLine" : direction > 0 ? "FocusPreviousLine" : "FocusNextLine",
    incoming: "FocusCurrentLine",
  };
}

const INACTIVE_LINE_TRANSITION: FullscreenLineTransitionState = {
  anchorIndex: null,
  active: false,
  fromIndex: null,
  toIndex: null,
  direction: 0,
  progress: 1,
};

/**
 * Playback-clock-driven focus transition tracking. Normal-mode null anchors
 * retain the last line so the focus stage stays stable; Reveal can opt out so
 * a true timing gap remains empty as promised by that mode.
 */
export class FullscreenLineTransitionTracker {
  private anchorIndex: number | null = null;
  private fromIndex: number | null = null;
  private transitionStartedAt = 0;
  private lastPosition: number | null = null;

  constructor(
    private readonly durationMs = 450,
    private readonly seekThresholdMs = 1_000
  ) {}

  update(
    nextAnchorIndex: number | null,
    positionMs: number,
    retainThroughGap = true
  ): FullscreenLineTransitionState {
    if (!Number.isFinite(positionMs)) return this.snapshot(positionMs);

    const positionDelta = this.lastPosition === null ? 0 : positionMs - this.lastPosition;
    const isSeek =
      this.lastPosition !== null &&
      (positionDelta < -50 || Math.abs(positionDelta) > this.seekThresholdMs);

    if (nextAnchorIndex !== null) {
      if (this.anchorIndex === null || isSeek) {
        this.anchorIndex = nextAnchorIndex;
        this.fromIndex = null;
      } else if (nextAnchorIndex !== this.anchorIndex) {
        this.fromIndex = this.anchorIndex;
        this.anchorIndex = nextAnchorIndex;
        this.transitionStartedAt = positionMs;
      }
    } else if (isSeek || !retainThroughGap) {
      this.anchorIndex = null;
      this.fromIndex = null;
    }

    if (isSeek && nextAnchorIndex === this.anchorIndex) {
      this.fromIndex = null;
    }

    this.lastPosition = positionMs;
    return this.snapshot(positionMs);
  }

  reset(): void {
    this.anchorIndex = null;
    this.fromIndex = null;
    this.transitionStartedAt = 0;
    this.lastPosition = null;
  }

  private snapshot(positionMs: number): FullscreenLineTransitionState {
    if (this.anchorIndex === null) {
      return { ...INACTIVE_LINE_TRANSITION };
    }
    if (this.fromIndex === null) {
      return {
        ...INACTIVE_LINE_TRANSITION,
        anchorIndex: this.anchorIndex,
        toIndex: this.anchorIndex,
      };
    }

    const progress = timedElementProgress(
      positionMs,
      this.transitionStartedAt,
      this.transitionStartedAt + Math.max(1, this.durationMs)
    );
    const direction = Math.sign(this.anchorIndex - this.fromIndex) as -1 | 0 | 1;
    if (progress >= 1) {
      this.fromIndex = null;
      return {
        ...INACTIVE_LINE_TRANSITION,
        anchorIndex: this.anchorIndex,
        toIndex: this.anchorIndex,
      };
    }

    return {
      anchorIndex: this.anchorIndex,
      active: true,
      fromIndex: this.fromIndex,
      toIndex: this.anchorIndex,
      direction,
      progress,
    };
  }
}

export interface FullscreenOutroState {
  active: boolean;
  spinProgress: number;
  popProgress: number;
  rotationDeg: number;
  scale: number;
  opacity: number;
}

export const FULLSCREEN_OUTRO_POP_DURATION_MS = 375;

export function getGuaranteedFullscreenOutroStart(
  rawLineEndMs: number,
  rawDurationMs: number,
  popDurationMs = FULLSCREEN_OUTRO_POP_DURATION_MS
): number {
  if (!Number.isFinite(rawLineEndMs) || !Number.isFinite(rawDurationMs)) {
    return 0;
  }
  const guaranteedWindow = Math.max(
    0,
    rawDurationMs - Math.min(Math.max(1, popDurationMs), rawDurationMs)
  );
  return Math.max(0, Math.min(rawLineEndMs, guaranteedWindow));
}

const INACTIVE_OUTRO: FullscreenOutroState = {
  active: false,
  spinProgress: 0,
  popProgress: 0,
  rotationDeg: 0,
  scale: 1,
  opacity: 1,
};

const clamp01 = (value: number) => Math.max(0, Math.min(1, value));

/**
 * A deterministic, playback-position-driven outro. Recomputing this after a
 * seek produces the correct frame immediately; it never relies on a wall-clock
 * CSS animation that can continue while playback is paused.
 */
export function computeFullscreenOutroState(
  positionMs: number,
  finalGroupEndMs: number,
  durationMs: number,
  popDurationMs = FULLSCREEN_OUTRO_POP_DURATION_MS,
  startScale = 1
): FullscreenOutroState {
  if (
    !Number.isFinite(positionMs) ||
    !Number.isFinite(finalGroupEndMs) ||
    !Number.isFinite(durationMs) ||
    durationMs <= finalGroupEndMs ||
    positionMs < finalGroupEndMs
  ) {
    return { ...INACTIVE_OUTRO };
  }

  // The predictive playback clock can briefly overshoot its clamped end. Keep
  // the final hidden frame instead of clearing the state and flashing the line
  // back in immediately before the song-change reset.
  const clampedPosition = Math.min(positionMs, durationMs);
  const safePopDuration = Math.max(1, Math.min(popDurationMs, durationMs - finalGroupEndMs));
  const popStart = Math.max(finalGroupEndMs, durationMs - safePopDuration);
  // When an extreme positive lyric offset pushes the visual line end into the
  // guaranteed pop window, rotate throughout the remaining window instead of
  // compressing 0→90 degrees into a single millisecond.
  const spinEnd = popStart > finalGroupEndMs ? popStart : durationMs;
  const spinDuration = Math.max(1, spinEnd - finalGroupEndMs);
  const spinProgress = clamp01((clampedPosition - finalGroupEndMs) / spinDuration);
  const popProgress =
    clampedPosition >= popStart
      ? clamp01((clampedPosition - popStart) / Math.max(1, durationMs - popStart))
      : 0;

  // The line reaches 0.78 scale as the spin completes, briefly grows at the
  // beginning of the pop, then collapses completely before the next track.
  const safeStartScale = Number.isFinite(startScale) ? startScale : 1;
  const spunScale = safeStartScale + (0.78 - safeStartScale) * spinProgress;
  const popScale =
    popProgress === 0
      ? spunScale
      : popProgress < 0.35
        ? spunScale + (0.9 - spunScale) * (popProgress / 0.35)
        : 0.9 * (1 - (popProgress - 0.35) / 0.65);
  const spunOpacity = 1 - 0.62 * spinProgress;

  return {
    active: true,
    spinProgress,
    popProgress,
    rotationDeg: 90 * spinProgress,
    scale: Math.max(0, popScale),
    opacity: Math.max(0, spunOpacity * (1 - popProgress)),
  };
}
