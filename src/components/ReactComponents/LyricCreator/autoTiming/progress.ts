import type { AutoTimingModuleFile, AutoTimingProgress, AutoTimingProgressPhase } from "./types.ts";

export function clampAutoTimingProgress(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
}

/** Phase budgets describe completed work, not an estimate of time remaining. */
export function createAutoTimingProgressTracker(needsDownload: boolean) {
  const loadingStart = needsDownload ? 0.2 : 0.02;
  const transcribingStart = needsDownload ? 0.3 : 0.12;
  const ranges: Record<AutoTimingProgressPhase, readonly [number, number]> = {
    preparing: [0, 0.02],
    downloading: [0.02, 0.2],
    "loading-model": [loadingStart, transcribingStart],
    transcribing: [transcribingStart, 0.95],
    aligning: [0.95, 0.99],
    review: [1, 1],
  };
  let highWaterMark = 0;
  return (update: AutoTimingProgress): AutoTimingProgress => {
    const [start, end] = ranges[update.phase];
    // A GPU fallback can restart a phase. Keep the bar still until real work
    // catches up, while continuing to show the new attempt's status text.
    highWaterMark = Math.max(highWaterMark, start + (end - start) * clampAutoTimingProgress(update.progress));
    return { ...update, progress: highWaterMark };
  };
}

interface ModelProgressEvent {
  status?: string;
  file?: string;
  progress?: number;
}

/** Use the verified manifest's fixed denominator, never a single file's %. */
export function createModelLoadingProgress(files: Pick<AutoTimingModuleFile, "path" | "size">[]) {
  const sizes = new Map(files.map((file) => [file.path, file.size]));
  const fractions = new Map<string, number>();
  const total = [...sizes.values()].reduce((sum, size) => sum + size, 0);
  let loaded = 0;
  return (event: ModelProgressEvent): number | null => {
    // The library also emits aggregate and status-only events. Mixing them
    // with per-file progress causes resets and prematurely full bars.
    if (!event.file || !sizes.has(event.file) || total <= 0) return null;
    if (event.status !== "done" && (event.status !== "progress" || !Number.isFinite(event.progress))) return null;
    const previous = fractions.get(event.file) ?? 0;
    const next = event.status === "done" ? 1 : clampAutoTimingProgress(event.progress! / 100);
    if (next <= previous) return null;
    fractions.set(event.file, next);
    loaded += (next - previous) * sizes.get(event.file)!;
    // Reading the last asset is not the end of model initialization; reserve
    // the final part for pipeline/session compilation to actually resolve.
    return Math.min(0.95, loaded / total * 0.95);
  };
}
