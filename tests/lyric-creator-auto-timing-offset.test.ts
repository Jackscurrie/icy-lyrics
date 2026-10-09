import { describe, expect, it } from "vitest";
import {
  createEmptyProject,
  creatorProjectToLyrics,
  importPlainText,
  syncLineTiming,
  type CreatorProject,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import { creatorProjectCheckpoint } from "../src/components/ReactComponents/LyricCreator/sourceSwitch.ts";
import { applyCreatorProjectOffset } from "../src/components/ReactComponents/LyricCreator/projectOffset.ts";
import { applyTimingAction } from "../src/components/ReactComponents/LyricCreator/timing.ts";
import { applyAutoTimingCandidate } from "../src/components/ReactComponents/LyricCreator/autoTiming/alignment.ts";
import {
  applyAutoTimingCandidateWithOffset,
  buildAutoTimingCandidateWithOffset,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/projectOffset.ts";
import type {
  AutoTimingCandidate,
  AutoTimingFragmentCandidate,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/types.ts";

function projectWithText(text: string): CreatorProject {
  return { ...createEmptyProject(), lines: importPlainText(text) };
}

function candidateFor(project: CreatorProject, times: Array<[number, number]>, durationMs = 10_000): AutoTimingCandidate {
  const fragments: Record<string, AutoTimingFragmentCandidate> = {};
  let index = 0;
  project.lines.forEach((line, lineIndex) => line.tokens.forEach((token, tokenIndex) =>
    token.fragments.forEach((fragment, fragmentIndex) => {
      const [startTimeMs, endTimeMs] = times[index++];
      fragments[fragment.id] = {
        fragmentId: fragment.id,
        lineId: line.id,
        lineIndex,
        tokenIndex,
        fragmentIndex,
        startTimeMs,
        endTimeMs,
        confidence: "high",
        method: "aligned",
        score: 1,
      };
    })
  ));
  return {
    projectFingerprint: creatorProjectCheckpoint(project),
    modelId: "fast",
    modelVersion: "test",
    durationMs,
    createdAt: 0,
    fragments,
    transcriptWordCount: index,
    supportedLineIds: project.lines.map((line) => line.id),
  };
}

describe("Auto-time with a whole-song offset", () => {
  it("aligns against unshifted audio anchors and keeps review seeks in audio time", () => {
    const source = projectWithText("hello");
    const fragment = source.lines[0].tokens[0].fragments[0];
    fragment.startTimeMs = 6000;
    fragment.endTimeMs = 7000;
    syncLineTiming(source.lines[0]);
    const fingerprint = creatorProjectCheckpoint(source);
    const candidate = buildAutoTimingCandidateWithOffset({
      project: source,
      projectFingerprint: fingerprint,
      modelId: "fast",
      modelVersion: "test",
      durationMs: 10_000,
      words: [
        { text: "hello", startTimeMs: 1000, endTimeMs: 2000 },
        { text: "hello", startTimeMs: 6000, endTimeMs: 7000 },
      ],
    }, 5000);

    expect(candidate.fragments[fragment.id]).toMatchObject({ startTimeMs: 1000, endTimeMs: 2000 });
    expect(candidate.projectFingerprint).toBe(fingerprint);
    expect(creatorProjectCheckpoint(source)).toBe(fingerprint);
    expect(applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, 5000)).toEqual(source);
  });

  it("keeps Auto-time, new F/G/H stamps, preview, and resetting offset in the same coordinate system", () => {
    const source = projectWithText("first next");
    const candidate = candidateFor(source, [[1000, 1500], [2000, 2500]]);
    const applied = applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, 200);
    expect(applied.lines[0].tokens[0].fragments[0]).toMatchObject({ startTimeMs: 1200, endTimeMs: 1700 });
    expect(applied.lines[0].tokens[1].fragments[0]).toMatchObject({ startTimeMs: 2200, endTimeMs: 2700 });
    const manual = applyTimingAction(applied, 0, "start", 1000, { offsetMs: 200 });
    expect(manual.project.lines[0].tokens[0].fragments[0].startTimeMs).toBe(1200);
    expect(creatorProjectToLyrics(applied).Content[0].Lead.StartTime).toBe(1.2);
    const reset = applyCreatorProjectOffset(applied, 200, 0);
    expect(reset.project.lines[0].tokens[0].fragments[0]).toMatchObject({ startTimeMs: 1000, endTimeMs: 1500 });
    expect(candidate.fragments[source.lines[0].tokens[0].fragments[0].id].startTimeMs).toBe(1000);
    expect(source.lines[0].tokens[0].fragments[0].startTimeMs).toBeNull();
  });

  it("offsets only newly filled endpoints, preserving complete and partially manual timing", () => {
    const source = projectWithText("before middle after");
    const [before, middle, after] = source.lines[0].tokens.map((token) => token.fragments[0]);
    before.startTimeMs = 1200;
    before.endTimeMs = 1600;
    middle.startTimeMs = 1800;
    after.endTimeMs = 2800;
    syncLineTiming(source.lines[0]);
    const candidate = candidateFor(source, [[1000, 1400], [1600, 2000], [2200, 2600]]);
    const applied = applyAutoTimingCandidateWithOffset(source, candidate, { mode: "missing" }, 200);
    const output = applied.lines[0].tokens.map((token) => token.fragments[0]);

    expect(output[0]).toEqual(before);
    expect(output[1]).toMatchObject({ startTimeMs: 1800, endTimeMs: 2200 });
    expect(output[2]).toMatchObject({ startTimeMs: 2400, endTimeMs: 2800 });
    expect(middle.endTimeMs).toBeNull();
    expect(after.startTimeMs).toBeNull();
  });

  it.each(["missing", "replace"] as const)("keeps unselected rows unchanged in %s mode", (mode) => {
    const source = projectWithText("chosen\nretained");
    source.lines[1].tokens[0].fragments[0].startTimeMs = 3200;
    source.lines[1].tokens[0].fragments[0].endTimeMs = 4200;
    syncLineTiming(source.lines[1]);
    const candidate = candidateFor(source, [[1000, 2000], [3000, 4000]]);
    const applied = applyAutoTimingCandidateWithOffset(source, candidate, {
      mode,
      includedLineIds: new Set([source.lines[0].id]),
    }, 200);
    expect(applied.lines[0].tokens[0].fragments[0]).toMatchObject({ startTimeMs: 1200, endTimeMs: 2200 });
    expect(applied.lines[1]).toEqual(source.lines[1]);
  });

  it("does not clip positive-offset proposals at the raw audio duration", () => {
    const source = projectWithText("final");
    const applied = applyAutoTimingCandidateWithOffset(source,
      candidateFor(source, [[9000, 10_000]]), { mode: "replace" }, 500);
    expect(applied.lines[0].tokens[0].fragments[0]).toMatchObject({ startTimeMs: 9500, endTimeMs: 10_500 });
  });

  it("handles negative offsets without negative or collapsed word intervals", () => {
    const source = projectWithText("early clipped later");
    const candidate = candidateFor(source, [[20, 80], [100, 400], [500, 900]]);
    const applied = applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, -200);
    const output = applied.lines[0].tokens.map((token) => token.fragments[0]);
    expect(output[0]).toMatchObject({ startTimeMs: null, endTimeMs: null });
    expect(output[1]).toMatchObject({ startTimeMs: 0, endTimeMs: 200 });
    expect(output[2]).toMatchObject({ startTimeMs: 300, endTimeMs: 700 });
  });

  it("does not make malformed raw proposals valid by offsetting them", () => {
    const source = projectWithText("negative overrun infinite");
    const candidate = candidateFor(source, [[-100, 400], [9000, 11_000], [500, Infinity]]);
    const applied = applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, 500);
    expect(applied).toEqual(source);
  });

  it("keeps the zero-offset path unchanged and rejects unsafe offsets", () => {
    const source = projectWithText("normal");
    const candidate = candidateFor(source, [[1000, 2000]]);
    expect(applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }))
      .toEqual(applyAutoTimingCandidate(source, candidate, { mode: "replace" }));
    expect(() => applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, NaN)).toThrow(RangeError);
    expect(() => applyAutoTimingCandidateWithOffset(source, candidate, { mode: "replace" }, Number.MAX_SAFE_INTEGER))
      .toThrow(RangeError);
  });
});
