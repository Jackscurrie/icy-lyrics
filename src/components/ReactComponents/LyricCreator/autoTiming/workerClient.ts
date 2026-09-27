import workerSource from "../../../../generated/autoTimingWorkerSource.ts";
import { clampAutoTimingProgress } from "./progress.ts";
import type {
  AutoTimingManifest,
  AutoTimingModuleDefinition,
  AutoTimingProgress,
  AutoTimingWord,
} from "./types.ts";

interface TranscriptionInput {
  pcm: Float32Array;
  language?: string;
  manifest: AutoTimingManifest;
  definition: AutoTimingModuleDefinition;
  devicePreference?: "auto" | "webgpu" | "wasm";
  signal?: AbortSignal;
  onProgress?: (progress: AutoTimingProgress) => void;
}

interface WorkerMessage {
  type: "progress" | "result" | "error";
  requestId: number;
  phase?: AutoTimingProgress["phase"];
  progress?: number;
  message?: string;
  words?: AutoTimingWord[];
  device?: "webgpu" | "wasm";
}

let requestSequence = 0;

export interface AutoTimingTranscriptionResult {
  words: AutoTimingWord[];
  device: "webgpu" | "wasm";
}

function runTranscriptionWorker(
  input: TranscriptionInput,
  pcm: Float32Array,
  devicePreference: "webgpu" | "wasm"
): Promise<AutoTimingTranscriptionResult> {
  if (input.signal?.aborted) return Promise.reject(new DOMException("Aborted", "AbortError"));
  const requestId = ++requestSequence;
  const workerUrl = URL.createObjectURL(new Blob([workerSource], { type: "text/javascript" }));
  let worker: Worker;
  try {
    worker = new Worker(workerUrl);
  } catch (error) {
    URL.revokeObjectURL(workerUrl);
    return Promise.reject(error);
  }
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (callback: () => void) => {
      if (settled) return;
      settled = true;
      input.signal?.removeEventListener("abort", onAbort);
      worker.terminate();
      URL.revokeObjectURL(workerUrl);
      callback();
    };
    const onAbort = () => finish(() => reject(new DOMException("Aborted", "AbortError")));
    if (input.signal?.aborted) {
      onAbort();
      return;
    }
    input.signal?.addEventListener("abort", onAbort, { once: true });
    worker.addEventListener("error", (event) => {
      finish(() => reject(new Error(event.message || "The Auto-time worker crashed.")));
    });
    worker.addEventListener("message", (event: MessageEvent<WorkerMessage>) => {
      const message = event.data;
      if (settled || message.requestId !== requestId) return;
      if (message.type === "progress") {
        input.onProgress?.({
          phase: message.phase ?? "transcribing",
          progress: clampAutoTimingProgress(message.progress ?? 0),
          message: message.message ?? "Transcribing locally…",
        });
        return;
      }
      if (message.type === "error") {
        finish(() => reject(new Error(message.message || "On-device transcription failed.")));
        return;
      }
      if (message.type === "result") {
        finish(() => resolve({ words: message.words ?? [], device: message.device ?? "wasm" }));
      }
    });
    try {
      worker.postMessage(
        {
          type: "transcribe",
          requestId,
          modelId: input.definition.id,
          modelName: input.definition.modelId,
          modelVersion: input.definition.version,
          revision: input.definition.revision,
          dtype: input.definition.dtype,
          remoteHost: input.manifest.remoteHost,
          remotePathTemplate: input.manifest.remotePathTemplate,
          runtimeFiles: input.manifest.runtime.files.map(({ path, url }) => ({ path, url })),
          modelFiles: input.definition.files.map(({ path, size }) => ({ path, size })),
          language: input.language || undefined,
          devicePreference,
          pcm,
        },
        [pcm.buffer]
      );
    } catch (error) {
      finish(() => reject(error));
    }
  });
}

export async function transcribeAutoTimingAudio(
  input: TranscriptionInput
): Promise<AutoTimingTranscriptionResult> {
  if (input.signal?.aborted) throw new DOMException("Aborted", "AbortError");
  // Ownership of the caller's decoded audio stays with the editor so retries,
  // model changes and waveform playback cannot encounter a detached buffer.
  const pcm = input.pcm.slice();
  if (input.devicePreference === "wasm" || input.devicePreference === "webgpu") {
    return runTranscriptionWorker(input, pcm, input.devicePreference);
  }
  const hasWebGpu = Boolean((navigator as Navigator & { gpu?: unknown }).gpu);
  if (!hasWebGpu) return runTranscriptionWorker(input, pcm, "wasm");
  try {
    return await runTranscriptionWorker(input, pcm, "webgpu");
  } catch (error) {
    if (input.signal?.aborted || (error as Error)?.name === "AbortError") throw error;
    input.onProgress?.({
      phase: "loading-model",
      progress: 0,
      message: "WebGPU was unavailable. Retrying locally with WASM…",
    });
    return runTranscriptionWorker(input, input.pcm.slice(), "wasm");
  }
}
