import { describe, expect, it, vi } from "vitest";
import { createLyricsClock } from "../src/utils/Lyrics/clock.ts";
import { resolveLyricsScrollRequest } from "../src/utils/Scrolling/ScrollRequest.ts";
import {
  getLyricsAnimationPosition,
  getLyricsInputPositionForAnimation,
} from "../src/utils/Lyrics/Animator/Shared.ts";

const fallbackClock = () => ({
  positionMs: vi.fn(() => 8_025),
  rawPositionMs: vi.fn(() => 8_000),
  isPlaying: vi.fn(() => true),
});

describe("shared lyric animation and scroll clock", () => {
  it("preserves the normal Spotify animation and raw clock readings", () => {
    const fallback = fallbackClock();
    const clock = createLyricsClock(fallback);
    expect(clock.read()).toEqual({ positionMs: 8_025, rawPositionMs: 8_000, isPlaying: true });
    expect(fallback.positionMs).toHaveBeenCalledOnce();
    expect(fallback.rawPositionMs).toHaveBeenCalledOnce();
    // Existing listening-mode behavior remains unchanged outside Preview.
    expect(getLyricsAnimationPosition(8_025, true)).toBe(7_991.5);
  });

  it("uses the preview clock for every consumer without reading a different Spotify song", () => {
    const fallback = fallbackClock();
    const clock = createLyricsClock(fallback);
    let localPosition = 0;
    clock.acquire(() => ({ positionMs: localPosition, rawPositionMs: localPosition, isPlaying: false }));
    expect(clock.read()).toEqual({ positionMs: 0, rawPositionMs: 0, isPlaying: false });
    localPosition = 1234.567;
    const animationClock = clock.read();
    const scrollClock = clock.read();
    expect(animationClock).toEqual(scrollClock);
    expect(scrollClock.positionMs).toBe(localPosition);
    expect(fallback.positionMs).not.toHaveBeenCalled();
    expect(fallback.rawPositionMs).not.toHaveBeenCalled();
    expect(fallback.isPlaying).not.toHaveBeenCalled();
  });

  it("falls back for null providers and retains optional raw clock compatibility", () => {
    const clock = createLyricsClock(fallbackClock());
    clock.acquire(() => null);
    expect(clock.read()).toEqual({ positionMs: 8_025, rawPositionMs: 8_000, isPlaying: true });
    clock.acquire(() => ({ positionMs: 500 }));
    expect(clock.read()).toEqual({ positionMs: 500, rawPositionMs: 8_000, isPlaying: true });
  });

  it("does not classify playing local audio as repeated paused seeks when Spotify is paused", () => {
    const fallback = { ...fallbackClock(), isPlaying: vi.fn(() => false) };
    const clock = createLyricsClock(fallback);
    let playing = true;
    clock.acquire(() => ({ positionMs: 1_016, rawPositionMs: 1_016, isPlaying: playing }));
    const scrollRequest = () => {
      const reading = clock.read();
      return resolveLyricsScrollRequest({
        forceQueued: false,
        smoothQueued: false,
        lastLineMissing: false,
        drasticPositionChange: false,
        pausedPositionChanged: !reading.isPlaying && reading.positionMs !== 1_000,
      });
    };
    expect(scrollRequest()).toBeNull();
    playing = false;
    expect(scrollRequest()).toBe("smooth");
    expect(fallback.isPlaying).not.toHaveBeenCalled();
  });

  it("does not let older preview cleanup remove a newer preview clock", () => {
    const clock = createLyricsClock(fallbackClock());
    const releaseOlder = clock.acquire(() => ({ positionMs: 100, rawPositionMs: 100 }));
    const releaseNewer = clock.acquire(() => ({ positionMs: 200, rawPositionMs: 200 }));
    releaseOlder();
    expect(clock.read().positionMs).toBe(200);
    releaseNewer();
    expect(clock.read()).toEqual({ positionMs: 8_025, rawPositionMs: 8_000, isPlaying: true });
    releaseOlder();
    releaseNewer();
    expect(clock.read().positionMs).toBe(8_025);
  });

  it.each([false, true])("previews exact media timing with simple mode %s and unchanged raw time", (simpleMode) => {
    const clock = createLyricsClock(fallbackClock());
    let rawPositionMs = 0;
    clock.acquire(() => ({
      positionMs: getLyricsInputPositionForAnimation(rawPositionMs, simpleMode),
      rawPositionMs,
    }));
    for (const position of [0, 1, 33.5, 999.875, 10_000, 2_000, 0]) {
      rawPositionMs = position;
      const reading = clock.read();
      expect(getLyricsAnimationPosition(reading.positionMs, simpleMode)).toBe(position);
      expect(reading.rawPositionMs).toBe(position);
    }
  });
});
