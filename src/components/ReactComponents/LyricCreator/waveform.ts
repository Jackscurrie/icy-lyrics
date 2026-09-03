import type { CreatorLine } from "./model.ts";

export const DEFAULT_CREATOR_WAVEFORM_SAMPLE_COUNT = 240;

export interface CreatorWaveformSegment {
  start?: number;
  duration?: number;
  loudness_start?: number;
  loudness_max?: number;
  loudness_max_time?: number;
  loudness_end?: number;
}

export interface CreatorWaveformAnalysis {
  track?: {
    duration?: number;
  };
  segments?: CreatorWaveformSegment[];
}

export interface CreatorWaveform {
  /** Track duration represented by the samples. */
  durationMs: number;
  /** Peak-normalized amplitudes in the inclusive range 0..1. */
  samples: number[];
  /** False when Spotify did not expose usable segment/loudness data. */
  available: boolean;
}

export interface CreatorWaveformOptions {
  sampleCount?: number;
  /** Prefer the live player duration when it is known. */
  durationMs?: number;
}

export type CreatorLineTimingStatus = "untimed" | "partial" | "timed" | "invalid";

export interface CreatorLineTimingRange {
  status: CreatorLineTimingStatus;
  startMs: number | null;
  endMs: number | null;
}

export interface CreatorLineTimelineGeometry extends CreatorLineTimingRange {
  visible: boolean;
  leftPercent: number;
  widthPercent: number;
  startPercent: number;
  endPercent: number;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

function finiteNonNegative(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

function normalizedSampleCount(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return DEFAULT_CREATOR_WAVEFORM_SAMPLE_COUNT;
  }
  return clamp(Math.round(value), 1, 4096);
}

/**
 * Resamples arbitrary amplitude data and peak-normalizes it for drawing. Invalid
 * values become silence. Downsampling uses RMS so narrow peaks remain visible;
 * upsampling uses linear interpolation to avoid a stepped waveform.
 */
export function normalizeCreatorWaveformSamples(
  input: readonly number[],
  sampleCount = input.length
): number[] {
  const count = normalizedSampleCount(sampleCount);
  if (input.length === 0) return Array.from({ length: count }, () => 0);

  const source = input.map((sample) =>
    typeof sample === "number" && Number.isFinite(sample) ? Math.abs(sample) : 0
  );
  let resampled: number[];

  if (source.length === count) {
    resampled = source;
  } else if (count < source.length) {
    resampled = Array.from({ length: count }, (_, index) => {
      const from = Math.floor((index * source.length) / count);
      const to = Math.max(from + 1, Math.ceil(((index + 1) * source.length) / count));
      const bucket = source.slice(from, Math.min(source.length, to));
      return Math.sqrt(bucket.reduce((sum, value) => sum + value * value, 0) / bucket.length);
    });
  } else if (source.length === 1) {
    resampled = Array.from({ length: count }, () => source[0]);
  } else {
    resampled = Array.from({ length: count }, (_, index) => {
      const position = (index * (source.length - 1)) / Math.max(1, count - 1);
      const left = Math.floor(position);
      const right = Math.min(source.length - 1, left + 1);
      const progress = position - left;
      return source[left] + (source[right] - source[left]) * progress;
    });
  }

  const peak = Math.max(...resampled);
  if (!(peak > 0) || !Number.isFinite(peak)) {
    return resampled.map(() => 0);
  }
  return resampled.map((sample) => clamp(sample / peak, 0, 1));
}

/** Builds an RMS waveform from decoded local-audio PCM channels. */
export function creatorWaveformFromPcmChannels(
  channels: readonly ArrayLike<number>[],
  durationMs: number,
  sampleCount = DEFAULT_CREATOR_WAVEFORM_SAMPLE_COUNT
): CreatorWaveform {
  const count = normalizedSampleCount(sampleCount);
  const usable = channels.filter((channel) => channel.length > 0);
  if (usable.length === 0 || !Number.isFinite(durationMs) || durationMs <= 0) {
    return {
      durationMs: Math.max(0, Math.round(durationMs) || 0),
      samples: Array.from({ length: count }, () => 0),
      available: false,
    };
  }

  const length = Math.max(...usable.map((channel) => channel.length));
  const samples = Array.from({ length: count }, (_, index) => {
    const from = Math.floor((index * length) / count);
    const to = Math.max(from + 1, Math.ceil(((index + 1) * length) / count));
    let squared = 0;
    let values = 0;
    for (const channel of usable) {
      const channelEnd = Math.min(channel.length, to);
      for (let offset = Math.min(from, channelEnd); offset < channelEnd; offset += 1) {
        const value = Number(channel[offset]);
        if (!Number.isFinite(value)) continue;
        squared += value * value;
        values += 1;
      }
    }
    return values > 0 ? Math.sqrt(squared / values) : 0;
  });

  return {
    durationMs: Math.round(durationMs),
    samples: normalizeCreatorWaveformSamples(samples, count),
    available: samples.some((sample) => sample > 0),
  };
}

function loudnessToAmplitude(loudnessDb: number): number {
  // Spotify's segment loudness is expressed in dB. Clamp corrupt/outlier data
  // before converting it to a linear amplitude envelope.
  return Math.pow(10, clamp(loudnessDb, -80, 6) / 20);
}

function segmentLoudnessAt(segment: CreatorWaveformSegment, progress: number): number | null {
  const suppliedStart = Number.isFinite(segment.loudness_start) ? segment.loudness_start! : null;
  const suppliedPeak = Number.isFinite(segment.loudness_max) ? segment.loudness_max! : null;
  const suppliedEnd = Number.isFinite(segment.loudness_end) ? segment.loudness_end! : null;
  if (suppliedStart === null && suppliedPeak === null && suppliedEnd === null) return null;
  const peak = suppliedPeak ?? suppliedStart ?? suppliedEnd!;
  const start = suppliedStart ?? peak;
  const end = suppliedEnd ?? peak;

  const duration = finiteNonNegative(segment.duration) ?? 0;
  const peakTime =
    duration > 0 ? clamp((finiteNonNegative(segment.loudness_max_time) ?? 0) / duration, 0, 1) : 0;
  const normalizedProgress = clamp(progress, 0, 1);

  if (normalizedProgress <= peakTime && peakTime > 0) {
    return start + (peak - start) * (normalizedProgress / peakTime);
  }
  if (peakTime < 1) {
    return peak + (end - peak) * ((normalizedProgress - peakTime) / (1 - peakTime));
  }
  return peak;
}

function analysisDurationMs(analysis: CreatorWaveformAnalysis): number {
  const trackDurationSeconds = finiteNonNegative(analysis.track?.duration);
  if (trackDurationSeconds !== null && trackDurationSeconds > 0) {
    return Math.round(trackDurationSeconds * 1000);
  }

  const segmentEndSeconds = (analysis.segments ?? []).reduce((maximum, segment) => {
    const start = finiteNonNegative(segment.start);
    const duration = finiteNonNegative(segment.duration);
    return start === null || duration === null ? maximum : Math.max(maximum, start + duration);
  }, 0);
  return Math.round(segmentEndSeconds * 1000);
}

/**
 * Builds an amplitude envelope from Spotify's internal audio-analysis segments.
 * This is intentionally fetch-agnostic: callers can use the existing cached
 * `getDynamicAudioAnalysis(uri)` utility and pass its result here.
 */
export function creatorWaveformFromSpotifyAnalysis(
  input: CreatorWaveformAnalysis | null | undefined,
  options: CreatorWaveformOptions = {}
): CreatorWaveform {
  const sampleCount = normalizedSampleCount(options.sampleCount);
  const suppliedDuration = finiteNonNegative(options.durationMs);
  const analysis = input ?? {};
  const durationMs =
    suppliedDuration !== null && suppliedDuration > 0
      ? Math.round(suppliedDuration)
      : analysisDurationMs(analysis);
  const segments = Array.isArray(analysis.segments) ? analysis.segments : [];

  if (durationMs <= 0 || segments.length === 0) {
    return {
      durationMs,
      samples: Array.from({ length: sampleCount }, () => 0),
      available: false,
    };
  }

  const raw = Array.from({ length: sampleCount }, () => 0);
  const durationSeconds = durationMs / 1000;

  for (const segment of segments) {
    const start = finiteNonNegative(segment.start);
    const segmentDuration = finiteNonNegative(segment.duration);
    if (start === null || segmentDuration === null || segmentDuration <= 0) continue;
    const end = start + segmentDuration;
    if (end <= 0 || start >= durationSeconds) continue;

    const firstIndex = clamp(
      Math.floor((start / durationSeconds) * sampleCount),
      0,
      sampleCount - 1
    );
    const lastIndex = clamp(
      Math.ceil((end / durationSeconds) * sampleCount) - 1,
      firstIndex,
      sampleCount - 1
    );

    for (let index = firstIndex; index <= lastIndex; index += 1) {
      const bucketStart = (index / sampleCount) * durationSeconds;
      const bucketEnd = ((index + 1) / sampleCount) * durationSeconds;
      const sampleTime = clamp((bucketStart + bucketEnd) / 2, start, end);
      const loudness = segmentLoudnessAt(segment, (sampleTime - start) / segmentDuration);
      if (loudness === null) continue;
      raw[index] = Math.max(raw[index], loudnessToAmplitude(loudness));
    }
  }

  const available = raw.some((sample) => sample > 0);
  return {
    durationMs,
    samples: normalizeCreatorWaveformSamples(raw, sampleCount),
    available,
  };
}

/** Resolves a line range from fragment timings, falling back to line-level timing. */
export function resolveCreatorLineTiming(line: CreatorLine): CreatorLineTimingRange {
  const fragments = line.tokens.flatMap((token) => token.fragments);
  const fragmentStarts = fragments
    .map((fragment) => finiteNonNegative(fragment.startTimeMs))
    .filter((value): value is number => value !== null);
  const fragmentEnds = fragments
    .map((fragment) => finiteNonNegative(fragment.endTimeMs))
    .filter((value): value is number => value !== null);

  const startMs =
    fragmentStarts.length > 0 ? Math.min(...fragmentStarts) : finiteNonNegative(line.startTimeMs);
  const endMs =
    fragmentEnds.length > 0 ? Math.max(...fragmentEnds) : finiteNonNegative(line.endTimeMs);

  if (startMs === null && endMs === null) return { status: "untimed", startMs, endMs };
  if (startMs === null || endMs === null) return { status: "partial", startMs, endMs };
  if (endMs <= startMs) return { status: "invalid", startMs, endMs };
  return { status: "timed", startMs, endMs };
}

/**
 * Converts line timing into safe CSS percentages. Partial/zero-length ranges
 * remain visible as a narrow marker, while untimed lines stay hidden.
 */
export function creatorLineTimelineGeometry(
  line: CreatorLine,
  durationMs: number,
  minimumWidthPercent = 0.6
): CreatorLineTimelineGeometry {
  const range = resolveCreatorLineTiming(line);
  const duration = finiteNonNegative(durationMs) ?? 0;
  if (duration <= 0 || range.status === "untimed") {
    return {
      ...range,
      visible: false,
      leftPercent: 0,
      widthPercent: 0,
      startPercent: 0,
      endPercent: 0,
    };
  }

  const markerWidth = clamp(
    Number.isFinite(minimumWidthPercent) ? minimumWidthPercent : 0.6,
    0.1,
    100
  );
  const startPercent = clamp(((range.startMs ?? range.endMs ?? 0) / duration) * 100, 0, 100);
  const endPercent = clamp(((range.endMs ?? range.startMs ?? 0) / duration) * 100, 0, 100);
  let leftPercent = Math.min(startPercent, endPercent);
  let widthPercent = Math.abs(endPercent - startPercent);

  if (widthPercent < markerWidth) {
    if (range.startMs === null && range.endMs !== null) {
      leftPercent = Math.max(0, endPercent - markerWidth);
    }
    widthPercent = Math.min(markerWidth, 100 - leftPercent);
  }

  return {
    ...range,
    visible: true,
    leftPercent,
    widthPercent,
    startPercent,
    endPercent,
  };
}
