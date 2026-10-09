import { describe, expect, it } from "vitest";
import { shouldReplaceSpotifyControl } from "../src/utils/SpotifyControlReplacement.ts";

describe("native playbar control replacement", () => {
  it("replaces only fullscreen and an enabled Icy PiP equivalent", () => {
    expect(shouldReplaceSpotifyControl("fullscreen-mode-button", false)).toBe(true);
    expect(shouldReplaceSpotifyControl("pip-toggle-button", true)).toBe(true);
    expect(shouldReplaceSpotifyControl("pip-toggle-button", false)).toBe(false);
  });

  it.each([
    undefined, "queue-button", "lyrics-button", "control-button-playpause",
    "volume-bar-toggle-mute-button", "connect-device-button", "theme-control",
  ])("does not hide unrelated %s controls, even if they use control-button", (testId) => {
    expect(shouldReplaceSpotifyControl(testId, true)).toBe(false);
  });
});
