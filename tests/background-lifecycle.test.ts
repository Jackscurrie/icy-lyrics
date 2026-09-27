import { afterEach, describe, expect, it, vi } from "vitest";
import { decodeBackgroundImage, KawarpRegistry, ManagedKawarp, type DecodedBackgroundImage } from "../src/components/DynamicBG/ManagedKawarp.ts";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}

function setup() {
  const renderer = {
    loadImageElement: vi.fn(), start: vi.fn(), stop: vi.fn(), resize: vi.fn(),
    dispose: vi.fn(), setOptions: vi.fn(), getOptions: vi.fn(() => ({ blurPasses: 8 } as any)),
  };
  const loads: Array<ReturnType<typeof deferred<DecodedBackgroundImage>> & { signal: AbortSignal }> = [];
  const managed = new ManagedKawarp({} as HTMLCanvasElement, { blurPasses: 8 }, {
    createRenderer: () => renderer,
    decode: (_source, signal) => {
      const pending = deferred<DecodedBackgroundImage>();
      loads.push({ ...pending, signal });
      return pending.promise;
    },
  });
  const image = () => ({ source: {} as HTMLImageElement, release: vi.fn() });
  return { renderer, managed, loads, image };
}

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("background renderer lifecycle", () => {
  it("does not upload or restart when an image finishes after disposal", async () => {
    const { managed, renderer, loads, image } = setup();
    const load = managed.load({ kind: "url", value: "cover" });
    managed.dispose();
    const decoded = image();
    loads[0].resolve(decoded);
    expect(await load).toBe(false);
    managed.start();
    managed.resize();
    managed.setOptions({ blurPasses: 0 });
    managed.dispose();
    expect(loads[0].signal.aborted).toBe(true);
    expect(decoded.release).toHaveBeenCalledOnce();
    expect(renderer.loadImageElement).not.toHaveBeenCalled();
    expect(renderer.start).not.toHaveBeenCalled();
    expect(renderer.resize).not.toHaveBeenCalled();
    expect(renderer.setOptions).not.toHaveBeenCalled();
    expect(renderer.dispose).toHaveBeenCalledOnce();
    expect(await managed.load({ kind: "url", value: "later" })).toBe(false);
    expect(loads).toHaveLength(1);
  });

  it("only uploads the latest cover when older decoding completes out of order", async () => {
    const { managed, renderer, loads, image } = setup();
    managed.start();
    expect(renderer.start).not.toHaveBeenCalled();
    const oldLoad = managed.load({ kind: "url", value: "old" });
    const newLoad = managed.load({ kind: "url", value: "new" });
    const oldImage = image(), newImage = image();
    loads[1].resolve(newImage);
    expect(await newLoad).toBe(true);
    managed.start();
    loads[0].resolve(oldImage);
    expect(await oldLoad).toBe(false);
    expect(renderer.loadImageElement).toHaveBeenCalledExactlyOnceWith(newImage.source);
    expect(renderer.start).toHaveBeenCalledOnce();
    expect(oldImage.release).toHaveBeenCalledOnce();
    expect(newImage.release).toHaveBeenCalledOnce();
    expect(managed.hasImage).toBe(true);
  });

  it("drops results from a replaced page even if its renderer has not yet been disposed", async () => {
    const { managed, renderer, loads, image } = setup();
    let activePage = true;
    const pending = managed.load({ kind: "url", value: "cover" }, () => activePage);
    activePage = false;
    const decoded = image();
    loads[0].resolve(decoded);
    expect(await pending).toBe(false);
    expect(renderer.loadImageElement).not.toHaveBeenCalled();
    expect(decoded.release).toHaveBeenCalledOnce();
  });

  it("cancels delayed options on dispose and settles awaiting transitions", async () => {
    vi.useFakeTimers();
    const { managed, renderer } = setup();
    const pending = managed.setOptionsAfter({ transitionDuration: 1000 }, 1000);
    managed.dispose();
    await pending;
    await managed.setOptionsAfter({ blurPasses: 0 }, 1000);
    await vi.runAllTimersAsync();
    expect(renderer.setOptions).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("preserves normal render options and reports only live decoding errors", async () => {
    vi.useFakeTimers();
    const { managed, renderer, loads } = setup();
    const options = managed.setOptionsAfter({ transitionDuration: 1000 }, 500);
    await vi.advanceTimersByTimeAsync(500);
    await options;
    expect(renderer.setOptions).toHaveBeenCalledExactlyOnceWith({ transitionDuration: 1000 });
    const failure = managed.load({ kind: "url", value: "missing" });
    const rejection = expect(failure).rejects.toThrow("missing cover");
    loads[0].reject(new Error("missing cover"));
    await rejection;
    const abandoned = managed.load({ kind: "url", value: "late" });
    managed.dispose();
    loads[1].reject(new Error("late network error"));
    expect(await abandoned).toBe(false);
  });

  it("invalidates pending owners on deletion and disposes replaced instances once", () => {
    const registry = new KawarpRegistry();
    const older = registry.begin("lpagebg");
    registry.delete("lpagebg");
    expect(older()).toBe(false);
    const current = registry.begin("lpagebg");
    const first = setup(), second = setup();
    registry.set("lpagebg", first.managed);
    registry.set("lpagebg", second.managed);
    expect(first.renderer.dispose).toHaveBeenCalledOnce();
    expect(current()).toBe(true);
    registry.delete("lpagebg");
    registry.delete("lpagebg");
    expect(current()).toBe(false);
    expect(second.renderer.dispose).toHaveBeenCalledOnce();
    const element = {} as HTMLElement;
    const elementOwner = registry.begin(element);
    registry.clear();
    expect(elementOwner()).toBe(false);
  });

  it("closes a decoded local bitmap if cancellation arrives while decoding", async () => {
    const bitmap = { close: vi.fn() } as unknown as ImageBitmap;
    const pending = deferred<ImageBitmap>();
    vi.stubGlobal("createImageBitmap", vi.fn(() => pending.promise));
    const controller = new AbortController();
    const decode = decodeBackgroundImage({ kind: "blob", value: new Blob() }, controller.signal);
    controller.abort();
    const rejection = expect(decode).rejects.toMatchObject({ name: "AbortError" });
    pending.resolve(bitmap);
    await rejection;
    expect(bitmap.close).toHaveBeenCalledOnce();
  });

  it("cancels pending remote image handlers without a late upload callback", async () => {
    const images: any[] = [];
    vi.stubGlobal("Image", class {
      src = "";
      crossOrigin = "";
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      constructor() { images.push(this); }
    });
    const controller = new AbortController();
    const pending = decodeBackgroundImage({ kind: "url", value: "https://images.test/art" }, controller.signal);
    expect(images[0].crossOrigin).toBe("anonymous");
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(images[0]).toMatchObject({ src: "", onload: null, onerror: null });
  });
});
