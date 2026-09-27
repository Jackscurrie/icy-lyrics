import { describe, expect, it } from "vitest";
import { DOMImplementation, DOMParser, XMLSerializer } from "@xmldom/xmldom";
import {
  cloneCreatorProject, createEmptyProject, createFragment, createLine, createToken,
  importPlainText, lineText, syncLineTiming,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import { applyCreatorFragmentationProposal, splitCreatorWordOnBackslashes } from "../src/components/ReactComponents/LyricCreator/fragmentation.ts";
import { creatorTimingTargets, nextCreatorTimingWordIndex } from "../src/components/ReactComponents/LyricCreator/timing.ts";
import { parseCreatorTTML, serializeCreatorTTML } from "../src/components/ReactComponents/LyricCreator/ttml.ts";

describe("Creator word fragmentation", () => {
  it("imports spaces as words, slashes as fragments, and newlines as lines", () => {
    const lines = importPlainText("for\\ev\\er  to\\gether\twe\r\nsing again");
    expect(lines).toHaveLength(2);
    expect(lines[0].tokens.map((token) => token.fragments.map((fragment) => fragment.text)))
      .toEqual([["for", "ev", "er"], ["to", "gether"], ["we"]]);
    expect(lines.map(lineText)).toEqual(["forever  together\twe", "sing again"]);
    expect(lines[0].tokens.flatMap((token) => token.fragments).every((fragment) => fragment.startTimeMs === null)).toBe(true);
  });

  it("ignores empty slash segments without inventing blank fragments", () => {
    const lines = importPlainText(String.raw`\for\\ever\ \ \\ together`);
    expect(lines[0].tokens.map((token) => token.fragments.map((fragment) => fragment.text)))
      .toEqual([["for", "ever"], ["together"]]);
    expect(importPlainText("\\ \\\\\n\n")).toEqual([]);
  });

  it("fragments an edited word and retains its outer timings without inventing internal times", () => {
    const project = createEmptyProject("spotify:track:aaaaaaaaaaaaaaaaaaaaaa");
    const token = createToken(String.raw`for\ev\er`);
    token.fragments[0].startTimeMs = 0;
    token.fragments[0].endTimeMs = 600;
    token.boundaryAfter = " ";
    project.lines = [createLine([token, createToken("forever"), createToken("Forever"), createToken("forever!")])];
    const checkpoint = JSON.stringify(project);
    const result = splitCreatorWordOnBackslashes(project, token.id)!;
    expect(result.project.lines[0].tokens[0].fragments).toMatchObject([
      { text: "for", startTimeMs: 0, endTimeMs: null },
      { text: "ev", startTimeMs: null, endTimeMs: null },
      { text: "er", startTimeMs: null, endTimeMs: 600 },
    ]);
    expect(result.project.lines[0].tokens[0].boundaryAfter).toBe(" ");
    expect(result.proposal?.matchingTokenIds).toEqual([project.lines[0].tokens[1].id]);
    expect(JSON.stringify(project)).toBe(checkpoint);
  });

  it("keeps unchanged fragment IDs and boundary timings on a further split", () => {
    const project = createEmptyProject();
    const token = createToken();
    const first = { ...createFragment("for"), startTimeMs: 100, endTimeMs: 200 };
    token.fragments = [first, { ...createFragment(String.raw`ev\er`), startTimeMs: 200, endTimeMs: 500 }];
    project.lines = [createLine([token])];
    const result = splitCreatorWordOnBackslashes(project, token.id)!;
    expect(result.project.lines[0].tokens[0].fragments).toMatchObject([
      first,
      { text: "ev", startTimeMs: 200, endTimeMs: null },
      { text: "er", startTimeMs: null, endTimeMs: 500 },
    ]);
    expect(result.proposal).toBeNull();
  });

  it("applies a suggestion only to exact matching words and preserves flags, joins and outer timings", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("forever together\nforever");
    const source = project.lines[0].tokens[0];
    source.fragments[0].text = String.raw`for\ev\er`;
    const matching = project.lines[1].tokens[0];
    matching.fragments[0].startTimeMs = 2000;
    matching.fragments[0].endTimeMs = 2700;
    project.lines[1].isBackground = true;
    project.lines[1].isSecondSpeaker = true;
    project.lines[1].attachedToLineId = project.lines[0].id;
    const result = splitCreatorWordOnBackslashes(project, source.id)!;
    const applied = applyCreatorFragmentationProposal(result.project, result.proposal!)!;
    expect(applied.lines[1]).toMatchObject({ isBackground: true, isSecondSpeaker: true, attachedToLineId: project.lines[0].id });
    expect(applied.lines[1].tokens[0].fragments).toMatchObject([
      { text: "for", startTimeMs: 2000, endTimeMs: null },
      { text: "ev", startTimeMs: null, endTimeMs: null },
      { text: "er", startTimeMs: null, endTimeMs: 2700 },
    ]);
    expect(applied.lines.map(lineText)).toEqual(["forever together", "forever"]);
    const ids = applied.lines.flatMap((line) => line.tokens.flatMap((token) => token.fragments.map((fragment) => fragment.id)));
    expect(new Set(ids).size).toBe(ids.length);
    expect(applyCreatorFragmentationProposal(applied, result.proposal!)).toBeNull();
  });

  it("rejects suggestions after a project switch or further edit", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("forever forever");
    project.lines[0].tokens[0].fragments[0].text = String.raw`for\ever`;
    const result = splitCreatorWordOnBackslashes(project, project.lines[0].tokens[0].id)!;
    const changed = cloneCreatorProject(result.project);
    changed.metadata.name = "Edited after suggestion";
    expect(applyCreatorFragmentationProposal(changed, result.proposal!)).toBeNull();
    changed.metadata.name = result.project.metadata.name;
    changed.uri = "spotify:local:another:track";
    expect(applyCreatorFragmentationProposal(changed, result.proposal!)).toBeNull();
    expect(splitCreatorWordOnBackslashes(createEmptyProject(), "missing")).toBeNull();
  });

  it("exports adjacent fragments without spaces and retains spaces between words", () => {
    const project = createEmptyProject();
    project.lines = importPlainText(String.raw`for\ev\er together`);
    creatorTimingTargets(project).forEach(({ fragment }, index) => {
      fragment.startTimeMs = index * 500;
      fragment.endTimeMs = (index + 1) * 500;
    });
    project.lines.forEach(syncLineTiming);
    const ttml = serializeCreatorTTML(project, { domImplementation: new DOMImplementation(), xmlSerializer: new XMLSerializer() });
    const parsed = parseCreatorTTML(ttml, { domParser: new DOMParser() });
    expect(lineText(parsed.lines[0])).toBe("forever together");
    expect(parsed.lines[0].tokens[0].fragments.map((fragment) => fragment.text)).toEqual(["for", "ev", "er"]);
  });
});

describe("Creator selection-only timing navigation", () => {
  it("skips remaining fragments in a word, advances across lines, and stops at the end", () => {
    const project = createEmptyProject();
    project.lines = importPlainText(String.raw`for\ev\er together` + "\nwe sing");
    const checkpoint = JSON.stringify(project);
    const targets = creatorTimingTargets(project);
    expect(nextCreatorTimingWordIndex(targets, 0)).toBe(3);
    expect(nextCreatorTimingWordIndex(targets, 1)).toBe(3);
    expect(nextCreatorTimingWordIndex(targets, 3)).toBe(4);
    expect(nextCreatorTimingWordIndex(targets, 4)).toBe(5);
    expect(nextCreatorTimingWordIndex(targets, 5)).toBe(5);
    expect(nextCreatorTimingWordIndex([], 0)).toBe(0);
    expect(JSON.stringify(project)).toBe(checkpoint);
  });

  it("honors the ignored-background target list and does not write timings", () => {
    const project = createEmptyProject();
    project.lines = importPlainText("lead\nbackground\nnext line");
    project.lines[1].isBackground = true;
    const targets = creatorTimingTargets(project, { ignoreBackground: true });
    expect(targets[nextCreatorTimingWordIndex(targets, 0)].lineIndex).toBe(2);
    expect(targets.every(({ fragment }) => fragment.startTimeMs === null && fragment.endTimeMs === null)).toBe(true);
  });
});
