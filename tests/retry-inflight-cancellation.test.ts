import { afterEach, describe, expect, it, vi } from "vitest";
import {
  LyricsQueueRetryController,
  type QueueFetchResult,
} from "../src/utils/Lyrics/LyricsQueueRetryCore.ts";

afterEach(() => vi.useRealTimers());

describe("in-flight lyrics queue cancellation", () => {
  it("does not apply or reschedule an old URI when playback changes during fetch", async () => {
    vi.useFakeTimers();
    const oldUri = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";
    const newUri = "spotify:track:bbbbbbbbbbbbbbbbbbbbbb";
    let currentUri: string | null = oldUri;
    let resolveFetch!: (result: QueueFetchResult) => void;
    const fetchResult = new Promise<QueueFetchResult>((resolve) => {
      resolveFetch = resolve;
    });
    const apply = vi.fn(async () => {});
    const fetch = vi.fn(async () => fetchResult);
    const controller = new LyricsQueueRetryController({
      getCurrentUri: () => currentUri,
      fetch,
      apply,
      showQueue: vi.fn(),
      setTimer: (callback, delay) => setTimeout(callback, delay),
      clearTimer: (timer) => clearTimeout(timer),
    });

    controller.HandleQueued(oldUri);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(fetch).toHaveBeenCalledOnce();

    currentUri = newUri;
    controller.OnSongChange(newUri);
    resolveFetch([{ Type: "Line" }, 200]);
    await vi.runAllTimersAsync();

    expect(apply).not.toHaveBeenCalled();
    expect(controller.IsRetryingFor(oldUri)).toBe(false);
    expect(fetch).toHaveBeenCalledOnce();
  });
});
