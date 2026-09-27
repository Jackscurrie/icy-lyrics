import type { AutoTimingWord } from "./types.ts";

// Whisper can return null endpoints, especially for the final word. Never
// coerce null to zero: that would make an unknown timestamp look recognized.
export function autoTimingWordResults(output: unknown, durationMs: number): AutoTimingWord[] {
  const chunks = (output as { chunks?: unknown[] })?.chunks;
  if (!Array.isArray(chunks) || !Number.isFinite(durationMs) || durationMs <= 0) return [];
  return chunks.flatMap((value) => {
    const chunk = value as { text?: unknown; timestamp?: unknown[] };
    if (!chunk || typeof chunk.text !== "string" || !Array.isArray(chunk.timestamp)) return [];
    const [start, end] = chunk.timestamp;
    if (typeof start !== "number" || !Number.isFinite(start) || start < 0) return [];
    const text = chunk.text.trim();
    const startTimeMs = Math.round(start * 1000);
    if (!text || startTimeMs >= durationMs) return [];
    // Repeated zero-duration words are a common Whisper music hallucination.
    // Giving each one an invented 250 ms duration made them look recognized.
    if (typeof end === "number" && (!Number.isFinite(end) || end <= start)) return [];
    const estimatedTiming = typeof end !== "number";
    const endTimeMs = Math.min(
      durationMs,
      typeof end === "number" && Number.isFinite(end) && end > start
        ? Math.round(end * 1000)
        : startTimeMs + 250
    );
    if (endTimeMs <= startTimeMs) return [];
    return [{ text, startTimeMs, endTimeMs, ...(estimatedTiming ? { estimatedTiming: true } : {}) }];
  });
}

// A digital-silence guard prevents Whisper's familiar hallucinated captions.
// This deliberately does not attempt to distinguish quiet vocals from music.
export function hasAutoTimingSignal(pcm: Float32Array): boolean {
  return pcm.some((sample) => Number.isFinite(sample) && Math.abs(sample) > 0.000001);
}

export interface AutoTimingAudioWindow {
  startSample: number;
  endSample: number;
  keepStartMs: number;
  keepEndMs: number;
}

// Merge by audio ownership rather than textual similarity. Whisper's default
// text merger can mistake repeated choruses for the overlap between windows.
export function autoTimingAudioWindows(sampleCount: number): AutoTimingAudioWindow[] {
  const windowSamples = 29 * 16_000;
  const overlapSamples = 5 * 16_000;
  const jumpSamples = windowSamples - 2 * overlapSamples;
  const windows: AutoTimingAudioWindow[] = [];
  for (let startSample = 0; startSample < sampleCount; startSample += jumpSamples) {
    const endSample = Math.min(sampleCount, startSample + windowSamples);
    const last = endSample === sampleCount;
    windows.push({
      startSample, endSample,
      keepStartMs: (startSample + (startSample ? overlapSamples : 0)) / 16,
      keepEndMs: (endSample - (last ? 0 : overlapSamples)) / 16,
    });
    if (last) break;
  }
  return windows;
}

export function autoTimingWindowWords(output: unknown, window: AutoTimingAudioWindow): AutoTimingWord[] {
  const offsetMs = window.startSample / 16;
  return autoTimingWordResults(output, (window.endSample - window.startSample) / 16)
    .map((word) => ({
      ...word, startTimeMs: word.startTimeMs + offsetMs, endTimeMs: word.endTimeMs + offsetMs,
    }))
    .filter((word) => {
      const midpoint = (word.startTimeMs + word.endTimeMs) / 2;
      return midpoint >= window.keepStartMs && midpoint < window.keepEndMs;
    });
}
