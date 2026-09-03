import type { CreatorTrack } from "./data.ts";

export type CreatorTrackSearch = (query: string, signal?: AbortSignal) => Promise<CreatorTrack[]>;

export interface CreatorSearchCallbacks {
  onPending: (pending: boolean) => void;
  onResults: (results: CreatorTrack[]) => void;
  onError: (error: Error) => void;
}

export interface CreatorSearchControllerOptions {
  debounceMs?: number;
  minimumLength?: number;
  setTimer?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void;
}

/** A debounced, abortable, last-query-wins Spotify search controller. */
export class CreatorSearchController {
  private readonly debounceMs: number;
  private readonly minimumLength: number;
  private readonly setTimer: NonNullable<CreatorSearchControllerOptions["setTimer"]>;
  private readonly clearTimer: NonNullable<CreatorSearchControllerOptions["clearTimer"]>;
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private request: AbortController | null = null;
  private clearPending: (() => void) | null = null;

  constructor(
    private readonly search: CreatorTrackSearch,
    options: CreatorSearchControllerOptions = {}
  ) {
    this.debounceMs = options.debounceMs ?? 260;
    this.minimumLength = options.minimumLength ?? 2;
    this.setTimer = options.setTimer ?? ((callback, delayMs) => setTimeout(callback, delayMs));
    this.clearTimer = options.clearTimer ?? ((timer) => clearTimeout(timer));
  }

  update(query: string, callbacks: CreatorSearchCallbacks): void {
    this.schedule(query, callbacks, this.debounceMs);
  }

  searchNow(query: string, callbacks: CreatorSearchCallbacks): void {
    this.schedule(query, callbacks, 0);
  }

  cancel(): void {
    this.generation += 1;
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = null;
    this.request?.abort();
    this.request = null;
    this.clearPending?.();
    this.clearPending = null;
  }

  private schedule(query: string, callbacks: CreatorSearchCallbacks, delayMs: number): void {
    this.cancel();
    const generation = this.generation;
    const normalized = query.trim();
    if (normalized.length < this.minimumLength) {
      callbacks.onPending(false);
      callbacks.onResults([]);
      return;
    }

    callbacks.onPending(true);
    this.clearPending = () => callbacks.onPending(false);
    this.timer = this.setTimer(() => {
      this.timer = null;
      void this.run(normalized, generation, callbacks);
    }, delayMs);
  }

  private async run(
    query: string,
    generation: number,
    callbacks: CreatorSearchCallbacks
  ): Promise<void> {
    const request = new AbortController();
    this.request = request;
    try {
      const results = await this.search(query, request.signal);
      if (request.signal.aborted || generation !== this.generation) return;
      callbacks.onResults(results);
    } catch (error) {
      if (request.signal.aborted || generation !== this.generation) return;
      if ((error as Error)?.name !== "AbortError") {
        callbacks.onError(error instanceof Error ? error : new Error("Spotify search failed."));
      }
    } finally {
      if (!request.signal.aborted && generation === this.generation) {
        this.request = null;
        this.clearPending = null;
        callbacks.onPending(false);
      }
    }
  }
}

export interface CreatorSearchKeyResult {
  activeIndex: number;
  selectIndex: number | null;
  dismiss: boolean;
}

export function creatorSearchKeyResult(
  key: string,
  activeIndex: number,
  resultCount: number
): CreatorSearchKeyResult {
  if (resultCount <= 0) return { activeIndex: -1, selectIndex: null, dismiss: key === "Escape" };
  if (key === "ArrowDown") {
    return {
      activeIndex: (activeIndex + 1 + resultCount) % resultCount,
      selectIndex: null,
      dismiss: false,
    };
  }
  if (key === "ArrowUp") {
    return {
      activeIndex: activeIndex <= 0 ? resultCount - 1 : activeIndex - 1,
      selectIndex: null,
      dismiss: false,
    };
  }
  if (key === "Home") return { activeIndex: 0, selectIndex: null, dismiss: false };
  if (key === "End") {
    return { activeIndex: resultCount - 1, selectIndex: null, dismiss: false };
  }
  if (key === "Enter" && activeIndex >= 0 && activeIndex < resultCount) {
    return { activeIndex, selectIndex: activeIndex, dismiss: false };
  }
  return { activeIndex, selectIndex: null, dismiss: key === "Escape" };
}
