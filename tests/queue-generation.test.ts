import { afterEach, describe, expect, it, vi } from "vitest";
import {
  computeQueueRetryDelay,
  LyricsQueueRetryController,
  type QueueFetchResult,
} from "../src/utils/Lyrics/LyricsQueueRetryCore.ts";
import { LyricsRequestGeneration } from "../src/utils/Lyrics/requestGeneration.ts";

afterEach(() => vi.useRealTimers());

describe("lyrics queue retries", () => {
  it("uses 2s × 1.5 backoff capped at 10s", () => {
    expect([0, 1, 2, 3, 4, 5, 20].map(computeQueueRetryDelay)).toEqual([
      2000, 3000, 4500, 6750, 10000, 10000, 10000,
    ]);
  });

  it("cancels immediately when the URI changes", async () => {
    vi.useFakeTimers();
    let currentUri: string | null = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";
    const fetch = vi.fn<() => Promise<QueueFetchResult>>(async () => ["lyrics-queued", 503]);
    const apply = vi.fn(async () => {});
    const controller = new LyricsQueueRetryController({
      getCurrentUri: () => currentUri,
      fetch,
      apply,
      showQueue: vi.fn(),
      setTimer: (callback, delay) => setTimeout(callback, delay),
      clearTimer: (timer) => clearTimeout(timer),
    });

    controller.HandleQueued(currentUri);
    currentUri = "spotify:track:bbbbbbbbbbbbbbbbbbbbbb";
    controller.OnSongChange(currentUri);
    await vi.advanceTimersByTimeAsync(20_000);

    expect(fetch).not.toHaveBeenCalled();
    expect(controller.IsRetryingFor("spotify:track:aaaaaaaaaaaaaaaaaaaaaa")).toBe(false);
  });

  it("retries queued responses and stops after a resolution", async () => {
    vi.useFakeTimers();
    const uri = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";
    const fetch = vi
      .fn<() => Promise<QueueFetchResult>>()
      .mockResolvedValueOnce(["lyrics-queued", 503])
      .mockResolvedValueOnce([{ Type: "Line" }, 200]);
    const controller = new LyricsQueueRetryController({
      getCurrentUri: () => uri,
      fetch,
      apply: vi.fn(async () => {}),
      showQueue: vi.fn(),
      setTimer: (callback, delay) => setTimeout(callback, delay),
      clearTimer: (timer) => clearTimeout(timer),
    });

    controller.HandleQueued(uri);
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(3000);

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(controller.IsRetryingFor(uri)).toBe(false);
  });
});

describe("URI/generation guards", () => {
  it("rejects earlier same-URI work and all work after a null song change", () => {
    const guard = new LyricsRequestGeneration();
    const first = guard.begin("spotify:track:aaaaaaaaaaaaaaaaaaaaaa");
    const second = guard.begin("spotify:track:aaaaaaaaaaaaaaaaaaaaaa");
    expect(guard.isCurrent(first, first.uri)).toBe(false);
    expect(guard.isCurrent(second, second.uri)).toBe(true);
    expect(guard.isCurrent(second, null)).toBe(false);

    guard.invalidate(null);
    expect(guard.isCurrent(second, null)).toBe(false);
  });
});
