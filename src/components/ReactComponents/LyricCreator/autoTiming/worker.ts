/// <reference lib="webworker" />

import { env, pipeline } from "@huggingface/transformers";
import { AUTO_TIMING_CACHE_NAME } from "./modelRepository.ts";
import type { AutoTimingModelId, AutoTimingWord } from "./types.ts";
import { autoTimingAudioWindows, autoTimingWindowWords, hasAutoTimingSignal } from "./transcript.ts";
import { autoTimingWasmResponse } from "./runtimeResponse.ts";
import { createModelLoadingProgress } from "./progress.ts";

interface WorkerRequest {
  type: "transcribe";
  requestId: number;
  modelId: AutoTimingModelId;
  modelName: string;
  modelVersion: string;
  revision: string;
  dtype: "q4";
  remoteHost: string;
  remotePathTemplate: string;
  runtimeFiles: Array<{ path: string; url: string }>;
  modelFiles: Array<{ path: string; size: number }>;
  language?: string;
  devicePreference?: "auto" | "webgpu" | "wasm";
  pcm: Float32Array;
}

type WorkerResponse =
  | { type: "progress"; requestId: number; phase: string; progress: number; message: string }
  | { type: "result"; requestId: number; words: AutoTimingWord[]; device: "webgpu" | "wasm" }
  | { type: "error"; requestId: number; message: string };

const scope = self as unknown as DedicatedWorkerGlobalScope;

function post(message: WorkerResponse): void {
  scope.postMessage(message);
}

async function createTranscriber(request: WorkerRequest, device: "webgpu" | "wasm") {
  // Transformers calls Icy's signed module layout a "remote" model even when
  // every byte comes from CacheStorage. Keep local filesystem paths disabled,
  // and replace fetch inside this worker with a cache-only gate so a missing
  // asset can never silently fall back to any network host.
  env.allowLocalModels = false;
  env.allowRemoteModels = true;
  env.useBrowserCache = false;
  env.useFSCache = false;
  // Icy owns every installed byte. A second Transformers WASM cache would
  // survive Uninstall and consume storage without being shown in Settings.
  env.useWasmCache = false;
  env.useCustomCache = true;
  const modelCache = await caches.open(AUTO_TIMING_CACHE_NAME);
  const wasmUrls = new Set(request.runtimeFiles.filter((file) => file.path.endsWith(".wasm")).map((file) => file.url));
  env.customCache = modelCache;
  scope.fetch = (async (input: RequestInfo | URL) => {
    const cacheKey = input instanceof URL ? input.toString() : input;
    const cached = await modelCache.match(cacheKey);
    const url = input instanceof Request ? input.url : String(input);
    if (cached) return wasmUrls.has(url) ? autoTimingWasmResponse(cached) : cached;
    throw new Error(`Verified Auto-time asset is missing from local storage: ${url}`);
  }) as typeof fetch;
  env.remoteHost = request.remoteHost;
  // Transformers' pipeline discovery probes omit the revision argument even
  // when pipeline() receives one. Resolve the signed revision before those
  // probes so discovery and inference read exactly the same immutable assets.
  env.remotePathTemplate = request.remotePathTemplate.replaceAll(
    "{revision}", encodeURIComponent(request.revision)
  );
  const runtimeFile = request.runtimeFiles.find((file) =>
    device === "webgpu"
      ? file.path.endsWith(".asyncify.wasm")
      : file.path.endsWith(".wasm") && !file.path.endsWith(".asyncify.wasm")
  );
  const runtimeScript = request.runtimeFiles.find((file) =>
    device === "webgpu"
      ? file.path.endsWith(".asyncify.mjs")
      : file.path.endsWith(".mjs") && !file.path.endsWith(".asyncify.mjs")
  );
  if (!runtimeFile || !runtimeScript) {
    throw new Error(`The installed ${device.toUpperCase()} runtime is incomplete.`);
  }
  const runtimeResponse = await modelCache.match(runtimeFile.url);
  const runtimeScriptResponse = await modelCache.match(runtimeScript.url);
  if (!runtimeResponse || !runtimeScriptResponse) {
    throw new Error(`The installed ${device.toUpperCase()} runtime could not be opened.`);
  }
  const runtimeScriptUrl = URL.createObjectURL(
    new Blob([await runtimeScriptResponse.arrayBuffer()], { type: "text/javascript" })
  );
  env.backends.onnx.wasm.wasmBinary = undefined;
  env.backends.onnx.wasm.wasmPaths = {
    mjs: runtimeScriptUrl,
    wasm: runtimeFile.url,
  };
  env.backends.onnx.wasm.initTimeout = 30_000;
  env.backends.onnx.wasm.numThreads = scope.crossOriginIsolated
    ? Math.max(1, Math.min(4, scope.navigator.hardwareConcurrency || 1))
    : 1;

  const trackLoading = createModelLoadingProgress(request.modelFiles);
  let lastPercent = -1;
  try {
    return await pipeline("automatic-speech-recognition", request.modelName, {
      revision: request.revision,
      dtype: request.dtype,
      device,
      progress_callback: (event: any) => {
        const fraction = trackLoading(event);
        if (fraction === null || Math.floor(fraction * 100) === lastPercent) return;
        lastPercent = Math.floor(fraction * 100);
        post({
          type: "progress",
          requestId: request.requestId,
          phase: "loading-model",
          progress: fraction,
          message: fraction >= 0.95 ? "Initializing timing model…" : "Loading timing model from this device…",
        });
      },
    });
  } finally {
    URL.revokeObjectURL(runtimeScriptUrl);
  }
}

async function transcribe(request: WorkerRequest): Promise<void> {
  const device: "webgpu" | "wasm" = request.devicePreference === "webgpu" ? "webgpu" : "wasm";
  if (!hasAutoTimingSignal(request.pcm)) {
    post({ type: "result", requestId: request.requestId, words: [], device });
    return;
  }
  post({
    type: "progress", requestId: request.requestId, phase: "loading-model", progress: 0,
    message: `Loading timing model for ${device === "webgpu" ? "WebGPU" : "WASM"}…`,
  });
  const transcriber = await createTranscriber(request, device);
  post({
    type: "progress",
    requestId: request.requestId,
    phase: "transcribing",
    progress: 0,
    message: `Transcribing locally with ${device === "webgpu" ? "WebGPU" : "WASM"}…`,
  });
  try {
    const words: AutoTimingWord[] = [];
    const windows = autoTimingAudioWindows(request.pcm.length);
    for (const [index, window] of windows.entries()) {
      post({
        type: "progress", requestId: request.requestId, phase: "transcribing",
        progress: index / windows.length,
        message: `Transcribing audio section ${index + 1} of ${windows.length} locally…`,
      });
      const pcm = request.pcm.subarray(window.startSample, window.endSample);
      if (hasAutoTimingSignal(pcm)) {
        const output = await transcriber(pcm, {
          return_timestamps: "word",
          chunk_length_s: 0,
          force_full_sequences: false,
          task: "transcribe",
          ...(request.language ? { language: request.language } : {}),
        });
        words.push(...autoTimingWindowWords(output, window));
      }
      post({
        type: "progress", requestId: request.requestId, phase: "transcribing",
        progress: (index + 1) / windows.length,
        message: `Transcribed ${index + 1} of ${windows.length} audio sections locally…`,
      });
    }
    post({
      type: "result",
      requestId: request.requestId,
      words,
      device,
    });
  } finally {
    await transcriber.dispose?.();
  }
}

scope.addEventListener("message", (event: MessageEvent<WorkerRequest>) => {
  if (event.data?.type !== "transcribe") return;
  void transcribe(event.data).catch((error) => {
    post({
      type: "error",
      requestId: event.data.requestId,
      message: error instanceof Error ? error.message : "On-device transcription failed.",
    });
  });
});
