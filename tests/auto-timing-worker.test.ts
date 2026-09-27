import { afterEach, describe, expect, it, vi } from "vitest";
import {
  autoTimingWordResults,
  autoTimingAudioWindows,
  autoTimingWindowWords,
  hasAutoTimingSignal,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/transcript.ts";
import { transcribeAutoTimingAudio } from "../src/components/ReactComponents/LyricCreator/autoTiming/workerClient.ts";
import { createAutoTimingProgressTracker } from "../src/components/ReactComponents/LyricCreator/autoTiming/progress.ts";
import type {
  AutoTimingManifest,
  AutoTimingModuleDefinition,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/types.ts";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("Auto-time transcript boundaries", () => {
  it("rejects unknown starts without treating null as the start of the song", () => {
    expect(
      autoTimingWordResults(
        {
          chunks: [
            { text: "unknown", timestamp: [null, 2] },
            { text: "bad", timestamp: [NaN, 2] },
            { text: "negative", timestamp: [-1, 2] },
            { text: "valid", timestamp: [1, null] },
          ],
        },
        5000
      )
    ).toEqual([{ text: "valid", startTimeMs: 1000, endTimeMs: 1250, estimatedTiming: true }]);
  });

  it("bounds final-word estimates to the audio and discards words beyond it", () => {
    expect(
      autoTimingWordResults(
        {
          chunks: [
            { text: "last", timestamp: [4.9, null] },
            { text: "outside", timestamp: [5, 5.5] },
          ],
        },
        5000
      )
    ).toEqual([{ text: "last", startTimeMs: 4900, endTimeMs: 5000, estimatedTiming: true }]);
  });

  it("does not fabricate timings for collapsed/reversed hallucinated word sequences", () => {
    expect(autoTimingWordResults({ chunks: [
      { text: "real", timestamp: [1, 1.5] },
      { text: "loop", timestamp: [2, 2] },
      { text: "again", timestamp: [2, 2] },
      { text: "backwards", timestamp: [3, 2.5] },
    ] }, 5000)).toEqual([{ text: "real", startTimeMs: 1000, endTimeMs: 1500 }]);
  });

  it("rejects digital silence but keeps quiet real signal", () => {
    expect(hasAutoTimingSignal(new Float32Array(16_000))).toBe(false);
    expect(hasAutoTimingSignal(Float32Array.from([NaN, Infinity, 0]))).toBe(false);
    expect(hasAutoTimingSignal(Float32Array.from([0, 0.0001, 0]))).toBe(true);
  });

  it("preserves repeated words across long silent gaps between processing windows", () => {
    const windows = autoTimingAudioWindows(40 * 16_000);
    expect(windows.map((window) => [window.keepStartMs, window.keepEndMs])).toEqual([[0, 24000], [24000, 40000]]);
    const first = autoTimingWindowWords({ chunks: [{ text: "again", timestamp: [1, 2] }] }, windows[0]);
    const repeated = autoTimingWindowWords({ chunks: [{ text: "again", timestamp: [12, 13] }] }, windows[1]);
    expect([...first, ...repeated]).toEqual([
      { text: "again", startTimeMs: 1000, endTimeMs: 2000 },
      { text: "again", startTimeMs: 31000, endTimeMs: 32000 },
    ]);
  });

  it("owns each overlap once, including a word centered exactly at a window boundary", () => {
    const windows = autoTimingAudioWindows(40 * 16_000);
    const first = autoTimingWindowWords({ chunks: [{ text: "boundary", timestamp: [23.8, 24.2] }] }, windows[0]);
    const second = autoTimingWindowWords({ chunks: [{ text: "boundary", timestamp: [4.8, 5.2] }] }, windows[1]);
    expect(first).toEqual([]);
    expect(second).toEqual([{ text: "boundary", startTimeMs: 23800, endTimeMs: 24200 }]);
    expect(autoTimingAudioWindows(0)).toEqual([]);
  });
});

describe("Auto-time worker lifecycle", () => {
  const manifest = {
    remoteHost: "https://jackscurrie.com/",
    remotePathTemplate: "models/{model}/",
    runtime: { files: [] },
  } as unknown as AutoTimingManifest;
  const definition: AutoTimingModuleDefinition = {
    id: "fast",
    modelId: "icy/fast",
    version: "test",
    revision: "main",
    dtype: "q4",
    displayName: "Fast",
    description: "Test model",
    size: 1,
    files: [],
  };

  function mockWorkers(mode: "fallback" | "throw" | "wait") {
    const instances: MockWorker[] = [];
    class MockWorker extends EventTarget {
      terminated = false;
      payload: any;
      constructor() {
        super();
        instances.push(this);
      }
      terminate() {
        this.terminated = true;
      }
      emitMessage(data: Record<string, unknown>) {
        this.dispatchEvent(new MessageEvent("message", {
          data: { requestId: this.payload.requestId, ...data },
        }));
      }
      postMessage(payload: any, transfer: Transferable[]) {
        if (mode === "throw") throw new DOMException("Cannot clone", "DataCloneError");
        this.payload = structuredClone(payload, { transfer });
        if (mode === "wait") return;
        queueMicrotask(() =>
          this.dispatchEvent(
            new MessageEvent("message", {
              data:
                this.payload.devicePreference === "webgpu"
                  ? { type: "error", requestId: payload.requestId, message: "GPU unavailable" }
                  : { type: "result", requestId: payload.requestId, words: [], device: "wasm" },
            })
          )
        );
      }
    }
    vi.stubGlobal("Worker", MockWorker);
    vi.stubGlobal("navigator", { gpu: {} });
    const create = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:worker-test");
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    return { instances, create, revoke };
  }

  it("retries GPU failure with a fresh WASM worker and intact audio", async () => {
    const { instances, revoke } = mockWorkers("fallback");
    const pcm = Float32Array.from([0.1, 0.2, 0.3]);
    const result = await transcribeAutoTimingAudio({ pcm, manifest, definition });
    expect(result.device).toBe("wasm");
    expect(instances.map((worker) => worker.payload.devicePreference)).toEqual(["webgpu", "wasm"]);
    expect(Array.from(instances[1].payload.pcm)).toEqual(Array.from(pcm));
    expect(pcm.byteLength).toBe(12);
    expect(instances.every((worker) => worker.terminated)).toBe(true);
    expect(revoke).toHaveBeenCalledTimes(2);
  });

  it("cleans up a worker if posting its input throws", async () => {
    const { instances, revoke } = mockWorkers("throw");
    await expect(
      transcribeAutoTimingAudio({
        pcm: new Float32Array(4),
        manifest,
        definition,
        devicePreference: "wasm",
      })
    ).rejects.toMatchObject({ name: "DataCloneError" });
    expect(instances[0].terminated).toBe(true);
    expect(revoke).toHaveBeenCalledOnce();
  });

  it.each([
    [NaN, 0],
    [Infinity, 0],
    [-Infinity, 0],
    [-0.5, 0],
    [1.5, 1],
    [0.42, 0.42],
    [undefined, 0],
  ])("sanitizes worker progress %s to %s", async (progress, expected) => {
    const { instances } = mockWorkers("wait");
    const onProgress = vi.fn();
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4), manifest, definition, devicePreference: "wasm", onProgress,
    });
    instances[0].emitMessage({ type: "progress", phase: "transcribing", progress, message: "Working" });
    expect(onProgress).toHaveBeenCalledExactlyOnceWith({
      phase: "transcribing", progress: expected, message: "Working",
    });
    instances[0].emitMessage({ type: "result", words: [], device: "wasm" });
    await result;
  });

  it("ignores messages from another request and queued messages after success", async () => {
    const { instances, revoke } = mockWorkers("wait");
    const onProgress = vi.fn();
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4), manifest, definition, devicePreference: "wasm", onProgress,
    });
    const worker = instances[0];
    worker.emitMessage({ type: "progress", requestId: worker.payload.requestId + 1, progress: 0.5 });
    worker.emitMessage({ type: "result", words: [], device: "wasm" });
    worker.emitMessage({ type: "progress", phase: "loading-model", progress: 0 });
    worker.emitMessage({ type: "error", message: "Late error" });
    await expect(result).resolves.toEqual({ words: [], device: "wasm" });
    expect(onProgress).not.toHaveBeenCalled();
    expect(revoke).toHaveBeenCalledOnce();
  });

  it("ignores queued progress and results after cancellation without GPU fallback", async () => {
    const { instances, revoke } = mockWorkers("wait");
    const controller = new AbortController();
    const onProgress = vi.fn();
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4), manifest, definition, signal: controller.signal, onProgress,
    });
    const rejected = expect(result).rejects.toMatchObject({ name: "AbortError" });
    controller.abort();
    instances[0].emitMessage({ type: "progress", phase: "transcribing", progress: 0.8 });
    instances[0].emitMessage({ type: "error", message: "Late GPU error" });
    instances[0].emitMessage({ type: "result", words: [], device: "webgpu" });
    await rejected;
    expect(onProgress).not.toHaveBeenCalled();
    expect(instances).toHaveLength(1);
    expect(revoke).toHaveBeenCalledOnce();
  });

  it("passes exact model paths and signed sizes as a stable loading denominator", async () => {
    const { instances } = mockWorkers("wait");
    const files = [
      { path: "config.json", size: 20, url: "https://jackscurrie.com/config.json", sha256: "0".repeat(64) },
      { path: "onnx/encoder_model_q4.onnx", size: 980, url: "https://jackscurrie.com/encoder.onnx", sha256: "1".repeat(64) },
    ];
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4), manifest, definition: { ...definition, files }, devicePreference: "wasm",
    });
    expect(instances[0].payload.modelFiles).toEqual([
      { path: "config.json", size: 20 },
      { path: "onnx/encoder_model_q4.onnx", size: 980 },
    ]);
    instances[0].emitMessage({ type: "result", words: [], device: "wasm" });
    await result;
  });

  it("reports GPU fallback while preserving completed progress until WASM catches up", async () => {
    const { instances } = mockWorkers("wait");
    const trackProgress = createAutoTimingProgressTracker(false);
    const updates: Array<{ progress: number; message: string }> = [];
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4), manifest, definition,
      onProgress: (update) => updates.push(trackProgress(update)),
    });
    const gpu = instances[0];
    gpu.emitMessage({ type: "progress", phase: "transcribing", progress: 0.5, message: "GPU processing" });
    gpu.emitMessage({ type: "error", message: "GPU unavailable" });
    await Promise.resolve();
    expect(instances).toHaveLength(2);
    const wasm = instances[1];
    expect(wasm.payload.devicePreference).toBe("wasm");
    gpu.emitMessage({ type: "progress", phase: "review", progress: 1, message: "Stale GPU progress" });
    wasm.emitMessage({ type: "progress", phase: "loading-model", progress: 0.5, message: "WASM loading" });
    wasm.emitMessage({ type: "progress", phase: "transcribing", progress: 0.25, message: "WASM processing" });
    wasm.emitMessage({ type: "progress", phase: "transcribing", progress: 0.75, message: "WASM caught up" });
    wasm.emitMessage({ type: "result", words: [], device: "wasm" });
    await expect(result).resolves.toEqual({ words: [], device: "wasm" });
    expect(updates).toHaveLength(5);
    expect(updates[1].message).toContain("Retrying locally with WASM");
    expect(updates.slice(1, 4).every((update) => update.progress === updates[0].progress)).toBe(true);
    expect(updates[4].progress).toBeGreaterThan(updates[0].progress);
    expect(updates.every((update) => Number.isFinite(update.progress) && update.progress < 1)).toBe(true);
  });

  it("aborts without fallback and creates nothing for an already cancelled job", async () => {
    const { instances, create, revoke } = mockWorkers("wait");
    const controller = new AbortController();
    const result = transcribeAutoTimingAudio({
      pcm: new Float32Array(4),
      manifest,
      definition,
      signal: controller.signal,
    });
    controller.abort();
    await expect(result).rejects.toMatchObject({ name: "AbortError" });
    expect(instances).toHaveLength(1);
    expect(instances[0].terminated).toBe(true);
    expect(revoke).toHaveBeenCalledOnce();
    await expect(
      transcribeAutoTimingAudio({
        pcm: new Float32Array(4),
        manifest,
        definition,
        signal: controller.signal,
      })
    ).rejects.toMatchObject({ name: "AbortError" });
    expect(create).toHaveBeenCalledOnce();
  });
});
