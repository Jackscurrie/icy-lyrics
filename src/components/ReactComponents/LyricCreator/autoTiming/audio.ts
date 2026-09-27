import type { AutoTimingEnergyFrame } from "./types.ts";

export const AUTO_TIMING_MAX_AUDIO_BYTES = 500 * 1024 * 1024;
export const AUTO_TIMING_MAX_DURATION_MS = 30 * 60 * 1000;
export const AUTO_TIMING_SAMPLE_RATE = 16_000;

export interface DecodedAutoTimingAudio {
  pcm: Float32Array;
  durationMs: number;
  energyFrames: AutoTimingEnergyFrame[];
}

function mixChannels(buffer: AudioBuffer): Float32Array {
  const output = new Float32Array(buffer.length);
  for (let channel = 0; channel < buffer.numberOfChannels; channel += 1) {
    const input = buffer.getChannelData(channel);
    for (let index = 0; index < input.length; index += 1) {
      output[index] += input[index] / buffer.numberOfChannels;
    }
  }
  return output;
}

export function autoTimingEnergyFrames(
  pcm: Float32Array,
  sampleRate = AUTO_TIMING_SAMPLE_RATE
): AutoTimingEnergyFrame[] {
  const frameSamples = Math.max(1, Math.round(sampleRate * 0.02));
  const frames: AutoTimingEnergyFrame[] = [];
  for (let offset = 0; offset < pcm.length; offset += frameSamples) {
    const end = Math.min(pcm.length, offset + frameSamples);
    let squared = 0;
    for (let index = offset; index < end; index += 1) squared += pcm[index] * pcm[index];
    frames.push({
      timeMs: Math.round((offset / sampleRate) * 1000),
      energy: Math.sqrt(squared / Math.max(1, end - offset)),
    });
  }
  return frames;
}

export async function decodeAutoTimingAudio(file: File): Promise<DecodedAutoTimingAudio> {
  if (file.size <= 0) throw new Error("The selected audio file is empty.");
  if (file.size > AUTO_TIMING_MAX_AUDIO_BYTES) {
    throw new Error("Auto-time supports audio files up to 500 MiB.");
  }
  const OfflineContext =
    window.OfflineAudioContext ??
    (window as typeof window & { webkitOfflineAudioContext?: typeof OfflineAudioContext })
      .webkitOfflineAudioContext;
  if (!OfflineContext) throw new Error("Spotify does not expose audio decoding on this device.");
  // decodeAudioData uses the context's sample rate and the browser's filtered
  // resampler. Linear sample skipping aliases cymbals into the vocal band.
  // An offline context also avoids opening a real audio device for decoding.
  const context = new OfflineContext(1, 1, AUTO_TIMING_SAMPLE_RATE);
  const buffer = await context.decodeAudioData(await file.arrayBuffer());
  const durationMs = Math.round(buffer.duration * 1000);
  if (durationMs <= 0 || durationMs > AUTO_TIMING_MAX_DURATION_MS) {
    throw new Error("Auto-time supports audio up to 30 minutes long.");
  }
  const pcm = mixChannels(buffer);
  return { pcm, durationMs, energyFrames: autoTimingEnergyFrames(pcm) };
}
