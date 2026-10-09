import { describe, expect, it } from "vitest";
import { createEmptyProject, creatorProjectToLyrics, importPlainText } from "../src/components/ReactComponents/LyricCreator/model.ts";
import { applyCreatorProjectOffset } from "../src/components/ReactComponents/LyricCreator/projectOffset.ts";
import { applyTimingAction } from "../src/components/ReactComponents/LyricCreator/timing.ts";

function fixture() {
  const project = createEmptyProject();
  project.lines = importPlainText("first word\nbackground\nsecond speaker\nuntimed");
  project.lines.slice(0, 3).forEach((line, index) => {
    line.startTimeMs = 1000 + index * 1000;
    line.endTimeMs = 1800 + index * 1000;
    line.tokens[0].fragments[0].startTimeMs = line.startTimeMs;
    line.tokens[0].fragments[0].endTimeMs = line.endTimeMs;
  });
  project.lines[1].isBackground = true;
  project.lines[1].attachedToLineId = project.lines[0].id;
  project.lines[2].isSecondSpeaker = true;
  return project;
}

describe("whole-song creator timing offset", () => {
  it("shifts all voices and line/fragment bounds immediately without changing untimed text", () => {
    const source = fixture();
    const result = applyCreatorProjectOffset(source, 0, 150);
    expect(result.offsetMs).toBe(150);
    result.project.lines.slice(0, 3).forEach((line, index) => {
      expect(line.startTimeMs).toBe(1150 + index * 1000);
      expect(line.endTimeMs).toBe(1950 + index * 1000);
      expect(line.tokens[0].fragments[0].startTimeMs).toBe(line.startTimeMs);
    });
    expect(result.project.lines[3]).toEqual(source.lines[3]);
    expect(source.lines[0].startTimeMs).toBe(1000);
    const preview = creatorProjectToLyrics(result.project);
    expect(preview.Content[0].Lead.StartTime).toBe(1.15);
    expect(preview.Content[0].Background[0].StartTime).toBe(2.15);
    expect(preview.Content[1].Lead.StartTime).toBe(3.15);
  });

  it("applies the delta, not the full offset again, and can return exactly to zero", () => {
    const source = fixture();
    const first = applyCreatorProjectOffset(source, 0, 100);
    const second = applyCreatorProjectOffset(first.project, first.offsetMs, 150);
    expect(second.project.lines[0].startTimeMs).toBe(1150);
    expect(second.deltaMs).toBe(50);
    expect(applyCreatorProjectOffset(second.project, second.offsetMs, 0).project).toEqual(source);
  });

  it("limits a negative shift uniformly at zero instead of destroying word durations", () => {
    const source = fixture();
    const result = applyCreatorProjectOffset(source, 0, -2000);
    expect(result).toMatchObject({ offsetMs: -1000, deltaMs: -1000, limited: true });
    expect(result.project.lines[0].startTimeMs).toBe(0);
    expect(result.project.lines[0].endTimeMs).toBe(800);
    expect(result.project.lines[1].startTimeMs).toBe(1000);
    expect(applyCreatorProjectOffset(result.project, result.offsetMs, 0).project).toEqual(source);
  });

  it("retains partial and line-only timings", () => {
    const source = fixture();
    source.lines[0].tokens[0].fragments[0].endTimeMs = null;
    source.lines[2].tokens[0].fragments[0].startTimeMs = null;
    source.lines[2].tokens[0].fragments[0].endTimeMs = null;
    const result = applyCreatorProjectOffset(source, 0, -100).project;
    expect(result.lines[0].tokens[0].fragments[0].endTimeMs).toBeNull();
    expect(result.lines[2].startTimeMs).toBe(2900);
    expect(result.lines[2].tokens[0].fragments[0].startTimeMs).toBeNull();
  });

  it("keeps new F/G/H marks in the same offset coordinate system", () => {
    const shifted = applyCreatorProjectOffset(fixture(), 0, 200);
    const marked = applyTimingAction(shifted.project, 1, "start", 1800, { offsetMs: shifted.offsetMs });
    expect(marked.project.lines[0].tokens[1].fragments[0].startTimeMs).toBe(2000);
    expect(marked.project.lines[1].tokens[0].fragments[0].startTimeMs).toBe(2200);
  });

  it("has no mutation for identical or invalid values", () => {
    const source = fixture();
    expect(applyCreatorProjectOffset(source, 100, 100).project).toBe(source);
    for (const value of [NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER + 10]) {
      expect(applyCreatorProjectOffset(source, 0, value)).toMatchObject({ project: source, deltaMs: 0, limited: true });
    }
  });
});
