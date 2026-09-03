import { describe, expect, it, vi } from "vitest";
import {
  CreatorPreviewRenderQueue,
  creatorPreviewHasRenderedLyrics,
  prepareCreatorPreviewSurface,
  resolveCreatorPreviewPage,
} from "../src/components/ReactComponents/LyricCreator/previewOwnership.ts";

describe("Lyric Creator preview ownership", () => {
  it("waits through a transient open PageView with no exported container", async () => {
    const page = {} as HTMLElement;
    let currentPage: HTMLElement | null = null;
    let turns = 0;
    const openPage = vi.fn(async () => undefined);

    const resolved = await resolveCreatorPreviewPage({
      getPage: () => currentPage,
      isPageOpen: () => true,
      openPage,
      waitForTurn: async () => {
        turns += 1;
        if (turns === 2) currentPage = page;
      },
      maxAttempts: 4,
    });

    expect(resolved).toEqual({ page, createdPage: false });
    expect(openPage).not.toHaveBeenCalled();
  });

  it("opens a dedicated preview page when no PageView owner exists", async () => {
    const page = {} as HTMLElement;
    let currentPage: HTMLElement | null = null;
    const openPage = vi.fn(async () => {
      currentPage = page;
    });

    const resolved = await resolveCreatorPreviewPage({
      getPage: () => currentPage,
      isPageOpen: () => false,
      openPage,
      waitForTurn: async () => undefined,
    });

    expect(resolved).toEqual({ page, createdPage: true });
    expect(openPage).toHaveBeenCalledOnce();
  });

  it("retries a skipped render and only applies the newest queued project", async () => {
    let nextHandle = 0;
    const scheduled = new Map<number, () => void>();
    const cancelled: number[] = [];
    const renderedValues: string[] = [];
    let attempts = 0;
    const queue = new CreatorPreviewRenderQueue<string>({
      schedule: (callback) => {
        nextHandle += 1;
        scheduled.set(nextHandle, callback);
        return nextHandle;
      },
      cancelSchedule: (handle) => {
        cancelled.push(handle);
        scheduled.delete(handle);
      },
      render: async (value) => {
        renderedValues.push(value);
        attempts += 1;
        return attempts > 1;
      },
      maxAttempts: 3,
    });

    queue.update("old");
    queue.update("new");
    expect(cancelled).toEqual([1]);

    scheduled.get(2)?.();
    await Promise.resolve();
    await Promise.resolve();
    scheduled.get(3)?.();
    await Promise.resolve();
    await Promise.resolve();

    expect(renderedValues).toEqual(["new", "new"]);
    queue.dispose();
  });

  it("clears stale no-lyrics visibility gates before applying Creator lyrics", () => {
    const contentBox = { classList: { remove: vi.fn() } };
    const lyricsContainer = { classList: { remove: vi.fn() } };
    const lyricsContent = { classList: { remove: vi.fn() } };
    const page = {
      querySelector: vi.fn((selector: string) => {
        if (selector === ".ContentBox") return contentBox;
        if (selector === ".ContentBox .LyricsContainer") return lyricsContainer;
        if (selector === ".LyricsContainer .LyricsContent") return lyricsContent;
        return null;
      }),
    } as unknown as HTMLElement;

    prepareCreatorPreviewSurface(page);

    expect(contentBox.classList.remove).toHaveBeenCalledWith("LyricsHidden");
    expect(lyricsContainer.classList.remove).toHaveBeenCalledWith("Hidden");
    expect(lyricsContent.classList.remove).toHaveBeenCalledWith("HiddenTransitioned", "offline");
  });

  it("requires a mounted lyric line only when the project contains text", () => {
    const line = {};
    const renderedContainer = {
      querySelector: vi.fn(() => line),
    };
    const page = {
      querySelector: vi.fn(() => renderedContainer),
    } as unknown as HTMLElement;

    expect(creatorPreviewHasRenderedLyrics(page, true)).toBe(true);
    expect(renderedContainer.querySelector).toHaveBeenCalledWith(".line");
    expect(creatorPreviewHasRenderedLyrics(page, false)).toBe(true);
  });
});
