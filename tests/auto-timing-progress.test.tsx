import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import AutoTimingProgressBar from "../src/components/ReactComponents/LyricCreator/autoTiming/AutoTimingProgressBar.tsx";
import { clampAutoTimingProgress, createAutoTimingProgressTracker, createModelLoadingProgress } from "../src/components/ReactComponents/LyricCreator/autoTiming/progress.ts";
import type { AutoTimingProgress } from "../src/components/ReactComponents/LyricCreator/autoTiming/types.ts";

describe("Auto-time job progress", () => {
  it.each([false, true])("stays monotonic through every phase (download: %s)", (download) => {
    const track = createAutoTimingProgressTracker(download);
    const events: AutoTimingProgress[] = [
      { phase: "preparing", progress: 1, message: "Preparing" },
      ...(download ? [0, 0.3, 1].map((progress): AutoTimingProgress => ({ phase: "downloading", progress, message: "Installing" })) : []),
      ...[0, 0.5, 1].map((progress): AutoTimingProgress => ({ phase: "loading-model", progress, message: "Loading" })),
      ...[0, 0.2, 0.8, 1].map((progress): AutoTimingProgress => ({ phase: "transcribing", progress, message: "Transcribing" })),
      { phase: "aligning", progress: 0, message: "Aligning" },
      { phase: "aligning", progress: 1, message: "Aligning" },
      { phase: "review", progress: 1, message: "Review" },
    ];
    const values = events.map((event) => track(event).progress);
    expect(values).toEqual([...values].sort((a, b) => a - b));
    expect(values.slice(0, -1).every((value) => value < 1)).toBe(true);
    expect(values.at(-1)).toBe(1);
  });

  it("shows fallback status without sending progress backward; a new job starts fresh", () => {
    const track = createAutoTimingProgressTracker(false);
    const previous = track({ phase: "transcribing", progress: 0.5, message: "Working" });
    const retry = track({ phase: "loading-model", progress: 0, message: "Retrying with WASM" });
    expect(retry.progress).toBe(previous.progress);
    expect(retry.message).toBe("Retrying with WASM");
    expect(track({ phase: "transcribing", progress: 0.75, message: "Working" }).progress).toBeGreaterThan(previous.progress);
    expect(createAutoTimingProgressTracker(false)({ phase: "preparing", progress: 0, message: "New job" }).progress).toBe(0);
  });

  it("clamps invalid, negative and oversized values", () => {
    expect([NaN, Infinity, -Infinity, -1, 0.5, 2].map(clampAutoTimingProgress)).toEqual([0, 0, 0, 0, 0.5, 1]);
    const track = createAutoTimingProgressTracker(false);
    expect(track({ phase: "transcribing", progress: NaN, message: "Working" }).progress).toBe(0.12);
  });
});

describe("Auto-time cached model progress", () => {
  const files = [{ path: "config.json", size: 10 }, { path: "onnx/encoder.onnx", size: 90 }];

  it("weights all files by signed byte sizes, reserving completion for model initialization", () => {
    const update = createModelLoadingProgress(files);
    expect(update({ status: "done", file: "config.json" })).toBeCloseTo(0.095);
    expect(update({ status: "progress", file: "onnx/encoder.onnx", progress: 50 })).toBeCloseTo(0.5225);
    expect(update({ status: "done", file: "onnx/encoder.onnx" })).toBe(0.95);
  });

  it("ignores aggregate, status-only, unknown, duplicate, and regressing file updates", () => {
    const update = createModelLoadingProgress(files);
    for (const event of [
      { status: "initiate", file: "config.json" },
      { status: "progress_total", progress: 100 },
      { status: "ready" },
      { status: "progress", file: "not-installed.bin", progress: 100 },
      { status: "progress", file: "config.json", progress: NaN },
      { status: "progress", file: "config.json", progress: Infinity },
    ]) expect(update(event)).toBeNull();
    expect(update({ status: "done", file: "config.json" })).toBeCloseTo(0.095);
    expect(update({ status: "done", file: "config.json" })).toBeNull();
    expect(update({ status: "progress", file: "config.json", progress: 50 })).toBeNull();
    expect(createModelLoadingProgress([])({ status: "done", file: "config.json" })).toBeNull();
  });
});

describe("Auto-time progress presentation", () => {
  it("keeps the fill inside its track and exposes an accessible value and separate status", () => {
    const markup = renderToStaticMarkup(<AutoTimingProgressBar progress={{ phase: "transcribing", progress: 0.5, message: "Section 4 of 8" }} />);
    expect(markup).toContain('role="progressbar"');
    expect(markup).toContain('aria-valuenow="50"');
    expect(markup).toContain('aria-valuetext="50% — Section 4 of 8"');
    expect(markup).toContain('<span style="transform:scaleX(0.5)"></span></div>');
    expect(markup).toContain('<small role="status">Section 4 of 8</small>');
  });

  it.each([
    ["transcribing", 1, 99], ["loading-model", NaN, 0], ["aligning", 5, 99], ["review", 1, 100],
  ] as const)("renders a valid value for %s/%s", (phase, progress, percent) => {
    const markup = renderToStaticMarkup(<AutoTimingProgressBar progress={{ phase, progress, message: "Working" }} />);
    expect(markup).toContain(`aria-valuenow="${percent}"`);
    expect(markup).not.toContain("NaN");
  });

  it("allows status text to wrap and never shrinks the four-pixel track", () => {
    const css = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/styles.css", import.meta.url), "utf8");
    const container = css.match(/\.il-creator-autotime__progress\s*\{([^}]+)\}/)?.[1] ?? "";
    expect(container).toContain("flex: 0 0 auto");
    expect(container).toContain("grid-template-columns: minmax(0, 1fr)");
    expect(container).not.toContain("overflow: hidden");
    expect(container).not.toMatch(/(?:^|;)\s*height:/);
    expect(css).not.toContain(".il-creator-autotime__progress::before");
    expect(css).toContain(".il-creator-autotime__progress-track > span");
  });
});
