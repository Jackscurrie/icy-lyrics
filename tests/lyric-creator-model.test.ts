import { describe, expect, it, vi } from "vitest";
import {
  createEmptyProject,
  createLine,
  createToken,
  creatorProjectFromLyrics,
  creatorProjectToLyrics,
  importPlainText,
  lineText,
  moveCreatorTokenWithinLine,
} from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  applyCreatorPlaybackSpeed,
  applyTimingAction,
  classifyCreatorTimingTarget,
  creatorTimingErrorIndexes,
  creatorTimingPosition,
  creatorTimingTargets,
} from "../src/components/ReactComponents/LyricCreator/timing.ts";

describe("Lyric Creator plain-text model", () => {
  it("splits words only on backslashes and keeps exact boundary whitespace", () => {
    const [line] = importPlainText(String.raw`Hello  \world`);

    expect(line.tokens).toHaveLength(2);
    expect(line.tokens[0].fragments[0].text).toBe("Hello");
    expect(line.tokens[0].boundaryAfter).toBe("  ");
    expect(line.tokens[1].fragments[0].text).toBe("world");
    expect(lineText(line)).toBe("Hello  world");

    const [unsplit] = importPlainText("Hello world");
    expect(unsplit.tokens).toHaveLength(1);
    expect(unsplit.tokens[0].fragments[0].text).toBe("Hello world");
  });

  it("uses each input line as a lyric line and starts timing as null", () => {
    const lines = importPlainText(String.raw`First\line
Second\line`);

    expect(lines).toHaveLength(2);
    expect(lines.map(lineText)).toEqual(["First line", "Second line"]);
    expect(lines[0].tokens[0].fragments[0]).toMatchObject({
      startTimeMs: null,
      endTimeMs: null,
    });
  });

  it("uses the exact Local TTML source label for saved local lyrics", () => {
    const project = creatorProjectFromLyrics({
      uri: "spotify:track:aaaaaaaaaaaaaaaaaaaaaa",
      source: "ldb",
      Type: "Static",
      Content: ["Hello"],
    });

    expect(project.source).toMatchObject({ code: "ldb", label: "Local TTML" });
  });

  it("moves complete words within a line without losing fragments or separators", () => {
    const [line] = importPlainText(String.raw`one \two \three`);
    const movedId = line.tokens[0].id;

    expect(moveCreatorTokenWithinLine(line, 0, 2)).toBe(true);
    expect(line.tokens.map((token) => token.fragments[0].text)).toEqual(["two", "three", "one"]);
    expect(line.tokens[2].id).toBe(movedId);
    expect(lineText(line)).toBe("two three one");
    expect(moveCreatorTokenWithinLine(line, 2, 2)).toBe(false);
  });
});

describe("Lyric Creator timing keys", () => {
  it("accepts zero as a real F start time", () => {
    const project = createEmptyProject();
    const result = applyTimingAction(project, 0, "start", 0);

    expect(result.project.lines[0].tokens[0].fragments[0]).toMatchObject({
      startTimeMs: 0,
      endTimeMs: null,
    });
  });

  it("uses G to end the current fragment and start the next at the same instant", () => {
    const project = createEmptyProject();
    const first = project.lines[0].tokens[0].fragments[0];
    first.startTimeMs = 0;
    project.lines.push(createLine([createToken("next")]));

    const result = applyTimingAction(project, 0, "end-and-next", 1250);
    const targets = creatorTimingTargets(result.project);

    expect(targets[0].fragment.endTimeMs).toBe(1250);
    expect(targets[1].fragment.startTimeMs).toBe(1250);
    expect(result.targetIndex).toBe(1);
  });

  it("uses H to end without advancing", () => {
    const project = createEmptyProject();
    project.lines[0].tokens[0].fragments[0].startTimeMs = 500;

    const result = applyTimingAction(project, 0, "end", 900);

    expect(result.project.lines[0].tokens[0].fragments[0].endTimeMs).toBe(900);
    expect(result.targetIndex).toBe(0);
  });

  it("applies timing offset and clamps the effective time at zero", () => {
    const project = createEmptyProject();
    expect(
      applyTimingAction(project, 0, "start", 1000, { offsetMs: 125 }).project.lines[0].tokens[0]
        .fragments[0].startTimeMs
    ).toBe(1125);
    expect(creatorTimingPosition(80, -100)).toBe(0);
  });

  it("can skip background rows while G advances through the filtered target list", () => {
    const project = createEmptyProject();
    project.lines[0].tokens[0].fragments[0].text = "lead";
    const background = createLine([createToken("background")]);
    background.isBackground = true;
    project.lines.push(background, createLine([createToken("next lead")]));

    const result = applyTimingAction(project, 0, "end-and-next", 500, {
      ignoreBackground: true,
    });
    const filtered = creatorTimingTargets(result.project, { ignoreBackground: true });

    expect(filtered).toHaveLength(2);
    expect(filtered[1].fragment.text).toBe("next lead");
    expect(filtered[1].fragment.startTimeMs).toBe(500);
    expect(result.project.lines[1].tokens[0].fragments[0].startTimeMs).toBeNull();
  });

  it("classifies incomplete, invalid, and overlapping target timing", () => {
    const project = createEmptyProject();
    project.lines[0].tokens.push(createToken("second"), createToken("third"));
    const targets = creatorTimingTargets(project);
    targets[0].fragment.startTimeMs = 100;
    targets[1].fragment.startTimeMs = 300;
    targets[1].fragment.endTimeMs = 300;
    targets[2].fragment.startTimeMs = 250;
    targets[2].fragment.endTimeMs = 400;

    expect(classifyCreatorTimingTarget(targets, 0)).toBe("partial");
    expect(classifyCreatorTimingTarget(targets, 1)).toBe("invalid");
    expect(classifyCreatorTimingTarget(targets, 2)).toBe("overlap");
    expect([...creatorTimingErrorIndexes(project)]).toEqual([0, 1, 2]);
  });

  it("does not report expected overlap between separate lead and background rows", () => {
    const project = createEmptyProject();
    const lead = project.lines[0].tokens[0].fragments[0];
    lead.startTimeMs = 100;
    lead.endTimeMs = 1000;
    const background = createLine([createToken("echo")]);
    background.isBackground = true;
    background.tokens[0].fragments[0].startTimeMs = 300;
    background.tokens[0].fragments[0].endTimeMs = 700;
    project.lines.push(background);

    const targets = creatorTimingTargets(project);
    expect(classifyCreatorTimingTarget(targets, 1)).toBe("timed");
  });
});

describe("Lyric Creator preview conversion", () => {
  it("keeps a completely untimed draft visible as static lyrics", () => {
    const project = createEmptyProject();
    project.lines[0].tokens[0].fragments[0].text = "Visible draft";

    expect(creatorProjectToLyrics(project)).toMatchObject({
      Type: "Static",
      Lines: [{ Text: "Visible draft" }],
    });
  });

  it("synthesizes preview word intervals from line-synced source timing", () => {
    const project = createEmptyProject();
    project.lines[0] = createLine([createToken("one"), createToken("two")]);
    project.lines[0].startTimeMs = 10_000;
    project.lines[0].endTimeMs = 14_000;

    const lyrics = creatorProjectToLyrics(project);
    expect(lyrics.Type).toBe("Syllable");
    expect(lyrics.Content[0].Lead.Syllables).toEqual([
      expect.objectContaining({ Text: "one", StartTime: 10, EndTime: 12 }),
      expect.objectContaining({ Text: "two", StartTime: 12, EndTime: 14 }),
    ]);
  });
});

describe("Lyric Creator playback-speed adapter", () => {
  it("reports a confirmed supported speed", async () => {
    let observed = 1;
    const setSpeed = vi.fn((value: number) => {
      observed = value;
    });

    const result = await applyCreatorPlaybackSpeed(0.75, {
      setSpeed,
      readSpeed: () => observed,
      mediaType: () => "episode",
      wait: async () => undefined,
    });

    expect(setSpeed).toHaveBeenCalledWith(0.75);
    expect(result).toMatchObject({ applied: true, observed: 0.75 });
  });

  it("does not claim Spotify music changed speed when it remains at 1x", async () => {
    const result = await applyCreatorPlaybackSpeed(0.5, {
      setSpeed: vi.fn(),
      readSpeed: () => 1,
      mediaType: () => "track",
      wait: async () => undefined,
    });

    expect(result.applied).toBe(false);
    expect(result.message).toContain("limits its exposed speed control for music tracks");
  });
});
