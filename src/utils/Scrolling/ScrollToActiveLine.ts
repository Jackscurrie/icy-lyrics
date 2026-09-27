import {
  $currentLyricsType,
  $fullscreenOutroAnimation,
  $fullscreenRevealMode,
  $lyricsContainerExists,
  $playbackOffset,
  $simpleLyricsMode,
} from "../../utils/stores.ts";
import Global from "../../components/Global/Global.ts";
import { SpotifyPlayer } from "../../components/Global/SpotifyPlayer.ts";
import { PageContainer } from "../../components/Pages/PageView.ts";
import { IsCompactMode } from "../../components/Utils/CompactMode.ts";
import { IsPIP } from "../../components/Utils/PopupLyrics.ts";
import {
  type LyricsLine,
  LyricsObject,
  type LyricsSyllable,
  type LyricsType,
  getLyricsBetweenShow,
  readLyricsClock,
} from "../Lyrics/lyrics.ts";
import { ScrollIntoCenterViewCSS } from "../ScrollIntoView/Center.ts";
import { ScrollIntoTopViewCSS } from "../ScrollIntoView/Top.ts";
import {
  getLyricsVirtualizer,
  prepareLyricsPresentationExit,
  scrollLyricsToIndex,
  setLyricsPresentationIndices,
  setLyricsPresentationMode,
} from "../Lyrics/LyricsVirtualizer.ts";
import { getLyricsAnimationPosition } from "../Lyrics/Animator/Shared.ts";
import { setFrameClass, setFrameStyle } from "../DOM/FrameWrites.ts";
import { DeferredLyricsScroll, resolveLyricsScrollRequest } from "./ScrollRequest.ts";
import { ScrollEventBindings } from "./ScrollEventBindings.ts";
import {
  FULLSCREEN_OUTRO_POP_DURATION_MS,
  FullscreenLineTransitionTracker,
  computeFullscreenOutroState,
  computeFullscreenRevealFit,
  getFullscreenLineTransitionKind,
  getFullscreenLineTransitionPlan,
  getFullscreenAudibleLineEnd,
  getGuaranteedFullscreenOutroStart,
  getRawPlaybackTimeForLyricsAnimation,
  isFullscreenInterludeFullySilent,
  resolveFullscreenLineTransitionRoles,
  shouldAdvanceCompletedFullscreenInterlude,
  type FullscreenLineTransitionKind,
  type FullscreenLineTransitionState,
  type FullscreenTimingInterval,
} from "../FullscreenPresentation.ts";

// Define intersection types that include _LineIndex
type LyricsLineWithIndex = LyricsLine & { _LineIndex: number };
type LyricsSyllableWithIndex = LyricsSyllable & { _LineIndex: number };
type EnhancedLyricsItem = LyricsLineWithIndex | LyricsSyllableWithIndex;

// Define proper types for variables
let lastLine: HTMLElement | null = null;
let isUserScrolling = false;
let lastUserScrollTime = 0;
let lastPosition: number = 0;
const USER_SCROLL_COOLDOWN = 750; // 0.75 second cooldown
// const POSITION_THRESHOLD = 500; // 500ms threshold for start/end detection

// Force scroll queue mechanism
let forceScrollQueued = false;
let smoothForceScrollQueued = false;

const scrollEventBindings = new ScrollEventBindings();
const deferredLyricsScroll = new DeferredLyricsScroll();

const wasDrasticPositionChange = (lastPosition: number, newPosition: number) => {
  const positionChange = Math.abs(newPosition - lastPosition);
  return positionChange > 1000;
};

const handleWindowFocus = () => ResetLastLine();
const handleWindowResize = () => {
  ResetLastLine();
  invalidateFullscreenRevealFits();
};

// Create ResizeObserver to monitor LyricsContent container dimensions
const lyricsContentObserver = new ResizeObserver(() => {
  ResetLastLine();
});

// Function to setup the observer
function setupLyricsContentObserver() {
  const lyricsContent = PageContainer?.querySelector(".LyricsContainer .LyricsContent");
  if (lyricsContent) {
    // Ensure we don't observe multiple times if called again
    lyricsContentObserver.disconnect();
    lyricsContentObserver.observe(lyricsContent);
  }
}

function handleUserScroll(ScrollSimplebar: any | null) {
  // Allow null
  if (!ScrollSimplebar) return; // Add null check
  if (!isUserScrolling) {
    isUserScrolling = true;
    // Add HideLineBlur class when user starts scrolling
    const lyricsContent = PageContainer?.querySelector(".LyricsContainer .LyricsContent");
    if (lyricsContent) {
      lyricsContent.classList.add("HideLineBlur");
    } else {
      // --- NEW: Add warning if element not found ---
      console.warn(
        "IcyLyrics: Could not find .LyricsContent in handleUserScroll to add HideLineBlur."
      );
      // --- END NEW ---
    }
  }
  lastUserScrollTime = performance.now();
}

// Initialization function for scroll events and observers
export function InitializeScrollEvents(ScrollSimplebar: any) {
  if (!$lyricsContainerExists.get()) return;
  setupLyricsContentObserver();
  scrollEventBindings.bind(
    window,
    ScrollSimplebar?.getScrollElement() ?? null,
    handleWindowFocus,
    handleWindowResize,
    () => handleUserScroll(ScrollSimplebar)
  );
}

/**
 * How far ahead — counted in real lyric lines, background lines excluded — we
 * look when deciding whether the highest active line may keep the anchor.
 * Bump to 3 to let long lines hold the anchor for longer.
 */
const PIN_LOOKAHEAD = 2;

/** Only Syllable lines carry BGLine; line-synced lyrics have no background lines. */
const IsBGLine = (line: LyricsLine | LyricsSyllable): boolean =>
  (line as LyricsSyllable).BGLine === true;

/** Background lines belong to the lead line above them, and are never a scroll target. */
const ResolveToLeadIndex = (Lines: LyricsLine[] | LyricsSyllable[], index: number): number => {
  let i = index;
  while (i > 0 && IsBGLine(Lines[i])) i--;
  return i;
};

/** When the lead line at `leadIdx` and its background lines have all finished. */
const GetGroupEndTime = (Lines: LyricsLine[] | LyricsSyllable[], leadIdx: number): number => {
  let end = Lines[leadIdx].EndTime;
  for (let i = leadIdx + 1; i < Lines.length && IsBGLine(Lines[i]); i++) {
    if (Lines[i].EndTime > end) end = Lines[i].EndTime;
  }
  return end;
};

/** The PIN_LOOKAHEAD-th non-background line after `leadIdx`, or null past the end. */
const GetLookaheadLine = (Lines: LyricsLine[] | LyricsSyllable[], leadIdx: number) => {
  let remaining = PIN_LOOKAHEAD;
  for (let i = leadIdx + 1; i < Lines.length; i++) {
    if (IsBGLine(Lines[i])) continue;
    if (--remaining === 0) return Lines[i];
  }
  return null;
};

const GetScrollLine = (
  Lines: LyricsLine[] | LyricsSyllable[],
  ProcessedPosition: number,
  includeDotLines = true
) => {
  if ($currentLyricsType.get() === "Static" || $currentLyricsType.get() === "None" || !Lines)
    return;
  // 1) gather the indices of all active lines. This runs every animation frame,
  // so we keep indices rather than materialising a copy of each active line.
  const activeIndices: number[] = [];
  for (let i = 0; i < Lines.length; i++) {
    const line = Lines[i];
    if (!includeDotLines && line.DotLine) continue;
    if (
      typeof line.StartTime === "number" &&
      typeof line.EndTime === "number" &&
      line.StartTime <= ProcessedPosition &&
      line.EndTime >= ProcessedPosition
    ) {
      activeIndices.push(i);
    }
  }

  if (activeIndices.length === 0) return null;

  const enhance = (index: number) => ({ ...Lines[index], _LineIndex: index }) as EnhancedLyricsItem;

  // The highest active line keeps the anchor as long as it (and its background
  // lines) finish before the line PIN_LOOKAHEAD real lines further down starts.
  const anchorIdx = ResolveToLeadIndex(Lines, activeIndices[0]);
  const lookahead = GetLookaheadLine(Lines, anchorIdx);
  if (lookahead === null || GetGroupEndTime(Lines, anchorIdx) <= lookahead.StartTime) {
    return enhance(anchorIdx);
  }

  // Anchor refused — fall back to the original heuristic: contiguous or off by
  // only 1 → the first active line, a bigger gap → the last.
  const firstIdx = activeIndices[0];
  const lastIdx = activeIndices[activeIndices.length - 1];
  return enhance(ResolveToLeadIndex(Lines, lastIdx - firstIdx <= 1 ? firstIdx : lastIdx));
};

const ScrollTo = (
  container: HTMLElement,
  element: HTMLElement,
  instantScroll: boolean = false,
  type: "Center" | "Top" = "Center",
  lineIndex?: number
) => {
  if (
    PageContainer?.classList.contains("FullscreenView--lyrics") ||
    PageContainer?.classList.contains("FullscreenViewTransitioning")
  ) return;
  if (lineIndex !== undefined && getLyricsVirtualizer()) {
    // instantScroll is effectively always true in the virtualizer path
    // (we set scrollTop directly), but passing the flag keeps the intent
    // explicit and allows a future smooth-scroll path if needed.
    scrollLyricsToIndex(
      lineIndex,
      type === "Top" ? "start" : "center",
      instantScroll,
      type === "Top" ? (IsPIP ? -50 : -85) : 30
    );
    return;
  }
  if (type === "Center") {
    ScrollIntoCenterViewCSS(container, element, -30, instantScroll);
  } else if (type === "Top") {
    ScrollIntoTopViewCSS(container, element, IsPIP ? 50 : 85, instantScroll);
  }
};

let scrolledToLastLine = false;
let scrolledToFirstLine = false;

// Throttle layout-dependent viewport checks to avoid forced sync layouts on every tick
const VIEWPORT_CHECK_INTERVAL = 350; // milliseconds
let lastViewportCheckTime = 0;
let lastViewportLine: HTMLElement | null = null;
let lastViewportContainer: HTMLElement | null = null;
let lastIsLineInViewport = false;

type FocusLineRole = "FocusPreviousLine" | "FocusCurrentLine" | "FocusNextLine";

type FocusLineEntry = {
  line: LyricsLine | LyricsSyllable;
  index: number;
  element: HTMLElement;
};

type FocusLineState = {
  role: FocusLineRole;
  isBackground?: boolean;
  backgroundIndex?: number;
  isRevealCurrent?: boolean;
  transition?: "departing" | "outgoing" | "incoming" | "entering";
  transitionProgress?: number;
  transitionDirection?: -1 | 1;
};

const focusRoleClasses: FocusLineRole[] = [
  "FocusPreviousLine",
  "FocusCurrentLine",
  "FocusNextLine",
];
const focusLineClasses = [
  ...focusRoleClasses,
  "FocusBackgroundLine",
  "FocusRevealCurrentLine",
  "FocusTransitionDeparting",
  "FocusTransitionIncoming",
  "FocusTransitionOutgoing",
  "FocusTransitionEntering",
  "FullscreenOutroLine",
];

const fullscreenLineTransitionKindClasses = [
  "FullscreenLineTransition--line",
  "FullscreenLineTransition--enter-interlude",
  "FullscreenLineTransition--exit-interlude",
] as const;

const fullscreenLineTransitionTracker = new FullscreenLineTransitionTracker();
const presentedFocusLines = new Set<HTMLElement>();
let outroLine: HTMLElement | null = null;
let focusIndexLines: LyricsLine[] | LyricsSyllable[] | null = null;
let focusIndices = new Map<HTMLElement, number>();

const IsLyricsFocusMode = () =>
  PageContainer?.classList.contains("FullscreenView--lyrics") === true;

const IsBackgroundFocusLine = (line: LyricsLine | LyricsSyllable | undefined) =>
  !!line &&
  ((line as LyricsSyllable).BGLine === true || line.HTMLElement?.classList.contains("bg-line"));

const IsDisplayableLeadLine = (line: LyricsLine | LyricsSyllable | undefined) =>
  !!line && !line.DotLine && !IsBackgroundFocusLine(line) && !!line.HTMLElement;

let fullscreenVocalTimingCache:
  | {
      lines: LyricsLine[] | LyricsSyllable[];
      intervals: FullscreenTimingInterval[];
    }
  | undefined;
let fullscreenInterludeSilenceCache = new WeakMap<
  LyricsLine | LyricsSyllable,
  { minimumDurationMs: number; eligible: boolean }
>();

const GetFullscreenVocalTimingIntervals = (lines: LyricsLine[] | LyricsSyllable[]) => {
  if (fullscreenVocalTimingCache?.lines === lines) return fullscreenVocalTimingCache.intervals;
  const intervals = lines
    .filter((line) => !line.DotLine)
    .map((line) => ({ startTime: line.StartTime, endTime: line.EndTime }));
  fullscreenVocalTimingCache = { lines, intervals };
  return intervals;
};

const IsEligibleFullscreenInterlude = (
  lines: LyricsLine[] | LyricsSyllable[],
  line: LyricsLine | LyricsSyllable,
  position: number
) => {
  if (line.DotLine !== true || !Number.isFinite(position)) return false;
  const minimumDurationMs = getLyricsBetweenShow() * 1000;
  let cached = fullscreenInterludeSilenceCache.get(line);
  if (!cached || cached.minimumDurationMs !== minimumDurationMs) {
    cached = {
      minimumDurationMs,
      eligible: isFullscreenInterludeFullySilent(
        { startTime: line.StartTime, endTime: line.EndTime },
        GetFullscreenVocalTimingIntervals(lines),
        minimumDurationMs
      ),
    };
    fullscreenInterludeSilenceCache.set(line, cached);
  }
  return cached.eligible && position >= line.StartTime && position < line.EndTime;
};

const GetNearestFocusLine = (
  lines: LyricsLine[] | LyricsSyllable[],
  index: number,
  direction: -1 | 1
): FocusLineEntry | null => {
  for (let i = index + direction; i >= 0 && i < lines.length; i += direction) {
    const line = lines[i];
    if (IsDisplayableLeadLine(line)) {
      return { line, index: i, element: line.HTMLElement };
    }
  }
  return null;
};

/** Previous lead within the current lyric section. Dot interludes stop the
 * ordinary lookup so callers can explicitly decide whether that completed
 * lead should remain visible. */
const GetPreviousFocusLine = (
  lines: LyricsLine[] | LyricsSyllable[],
  index: number
): FocusLineEntry | null => {
  for (let i = index - 1; i >= 0; i--) {
    const line = lines[i];
    if (line.DotLine) return null;
    if (IsDisplayableLeadLine(line)) {
      return { line, index: i, element: line.HTMLElement };
    }
  }
  return null;
};

/**
 * Preserve the completed lead through an interlude and through the first line
 * after it. This gives the dots a stable Previous row and lets the parked Next
 * lyric promote to Current without moving that row. A later ordinary line
 * transition naturally replaces it with the preceding post-interlude lyric.
 */
const GetPersistentPreviousFocusLine = (
  lines: LyricsLine[] | LyricsSyllable[],
  index: number
): FocusLineEntry | null => {
  const previous = GetPreviousFocusLine(lines, index);
  if (previous) return previous;

  let interludeIndex = index - 1;
  while (interludeIndex >= 0 && IsBackgroundFocusLine(lines[interludeIndex])) {
    interludeIndex--;
  }
  if (!lines[interludeIndex]?.DotLine) return null;
  return GetPreviousFocusLine(lines, interludeIndex);
};

const GetFocusAnchorLine = (
  lines: LyricsLine[] | LyricsSyllable[],
  currentLine: EnhancedLyricsItem
): FocusLineEntry | null => {
  if (currentLine.DotLine || !IsBackgroundFocusLine(currentLine)) {
    return {
      line: currentLine,
      index: currentLine._LineIndex,
      element: currentLine.HTMLElement,
    };
  }

  for (let i = currentLine._LineIndex - 1; i >= 0; i--) {
    if (IsDisplayableLeadLine(lines[i])) {
      return { line: lines[i], index: i, element: lines[i].HTMLElement };
    }
  }
  return null;
};

/** Dot rows are synthesized from adjacent lead timings. Before allowing one
 * into fullscreen focus, re-check its window against every normalized vocal
 * row. An active duet/background/overlapping lead always wins; otherwise the
 * dot is eligible only inside a continuous all-vocal silence interval. */
const ResolveFullscreenFocusAnchor = (
  lines: LyricsLine[] | LyricsSyllable[],
  currentLine: EnhancedLyricsItem | null,
  position: number
): FocusLineEntry | null => {
  if (!currentLine?.HTMLElement) return null;
  const direct = GetFocusAnchorLine(lines, currentLine);
  if (!direct?.line.DotLine) return direct;

  const activeVocal = GetScrollLine(lines, position, false);
  if (activeVocal?.HTMLElement) return GetFocusAnchorLine(lines, activeVocal);
  return IsEligibleFullscreenInterlude(lines, direct.line, position) ? direct : null;
};

const GetFocusEntryAtIndex = (
  lines: LyricsLine[] | LyricsSyllable[],
  index: number | null
): FocusLineEntry | null => {
  if (index === null || index < 0 || index >= lines.length) return null;
  const line = lines[index];
  if (!line?.HTMLElement) return null;
  if (!IsBackgroundFocusLine(line)) {
    return { line, index, element: line.HTMLElement };
  }

  for (let i = index - 1; i >= 0; i--) {
    const candidate = lines[i];
    if (candidate?.HTMLElement && !IsBackgroundFocusLine(candidate)) {
      return { line: candidate, index: i, element: candidate.HTMLElement };
    }
  }
  return null;
};

const GetAttachedBackgroundLines = (
  lines: LyricsLine[] | LyricsSyllable[],
  index: number
): FocusLineEntry[] => {
  const attached: FocusLineEntry[] = [];
  for (let i = index + 1; i < lines.length; i++) {
    const line = lines[i];
    if (!IsBackgroundFocusLine(line)) break;
    attached.push({ line, index: i, element: line.HTMLElement });
  }
  return attached;
};

const clearOutroState = () => {
  if (!outroLine) return;
  const page = PageContainer;
  page?.classList.remove("FullscreenOutroActive", "FullscreenOutroPopping");
  page?.style.removeProperty("--fullscreen-outro-progress");
  page?.style.removeProperty("--fullscreen-outro-pop-progress");
  const line = outroLine;
  line.classList.remove("FullscreenOutroLine");
  line.style.removeProperty("--fullscreen-outro-rotation");
  line.style.removeProperty("--fullscreen-outro-scale");
  line.style.removeProperty("--fullscreen-outro-opacity");
  outroLine = null;
};

const syncLineTransitionState = (
  state: FullscreenLineTransitionState | null,
  kind: FullscreenLineTransitionKind | null = null
) => {
  const page = PageContainer;
  const isActive = state?.active === true && state.direction !== 0;
  if (!page) return;
  setFrameClass(page, "FullscreenLineTransitionActive", isActive);
  fullscreenLineTransitionKindClasses.forEach((className) => {
    setFrameClass(page, className, isActive && className === `FullscreenLineTransition--${kind}`);
  });
  // Progress belongs to the handful of moving lines, not the page. Updating an
  // inherited page variable invalidated styles for every mounted word/letter.
  // setFocusLineState reconciles transition classes when a line settles.
};

function invalidateFullscreenRevealFits() {
  PageContainer?.querySelectorAll<HTMLElement>(".FocusRevealCurrentLine").forEach((line) => {
    delete line.dataset.fullscreenRevealFitReady;
    line.style.removeProperty("--fullscreen-reveal-fit");
  });
}

const syncRevealLineFit = (element: HTMLElement, enabled: boolean) => {
  if (!enabled || element.classList.contains("musical-line")) {
    if (element.dataset.fullscreenRevealFitReady) delete element.dataset.fullscreenRevealFitReady;
    setFrameStyle(element, "--fullscreen-reveal-fit", null);
    return;
  }
  if (element.dataset.fullscreenRevealFitReady === "true") return;
  // Entry preparation deliberately stages animator-owned nodes before the
  // fullscreen class changes. Wait for focus-view geometry before measuring.
  if (!IsLyricsFocusMode()) return;
  const stage = element.closest<HTMLElement>(".FullscreenLyricsFocusStage");
  if (!stage) return;

  // The focus role has just changed, so this bounded calibration never enters
  // the steady per-frame lyric loop. scrollWidth catches an unbreakable token;
  // scrollHeight catches a many-row line after wrapping. One residual pass
  // absorbs text reflow caused by the first font-size correction.
  element.style.removeProperty("--fullscreen-reveal-fit");
  const maxWidth = element.clientWidth;
  const maxHeight = stage.clientHeight * 0.72;
  let fit = computeFullscreenRevealFit(
    element.scrollWidth,
    element.scrollHeight,
    maxWidth,
    maxHeight
  );
  element.style.setProperty("--fullscreen-reveal-fit", String(fit));
  if (fit < 1) {
    fit *= computeFullscreenRevealFit(
      element.scrollWidth,
      element.scrollHeight,
      maxWidth,
      maxHeight
    );
    element.style.setProperty("--fullscreen-reveal-fit", String(Math.min(1, fit)));
  }
  element.dataset.fullscreenRevealFitReady = "true";
};

const setFocusLineState = (element: HTMLElement, state: FocusLineState | null) => {
  for (const role of focusRoleClasses) {
    setFrameClass(element, role, state?.role === role);
  }
  setFrameClass(element, "FocusBackgroundLine", state?.isBackground === true);
  setFrameClass(element, "FocusRevealCurrentLine", state?.isRevealCurrent === true);
  setFrameClass(element, "FocusTransitionDeparting", state?.transition === "departing");
  setFrameClass(element, "FocusTransitionIncoming", state?.transition === "incoming");
  setFrameClass(element, "FocusTransitionOutgoing", state?.transition === "outgoing");
  setFrameClass(element, "FocusTransitionEntering", state?.transition === "entering");
  syncRevealLineFit(element, state?.isRevealCurrent === true);
  if (state?.isBackground) {
    setFrameStyle(element, "--focus-bg-offset", `${10 + (state.backgroundIndex ?? 0) * 5.2}cqh`);
  } else {
    setFrameStyle(element, "--focus-bg-offset", null);
  }
  if (state?.transition && state.transitionDirection) {
    setFrameStyle(element,
      "--fullscreen-line-transition-progress",
      (state.transitionProgress ?? 1).toFixed(4)
    );
    setFrameStyle(element,
      "--fullscreen-line-transition-direction",
      String(state.transitionDirection)
    );
  } else {
    setFrameStyle(element, "--fullscreen-line-transition-progress", null);
    setFrameStyle(element, "--fullscreen-line-transition-direction", null);
  }
};

export const ResetFullscreenLyricsPresentation = (deferVirtualizerRemeasure = false) => {
  fullscreenLineTransitionTracker.reset();
  syncLineTransitionState(null);
  // Include detached animator nodes; a page query alone misses them on a
  // song/page ownership handoff and can leave stale focus styling on re-entry.
  presentedFocusLines.forEach((line) => setFocusLineState(line, null));
  presentedFocusLines.clear();
  PageContainer?.querySelectorAll<HTMLElement>(
    ".FocusPreviousLine, .FocusCurrentLine, .FocusNextLine, .FocusBackgroundLine, .FocusRevealCurrentLine, .FocusTransitionDeparting, .FocusTransitionIncoming, .FocusTransitionOutgoing, .FocusTransitionEntering, .FullscreenOutroLine"
  ).forEach((line) => {
    line.classList.remove(...focusLineClasses);
    line.style.removeProperty("--focus-bg-offset");
    line.style.removeProperty("--fullscreen-line-transition-progress");
    line.style.removeProperty("--fullscreen-line-transition-direction");
    line.style.removeProperty("--fullscreen-reveal-fit");
    delete line.dataset.fullscreenRevealFitReady;
  });
  clearOutroState();
  setLyricsPresentationMode(false, deferVirtualizerRemeasure);
};

const GetFinalLeadLine = (lines: LyricsLine[] | LyricsSyllable[]): FocusLineEntry | null => {
  for (let i = lines.length - 1; i >= 0; i--) {
    if (IsDisplayableLeadLine(lines[i])) {
      return { line: lines[i], index: i, element: lines[i].HTMLElement };
    }
  }
  return null;
};

const updateOutro = (
  lines: LyricsLine[] | LyricsSyllable[],
  rawPosition: number,
  desired: Map<HTMLElement, FocusLineState>,
  reveal: boolean
): boolean => {
  if (!$fullscreenOutroAnimation.get()) {
    clearOutroState();
    return false;
  }

  const finalLine = GetFinalLeadLine(lines);
  if (!finalLine) {
    clearOutroState();
    return false;
  }
  const attachedBackground = GetAttachedBackgroundLines(lines, finalLine.index);
  const useTokenTiming = $currentLyricsType.get() === "Syllable";
  const groupEnd = attachedBackground.reduce(
    (end, entry) => Math.max(end, getFullscreenAudibleLineEnd(entry.line, useTokenTiming)),
    getFullscreenAudibleLineEnd(finalLine.line, useTokenTiming)
  );
  const duration = SpotifyPlayer.GetDuration();
  const rawGroupEnd = getRawPlaybackTimeForLyricsAnimation(
    groupEnd,
    $playbackOffset.get(),
    getLyricsAnimationPosition(0, $simpleLyricsMode.get())
  );
  const outroStart = getGuaranteedFullscreenOutroStart(
    rawGroupEnd,
    duration,
    FULLSCREEN_OUTRO_POP_DURATION_MS
  );
  const state = computeFullscreenOutroState(
    rawPosition,
    outroStart,
    duration,
    FULLSCREEN_OUTRO_POP_DURATION_MS,
    reveal ? 1 : 1.18
  );
  if (!state.active) {
    clearOutroState();
    return false;
  }

  desired.clear();
  desired.set(finalLine.element, {
    role: "FocusCurrentLine",
    isRevealCurrent: reveal,
  });
  if (outroLine && outroLine !== finalLine.element) clearOutroState();
  outroLine = finalLine.element;
  setFrameClass(outroLine, "FullscreenOutroLine", true);
  setFrameStyle(outroLine, "--fullscreen-outro-rotation", `${state.rotationDeg}deg`);
  setFrameStyle(outroLine, "--fullscreen-outro-scale", state.scale.toFixed(4));
  setFrameStyle(outroLine, "--fullscreen-outro-opacity", state.opacity.toFixed(4));
  if (PageContainer) {
    setFrameClass(PageContainer, "FullscreenOutroActive", true);
    setFrameClass(PageContainer, "FullscreenOutroPopping", state.popProgress > 0);
  }
  return true;
};

const UpdateFullscreenLyricsPresentation = (
  lines: LyricsLine[] | LyricsSyllable[],
  currentLine: EnhancedLyricsItem | null,
  position: number,
  rawPosition: number,
  prepareBeforeViewClass = false
) => {
  // View-boundary FLIPs own the stable lyric leaves for their short lifetime.
  // Pin roles while those compositor animations run so playback frames cannot
  // trigger DOM ownership/layout changes halfway through a mixed<->lyrics
  // transition. Fullscreen.ts removes the class and performs one reconciliation
  // against the current playback clock when the FLIP finishes.
  if (!prepareBeforeViewClass && PageContainer?.classList.contains("FullscreenViewTransitioning")) {
    return;
  }

  if (!IsLyricsFocusMode() && !prepareBeforeViewClass) {
    if (PageContainer?.classList.contains("FullscreenLyricsPresenting")) {
      ResetFullscreenLyricsPresentation();
    }
    return;
  }

  const desired = new Map<HTMLElement, FocusLineState>();
  const reveal = $fullscreenRevealMode.get();
  const directAnchor = ResolveFullscreenFocusAnchor(lines, currentLine, position);
  let transition = fullscreenLineTransitionTracker.update(
    directAnchor?.index ?? null,
    position,
    true
  );
  // A short untimed gap keeps the last lyric visible in both modes. Interlude
  // gaps are different: the lyric parked below the dots is promoted as soon as
  // the dots finish, even if its first word starts a little later.
  if (directAnchor === null && transition.anchorIndex !== null) {
    const heldLine = lines[transition.anchorIndex];
    if (heldLine?.DotLine) {
      const nextLine = GetNearestFocusLine(lines, transition.anchorIndex, 1);
      if (
        shouldAdvanceCompletedFullscreenInterlude(position, heldLine.EndTime, nextLine !== null)
      ) {
        transition = fullscreenLineTransitionTracker.update(nextLine!.index, position, true);
      }
    }
  }
  const anchor = directAnchor ?? GetFocusEntryAtIndex(lines, transition.anchorIndex);

  const add = (
    entry: FocusLineEntry | null,
    role: FocusLineRole,
    state: Omit<FocusLineState, "role"> = {}
  ) => {
    if (!entry) return;
    desired.set(entry.element, { role, ...state });
    if (entry.line.DotLine) return;
    GetAttachedBackgroundLines(lines, entry.index).forEach((background, index) => {
      desired.set(background.element, {
        role,
        ...state,
        isRevealCurrent: false,
        isBackground: true,
        backgroundIndex: index,
      });
    });
  };

  if (anchor) {
    if (anchor.line.DotLine) {
      if (!reveal) add(GetPersistentPreviousFocusLine(lines, anchor.index), "FocusPreviousLine");
      add(anchor, "FocusCurrentLine", { isRevealCurrent: reveal });
      if (!reveal) add(GetNearestFocusLine(lines, anchor.index, 1), "FocusNextLine");
    } else {
      if (!reveal) {
        add(GetPersistentPreviousFocusLine(lines, anchor.index), "FocusPreviousLine");
      }
      add(anchor, "FocusCurrentLine", { isRevealCurrent: reveal });
      if (!reveal) add(GetNearestFocusLine(lines, anchor.index, 1), "FocusNextLine");
    }
  }

  const outgoing = transition.active ? GetFocusEntryAtIndex(lines, transition.fromIndex) : null;
  const incoming = transition.active ? GetFocusEntryAtIndex(lines, transition.toIndex) : null;
  const hasLineTransition =
    transition.active &&
    transition.direction !== 0 &&
    outgoing !== null &&
    incoming !== null &&
    outgoing.index !== incoming.index;
  if (hasLineTransition) {
    const direction = transition.direction as -1 | 1;
    const kind = getFullscreenLineTransitionKind(
      outgoing.line.DotLine === true,
      incoming.line.DotLine === true
    );
    const plan = getFullscreenLineTransitionPlan(kind, reveal);
    const roles = resolveFullscreenLineTransitionRoles(reveal, direction);
    const transitionState = {
      transitionProgress: transition.progress,
      transitionDirection: direction,
    };
    if (kind === "enter-interlude") {
      // Evict the older Previous row, settle the just-completed lead into its
      // place, and bring the dots to center. The lyric below the dots was
      // already Next and remains completely still for the whole interlude.
      if (plan.showDepartingPrevious) {
        add(GetPersistentPreviousFocusLine(lines, outgoing.index), "FocusPreviousLine", {
          ...transitionState,
          transition: "departing",
        });
      }
      add(outgoing, plan.keepCompletedLeadAsPrevious ? "FocusPreviousLine" : "FocusCurrentLine", {
        ...transitionState,
        isRevealCurrent: reveal,
        transition: "outgoing",
      });
      add(incoming, "FocusCurrentLine", {
        ...transitionState,
        isRevealCurrent: reveal,
        transition: "incoming",
      });
    } else if (kind === "exit-interlude") {
      // The next lyric has stayed parked below the dots. Promote it without
      // moving the completed pre-interlude lead out of Previous.
      if (plan.keepCompletedLeadAsPrevious) {
        add(GetPersistentPreviousFocusLine(lines, outgoing.index), "FocusPreviousLine");
      }
      // A synthesized dot window can be shortened by a background/duet group
      // that was not considered when the row was created. Once any vocal is
      // active, unmount the dots immediately instead of crossfading them over
      // the singer for the remainder of the focus transition.
      if (IsEligibleFullscreenInterlude(lines, outgoing.line, position)) {
        add(outgoing, "FocusCurrentLine", {
          ...transitionState,
          isRevealCurrent: reveal,
          transition: "outgoing",
        });
      }
      add(incoming, "FocusCurrentLine", {
        ...transitionState,
        isRevealCurrent: reveal,
        transition: "incoming",
      });
      if (plan.showEnteringNext) {
        add(GetNearestFocusLine(lines, incoming.index, 1), "FocusNextLine", {
          ...transitionState,
          transition: "entering",
        });
      }
    } else if (reveal) {
      // Reveal has no persistent context rows, but still uses the same
      // playback-clock transition so seeks and pauses remain deterministic.
      add(outgoing, roles.outgoing, {
        ...transitionState,
        isRevealCurrent: true,
        transition: "outgoing",
      });
      add(incoming, roles.incoming, {
        ...transitionState,
        isRevealCurrent: true,
        transition: "incoming",
      });
    } else {
      // Four-part conveyor: old previous exits, current becomes previous, next
      // becomes current, and a fresh next lyric rises from below the viewport.
      add(GetPersistentPreviousFocusLine(lines, outgoing.index), "FocusPreviousLine", {
        ...transitionState,
        transition: "departing",
      });
      add(outgoing, roles.outgoing, {
        ...transitionState,
        transition: "outgoing",
      });
      add(incoming, roles.incoming, {
        ...transitionState,
        transition: "incoming",
      });
      add(GetNearestFocusLine(lines, incoming.index, 1), "FocusNextLine", {
        ...transitionState,
        transition: "entering",
      });
    }
    syncLineTransitionState(transition, kind);
  } else {
    syncLineTransitionState(null);
  }

  const outroActive = updateOutro(lines, rawPosition, desired, reveal);
  if (outroActive) syncLineTransitionState(null);

  if (focusIndexLines !== lines) {
    focusIndexLines = lines;
    focusIndices = new Map(lines.map((line, index) => [line.HTMLElement, index] as const));
  }
  const indices = [...desired.keys()]
    .map((element) => focusIndices.get(element))
    .filter((index): index is number => index !== undefined);
  setLyricsPresentationIndices(indices);

  presentedFocusLines.forEach((line) => {
    if (!desired.has(line)) {
      setFocusLineState(line, null);
      presentedFocusLines.delete(line);
    }
  });
  desired.forEach((state, line) => {
    setFocusLineState(line, state);
    presentedFocusLines.add(line);
  });
};

/**
 * Mount the current animator-owned focus lines synchronously before the lyrics
 * view class is committed. This prevents one frame of the full virtual list
 * flashing between regular fullscreen and the focus stage.
 */
export const PrepareFullscreenLyricsPresentation = () => {
  const currentType = $currentLyricsType.get() as LyricsType;
  if (currentType !== "Line" && currentType !== "Syllable") return;
  const lines = LyricsObject.Types[currentType].Lines as LyricsLine[] | LyricsSyllable[];
  const position = getLyricsAnimationPosition(SpotifyPlayer.GetPosition(), $simpleLyricsMode.get());
  const rawPosition = SpotifyPlayer.GetRawPosition();
  const currentLine = GetScrollLine(lines, position) as EnhancedLyricsItem | null;
  UpdateFullscreenLyricsPresentation(lines, currentLine, position, rawPosition, true);
};

/** Align the hidden virtual list to the focus anchor before its lines are
 * restored for a lyrics-to-mixed FLIP. Without this, a long stay in focus mode
 * can leave TanStack several verses behind and immediately unmount the very
 * leaves the transition is trying to animate. */
export const PrepareFullscreenLyricsPresentationExit = (knownIndex?: number): number | null => {
  const focusLine =
    knownIndex === undefined
      ? (PageContainer?.querySelector<HTMLElement>(
          ".FullscreenLyricsFocusStage .FocusTransitionIncoming:not(.FocusBackgroundLine)"
        ) ??
        PageContainer?.querySelector<HTMLElement>(
          ".FullscreenLyricsFocusStage .FocusCurrentLine:not(.FocusBackgroundLine, .FocusTransitionOutgoing)"
        ))
      : null;
  const index = knownIndex ?? Number(focusLine?.dataset.fullscreenFocusIndex);
  if (!Number.isInteger(index) || index < 0) return null;
  prepareLyricsPresentationExit(index, 30);
  return index;
};

const UpdateFullscreenLyricsFrame = (position: number, rawPosition: number) => {
  if (!Number.isFinite(position) || !Number.isFinite(rawPosition)) return;
  if (!IsLyricsFocusMode()) {
    if (PageContainer?.classList.contains("FullscreenViewTransitioning")) return;
    if (presentedFocusLines.size || PageContainer?.classList.contains("FullscreenLyricsPresenting")) {
      ResetFullscreenLyricsPresentation();
    }
    return;
  }
  const currentType = $currentLyricsType.get() as LyricsType;
  if (currentType !== "Line" && currentType !== "Syllable") return;
  const lines = LyricsObject.Types[currentType].Lines as LyricsLine[] | LyricsSyllable[];
  if (!lines.length) return;
  const currentLine = GetScrollLine(lines, position) as EnhancedLyricsItem | null;
  UpdateFullscreenLyricsPresentation(lines, currentLine, position, rawPosition);
};

/**
 * Stable lyric leaves used by the fullscreen view FLIP. The returned elements
 * are the same animator-owned nodes before and after they move into the focus
 * stage, so the compositor can translate them without cloning or rebuilding
 * lyric markup.
 */
export const GetFullscreenLyricsTransitionTargets = (): HTMLElement[] => {
  const staged = PageContainer?.querySelectorAll<HTMLElement>(
    ".FullscreenLyricsFocusStage .line[data-fullscreen-focus-index]"
  );
  if (staged && staged.length > 0) return [...staged];

  const currentType = $currentLyricsType.get() as LyricsType;
  if (currentType !== "Line" && currentType !== "Syllable") return [];
  const lines = LyricsObject.Types[currentType].Lines as LyricsLine[] | LyricsSyllable[];
  if (!lines.length) return [];

  const position = getLyricsAnimationPosition(SpotifyPlayer.GetPosition(), $simpleLyricsMode.get());
  const currentLine = GetScrollLine(lines, position) as EnhancedLyricsItem | null;
  let anchor = ResolveFullscreenFocusAnchor(lines, currentLine, position);

  // Resolve the same gap policy as the live presentation without mutating the
  // transition tracker merely to collect FLIP targets.
  if (!anchor) {
    for (let i = lines.length - 1; i >= 0; i--) {
      const line = lines[i];
      if (IsBackgroundFocusLine(line) || line.StartTime > position) continue;
      if (line.DotLine && !IsEligibleFullscreenInterlude(lines, line, position)) continue;
      anchor = { line, index: i, element: line.HTMLElement };
      break;
    }
    if (anchor?.line.DotLine) {
      const next = GetNearestFocusLine(lines, anchor.index, 1);
      if (shouldAdvanceCompletedFullscreenInterlude(position, anchor.line.EndTime, next !== null)) {
        anchor = next;
      }
    }
  }
  if (!anchor) return [];

  const targets = new Set<HTMLElement>();
  const add = (entry: FocusLineEntry | null) => {
    if (!entry) return;
    targets.add(entry.element);
    if (!entry.line.DotLine) {
      GetAttachedBackgroundLines(lines, entry.index).forEach((background) => {
        targets.add(background.element);
      });
    }
  };

  const reveal = $fullscreenRevealMode.get();
  if (!reveal) add(GetPersistentPreviousFocusLine(lines, anchor.index));
  add(anchor);
  if (!reveal) add(GetNearestFocusLine(lines, anchor.index, 1));

  return [...targets].filter((element) => element.isConnected);
};

const resetPlaybackDrivenPresentationState = () => {
  deferredLyricsScroll.cancel();
  fullscreenLineTransitionTracker.reset();
  syncLineTransitionState(null);
  clearOutroState();
  fullscreenVocalTimingCache = undefined;
  fullscreenInterludeSilenceCache = new WeakMap();
  focusIndexLines = null;
  focusIndices.clear();
};

Global.Event.listen("lyrics:frame", UpdateFullscreenLyricsFrame);
Global.Event.listen("lyrics:apply", resetPlaybackDrivenPresentationState);
Global.Event.listen("playback:songchange", resetPlaybackDrivenPresentationState);
Global.Event.listen("song:seek", resetPlaybackDrivenPresentationState);

const GetScrollType = (): "Center" | "Top" => {
  if (IsLyricsFocusMode()) return "Center";
  return IsCompactMode() ? "Top" : "Center";
};

const policyEventPreset = "policy:";

let allowForceScrolling = true;

export const SetForceScrollingPolicy = (value: boolean) => {
  allowForceScrolling = value; // true = allow force scrolling, false = disallow force scrolling
  Global.Event.evoke(`${policyEventPreset}force-scrolling`, value);
};
export const GetForceScrollingPolicy = () => {
  return allowForceScrolling;
};

export function ScrollToActiveLine(ScrollSimplebar: any) {
  if ($currentLyricsType.get() === "Static" || $currentLyricsType.get() === "None") return;
  if (!$lyricsContainerExists.get()) return;
  // A fullscreen view FLIP reads stable virtual-list destination boxes once and
  // then moves only compositor carriers. Mutating scrollTop during that window
  // changes the carriers' base coordinates underneath their active transforms,
  // making the three focus lines bunch together and snap when WAAPI releases
  // them. Fullscreen.ts queues one smooth reconciliation after the landing.
  if (PageContainer?.classList.contains("FullscreenViewTransitioning")) return;

  // The focus stage owns scrolling while active; leave queued list handoffs
  // intact for exit instead of scanning every lyric in a second timer loop.
  if (IsLyricsFocusMode()) {
    // Keep the discontinuity anchor fresh: elapsed focus playback is not a
    // seek and must not turn the queued smooth list landing into a snap.
    lastPosition = SpotifyPlayer.GetPosition();
    return;
  }

  const currentType = $currentLyricsType.get() as LyricsType;
  const Lines = LyricsObject.Types[currentType]?.Lines as LyricsLine[] | LyricsSyllable[];
  if (!Lines) return;

  // Check if a force scroll was queued
  const isForceScrollQueued = forceScrollQueued;
  const isSmoothForceScrollQueued = smoothForceScrollQueued;

  //if (Spicetify.Platform.History.location.pathname === "/IcyLyrics") {
  const { positionMs: Position, isPlaying } = readLyricsClock();
  const PositionOffset = 0;
  const ProcessedPosition =
    getLyricsAnimationPosition(Position, $simpleLyricsMode.get()) + PositionOffset;
  const currentLine = GetScrollLine(Lines, ProcessedPosition) as EnhancedLyricsItem | null;

  const allLinesNotSung = Lines.every((line: any) => line.Status === "NotSung");
  const activeLines = Lines.filter((line: any) => line.Status === "Active");
  const sungLines = Lines.filter((line: any) => line.Status === "Sung");
  const oneActiveNoSung = activeLines.length === 1 && sungLines.length === 0;
  const allLinesSung = Lines.every((line: any) => line.Status === "Sung");
  const scrollRequest = resolveLyricsScrollRequest({
    forceQueued: isForceScrollQueued,
    smoothQueued: isSmoothForceScrollQueued,
    lastLineMissing: lastLine === null,
    pausedPositionChanged: !isPlaying && lastPosition !== Position,
    drasticPositionChange: lastPosition !== 0 && wasDrasticPositionChange(lastPosition, Position),
  });

  if (scrollRequest) {
    if (!allowForceScrolling) return;
    const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
    if (!container) return;
    isUserScrolling = false;
    const scrollToLine = allLinesSung
      ? Lines[Lines.length - 1]?.HTMLElement
      : currentLine?.HTMLElement;
    if (!scrollToLine) return;
    deferredLyricsScroll.cancel();
    lastLine = scrollToLine;
    const forceScrollLineIndex = allLinesSung ? Lines.length - 1 : currentLine?._LineIndex;
    ScrollTo(
      container,
      scrollToLine,
      scrollRequest === "instant",
      GetScrollType(),
      forceScrollLineIndex
    );
    // A successful handoff consumes both queues. Keeping the superseded force
    // request would cause an instant second scroll on the following tick.
    forceScrollQueued = false;
    smoothForceScrollQueued = false;
    lastPosition = Position;
    return;
  }

  lastPosition = Position;

  if (!Lines) return;

  // --- NEW: Check conditions to scroll to top ---

  if (allLinesNotSung || oneActiveNoSung) {
    /*  const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
            if (container) {
                const timeSinceLastScroll = performance.now() - lastUserScrollTime;
                // Only auto-scroll if user hasn't scrolled recently
                if (timeSinceLastScroll > USER_SCROLL_COOLDOWN && !isUserScrolling) {
                    isUserScrolling = false;
                    const lyricsContent = PageContainer?.querySelector(".LyricsContainer .LyricsContent");
                    if (lyricsContent) {
                        lyricsContent.classList.remove("HideLineBlur");
                    }
                    // Use smooth scrolling to top
                    container.scrollTop = 0;
                }
                return; // Exit early after handling scroll to top
            } */
    if (scrolledToFirstLine) return;
    QueueSmoothForceScroll();
    scrolledToFirstLine = true;
  }
  // --- END NEW ---

  // Check if all lines are sung

  if (allLinesSung) {
    /* const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
            if (container) {
                const timeSinceLastScroll = performance.now() - lastUserScrollTime;

                // Only auto-scroll if user hasn't scrolled recently
                if (timeSinceLastScroll > USER_SCROLL_COOLDOWN && !isUserScrolling) {
                    isUserScrolling = false;
                    // Remove HideLineBlur class when auto-scroll resumes
                    const lyricsContent = PageContainer?.querySelector(".LyricsContainer .LyricsContent");
                    if (lyricsContent) {
                        lyricsContent.classList.remove("HideLineBlur");
                    }
                    // Get the last line element to scroll to
                    const lastLineElement = Lines[Lines.length - 1].HTMLElement as HTMLElement;
                    ScrollIntoCenterViewCSS(container, lastLineElement, true);
                }
                return;
            } */
    if (scrolledToLastLine) return;
    QueueSmoothForceScroll();
    scrolledToLastLine = true;
  }

  // Handle start of track
  //if (Position <= POSITION_THRESHOLD) {
  /* const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
            if (container) {
                // Use smooth scrolling to top
                container.scrollTop = 0;
                return;
            } */
  /* QueueForceScroll();
        } */

  // Handle end of track
  /* if (ProcessedPosition >= TrackDuration - POSITION_THRESHOLD) {
            /* const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
            if (container) {
                // Use smooth scrolling to bottom
                container.scrollTop = container.scrollHeight;
                return;
            }
            QueueForceScroll();
        } */

  Continue(currentLine);

  function Continue(currentLine: EnhancedLyricsItem | null) {
    if (currentLine) {
      const LineElem = currentLine?.HTMLElement as HTMLElement;
      if (!LineElem) return;
      const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
      if (!container) return;
      const now = performance.now();
      const timeSinceLastScroll = now - lastUserScrollTime;

      // Throttled layout read for viewport visibility
      const shouldRecalculateViewport =
        now - lastViewportCheckTime > VIEWPORT_CHECK_INTERVAL ||
        lastViewportLine !== LineElem ||
        lastViewportContainer !== container;

      if (shouldRecalculateViewport) {
        // Check if the line is at least 5px visible within the scroll container
        const elementOffsetTop = LineElem.offsetTop;
        const elementBottom = elementOffsetTop + LineElem.clientHeight;
        const viewportTop = container.scrollTop;
        const viewportBottom = viewportTop + container.clientHeight;

        const visibleTop = Math.max(elementOffsetTop, viewportTop);
        const visibleBottom = Math.min(elementBottom, viewportBottom);
        const visibleHeight = Math.max(0, visibleBottom - visibleTop);

        // Consider the line "in viewport" if at least 5px is visible
        lastIsLineInViewport = visibleHeight >= 5;
        lastViewportCheckTime = now;
        lastViewportLine = LineElem;
        lastViewportContainer = container;
      }

      // When virtualizer is active, detached (off-screen) elements have offsetTop=0,
      // making the standard viewport check unreliable. Use isConnected as a proxy:
      // mounted elements are near the current scroll position (within overscan), so
      // treating them as "in viewport" is close enough. Detached elements mean the
      // user scrolled far away — preserve the original no-scroll behavior.
      const isLineInViewport =
        lastIsLineInViewport || (getLyricsVirtualizer() !== null && LineElem.isConnected);

      const isSameLine = lastLine === LineElem;

      /*
                for (let i = 0; i < Lines.length; i++) {
                    const line = Lines[i];
                    if (line.HTMLElement) {
                        const container = ScrollSimplebar?.getScrollElement() as HTMLElement;
                        if (!container) return;
                        const LineElem = line.HTMLElement;
                        const lineRect = LineElem.getBoundingClientRect();
                        const containerRect = container.getBoundingClientRect();
                        const isLineInViewport = lineRect.top >= containerRect.top && lineRect.bottom <= containerRect.bottom;

                        if (!isLineInViewport) {
                            if (!LineElem.classList.contains("NotInViewport")) LineElem.classList.add("NotInViewport")
                        } else {
                            if (LineElem.classList.contains("NotInViewport")) LineElem.classList.remove("NotInViewport")
                        }
                    }
                } */

      // If this is the first line (no previous line), force scroll without checks
      /* if (!shouldForceScroll) {
                    isUserScrolling = false;
                    lastLine = LineElem;
                    ScrollIntoCenterViewCSS(container, LineElem, true);
                    return;
                } */

      // Only auto-scroll if BOTH conditions are met:
      // 1. User hasn't scrolled in the last second (cooldown passed)
      // 2. AND the active line is in viewport
      if (timeSinceLastScroll > USER_SCROLL_COOLDOWN && isLineInViewport) {
        // --- REVISED LOGIC for resuming auto-scroll ---
        //const wasUserScrolling = isUserScrolling; // Capture state before changing
        isUserScrolling = false;
        // Remove HideLineBlur class ONLY if we were user scrolling
        //if (wasUserScrolling) {
        const lyricsContent = PageContainer?.querySelector<HTMLElement>(".LyricsContainer .LyricsContent");
        if (lyricsContent) {
          setFrameClass(lyricsContent, "HideLineBlur", false);
        } else {
          console.warn(
            "IcyLyrics: Could not find .LyricsContent in ScrollToActiveLine to remove HideLineBlur."
          );
        }
        //}
        // Scroll if the line is different from the last auto-scrolled line
        if (!isSameLine) {
          deferredLyricsScroll.cancel();
          lastLine = LineElem;
          const Scroll = () => {
            ScrollTo(container, LineElem, false, GetScrollType(), currentLine._LineIndex);
            scrolledToLastLine = false;
            scrolledToFirstLine = false;
          };
          if (
            Lines[currentLine._LineIndex - 1] &&
            Lines[currentLine._LineIndex - 1].DotLine === true
          ) {
            deferredLyricsScroll.schedule(Scroll, 240);
          } else {
            Scroll();
          }
        }
        // --- END REVISED LOGIC ---
      }
    }
  }
  //}
}

// Function to queue a force scroll for the next frame
export function QueueForceScroll() {
  forceScrollQueued = true;
}

export function QueueSmoothForceScroll() {
  smoothForceScrollQueued = true;
}

export function ResetLastLine() {
  deferredLyricsScroll.cancel();
  lastLine = null;
  lastViewportLine = null;
  lastViewportContainer = null;
  lastIsLineInViewport = false;
  lastViewportCheckTime = 0;
  isUserScrolling = false;
  lastUserScrollTime = 0;
  lastPosition = 0;
  forceScrollQueued = false;
  smoothForceScrollQueued = false;
  scrolledToLastLine = false;
  scrolledToFirstLine = false;
  // Also disconnect observer on reset if needed, though setup handles disconnect now
  // lyricsContentObserver.disconnect();
}

// --- NEW: Cleanup Function ---
export function CleanupScrollEvents() {
  deferredLyricsScroll.cancel();
  scrollEventBindings.dispose();
  // Disconnect observer
  lyricsContentObserver?.disconnect();
  forceScrollQueued = false; // Reset force scroll queue
  smoothForceScrollQueued = false;
  scrolledToLastLine = false;
  scrolledToFirstLine = false;
  // console.log("IcyLyrics scroll events cleaned up.");
}
// --- END NEW ---
