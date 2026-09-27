import { afterEach, describe, expect, it, vi } from "vitest";
import { DeferredLyricsScroll, resolveLyricsScrollRequest } from "../src/utils/Scrolling/ScrollRequest.ts";

const settled = {
  forceQueued: false,
  smoothQueued: false,
  lastLineMissing: false,
  pausedPositionChanged: false,
  drasticPositionChange: false,
};

describe("fullscreen lyric list scroll handoffs", () => {
  it("keeps an immediate focus reversal smooth despite the entry force queue and missing anchor", () => {
    expect(resolveLyricsScrollRequest({ ...settled, forceQueued: true, smoothQueued: true, lastLineMissing: true })).toBe("smooth");
  });

  it("keeps a long focus stay smooth despite more than one second of playback drift", () => {
    expect(resolveLyricsScrollRequest({ ...settled, smoothQueued: true, drasticPositionChange: true })).toBe("smooth");
  });

  it("keeps a paused or seeked exit smooth when explicitly handed off by a completed FLIP", () => {
    expect(resolveLyricsScrollRequest({ ...settled, smoothQueued: true, forceQueued: true, pausedPositionChanged: true, drasticPositionChange: true })).toBe("smooth");
  });

  it("preserves instant first-open, explicit force and ordinary seek behavior outside a handoff", () => {
    expect(resolveLyricsScrollRequest({ ...settled, lastLineMissing: true })).toBe("instant");
    expect(resolveLyricsScrollRequest({ ...settled, forceQueued: true })).toBe("instant");
    expect(resolveLyricsScrollRequest({ ...settled, drasticPositionChange: true })).toBe("instant");
  });

  it("preserves smooth short paused-position changes and leaves settled playback alone", () => {
    expect(resolveLyricsScrollRequest({ ...settled, pausedPositionChanged: true })).toBe("smooth");
    expect(resolveLyricsScrollRequest(settled)).toBeNull();
  });
});

describe("post-interlude scroll lifetime", () => {
  afterEach(() => vi.useRealTimers());

  it("does not apply an old lyric index after seek, lyrics replacement or cleanup", () => {
    vi.useFakeTimers();
    const request = new DeferredLyricsScroll();
    const scroll = vi.fn();
    request.schedule(scroll, 240);
    vi.advanceTimersByTime(100);
    request.cancel();
    vi.advanceTimersByTime(500);
    expect(scroll).not.toHaveBeenCalled();
  });

  it("allows only the newest line request to run", () => {
    vi.useFakeTimers();
    const request = new DeferredLyricsScroll();
    const oldScroll = vi.fn();
    const newScroll = vi.fn();
    request.schedule(oldScroll, 240);
    vi.advanceTimersByTime(100);
    request.schedule(newScroll, 240);
    vi.advanceTimersByTime(240);
    expect(oldScroll).not.toHaveBeenCalled();
    expect(newScroll).toHaveBeenCalledTimes(1);
    request.cancel();
    vi.advanceTimersByTime(500);
    expect(newScroll).toHaveBeenCalledTimes(1);
  });
});
