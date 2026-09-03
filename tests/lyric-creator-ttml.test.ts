import { DOMImplementation, DOMParser, XMLSerializer } from "@xmldom/xmldom";
import { beforeAll, describe, expect, it } from "vitest";
import {
  createEmptyProject,
  createLine,
  createToken,
  syncLineTiming,
  type CreatorLine,
  type CreatorProject,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  CreatorValidationError,
  parseCreatorTTML,
  serializeCreatorTTML,
  validateCreatorProject,
} from "../src/components/ReactComponents/LyricCreator/ttml.ts";
import { ParseTTMLLocally } from "../src/utils/Lyrics/manager/parseTTML.ts";

const generatorOptions = {
  domImplementation: new DOMImplementation(),
  xmlSerializer: new XMLSerializer(),
};
const parserOptions = { domParser: new DOMParser() };

function timedLine(
  text: string,
  startTimeMs: number,
  endTimeMs: number,
  options: { background?: boolean; secondSpeaker?: boolean } = {}
): CreatorLine {
  const token = createToken(text);
  token.fragments[0].startTimeMs = startTimeMs;
  token.fragments[0].endTimeMs = endTimeMs;
  const line = createLine([token]);
  line.isBackground = options.background === true;
  line.isSecondSpeaker = options.secondSpeaker === true;
  return syncLineTiming(line);
}

function leadAndBackground(
  leadSecondSpeaker: boolean,
  backgroundSecondSpeaker: boolean
): CreatorProject {
  const project = createEmptyProject("spotify:track:aaaaaaaaaaaaaaaaaaaaaa");
  const lead = timedLine("Lead", 0, 1000, { secondSpeaker: leadSecondSpeaker });
  const background = timedLine("Back", 200, 800, {
    background: true,
    secondSpeaker: backgroundSecondSpeaker,
  });
  background.attachedToLineId = lead.id;
  project.lines = [lead, background];
  project.metadata = {
    ...project.metadata,
    name: "Agent matrix",
    artists: ["Artist one", "Artist two"],
    songwriters: ["Writer"],
    albums: ["Album"],
    spotifyTrackId: "aaaaaaaaaaaaaaaaaaaaaa",
    appleMusicTrackId: "12345",
    isrc: "USAAA0000001",
  };
  return project;
}

beforeAll(() => {
  Object.defineProperty(globalThis, "DOMParser", {
    value: DOMParser,
    configurable: true,
  });
});

describe("Lyric Creator TTML codec", () => {
  for (const leadSecondSpeaker of [false, true]) {
    for (const backgroundSecondSpeaker of [false, true]) {
      it(`round-trips lead ${leadSecondSpeaker ? "v2" : "v1"} with BG ${backgroundSecondSpeaker ? "v2" : "v1"}`, () => {
        const project = leadAndBackground(leadSecondSpeaker, backgroundSecondSpeaker);
        const raw = serializeCreatorTTML(project, generatorOptions);
        const parsed = parseCreatorTTML(raw, parserOptions);
        const [lead, background] = parsed.lines;

        expect(parsed.source).toMatchObject({ code: "ttml", label: "Local TTML" });
        expect(raw).toMatch(
          new RegExp(`ttm:role="x-bg"[^>]*ttm:agent="${backgroundSecondSpeaker ? "v2" : "v1"}"`)
        );
        expect(lead.isSecondSpeaker).toBe(leadSecondSpeaker);
        expect(background).toMatchObject({
          isBackground: true,
          isSecondSpeaker: backgroundSecondSpeaker,
          attachedToLineId: lead.id,
        });
        expect(parsed.metadata).toMatchObject({
          name: "Agent matrix",
          artists: ["Artist one", "Artist two"],
          songwriters: ["Writer"],
          albums: ["Album"],
          spotifyTrackId: "aaaaaaaaaaaaaaaaaaaaaa",
          appleMusicTrackId: "12345",
          isrc: "USAAA0000001",
        });
      });
    }
  }

  it("injects background agents in lead output order when attachment rows are reordered", () => {
    const project = createEmptyProject();
    const leadOne = timedLine("Lead one", 0, 1000);
    const leadTwo = timedLine("Lead two", 1000, 2000, { secondSpeaker: true });
    const backgroundForTwo = timedLine("Back two", 1200, 1800, {
      background: true,
      secondSpeaker: true,
    });
    const backgroundForOne = timedLine("Back one", 200, 800, {
      background: true,
      secondSpeaker: false,
    });
    backgroundForTwo.attachedToLineId = leadTwo.id;
    backgroundForOne.attachedToLineId = leadOne.id;
    project.lines = [leadOne, leadTwo, backgroundForTwo, backgroundForOne];

    const raw = serializeCreatorTTML(project, generatorOptions);
    const parsed = parseCreatorTTML(raw, parserOptions);

    expect(
      parsed.lines.map((line) => ({
        text: line.tokens[0].fragments[0].text,
        background: line.isBackground,
        speaker2: line.isSecondSpeaker,
      }))
    ).toEqual([
      { text: "Lead one", background: false, speaker2: false },
      { text: "Back one", background: true, speaker2: false },
      { text: "Lead two", background: false, speaker2: true },
      { text: "Back two", background: true, speaker2: true },
    ]);
  });

  it("round-trips independently timed fragments as one partial-word token", () => {
    const project = createEmptyProject();
    const line = timedLine("sing", 0, 400);
    const token = line.tokens[0];
    token.fragments = [
      { ...token.fragments[0], text: "sing", startTimeMs: 0, endTimeMs: 400 },
      {
        ...token.fragments[0],
        id: `${token.fragments[0].id}-suffix`,
        text: "ing",
        startTimeMs: 400,
        endTimeMs: 850,
      },
    ];
    token.boundaryAfter = "";
    syncLineTiming(line);
    project.lines = [line];

    const parsed = parseCreatorTTML(serializeCreatorTTML(project, generatorOptions), parserOptions);

    expect(parsed.lines[0].tokens).toHaveLength(1);
    expect(
      parsed.lines[0].tokens[0].fragments.map((fragment) => ({
        text: fragment.text,
        startTimeMs: fragment.startTimeMs,
        endTimeMs: fragment.endTimeMs,
      }))
    ).toEqual([
      { text: "sing", startTimeMs: 0, endTimeMs: 400 },
      { text: "ing", startTimeMs: 400, endTimeMs: 850 },
    ]);
  });

  it("rejects untimed fragments instead of coercing them", () => {
    const project = createEmptyProject();
    project.lines[0].tokens[0].fragments[0].text = "Untimed";
    expect(validateCreatorProject(project)[0]?.message).toContain("not been fully timed");
    expect(() => serializeCreatorTTML(project, generatorOptions)).toThrow(CreatorValidationError);
  });

  it("rejects stale or forward background attachments", () => {
    const project = createEmptyProject();
    const background = timedLine("Back", 0, 500, { background: true });
    const laterLead = timedLine("Lead", 500, 1000);
    background.attachedToLineId = laterLead.id;
    project.lines = [background, laterLead];

    expect(validateCreatorProject(project)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ message: expect.stringContaining("earlier lead") }),
      ])
    );

    background.attachedToLineId = "line-missing";
    expect(validateCreatorProject(project)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ message: expect.stringContaining("earlier lead") }),
      ])
    );
  });

  it("explicitly rejects multiple background rows for one lead", () => {
    const project = createEmptyProject();
    const lead = timedLine("Lead", 0, 1000);
    const first = timedLine("First", 100, 400, { background: true });
    const second = timedLine("Second", 500, 900, { background: true });
    first.attachedToLineId = lead.id;
    second.attachedToLineId = lead.id;
    project.lines = [lead, first, second];

    expect(validateCreatorProject(project)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ message: expect.stringContaining("second background row") }),
      ])
    );
  });

  it("preserves an explicit BG speaker override in Icy's installed-TTML parser", () => {
    const project = leadAndBackground(true, false);
    const raw = serializeCreatorTTML(project, generatorOptions);
    const parsed = ParseTTMLLocally(raw)?.Result;

    expect(parsed?.Content[0].OppositeAligned).toBe(true);
    expect(parsed?.Content[0].Background[0].OppositeAligned).toBeUndefined();
  });
});
