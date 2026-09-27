export type LyricsScrollRequest = "instant" | "smooth" | null;

/** A view FLIP has already positioned the list. Its explicit smooth landing
 * supersedes hidden-list requests and playback drift accumulated while pinned. */
export function resolveLyricsScrollRequest({
  forceQueued,
  smoothQueued,
  lastLineMissing,
  pausedPositionChanged,
  drasticPositionChange,
}: {
  forceQueued: boolean;
  smoothQueued: boolean;
  lastLineMissing: boolean;
  pausedPositionChanged: boolean;
  drasticPositionChange: boolean;
}): LyricsScrollRequest {
  if (smoothQueued) return "smooth";
  if (forceQueued || lastLineMissing || drasticPositionChange) return "instant";
  return pausedPositionChanged ? "smooth" : null;
}

/** The post-interlude delay belongs to the current lyric list/seek only. */
export class DeferredLyricsScroll {
  private timer: ReturnType<typeof setTimeout> | null = null;

  schedule(scroll: () => void, delayMs: number): void {
    this.cancel();
    this.timer = setTimeout(() => {
      this.timer = null;
      scroll();
    }, delayMs);
  }

  cancel(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
