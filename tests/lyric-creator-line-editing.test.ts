import { describe, expect, it, vi } from "vitest";
import {
  createEmptyProject,
  importPlainText,
  type CreatorProject,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  duplicateCreatorLine,
  moveCreatorLine,
} from "../src/components/ReactComponents/LyricCreator/lineEditing.ts";

function fixture(): CreatorProject {
  return { ...createEmptyProject(), lines: importPlainText("First line\nSecond line\nThird line\nFinal line") };
}

describe("Lyric Creator whole-line movement", () => {
  it.each([
    [0, 3, "after", [1, 2, 3, 0]],
    [3, 0, "before", [3, 0, 1, 2]],
    [0, 2, "before", [1, 0, 2, 3]],
    [3, 1, "after", [0, 1, 3, 2]],
    [1, 2, "after", [0, 2, 1, 3]],
    [2, 1, "before", [0, 2, 1, 3]],
  ] as const)("moves row %i %s %s with stable identities", (from, to, placement, order) => {
    const project = fixture();
    const originalLines = [...project.lines];
    const original = JSON.stringify(project);
    const result = moveCreatorLine(project, project.lines[from].id, project.lines[to].id, placement)!;

    expect(result.lines).toEqual(order.map((index) => originalLines[index]));
    result.lines.forEach((line, index) => expect(line).toBe(originalLines[order[index]]));
    expect(result).not.toBe(project);
    expect(result.lines).not.toBe(project.lines);
    expect(JSON.stringify(project)).toBe(original);
  });

  it("does not alter vocal attachments, flags, timings, or other row order", () => {
    const project = fixture();
    const background = project.lines[2];
    background.isBackground = true;
    background.isSecondSpeaker = true;
    background.attachedToLineId = project.lines[0].id;
    background.startTimeMs = 0;
    background.endTimeMs = 250;
    background.tokens[0].fragments[0].startTimeMs = 0;
    background.tokens[0].fragments[0].endTimeMs = 150;
    const before = JSON.stringify(background);

    const result = moveCreatorLine(project, background.id, project.lines[0].id, "before")!;
    expect(JSON.stringify(result.lines[0])).toBe(before);
    expect(result.lines.slice(1)).toEqual([project.lines[0], project.lines[1], project.lines[3]]);
  });

  it("returns null for invalid targets and moves that already have the desired order", () => {
    const project = fixture();
    const [first, second] = project.lines;
    expect(moveCreatorLine(project, first.id, first.id, "before")).toBeNull();
    expect(moveCreatorLine(project, first.id, second.id, "before")).toBeNull();
    expect(moveCreatorLine(project, second.id, first.id, "after")).toBeNull();
    expect(moveCreatorLine(project, "missing", second.id, "after")).toBeNull();
    expect(moveCreatorLine(project, first.id, "missing", "before")).toBeNull();
    expect(moveCreatorLine({ ...project, lines: [] }, first.id, second.id, "before")).toBeNull();
  });
});

describe("Lyric Creator duplicate line", () => {
  it("copies text, all timings, spacing, flags, and vocal attachment immediately below the source", () => {
    const project = fixture();
    const source = project.lines[1];
    source.tokens = importPlainText(String.raw`Sec\ond  line`)[0].tokens;
    source.isBackground = true;
    source.isSecondSpeaker = true;
    source.attachedToLineId = project.lines[0].id;
    source.startTimeMs = 0;
    source.endTimeMs = 1500;
    source.tokens[0].fragments[0].startTimeMs = 0;
    source.tokens[0].fragments[0].endTimeMs = 250;
    source.tokens[0].fragments[1].startTimeMs = 250;
    source.tokens[0].fragments[1].endTimeMs = 1000;
    source.tokens[1].fragments[0].startTimeMs = 1000;
    source.tokens[1].fragments[0].endTimeMs = 1500;
    const before = JSON.stringify(project);

    const result = duplicateCreatorLine(project, source.id)!;
    const duplicate = result.project.lines[2];
    expect(duplicate.id).toBe(result.lineId);
    expect(result.project.lines.filter((line) => line.id !== result.lineId)).toEqual(project.lines);
    expect(duplicate).toEqual({
      ...source,
      id: result.lineId,
      tokens: source.tokens.map((token, index) => ({
        ...token,
        id: duplicate.tokens[index].id,
        fragments: token.fragments.map((fragment, fragmentIndex) => ({
          ...fragment,
          id: duplicate.tokens[index].fragments[fragmentIndex].id,
        })),
      })),
    });
    expect(JSON.stringify(project)).toBe(before);
    expect(duplicate.tokens).not.toBe(source.tokens);
    duplicate.tokens.forEach((token, index) => {
      expect(token).not.toBe(source.tokens[index]);
      expect(token.fragments).not.toBe(source.tokens[index].fragments);
      token.fragments.forEach((fragment, fragmentIndex) => {
        expect(fragment).not.toBe(source.tokens[index].fragments[fragmentIndex]);
      });
    });
    const ids = result.project.lines.flatMap((line) => [
      line.id,
      ...line.tokens.flatMap((token) => [token.id, ...token.fragments.map((fragment) => fragment.id)]),
    ]);
    expect(new Set(ids).size).toBe(ids.length);

    duplicate.tokens[0].fragments[0].text = "Changed";
    duplicate.tokens[0].fragments[0].startTimeMs = 99;
    expect(JSON.stringify(project)).toBe(before);
  });

  it("retains null timings and can duplicate the first and final rows", () => {
    const project = fixture();
    const first = duplicateCreatorLine(project, project.lines[0].id)!;
    expect(first.project.lines[1].id).toBe(first.lineId);
    expect(first.project.lines[1].startTimeMs).toBeNull();
    expect(first.project.lines[1].tokens[0].fragments[0].endTimeMs).toBeNull();
    const last = duplicateCreatorLine(first.project, project.lines.at(-1)!.id)!;
    expect(last.project.lines.at(-1)!.id).toBe(last.lineId);
    expect(last.project.lines.at(-2)!.id).toBe(project.lines.at(-1)!.id);
  });

  it("does not duplicate attached background rows or retarget existing attachments", () => {
    const project = fixture();
    project.lines[1].isBackground = true;
    project.lines[1].attachedToLineId = project.lines[0].id;
    const result = duplicateCreatorLine(project, project.lines[0].id)!;
    expect(result.project.lines).toHaveLength(project.lines.length + 1);
    expect(result.project.lines[2]).toBe(project.lines[1]);
    expect(result.project.lines[2].attachedToLineId).toBe(project.lines[0].id);
  });

  it("drops an invalid self-attachment from the duplicate without mutating the source", () => {
    const project = fixture();
    project.lines[0].attachedToLineId = project.lines[0].id;
    const result = duplicateCreatorLine(project, project.lines[0].id)!;
    expect(result.project.lines[1].attachedToLineId).toBeNull();
    expect(project.lines[0].attachedToLineId).toBe(project.lines[0].id);
  });

  it("returns null for a missing row without altering the project", () => {
    const project = fixture();
    const before = JSON.stringify(project);
    expect(duplicateCreatorLine(project, "missing")).toBeNull();
    expect(duplicateCreatorLine({ ...project, lines: [] }, "missing")).toBeNull();
    expect(JSON.stringify(project)).toBe(before);
  });

  it("still produces unique IDs when an ID generator repeats", () => {
    const project = fixture();
    const uuid = "00000000-0000-4000-8000-000000000000";
    const stub = vi.spyOn(globalThis.crypto, "randomUUID").mockReturnValue(uuid);
    try {
      const first = duplicateCreatorLine(project, project.lines[0].id)!;
      const second = duplicateCreatorLine(first.project, first.lineId)!;
      const ids = second.project.lines.flatMap((line) => [
        line.id,
        ...line.tokens.flatMap((token) => [token.id, ...token.fragments.map((fragment) => fragment.id)]),
      ]);
      expect(new Set(ids).size).toBe(ids.length);
    } finally {
      stub.mockRestore();
    }
  });
});
