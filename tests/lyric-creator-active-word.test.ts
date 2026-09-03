import { describe, expect, it } from "vitest";
import {
  creatorPlaybackActivity,
  isCreatorPlaybackIntervalActive,
} from "../src/components/ReactComponents/LyricCreator/activeWord.ts";
import {
  createEmptyProject,
  createFragment,
  createLine,
  createToken,
} from "../src/components/ReactComponents/LyricCreator/model.ts";

function timedFragment(id: string, startTimeMs: number | null, endTimeMs: number | null) {
  return { ...createFragment(id), id, startTimeMs, endTimeMs };
}

describe("Lyric Creator playback activity", () => {
  it("uses start-inclusive and end-exclusive intervals", () => {
    expect(isCreatorPlaybackIntervalActive(1_000, 2_000, 999)).toBe(false);
    expect(isCreatorPlaybackIntervalActive(1_000, 2_000, 1_000)).toBe(true);
    expect(isCreatorPlaybackIntervalActive(1_000, 2_000, 1_999)).toBe(true);
    expect(isCreatorPlaybackIntervalActive(1_000, 2_000, 2_000)).toBe(false);
  });

  it("does not highlight incomplete, invalid, or zero-duration timings", () => {
    expect(isCreatorPlaybackIntervalActive(1_000, null, 1_500)).toBe(false);
    expect(isCreatorPlaybackIntervalActive(null, 2_000, 1_500)).toBe(false);
    expect(isCreatorPlaybackIntervalActive(2_000, 1_000, 1_500)).toBe(false);
    expect(isCreatorPlaybackIntervalActive(1_000, 1_000, 1_000)).toBe(false);
    expect(isCreatorPlaybackIntervalActive(Number.NaN, 2_000, 1_500)).toBe(false);
  });

  it("moves cleanly from one adjacent fragment to the next", () => {
    const project = createEmptyProject();
    const first = timedFragment("first", 1_000, 2_000);
    const second = timedFragment("second", 2_000, 3_000);
    const firstToken = { ...createToken(), id: "first-token", fragments: [first] };
    const secondToken = { ...createToken(), id: "second-token", fragments: [second] };
    project.lines = [{ ...createLine(), id: "line", tokens: [firstToken, secondToken] }];

    const activity = creatorPlaybackActivity(project, 2_000);

    expect([...activity.fragmentIds]).toEqual(["second"]);
    expect([...activity.tokenIds]).toEqual(["second-token"]);
    expect([...activity.lineIds]).toEqual(["line"]);
  });

  it("marks a split word once while exposing its active fragment", () => {
    const project = createEmptyProject();
    const token = {
      ...createToken(),
      id: "split-word",
      fragments: [timedFragment("frag-a", 500, 700), timedFragment("frag-b", 700, 1_000)],
    };
    project.lines = [{ ...createLine(), id: "split-line", tokens: [token] }];

    const activity = creatorPlaybackActivity(project, 750);

    expect([...activity.fragmentIds]).toEqual(["frag-b"]);
    expect([...activity.tokenIds]).toEqual(["split-word"]);
    expect([...activity.lineIds]).toEqual(["split-line"]);
  });

  it("supports simultaneous lead and background vocal lanes", () => {
    const project = createEmptyProject();
    const lead = {
      ...createToken(),
      id: "lead-token",
      fragments: [timedFragment("lead-fragment", 1_000, 3_000)],
    };
    const background = {
      ...createToken(),
      id: "background-token",
      fragments: [timedFragment("background-fragment", 1_500, 2_500)],
    };
    project.lines = [
      { ...createLine(), id: "lead-line", tokens: [lead] },
      {
        ...createLine(),
        id: "background-line",
        isBackground: true,
        tokens: [background],
      },
    ];

    const activity = creatorPlaybackActivity(project, 2_000);

    expect([...activity.fragmentIds]).toEqual(["lead-fragment", "background-fragment"]);
    expect([...activity.tokenIds]).toEqual(["lead-token", "background-token"]);
    expect([...activity.lineIds]).toEqual(["lead-line", "background-line"]);
  });

  it("returns no activity for a non-finite or negative playhead", () => {
    const project = createEmptyProject();
    project.lines[0].tokens[0].fragments[0] = timedFragment("fragment", 0, 1_000);

    expect(creatorPlaybackActivity(project, -1).fragmentIds.size).toBe(0);
    expect(creatorPlaybackActivity(project, Number.NaN).fragmentIds.size).toBe(0);
  });
});
