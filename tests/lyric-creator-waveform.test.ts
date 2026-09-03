import { describe, expect, it } from "vitest";
import {
  creatorLineTimelineGeometry,
  creatorWaveformFromPcmChannels,
  creatorWaveformFromSpotifyAnalysis,
  normalizeCreatorWaveformSamples,
  resolveCreatorLineTiming,
} from "../src/components/ReactComponents/LyricCreator/waveform.ts";
import {
  createFragment,
  createLine,
  createToken,
} from "../src/components/ReactComponents/LyricCreator/model.ts";

describe("Lyric Creator waveform", () => {
  it("sanitizes, resamples, and peak-normalizes amplitude data", () => {
    const samples = normalizeCreatorWaveformSamples([0, -2, Number.NaN, 4], 2);

    expect(samples).toHaveLength(2);
    expect(samples[0]).toBeCloseTo(0.5);
    expect(samples[1]).toBe(1);
    expect(samples.every((sample) => sample >= 0 && sample <= 1)).toBe(true);
  });

  it("uses Spotify segment loudness as a normalized audio envelope", () => {
    const waveform = creatorWaveformFromSpotifyAnalysis(
      {
        track: { duration: 4 },
        segments: [
          {
            start: 0,
            duration: 2,
            loudness_start: -30,
            loudness_max: -20,
            loudness_max_time: 0.25,
            loudness_end: -28,
          },
          {
            start: 2,
            duration: 2,
            loudness_start: -12,
            loudness_max: -4,
            loudness_max_time: 0.25,
            loudness_end: -10,
          },
        ],
      },
      { sampleCount: 8 }
    );

    expect(waveform.durationMs).toBe(4000);
    expect(waveform.samples).toHaveLength(8);
    expect(waveform.available).toBe(true);
    expect(Math.max(...waveform.samples)).toBe(1);
    expect(Math.max(...waveform.samples.slice(4))).toBeGreaterThan(
      Math.max(...waveform.samples.slice(0, 4))
    );
  });

  it("returns a stable silent envelope when analysis is unavailable", () => {
    expect(
      creatorWaveformFromSpotifyAnalysis(null, { durationMs: 180_000, sampleCount: 12 })
    ).toEqual({
      durationMs: 180_000,
      samples: Array.from({ length: 12 }, () => 0),
      available: false,
    });
  });

  it("accepts Spotify segments that only include peak loudness", () => {
    const waveform = creatorWaveformFromSpotifyAnalysis(
      {
        track: { duration: 1 },
        segments: [{ start: 0, duration: 1, loudness_max: -6, loudness_max_time: 0.1 }],
      },
      { sampleCount: 4 }
    );

    expect(waveform.available).toBe(true);
    expect(waveform.samples).toEqual([1, 1, 1, 1]);
  });

  it("builds the same normalized timeline from decoded local audio channels", () => {
    const waveform = creatorWaveformFromPcmChannels(
      [new Float32Array([0, 0.1, 0.25, 0.5, 1, 0.5, 0.25, 0])],
      2_000,
      4
    );

    expect(waveform.durationMs).toBe(2_000);
    expect(waveform.samples).toHaveLength(4);
    expect(waveform.available).toBe(true);
    expect(Math.max(...waveform.samples)).toBe(1);
  });
});

describe("Lyric Creator line timeline geometry", () => {
  it("uses fragment timing in preference to stale line-level timing", () => {
    const first = { ...createFragment("one"), startTimeMs: 20_000, endTimeMs: 25_000 };
    const second = { ...createFragment("two"), startTimeMs: 25_000, endTimeMs: 40_000 };
    const line = {
      ...createLine(),
      startTimeMs: 1_000,
      endTimeMs: 2_000,
      tokens: [
        { ...createToken(), fragments: [first] },
        { ...createToken(), fragments: [second] },
      ],
    };

    expect(resolveCreatorLineTiming(line)).toEqual({
      status: "timed",
      startMs: 20_000,
      endMs: 40_000,
    });
    expect(creatorLineTimelineGeometry(line, 100_000)).toMatchObject({
      visible: true,
      leftPercent: 20,
      widthPercent: 20,
      startPercent: 20,
      endPercent: 40,
    });
  });

  it("shows partial timing as a bounded marker", () => {
    const startOnly = createLine();
    startOnly.tokens[0].fragments[0].startTimeMs = 90_000;
    const endOnly = createLine();
    endOnly.tokens[0].fragments[0].endTimeMs = 10_000;

    expect(creatorLineTimelineGeometry(startOnly, 100_000, 2)).toMatchObject({
      status: "partial",
      visible: true,
      leftPercent: 90,
      widthPercent: 2,
    });
    expect(creatorLineTimelineGeometry(endOnly, 100_000, 2)).toMatchObject({
      status: "partial",
      visible: true,
      leftPercent: 8,
      widthPercent: 2,
    });
  });

  it("hides untimed lines and avoids negative geometry for invalid ranges", () => {
    const untimed = createLine();
    expect(creatorLineTimelineGeometry(untimed, 100_000)).toMatchObject({
      status: "untimed",
      visible: false,
      widthPercent: 0,
    });

    const invalid = createLine();
    invalid.tokens[0].fragments[0].startTimeMs = 80_000;
    invalid.tokens[0].fragments[0].endTimeMs = 20_000;
    const geometry = creatorLineTimelineGeometry(invalid, 100_000);
    expect(geometry).toMatchObject({
      status: "invalid",
      visible: true,
      leftPercent: 20,
      widthPercent: 60,
      startPercent: 80,
      endPercent: 20,
    });
    expect(geometry.widthPercent).toBeGreaterThanOrEqual(0);
  });

  it("clamps out-of-track ranges to the waveform bounds", () => {
    const line = createLine();
    line.tokens[0].fragments[0].startTimeMs = 90_000;
    line.tokens[0].fragments[0].endTimeMs = 120_000;

    expect(creatorLineTimelineGeometry(line, 100_000)).toMatchObject({
      leftPercent: 90,
      widthPercent: 10,
      endPercent: 100,
    });
  });
});
