import { describe, expect, it } from "vitest";
import {
  createEmptyProject,
  createLine,
  createToken,
  importPlainText,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  applyAutoTimingCandidate,
  autoTimingTextSimilarity,
  buildAutoTimingCandidate,
  normalizeAutoTimingText,
  summarizeAutoTimingCandidate,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/alignment.ts";
import { autoTimingEnergyFrames } from "../src/components/ReactComponents/LyricCreator/autoTiming/audio.ts";

function candidateFor(
  project: ReturnType<typeof createEmptyProject>,
  words: Array<{ text: string; startTimeMs: number; endTimeMs: number }>,
  durationMs = 10_000
) {
  return buildAutoTimingCandidate({
    project,
    projectFingerprint: "checkpoint",
    modelId: "fast",
    modelVersion: "test",
    durationMs,
    words,
  });
}

describe("Lyric Creator automatic timing alignment", () => {
  it("normalizes Unicode punctuation and scores close recognition matches", () => {
    expect(normalizeAutoTimingText("Don’t—STOP!")) .toBe("dontstop");
    expect(autoTimingTextSimilarity("colour", "color")).toBeGreaterThan(0.8);
    expect(autoTimingTextSimilarity("hello", "unrelated")).toBeLessThan(0.5);
  });

  it("aligns words monotonically and gives exact matches high confidence", () => {
    const project = createEmptyProject();
    project.lines = importPlainText(`hello world
sing again`);
    const candidate = candidateFor(project, [
      { text: "hello", startTimeMs: 1000, endTimeMs: 1400 },
      { text: "world", startTimeMs: 1450, endTimeMs: 2000 },
      { text: "sing", startTimeMs: 4000, endTimeMs: 4400 },
      { text: "again", startTimeMs: 4450, endTimeMs: 5100 },
    ]);
    const fragments = Object.values(candidate.fragments).sort(
      (left, right) => left.startTimeMs - right.startTimeMs
    );

    expect(fragments.map((fragment) => fragment.startTimeMs)).toEqual([1000, 1450, 4000, 4450]);
    expect(fragments.every((fragment) => fragment.confidence === "high")).toBe(true);
    expect(summarizeAutoTimingCandidate(candidate).high).toBe(4);
  });

  it("subdivides a recognized word across editable word fragments", () => {
    const project = createEmptyProject();
    const token = createToken("sun");
    token.fragments = [
      { ...token.fragments[0], text: "sun" },
      { ...token.fragments[0], id: `${token.fragments[0].id}-2`, text: "shine" },
    ];
    project.lines = [createLine([token])];
    const candidate = candidateFor(project, [
      { text: "sunshine", startTimeMs: 1000, endTimeMs: 1800 },
    ]);
    const [first, second] = token.fragments.map((fragment) => candidate.fragments[fragment.id]);

    expect(first.startTimeMs).toBe(1000);
    expect(first.endTimeMs).toBeLessThan(second.endTimeMs);
    expect(second.startTimeMs).toBe(first.endTimeMs);
    expect(second.endTimeMs).toBe(1800);
    expect(first.confidence).toBe("estimated");
    expect(second.confidence).toBe("estimated");
    expect(candidate.supportedLineIds).toEqual([project.lines[0].id]);
  });

  it("fills only missing timing by default and explicitly replaces all timing", () => {
    const project = createEmptyProject();
    project.lines = [createLine([createToken("kept"), createToken("new")])];
    const kept = project.lines[0].tokens[0].fragments[0];
    kept.startTimeMs = 200;
    kept.endTimeMs = 500;
    const candidate = candidateFor(project, [
      { text: "kept", startTimeMs: 1000, endTimeMs: 1300 },
      { text: "new", startTimeMs: 1400, endTimeMs: 1700 },
    ]);

    const filled = applyAutoTimingCandidate(project, candidate, { mode: "missing" });
    expect(filled.lines[0].tokens[0].fragments[0]).toMatchObject({
      startTimeMs: 200,
      endTimeMs: 500,
    });
    expect(filled.lines[0].tokens[1].fragments[0]).toMatchObject({
      startTimeMs: 1400,
      endTimeMs: 1700,
    });

    const replaced = applyAutoTimingCandidate(project, candidate, { mode: "replace" });
    expect(replaced.lines[0].tokens[0].fragments[0]).toMatchObject({
      startTimeMs: 1000,
      endTimeMs: 1300,
    });
    expect(project.lines[0].tokens[0].fragments[0].startTimeMs).toBe(200);
  });

  it("times background and second-speaker lanes while allowing intentional overlap", () => {
    const project = createEmptyProject();
    project.lines[0] = createLine([createToken("echo")]);
    const background = createLine([createToken("echo")]);
    background.isBackground = true;
    background.isSecondSpeaker = true;
    project.lines.push(background);
    const candidate = candidateFor(project, [
      { text: "echo", startTimeMs: 2200, endTimeMs: 2800 },
    ]);

    const leadTiming = candidate.fragments[project.lines[0].tokens[0].fragments[0].id];
    const backgroundTiming = candidate.fragments[background.tokens[0].fragments[0].id];
    expect(leadTiming).toMatchObject({ startTimeMs: 2200, endTimeMs: 2800 });
    expect(backgroundTiming).toMatchObject({ startTimeMs: 2200, endTimeMs: 2800 });
    expect(leadTiming.confidence).toBe("high");
    expect(backgroundTiming.confidence).toBe("estimated");
    expect(candidate.supportedLineIds).toEqual([project.lines[0].id]);
  });

  it("interpolates unrecognized and CJK lyrics into bounded editable timing", () => {
    const project = createEmptyProject();
    project.lines = importPlainText(`君 の 名前
最後 の 歌`);
    const candidate = candidateFor(project, [], 6000);
    const fragments = Object.values(candidate.fragments).sort(
      (left, right) => left.startTimeMs - right.startTimeMs
    );

    expect(fragments).toHaveLength(6);
    expect(fragments[0].startTimeMs).toBe(0);
    expect(fragments.at(-1)?.endTimeMs).toBe(6000);
    expect(fragments.every((fragment) => fragment.confidence === "estimated")).toBe(true);
  });

  it("computes deterministic 20 ms RMS energy frames for silence snapping", () => {
    const pcm = new Float32Array(320 * 2);
    pcm.fill(0.5, 320);
    const frames = autoTimingEnergyFrames(pcm, 16_000);
    expect(frames).toHaveLength(2);
    expect(frames[0]).toEqual({ timeMs: 0, energy: 0 });
    expect(frames[1].timeMs).toBe(20);
    expect(frames[1].energy).toBeCloseTo(0.5);
  });

  it("preserves a manually timed boundary when filling a half-timed word", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("start end");
    const [start, end] = project.lines[0].tokens.map((token) => token.fragments[0]);
    start.startTimeMs = 500;
    end.endTimeMs = 2300;
    const candidate = candidateFor(project, [
      { text: "start", startTimeMs: 700, endTimeMs: 1000 },
      { text: "end", startTimeMs: 1500, endTimeMs: 1800 },
    ]);
    const filled = applyAutoTimingCandidate(project, candidate, { mode: "missing" });
    expect(filled.lines[0].tokens[0].fragments[0]).toMatchObject({ startTimeMs: 500, endTimeMs: 1000 });
    expect(filled.lines[0].tokens[1].fragments[0]).toMatchObject({ startTimeMs: 1500, endTimeMs: 2300 });
  });

  it("fits missing words between preserved manual timings instead of overlapping them", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("before missing after");
    const fragments = project.lines[0].tokens.map((token) => token.fragments[0]);
    fragments[0].startTimeMs = 200;
    fragments[0].endTimeMs = 800;
    fragments[2].startTimeMs = 1500;
    fragments[2].endTimeMs = 1800;
    const candidate = candidateFor(project, [
      { text: "before", startTimeMs: 100, endTimeMs: 300 },
      { text: "missing", startTimeMs: 1700, endTimeMs: 2200 },
      { text: "after", startTimeMs: 2300, endTimeMs: 2600 },
    ]);
    const filled = applyAutoTimingCandidate(project, candidate, { mode: "missing" });
    const proposed = filled.lines[0].tokens[1].fragments[0];
    expect(proposed.startTimeMs).toBeGreaterThanOrEqual(800);
    expect(proposed.endTimeMs).toBeLessThanOrEqual(1500);
    expect(proposed.endTimeMs).toBeGreaterThan(proposed.startTimeMs!);
    expect(filled.lines[0].tokens[0].fragments[0]).toEqual(fragments[0]);
    expect(filled.lines[0].tokens[2].fragments[0]).toEqual(fragments[2]);
  });

  it("uses existing line timing to constrain words that were not recognized", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("one two");
    project.lines[0].startTimeMs = 4000;
    project.lines[0].endTimeMs = 6000;
    const candidate = candidateFor(project, []);
    const fragments = Object.values(candidate.fragments);
    expect(fragments[0].startTimeMs).toBe(4000);
    expect(fragments.at(-1)?.endTimeMs).toBe(6000);
  });

  it("keeps an attached background echo near its lead when the transcript repeats", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("sing");
    project.lines[0].startTimeMs = 1000;
    project.lines[0].endTimeMs = 3000;
    const background = createLine([createToken("echo")]);
    background.isBackground = true;
    background.attachedToLineId = project.lines[0].id;
    project.lines.push(background);
    const candidate = candidateFor(project, [
      { text: "sing", startTimeMs: 1100, endTimeMs: 1600 },
      { text: "echo", startTimeMs: 2000, endTimeMs: 2500 },
      { text: "echo", startTimeMs: 7000, endTimeMs: 7500 },
    ]);
    expect(candidate.fragments[background.tokens[0].fragments[0].id].startTimeMs).toBe(2000);
  });

  it("uses an end-only manual anchor against recognized end time", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("hold");
    project.lines[0].tokens[0].fragments[0].endTimeMs = 6000;
    const candidate = candidateFor(project, [
      { text: "hold", startTimeMs: 1000, endTimeMs: 6000 },
      { text: "hold", startTimeMs: 5900, endTimeMs: 6500 },
    ]);
    expect(Object.values(candidate.fragments)[0].startTimeMs).toBe(1000);
  });

  it("anchors background lyrics to the newly aligned lead when neither had timing", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("chorus");
    const background = createLine([createToken("echo")]);
    background.isBackground = true;
    background.attachedToLineId = project.lines[0].id;
    project.lines.push(background);
    const candidate = candidateFor(project, [
      { text: "echo", startTimeMs: 1000, endTimeMs: 1300 },
      { text: "chorus", startTimeMs: 6000, endTimeMs: 8000 },
      { text: "echo", startTimeMs: 6800, endTimeMs: 7300 },
    ]);
    expect(candidate.fragments[background.tokens[0].fragments[0].id].startTimeMs).toBe(6800);
  });

  it("marks timings split from a multiword ASR chunk as estimated", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("hello world");
    const candidate = candidateFor(project, [
      { text: "hello world", startTimeMs: 1000, endTimeMs: 2000 },
    ]);
    const fragments = Object.values(candidate.fragments);
    expect(fragments).toHaveLength(2);
    expect(fragments[0]).toMatchObject({ startTimeMs: 1000, endTimeMs: 1500, confidence: "estimated" });
    expect(fragments[1]).toMatchObject({ startTimeMs: 1500, endTimeMs: 2000, confidence: "estimated" });
  });

  it("matches a creator token containing a phrase against several transcript words", () => {
    const project = createEmptyProject();
    project.lines = [createLine([createToken("hello world")])];
    const candidate = candidateFor(project, [
      { text: "hello", startTimeMs: 1000, endTimeMs: 1400 },
      { text: "world", startTimeMs: 1600, endTimeMs: 2000 },
    ]);
    expect(Object.values(candidate.fragments)[0]).toMatchObject({ startTimeMs: 1000, endTimeMs: 2000 });
  });

  it("never extends fragment subdivisions into the next word or beyond the audio", () => {
    const project = createEmptyProject();
    const token = createToken("abcdefghij");
    token.fragments.push({ ...token.fragments[0], id: "short-fragment", text: "x" });
    project.lines = [createLine([token, createToken("last")])];
    const candidate = candidateFor(project, [
      { text: "abcdefghijx", startTimeMs: 950, endTimeMs: 980 },
      { text: "last", startTimeMs: 980, endTimeMs: 1000 },
      { text: "invalid", startTimeMs: Number.NaN, endTimeMs: 1000 },
      { text: "late", startTimeMs: 1010, endTimeMs: 2000 },
    ], 1000);
    const fragments = Object.values(candidate.fragments);
    expect(fragments).toHaveLength(3);
    fragments.forEach((fragment, index) => {
      expect(fragment.startTimeMs).toBeLessThan(fragment.endTimeMs);
      expect(fragment.endTimeMs).toBeLessThanOrEqual(1000);
      if (index > 0) expect(fragment.startTimeMs).toBeGreaterThanOrEqual(fragments[index - 1].endTimeMs);
    });
  });

  it("does not mistake unrelated short words for a recognized match", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("in");
    const candidate = candidateFor(project, [{ text: "is", startTimeMs: 1000, endTimeMs: 1300 }]);
    expect(Object.values(candidate.fragments)[0].confidence).toBe("estimated");
  });

  it("respects excluded lines when applying either mode", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("first\nsecond");
    const candidate = candidateFor(project, [
      { text: "first", startTimeMs: 1000, endTimeMs: 1300 },
      { text: "second", startTimeMs: 2000, endTimeMs: 2300 },
    ]);
    for (const mode of ["missing", "replace"] as const) {
      const applied = applyAutoTimingCandidate(project, candidate, { mode, includedLineIds: new Set([project.lines[1].id]) });
      expect(applied.lines[0]).toEqual(project.lines[0]);
      expect(applied.lines[1].tokens[0].fragments[0].startTimeMs).toBe(2000);
    }
  });

  it("matches repeated choruses in order using their existing line windows", () => {
    const project = createEmptyProject();
    project.lines = importPlainText(`stay with me
stay with me`);
    project.lines[0].startTimeMs = 4000;
    project.lines[0].endTimeMs = 5500;
    project.lines[1].startTimeMs = 8000;
    project.lines[1].endTimeMs = 9500;
    const words = [0, 4000, 8000].flatMap((offset) =>
      ["stay", "with", "me"].map((text, index) => ({
        text,
        startTimeMs: offset + index * 500,
        endTimeMs: offset + index * 500 + 400,
      }))
    );
    const candidate = candidateFor(project, words);
    expect(Object.values(candidate.fragments).map((fragment) => fragment.startTimeMs))
      .toEqual([4000, 4500, 5000, 8000, 8500, 9000]);
  });

  it("leaves a word unfilled when preserved neighbors leave no time for it", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("before missing after");
    const fragments = project.lines[0].tokens.map((token) => token.fragments[0]);
    fragments[0].startTimeMs = 1000;
    fragments[0].endTimeMs = 1500;
    fragments[2].startTimeMs = 1500;
    fragments[2].endTimeMs = 2000;
    const candidate = candidateFor(project, [
      { text: "before", startTimeMs: 1000, endTimeMs: 1500 },
      { text: "missing", startTimeMs: 1600, endTimeMs: 1700 },
      { text: "after", startTimeMs: 1800, endTimeMs: 2100 },
    ]);
    const filled = applyAutoTimingCandidate(project, candidate, { mode: "missing" });
    expect(filled.lines[0].tokens[1].fragments[0]).toEqual(fragments[1]);
  });

  it("rejects a later repeated lyric outside a trusted line instead of moving the line", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("stay with me\nstay with me");
    project.lines[0].startTimeMs = 1000;
    project.lines[0].endTimeMs = 3000;
    project.lines[1].startTimeMs = 8000;
    project.lines[1].endTimeMs = 10_000;
    const candidate = candidateFor(project, [
      { text: "stay", startTimeMs: 8000, endTimeMs: 8500 },
      { text: "with", startTimeMs: 8500, endTimeMs: 9000 },
      { text: "me", startTimeMs: 9000, endTimeMs: 9500 },
    ]);
    for (const token of project.lines[0].tokens) {
      const proposal = candidate.fragments[token.fragments[0].id];
      expect(proposal.confidence).toBe("estimated");
      expect(proposal.startTimeMs).toBeGreaterThanOrEqual(1000);
      expect(proposal.endTimeMs).toBeLessThanOrEqual(3000);
    }
    expect(candidate.fragments[project.lines[1].tokens[0].fragments[0].id].startTimeMs).toBe(8000);
  });

  it("does not collapse omitted words when a matching chorus starts after its line window", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("there is a road\nthere is a road");
    project.lines[0].startTimeMs = 1000;
    project.lines[0].endTimeMs = 4000;
    project.lines[1].startTimeMs = 4000;
    project.lines[1].endTimeMs = 7000;
    const candidate = candidateFor(project, [
      { text: "there", startTimeMs: 3900, endTimeMs: 4100 },
      { text: "is", startTimeMs: 4300, endTimeMs: 4500 },
      { text: "a", startTimeMs: 4500, endTimeMs: 4800 },
      { text: "road", startTimeMs: 5000, endTimeMs: 5500 },
    ]);
    expect(Object.values(candidate.fragments)).toHaveLength(8);
    for (const line of project.lines) {
      for (const token of line.tokens) {
        const proposal = candidate.fragments[token.fragments[0].id];
        expect(proposal.endTimeMs).toBeGreaterThan(proposal.startTimeMs);
        expect(proposal.startTimeMs).toBeGreaterThanOrEqual(line.startTimeMs!);
        expect(proposal.endTimeMs).toBeLessThanOrEqual(line.endTimeMs!);
      }
    }
  });

  it("retains usable manual timing when hallucinations only match far outside it", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("hold");
    const original = project.lines[0].tokens[0].fragments[0];
    original.startTimeMs = 1000;
    original.endTimeMs = 2000;
    const candidate = candidateFor(project, [{ text: "hold", startTimeMs: 8000, endTimeMs: 9000 }]);
    const applied = applyAutoTimingCandidate(project, candidate, { mode: "replace" });
    expect(applied.lines[0].tokens[0].fragments[0]).toEqual(original);
  });

  it("keeps a synthesized transcript endpoint estimated even with an exact lyric match", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("held");
    const candidate = buildAutoTimingCandidate({ project, projectFingerprint: "test", modelId: "fast", modelVersion: "test", durationMs: 5000,
      words: [{ text: "held", startTimeMs: 1000, endTimeMs: 1250, estimatedTiming: true }],
    });
    expect(Object.values(candidate.fragments)[0]).toMatchObject({ confidence: "estimated", method: "interpolated" });
  });

  it("does not treat an isolated short function word as support for an untimed phrase", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("under a silver sky");
    const candidate = candidateFor(project, [{ text: "a", startTimeMs: 8000, endTimeMs: 8300 }]);
    expect(Object.values(candidate.fragments).every((fragment) => fragment.confidence === "estimated")).toBe(true);
  });

  it("keeps separate evidence for repeated words sung by different speakers", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("echo\necho");
    project.lines[0].startTimeMs = 1000;
    project.lines[0].endTimeMs = 2000;
    project.lines[1].startTimeMs = 6000;
    project.lines[1].endTimeMs = 7000;
    project.lines[1].isSecondSpeaker = true;
    const candidate = candidateFor(project, [
      { text: "echo", startTimeMs: 1100, endTimeMs: 1600 },
      { text: "echo", startTimeMs: 6200, endTimeMs: 6800 },
    ]);
    expect(Object.values(candidate.fragments).map((fragment) => fragment.confidence)).toEqual(["high", "high"]);
    expect(candidate.supportedLineIds).toEqual(project.lines.map((line) => line.id));
  });

  it("marks a joined phrase estimated if one constituent was reused from another voice", () => {
    const project = createEmptyProject();
    const lead = createLine([createToken("hello")]);
    const background = createLine([createToken("hello world")]);
    background.isBackground = true;
    project.lines = [lead, background];
    const candidate = candidateFor(project, [
      { text: "hello", startTimeMs: 1000, endTimeMs: 1400 },
      { text: "world", startTimeMs: 1500, endTimeMs: 2000 },
    ]);
    expect(candidate.fragments[background.tokens[0].fragments[0].id]).toMatchObject({ startTimeMs: 1000, endTimeMs: 2000, confidence: "estimated" });
    expect(candidate.supportedLineIds).toEqual([lead.id]);
  });
});
