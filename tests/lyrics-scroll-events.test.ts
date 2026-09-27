import { describe, expect, it, vi } from "vitest";
import { ScrollEventBindings } from "../src/utils/Scrolling/ScrollEventBindings.ts";

describe("lyric scroller listener lifetime", () => {
  it("restores resize/reveal invalidation and focus after every lyrics cleanup", () => {
    const bindings = new ScrollEventBindings();
    const windowTarget = new EventTarget();
    const scroller = new EventTarget();
    const focus = vi.fn();
    const resize = vi.fn();
    const scroll = vi.fn();
    for (let song = 0; song < 3; song++) {
      bindings.bind(windowTarget, scroller, focus, resize, scroll);
      windowTarget.dispatchEvent(new Event("focus"));
      windowTarget.dispatchEvent(new Event("resize"));
      scroller.dispatchEvent(new Event("wheel"));
      scroller.dispatchEvent(new Event("touchmove"));
      bindings.dispose();
      windowTarget.dispatchEvent(new Event("focus"));
      windowTarget.dispatchEvent(new Event("resize"));
      scroller.dispatchEvent(new Event("wheel"));
    }
    expect(focus).toHaveBeenCalledTimes(3);
    expect(resize).toHaveBeenCalledTimes(3);
    expect(scroll).toHaveBeenCalledTimes(6);
  });

  it("replaces the old scroller without accumulating window callbacks", () => {
    const bindings = new ScrollEventBindings();
    const windowTarget = new EventTarget();
    const oldScroller = new EventTarget();
    const newScroller = new EventTarget();
    const oldFocus = vi.fn();
    const oldResize = vi.fn();
    const oldScroll = vi.fn();
    const newFocus = vi.fn();
    const newResize = vi.fn();
    const newScroll = vi.fn();
    bindings.bind(windowTarget, oldScroller, oldFocus, oldResize, oldScroll);
    bindings.bind(windowTarget, newScroller, newFocus, newResize, newScroll);
    windowTarget.dispatchEvent(new Event("focus"));
    windowTarget.dispatchEvent(new Event("resize"));
    oldScroller.dispatchEvent(new Event("wheel"));
    newScroller.dispatchEvent(new Event("wheel"));
    expect(oldFocus).not.toHaveBeenCalled();
    expect(oldResize).not.toHaveBeenCalled();
    expect(oldScroll).not.toHaveBeenCalled();
    expect(newFocus).toHaveBeenCalledTimes(1);
    expect(newResize).toHaveBeenCalledTimes(1);
    expect(newScroll).toHaveBeenCalledTimes(1);
    bindings.dispose();
    bindings.dispose();
  });
});
