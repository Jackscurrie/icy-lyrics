import { describe, expect, it, vi } from "vitest";
import {
  acquireCreatorNativeDialog,
  installCreatorPointerIsolation,
  isCreatorNativeDialogActive,
  listenForCreatorNativeDialog,
  requestCreatorDocumentFullscreen,
  suppressSpotifyShellForCreator,
  type CreatorFullscreenDocument,
} from "../src/components/ReactComponents/LyricCreator/creatorChrome.ts";

function fakeShell() {
  const attributes = new Map<string, string>();
  const styles = new Map<string, { value: string; priority: string }>();
  styles.set("display", { value: "grid", priority: "" });
  const shell = {
    inert: false,
    style: {
      getPropertyValue: (name: string) => styles.get(name)?.value ?? "",
      getPropertyPriority: (name: string) => styles.get(name)?.priority ?? "",
      setProperty: (name: string, value: string, priority = "") =>
        styles.set(name, { value, priority }),
      removeProperty: (name: string) => styles.delete(name),
    },
    getAttribute: (name: string) => attributes.get(name) ?? null,
    setAttribute: (name: string, value: string) => attributes.set(name, value),
    removeAttribute: (name: string) => attributes.delete(name),
    hasAttribute: (name: string) => attributes.has(name),
  };
  return { shell, attributes, styles };
}

describe("Lyric Creator Spotify chrome ownership", () => {
  it("suppresses Spotify's shell and restores its exact prior state", () => {
    const { shell, attributes, styles } = fakeShell();
    attributes.set("aria-hidden", "false");
    const release = suppressSpotifyShellForCreator(shell as unknown as HTMLElement);

    expect(styles.get("display")).toEqual({ value: "none", priority: "important" });
    expect(shell.inert).toBe(true);
    expect(attributes.get("aria-hidden")).toBe("true");
    expect(attributes.get("data-icy-creator-suppressed")).toBe("true");

    release();
    release();
    expect(styles.get("display")).toEqual({ value: "grid", priority: "" });
    expect(shell.inert).toBe(false);
    expect(attributes.get("aria-hidden")).toBe("false");
    expect(attributes.has("inert")).toBe(false);
    expect(attributes.has("data-icy-creator-suppressed")).toBe(false);
  });

  it("requests true document fullscreen with native navigation hidden", async () => {
    const ownerDocument = { fullscreenElement: null } as unknown as CreatorFullscreenDocument & {
      fullscreenElement: Element | null;
    };
    const documentElement = {
      requestFullscreen: vi.fn(async () => {
        ownerDocument.fullscreenElement = documentElement as unknown as Element;
      }),
    } as unknown as HTMLElement;
    Object.assign(ownerDocument, { documentElement });

    await expect(requestCreatorDocumentFullscreen(ownerDocument)).resolves.toBe(true);
    expect(documentElement.requestFullscreen).toHaveBeenCalledWith({ navigationUI: "hide" });
  });

  it("keeps fullscreen failures recoverable for the next pointer gesture", async () => {
    const documentElement = {
      requestFullscreen: vi.fn().mockRejectedValue(new Error("activation required")),
    } as unknown as HTMLElement;
    const ownerDocument = {
      documentElement,
      fullscreenElement: null,
    } as unknown as CreatorFullscreenDocument;

    await expect(requestCreatorDocumentFullscreen(ownerDocument)).resolves.toBe(false);
  });

  it("stops Creator pointer events from reaching Spotify without cancelling them", () => {
    const listeners = new Map<string, EventListener>();
    const host = {
      addEventListener: vi.fn((type: string, listener: EventListener) =>
        listeners.set(type, listener)
      ),
      removeEventListener: vi.fn((type: string) => listeners.delete(type)),
    } as unknown as HTMLElement;
    const release = installCreatorPointerIsolation(host);
    const event = { stopPropagation: vi.fn() } as unknown as Event;

    listeners.get("click")?.(event);
    expect(event.stopPropagation).toHaveBeenCalledOnce();
    expect(listeners.has("pointerdown")).toBe(true);

    release();
    expect(listeners.size).toBe(0);
  });

  it("keeps a native file-dialog lease through fullscreen loss and releases it on focus return", () => {
    let focusListener: EventListener | null = null;
    let deferred: (() => void) | null = null;
    const states: boolean[] = [];
    const unlisten = listenForCreatorNativeDialog((active) => states.push(active));
    const release = acquireCreatorNativeDialog({
      addFocusListener: (listener) => {
        focusListener = listener;
      },
      removeFocusListener: (listener) => {
        if (focusListener === listener) focusListener = null;
      },
      defer: (callback) => {
        deferred = callback;
      },
    });

    expect(isCreatorNativeDialogActive()).toBe(true);
    expect(states).toEqual([false, true]);
    focusListener?.({} as Event);
    expect(isCreatorNativeDialogActive()).toBe(true);
    deferred?.();
    expect(isCreatorNativeDialogActive()).toBe(false);
    expect(states).toEqual([false, true, false]);

    release();
    unlisten();
  });
});
