import { describe, expect, it } from "vitest";
import { CreatorWorkspaceScrollTracker } from "../src/components/ReactComponents/LyricCreator/workspaceNavigation.ts";

describe("Lyric Creator workspace navigation", () => {
  it("restores the selected edit line after a Preview detour", () => {
    const tracker = new CreatorWorkspaceScrollTracker();

    expect(tracker.shouldScroll(2, "edit")).toBe(true);
    expect(tracker.shouldScroll(4, "preview")).toBe(false);
    expect(tracker.shouldScroll(2, "edit")).toBe(true);
  });
});
