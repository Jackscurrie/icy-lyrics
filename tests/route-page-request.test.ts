import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRoutePageRequest } from "../src/utils/RoutePageRequest.ts";

describe("lyrics button route readiness", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup() {
    let pathname = "/";
    let page: object | null = null;
    const onError = vi.fn();
    const navigate = vi.fn(() => { pathname = "/IcyLyrics"; });
    const controller = createRoutePageRequest({
      route: "/IcyLyrics",
      navigate,
      getPathname: () => pathname,
      getReadyPage: () => page,
      onError,
      timeoutMs: 500,
      pollMs: 50,
    });
    return {
      controller, onError, navigate,
      setPage(value: object | null) { page = value; },
      navigateAway() {
        pathname = "/collection/tracks";
        controller.onRouteChange(pathname);
      },
      setPathname(value: string) { pathname = value; },
    };
  }

  it("waits for an actually ready page before entering fullscreen, then stops polling", () => {
    const test = setup();
    const open = vi.fn();
    const page = {};
    test.controller.request(open);
    vi.advanceTimersByTime(200);
    expect(open).not.toHaveBeenCalled();
    test.setPage(page);
    vi.advanceTimersByTime(50);
    expect(open).toHaveBeenCalledExactlyOnceWith(page);
    expect(vi.getTimerCount()).toBe(0);
    expect(test.onError).not.toHaveBeenCalled();
  });

  it("reports a missing mount once instead of polling forever", () => {
    const test = setup();
    test.controller.request();
    vi.advanceTimersByTime(10_000);
    expect(test.onError).toHaveBeenCalledTimes(1);
    expect(test.onError.mock.calls[0][0].message).toMatch(/did not become ready/);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("cancels immediately when navigation leaves the requested route", () => {
    const test = setup();
    const open = vi.fn();
    test.controller.request(open);
    test.navigateAway();
    expect(vi.getTimerCount()).toBe(0);
    // A page appearing later, even on a quick return, cannot steal the UI.
    test.setPage({});
    test.setPathname("/IcyLyrics");
    vi.advanceTimersByTime(1_000);
    expect(open).not.toHaveBeenCalled();
    expect(test.onError).not.toHaveBeenCalled();
  });

  it("also cancels when a route change is discovered by the readiness check", () => {
    const test = setup();
    const open = vi.fn();
    test.controller.request(open);
    test.setPathname("/");
    test.setPage({});
    vi.advanceTimersByTime(50);
    expect(open).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("a newer click supersedes an old fullscreen/cinema request", () => {
    const test = setup();
    const previous = vi.fn();
    const latest = vi.fn();
    test.controller.request(previous);
    vi.advanceTimersByTime(150);
    test.controller.request(latest);
    test.setPage({});
    vi.advanceTimersByTime(1_000);
    expect(previous).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledTimes(1);
    expect(test.onError).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("explicit cancellation releases its timer", () => {
    const test = setup();
    test.controller.request();
    test.controller.cancel();
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(1_000);
    expect(test.onError).not.toHaveBeenCalled();
  });

  it("reports synchronous navigation and open failures without leaking timers", () => {
    const test = setup();
    test.navigate.mockImplementationOnce(() => { throw new Error("history unavailable"); });
    expect(() => test.controller.request()).not.toThrow();
    expect(test.onError).toHaveBeenCalledTimes(1);
    test.setPage({});
    expect(() => test.controller.request(() => { throw new Error("open failed"); })).not.toThrow();
    expect(test.onError).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("does not clear a new request created from an opening callback", () => {
    const test = setup();
    const next = vi.fn();
    test.setPage({});
    test.controller.request(() => {
      test.setPage(null);
      test.controller.request(next);
    });
    expect(vi.getTimerCount()).toBe(1);
    test.setPage({});
    vi.advanceTimersByTime(50);
    expect(next).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("reports a rejected asynchronous fullscreen entry without an unhandled rejection", async () => {
    const test = setup();
    const failure = new Error("fullscreen denied");
    test.setPage({});
    test.controller.request(() => Promise.reject(failure));
    await Promise.resolve();
    expect(test.onError).toHaveBeenCalledExactlyOnceWith(failure);
    expect(vi.getTimerCount()).toBe(0);
  });
});
