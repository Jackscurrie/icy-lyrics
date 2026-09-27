import Kawarp, { type KawarpOptions } from "@kawarp/core";

export type KawarpSource = { kind: "url"; value: string } | { kind: "blob"; value: Blob };
export interface DecodedBackgroundImage {
  source: TexImageSource;
  release: () => void;
}
type Renderer = Pick<Kawarp, "loadImageElement" | "start" | "stop" | "dispose" | "resize" | "setOptions" | "getOptions">;
interface Dependencies {
  createRenderer: (canvas: HTMLCanvasElement, options: KawarpOptions) => Renderer;
  decode: (source: KawarpSource, signal: AbortSignal) => Promise<DecodedBackgroundImage>;
}

const aborted = () => new DOMException("Background image load cancelled", "AbortError");

/** Decode outside Kawarp: its async loaders otherwise upload even after dispose. */
export async function decodeBackgroundImage(source: KawarpSource, signal: AbortSignal): Promise<DecodedBackgroundImage> {
  if (signal.aborted) throw aborted();
  if (source.kind === "blob") {
    const bitmap = await createImageBitmap(source.value);
    if (signal.aborted) {
      bitmap.close();
      throw aborted();
    }
    return { source: bitmap, release: () => bitmap.close() };
  }
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = "anonymous";
    const cleanup = () => {
      image.onload = null;
      image.onerror = null;
      signal.removeEventListener("abort", cancel);
    };
    const cancel = () => {
      cleanup();
      image.src = "";
      reject(aborted());
    };
    image.onload = () => {
      cleanup();
      resolve({ source: image, release: () => { image.src = ""; } });
    };
    image.onerror = () => {
      cleanup();
      image.src = "";
      reject(new Error("Failed to decode background artwork"));
    };
    signal.addEventListener("abort", cancel, { once: true });
    image.src = source.value;
  });
}

/** Keeps the original shaders/frame loop while owning every async image/timer. */
export class ManagedKawarp {
  private readonly renderer: Renderer;
  private readonly decode: Dependencies["decode"];
  private disposed = false;
  private generation = 0;
  private pending: AbortController | null = null;
  private loaded = false;
  private readonly timers = new Map<ReturnType<typeof setTimeout>, () => void>();

  constructor(readonly canvas: HTMLCanvasElement, options: KawarpOptions, dependencies: Dependencies = {
    createRenderer: (canvas, options) => new Kawarp(canvas, options),
    decode: decodeBackgroundImage,
  }) {
    this.renderer = dependencies.createRenderer(canvas, options);
    this.decode = dependencies.decode;
  }

  get isDisposed() { return this.disposed; }
  get hasImage() { return this.loaded; }

  async load(source: KawarpSource, isCurrent: () => boolean = () => true): Promise<boolean> {
    if (this.disposed) return false;
    this.pending?.abort();
    const controller = new AbortController();
    this.pending = controller;
    const generation = ++this.generation;
    let decoded: DecodedBackgroundImage | null = null;
    try {
      decoded = await this.decode(source, controller.signal);
      if (this.disposed || controller.signal.aborted || generation !== this.generation || !isCurrent()) return false;
      // This synchronous upload cannot race with a later dispose on the JS thread.
      this.renderer.loadImageElement(decoded.source);
      this.loaded = true;
      return true;
    } catch (error) {
      if (this.disposed || controller.signal.aborted || generation !== this.generation || !isCurrent()) return false;
      throw error;
    } finally {
      decoded?.release();
      if (this.pending === controller) this.pending = null;
    }
  }

  start() { if (!this.disposed && this.loaded) this.renderer.start(); }
  stop() { if (!this.disposed) this.renderer.stop(); }
  resize() { if (!this.disposed) this.renderer.resize(); }
  setOptions(options: Partial<KawarpOptions>) { if (!this.disposed) this.renderer.setOptions(options); }
  getOptions() { return this.renderer.getOptions(); }

  setOptionsAfter(options: Partial<KawarpOptions>, delay: number): Promise<void> {
    if (this.disposed) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.timers.delete(timer);
        this.setOptions(options);
        resolve();
      }, delay);
      this.timers.set(timer, resolve);
    });
  }

  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.generation += 1;
    this.pending?.abort();
    this.pending = null;
    for (const [timer, resolve] of this.timers) {
      clearTimeout(timer);
      resolve();
    }
    this.timers.clear();
    // Upstream stop makes even its untracked initial RAF return before GL work.
    // No completion can start that loop again after this point.
    this.renderer.dispose();
  }
}

/** Map deletion also cancels requests that have not created a renderer yet. */
export class KawarpRegistry extends Map<HTMLElement | string, ManagedKawarp> {
  private readonly tagRequests = new Map<string, symbol>();
  private elementRequests = new WeakMap<HTMLElement, symbol>();

  begin(key: HTMLElement | string): () => boolean {
    const owner = Symbol("BackgroundRequest");
    if (typeof key === "string") this.tagRequests.set(key, owner);
    else this.elementRequests.set(key, owner);
    return () => (typeof key === "string" ? this.tagRequests.get(key) : this.elementRequests.get(key)) === owner;
  }

  override set(key: HTMLElement | string, instance: ManagedKawarp): this {
    const previous = super.get(key);
    if (previous && previous !== instance) {
      previous.dispose();
      previous.canvas.remove?.();
    }
    return super.set(key, instance);
  }

  override delete(key: HTMLElement | string): boolean {
    if (typeof key === "string") this.tagRequests.delete(key);
    else this.elementRequests.delete(key);
    super.get(key)?.dispose();
    return super.delete(key);
  }

  override clear(): void {
    this.tagRequests.clear();
    this.elementRequests = new WeakMap();
    for (const instance of this.values()) instance.dispose();
    super.clear();
  }
}
