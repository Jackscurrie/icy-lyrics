import { DOMImplementation, DOMParser, XMLSerializer } from "@xmldom/xmldom";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  analyzeCreatorBackgroundAttachments,
  repairCreatorBackgroundAttachment,
} from "../src/components/ReactComponents/LyricCreator/backgroundAttachments.ts";
import { duplicateCreatorLine } from "../src/components/ReactComponents/LyricCreator/lineEditing.ts";
import {
  createEmptyProject,
  importPlainText,
  lineText,
  syncLineTiming,
  type CreatorProject,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  CreatorValidationError,
  parseCreatorTTML,
  serializeCreatorTTML,
  validateCreatorProject,
} from "../src/components/ReactComponents/LyricCreator/ttml.ts";

const generatorOptions = { domImplementation: new DOMImplementation(), xmlSerializer: new XMLSerializer() };
const parserOptions = { domParser: new DOMParser() };

function fixture(): CreatorProject {
  const project = { ...createEmptyProject(), lines: importPlainText("First lead\nBackground voice\nSecond lead") };
  project.lines.forEach((line, lineIndex) => {
    line.tokens.forEach((token, tokenIndex) => {
      token.fragments[0].startTimeMs = lineIndex * 1000 + tokenIndex * 250;
      token.fragments[0].endTimeMs = lineIndex * 1000 + tokenIndex * 250 + 200;
    });
    syncLineTiming(line);
  });
  project.lines[1].isBackground = true;
  project.lines[1].isSecondSpeaker = true;
  project.lines[1].attachedToLineId = project.lines[0].id;
  return project;
}

describe("editable background attachment warnings", () => {
  it("keeps a duplicate editable while immediately identifying its export conflict", () => {
    const project = fixture();
    const before = JSON.stringify(project);
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    const state = analyzeCreatorBackgroundAttachments(duplicate.project);
    expect(state.issues).toHaveLength(1);
    expect(state.issues[0]).toMatchObject({ lineIndex: 2, lineId: duplicate.lineId });
    expect(state.issues[0].message).toContain("second background row");
    expect(state.issues[0].message).toContain("Choose a different lead");
    expect(duplicate.project.lines).toHaveLength(4);
    expect(duplicate.project.lines[2].tokens).toEqual(expect.any(Array));
    expect(JSON.stringify(project)).toBe(before);
    expect(() => serializeCreatorTTML(duplicate.project, generatorOptions)).toThrow(CreatorValidationError);
  });

  it("reattaches a duplicate without changing any lyrics, timings, or second-speaker flags", () => {
    const project = fixture();
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    const before = JSON.stringify(duplicate.project);
    const repaired = repairCreatorBackgroundAttachment(duplicate.project, duplicate.lineId, project.lines[2].id)!;
    expect(repaired.lines[2]).toEqual({ ...duplicate.project.lines[2], attachedToLineId: project.lines[2].id });
    expect(repaired.lines[2].tokens).toBe(duplicate.project.lines[2].tokens);
    expect(repaired.lines.filter((line) => line.id !== duplicate.lineId)).toEqual(project.lines);
    expect(validateCreatorProject(repaired)).toEqual([]);
    expect(JSON.stringify(duplicate.project)).toBe(before);
    const loaded = parseCreatorTTML(serializeCreatorTTML(repaired, generatorOptions), parserOptions);
    const loadedBackgrounds = loaded.lines.filter((line) => line.isBackground);
    expect(loadedBackgrounds.map(lineText)).toEqual(["Background voice", "Background voice"]);
    expect(loadedBackgrounds.every((line) => line.isSecondSpeaker)).toBe(true);
    expect(loadedBackgrounds.map((line) => line.tokens.map((token) => token.fragments.map((fragment) => [fragment.startTimeMs, fragment.endTimeMs]))))
      .toEqual([project.lines[1], project.lines[1]].map((line) => line.tokens.map((token) => token.fragments.map((fragment) => [fragment.startTimeMs, fragment.endTimeMs]))));
  });

  it("promotes a duplicate to a lead without discarding its timings or speaker", () => {
    const project = fixture();
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    const repaired = repairCreatorBackgroundAttachment(duplicate.project, duplicate.lineId, null)!;
    expect(repaired.lines[2]).toEqual({ ...duplicate.project.lines[2], isBackground: false, attachedToLineId: null });
    expect(validateCreatorProject(repaired)).toEqual([]);
    const loaded = parseCreatorTTML(serializeCreatorTTML(repaired, generatorOptions), parserOptions);
    expect(loaded.lines).toHaveLength(4);
    expect(loaded.lines[2].isBackground).toBe(false);
    expect(loaded.lines[2].isSecondSpeaker).toBe(true);
  });

  it("also permits repairing the original row instead of deleting either duplicate", () => {
    const project = fixture();
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    const repaired = repairCreatorBackgroundAttachment(duplicate.project, project.lines[1].id, project.lines[2].id)!;
    expect(validateCreatorProject(repaired)).toEqual([]);
    expect(repaired.lines).toHaveLength(4);
    expect(repaired.lines[2].attachedToLineId).toBe(project.lines[0].id);
  });

  it("retains warnings rather than blocking selection of another occupied lead", () => {
    const project = fixture();
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    const retained = repairCreatorBackgroundAttachment(duplicate.project, duplicate.lineId, project.lines[0].id)!;
    expect(retained).not.toBeNull();
    expect(analyzeCreatorBackgroundAttachments(retained).issues).toHaveLength(1);
    expect(retained.lines).toHaveLength(4);
  });

  it("resolves implicit and forward explicit lead attachments with the same export rules", () => {
    const project = fixture();
    project.lines[1].attachedToLineId = null;
    expect(analyzeCreatorBackgroundAttachments(project).targetByLineId.get(project.lines[1].id)).toBe(project.lines[0].id);
    project.lines[1].attachedToLineId = project.lines[2].id;
    expect(analyzeCreatorBackgroundAttachments(project).targetByLineId.get(project.lines[1].id)).toBe(project.lines[2].id);
    expect(validateCreatorProject(project)).toEqual([]);
  });

  it("preserves other implicit attachments when converting a background row to a lead", () => {
    const project = fixture();
    const duplicate = duplicateCreatorLine(project, project.lines[1].id)!;
    duplicate.project.lines[2].attachedToLineId = null;
    const repaired = repairCreatorBackgroundAttachment(duplicate.project, project.lines[1].id, null)!;
    expect(repaired.lines[2].attachedToLineId).toBe(project.lines[0].id);
    expect(repaired.lines[2].tokens).toBe(duplicate.project.lines[2].tokens);
    expect(analyzeCreatorBackgroundAttachments(repaired).issues).toEqual([]);
  });

  it("repairs missing lead references and orphaned background rows", () => {
    const project = fixture();
    const background = project.lines[1];
    background.attachedToLineId = "missing-lead";
    expect(analyzeCreatorBackgroundAttachments(project).issues[0].message).toContain("must reference a lead");
    const repaired = repairCreatorBackgroundAttachment(project, background.id, project.lines[2].id)!;
    expect(validateCreatorProject(repaired)).toEqual([]);
    project.lines = [background];
    background.attachedToLineId = null;
    expect(analyzeCreatorBackgroundAttachments(project).issues[0].message).toContain("not attached");
    expect(validateCreatorProject(repairCreatorBackgroundAttachment(project, background.id, null)!)).toEqual([]);
  });

  it("rejects stale repair targets without mutating or dropping the project", () => {
    const project = fixture();
    const before = JSON.stringify(project);
    expect(repairCreatorBackgroundAttachment(project, "missing-line", project.lines[0].id)).toBeNull();
    expect(repairCreatorBackgroundAttachment(project, project.lines[1].id, "missing-lead")).toBeNull();
    expect(repairCreatorBackgroundAttachment(project, project.lines[1].id, project.lines[1].id)).toBeNull();
    expect(repairCreatorBackgroundAttachment(project, project.lines[0].id, null)).toBeNull();
    expect(JSON.stringify(project)).toBe(before);
  });

  it("wires accessible red warnings and brings failed exports to the repair controls", () => {
    const source = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/index.tsx", import.meta.url), "utf8");
    const css = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/styles.css", import.meta.url), "utf8");
    expect(source).toContain('aria-invalid={backgroundIssues.has(line.id)}');
    expect(source).toContain('id={`il-creator-line-warning-${line.id}`} role="status"');
    expect(source).toContain('setValidationFocus({ lineId: line.id })');
    expect(source).toContain('row?.querySelector<HTMLElement>("select[aria-invalid=\'true\']")');
    expect(css).toMatch(/\.il-creator-line-card\.has-validation-error\s*\{\s*border-color: #ff7189/);
    expect(css).toMatch(/\.il-creator-line-warning\s*\{[^}]*color: #ff9bad/);
  });
});
