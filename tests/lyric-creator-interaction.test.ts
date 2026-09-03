import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CREATOR_TTML_FILE_ACCEPT,
  downloadCreatorTTML,
  openCreatorFilePicker,
  readCreatorTextFile,
  sanitizeCreatorFilename,
} from "../src/components/ReactComponents/LyricCreator/fileActions.ts";
import {
  creatorTimingActionFromKeyboardEvent,
  isCreatorEditableTarget,
  isCreatorPlaybackShortcut,
  isCreatorTextEditingTarget,
} from "../src/components/ReactComponents/LyricCreator/interaction.ts";
import { constrainCreatorPreviewPage } from "../src/components/ReactComponents/LyricCreator/previewOwnership.ts";
import {
  CreatorSearchController,
  creatorSearchKeyResult,
} from "../src/components/ReactComponents/LyricCreator/searchController.ts";
import type { CreatorTrack } from "../src/components/ReactComponents/LyricCreator/data.ts";

const track = (name: string): CreatorTrack => ({
  uri: `spotify:track:${name.padEnd(22, "x").slice(0, 22)}`,
  name,
  artists: ["Artist"],
  album: "Album",
  coverUrl: "",
  durationMs: 1,
  isrc: "",
});

describe("Lyric Creator keyboard isolation", () => {
  it("recognizes nested editable controls and never turns their F/G/H into timing actions", () => {
    const inputTarget = { closest: vi.fn(() => ({ tagName: "INPUT" })) } as unknown as EventTarget;
    expect(isCreatorEditableTarget(inputTarget)).toBe(true);
    expect(
      creatorTimingActionFromKeyboardEvent({
        code: "KeyF",
        repeat: false,
        isComposing: false,
        target: inputTarget,
      })
    ).toBeNull();
  });

  it("maps F/G/H only for non-editable, non-repeated key presses", () => {
    const target = { closest: vi.fn(() => null) } as unknown as EventTarget;
    const keyboardEvent = { repeat: false, isComposing: false, target };
    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, code: "KeyF" })).toBe("start");
    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, code: "KeyG" })).toBe(
      "end-and-next"
    );
    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, code: "KeyH" })).toBe("end");
    expect(
      creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, code: "KeyF", repeat: true })
    ).toBeNull();
  });

  it("accepts key-only events emitted by embedded Chromium input paths", () => {
    const target = { closest: vi.fn(() => null) } as unknown as EventTarget;
    const keyboardEvent = { code: "", repeat: false, isComposing: false, target };

    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, key: "f" })).toBe("start");
    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, key: "G" })).toBe(
      "end-and-next"
    );
    expect(creatorTimingActionFromKeyboardEvent({ ...keyboardEvent, key: "h" })).toBe("end");
  });

  it("keeps timing and playback shortcuts active after timeline controls receive focus", () => {
    const rangeTarget = {
      closest: vi.fn((selector: string) =>
        selector.includes("input, textarea") ? ({ tagName: "INPUT" } as Element) : null
      ),
    } as unknown as EventTarget;
    expect(isCreatorEditableTarget(rangeTarget)).toBe(true);
    expect(isCreatorTextEditingTarget(rangeTarget)).toBe(false);
    expect(
      creatorTimingActionFromKeyboardEvent({
        code: "KeyG",
        repeat: false,
        isComposing: false,
        target: rangeTarget,
      })
    ).toBe("end-and-next");
    expect(
      isCreatorPlaybackShortcut({
        code: "Space",
        repeat: false,
        isComposing: false,
        target: rangeTarget,
      })
    ).toBe(true);
  });
});

describe("Lyric Creator live search", () => {
  afterEach(() => vi.useRealTimers());

  it("debounces input, aborts the old request, and ignores stale completions", async () => {
    vi.useFakeTimers();
    const resolvers = new Map<string, (tracks: CreatorTrack[]) => void>();
    const signals = new Map<string, AbortSignal>();
    const search = vi.fn(
      (query: string, signal?: AbortSignal) =>
        new Promise<CreatorTrack[]>((resolve) => {
          resolvers.set(query, resolve);
          if (signal) signals.set(query, signal);
        })
    );
    const controller = new CreatorSearchController(search, { debounceMs: 200 });
    const onResults = vi.fn();
    const callbacks = { onPending: vi.fn(), onResults, onError: vi.fn() };

    controller.update("first", callbacks);
    await vi.advanceTimersByTimeAsync(200);
    expect(search).toHaveBeenCalledWith("first", expect.any(AbortSignal));

    controller.update("second", callbacks);
    expect(signals.get("first")?.aborted).toBe(true);
    expect(callbacks.onPending).toHaveBeenCalledWith(false);
    await vi.advanceTimersByTimeAsync(200);
    resolvers.get("second")?.([track("second")]);
    await Promise.resolve();
    resolvers.get("first")?.([track("first")]);
    await Promise.resolve();

    expect(onResults).toHaveBeenCalledTimes(1);
    expect(onResults).toHaveBeenCalledWith([expect.objectContaining({ name: "second" })]);
  });

  it("supports wraparound keyboard navigation, selection, and dismissal", () => {
    expect(creatorSearchKeyResult("ArrowDown", -1, 3).activeIndex).toBe(0);
    expect(creatorSearchKeyResult("ArrowUp", 0, 3).activeIndex).toBe(2);
    expect(creatorSearchKeyResult("Enter", 2, 3).selectIndex).toBe(2);
    expect(creatorSearchKeyResult("Escape", 2, 3).dismiss).toBe(true);
  });
});

describe("Lyric Creator files and preview bounds", () => {
  it("uses extension-only Windows filters so TTML files remain selectable", () => {
    expect(CREATOR_TTML_FILE_ACCEPT).toBe(".ttml,.xml");
    expect(CREATOR_TTML_FILE_ACCEPT).not.toContain("application/");
  });

  it("reopens a file picker even when the user selects the same file", () => {
    const input = { value: "C:/lyrics/song.ttml", click: vi.fn() };
    expect(openCreatorFilePicker(input as unknown as HTMLInputElement)).toBe(true);
    expect(input.value).toBe("");
    expect(input.click).toHaveBeenCalledOnce();
    expect(openCreatorFilePicker(null)).toBe(false);
  });

  it("reads selected TTML files through the browser File API", async () => {
    const text = vi.fn().mockResolvedValue("<tt />");
    await expect(readCreatorTextFile({ text } as unknown as File)).resolves.toBe("<tt />");
    expect(text).toHaveBeenCalledOnce();
  });

  it("uses an attached anchor and defers object URL revocation", () => {
    const anchor = {
      href: "",
      download: "",
      hidden: false,
      click: vi.fn(),
      remove: vi.fn(),
    };
    const appendChild = vi.fn();
    const revokeObjectURL = vi.fn();
    let deferred: (() => void) | null = null;
    const filename = downloadCreatorTTML("<tt />", "A/B?", {
      document: {
        body: { appendChild } as unknown as HTMLElement,
        createElement: vi.fn(() => anchor) as unknown as Document["createElement"],
      },
      createObjectURL: vi.fn(() => "blob:creator"),
      revokeObjectURL,
      defer: (callback) => {
        deferred = callback;
      },
    });

    expect(filename).toBe("A_B_.ttml");
    expect(appendChild).toHaveBeenCalledWith(anchor);
    expect(anchor.click).toHaveBeenCalledOnce();
    expect(anchor.remove).toHaveBeenCalledOnce();
    expect(revokeObjectURL).not.toHaveBeenCalled();
    deferred?.();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:creator");
    expect(sanitizeCreatorFilename("<>")).toBe("__");
  });

  it("hard-bounds the borrowed renderer and restores its original state", () => {
    const createStyle = (initial: string) => ({
      cssText: initial,
      setProperty(name: string, value: string, priority?: string) {
        this.cssText += `${name}:${value}${priority ? `!${priority}` : ""};`;
      },
    });
    const pageAttributes = new Map<string, string>();
    const page = {
      style: createStyle("color:red;"),
      getAttribute: (name: string) => pageAttributes.get(name) ?? null,
      setAttribute: (name: string, value: string) => pageAttributes.set(name, value),
      removeAttribute: (name: string) => pageAttributes.delete(name),
    } as unknown as HTMLElement;
    const host = { style: createStyle("display:block;") } as unknown as HTMLElement;

    const release = constrainCreatorPreviewPage(page, host);
    expect(page.style.cssText).toContain("pointer-events:none!important");
    expect(page.getAttribute("aria-hidden")).toBe("true");
    expect(host.style.cssText).toContain("contain:strict!important");

    release();
    expect(page.style.cssText).toBe("color:red;");
    expect(host.style.cssText).toBe("display:block;");
    expect(page.getAttribute("aria-hidden")).toBeNull();
  });
});
