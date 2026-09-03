import { describe, expect, it, vi } from "vitest";
import { restoreCreatorDraftPlayback } from "../src/components/ReactComponents/LyricCreator/draftPlayback.ts";

const TRACK_URI = "spotify:track:0123456789ABCDEFGHIJKL";
const track = {
  uri: TRACK_URI,
  name: "Saved song",
  artists: ["Saved artist"],
  album: "Saved album",
  coverUrl: "",
  durationMs: 215000,
  isrc: "",
};

describe("Lyric Creator draft playback restoration", () => {
  it("loads and plays the saved Spotify track before returning its timing context", async () => {
    const resolveTrack = vi.fn().mockResolvedValue(track);
    const playUri = vi.fn().mockResolvedValue(undefined);

    await expect(
      restoreCreatorDraftPlayback(TRACK_URI, { resolveTrack, playUri })
    ).resolves.toEqual({ track, warning: null });
    expect(resolveTrack).toHaveBeenCalledWith(TRACK_URI);
    expect(playUri).toHaveBeenCalledWith(TRACK_URI);
  });

  it("keeps the draft usable when Spotify cannot restore its saved track", async () => {
    const resolveTrack = vi.fn().mockRejectedValue(new Error("offline"));
    const playUri = vi.fn();
    const warning = vi.spyOn(console, "warn").mockImplementation(() => undefined);

    await expect(
      restoreCreatorDraftPlayback(TRACK_URI, { resolveTrack, playUri })
    ).resolves.toEqual({
      track: null,
      warning: "Spotify could not restore this draft's saved track.",
    });
    expect(playUri).not.toHaveBeenCalled();
    warning.mockRestore();
  });
});
