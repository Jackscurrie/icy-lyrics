import { describe, expect, it, vi } from "vitest";
import {
  canonicalSpotifyTrackUrl,
  creatorAudioHandoffTrackUrl,
  openCreatorLucidaHandoff,
} from "../src/components/ReactComponents/LyricCreator/localAudioHandoff.ts";
import { CreatorTaskScope } from "../src/components/ReactComponents/LyricCreator/autoTiming/session.ts";

const firstId = "0123456789ABCDEFGHIJKL";
const secondId = "1234567890ABCDEFGHIJKL";
const firstUrl = `https://open.spotify.com/track/${firstId}`;

describe("Creator manual local-audio handoff", () => {
  it("turns an exact Spotify track URI into a canonical web link", () => {
    expect(canonicalSpotifyTrackUrl(`spotify:track:${firstId}`)).toBe(firstUrl);
    expect(canonicalSpotifyTrackUrl(`${firstUrl}?si=share#fragment`)).toBe(firstUrl);
  });

  it.each([
    `spotify:local:artist:album:song:123`,
    `spotify:episode:${firstId}`,
    `spotify:track:${firstId}:extra`,
    `spotify:track:short`,
    `https://open.spotify.com.evil.example/track/${firstId}`,
    `https://name@open.spotify.com/track/${firstId}`,
    `https://open.spotify.com/track/${firstId}/extra`,
  ])("does not link unsupported or malformed identities: %s", (value) => {
    expect(canonicalSpotifyTrackUrl(value)).toBeNull();
  });

  it("uses project identity before metadata or the separately selected track", () => {
    expect(creatorAudioHandoffTrackUrl({
      projectUri: `spotify:track:${firstId}`,
      metadataTrackId: secondId,
      selectedTrackUri: `spotify:track:${secondId}`,
    })).toBe(firstUrl);
    expect(creatorAudioHandoffTrackUrl({
      metadataTrackId: firstId,
      selectedTrackUri: `spotify:track:${secondId}`,
    })).toBe(firstUrl);
    expect(creatorAudioHandoffTrackUrl({ selectedTrackUri: `spotify:track:${firstId}` })).toBe(firstUrl);
    expect(creatorAudioHandoffTrackUrl({ projectUri: "spotify:local:artist:album:track:123" })).toBeNull();
    expect(creatorAudioHandoffTrackUrl({})).toBeNull();
  });

  it("only opens the fixed Lucida homepage and copies the selected track link", async () => {
    const copy = vi.fn().mockResolvedValue(undefined);
    const open = vi.fn().mockReturnValue(null);
    const pending = openCreatorLucidaHandoff(firstUrl, { copy, open });
    expect(copy).toHaveBeenCalledWith(firstUrl);
    expect(open).toHaveBeenCalledWith("https://lucida.to/");
    await expect(pending).resolves.toEqual({ trackUrl: firstUrl, copied: true, openRequested: true });
  });

  it("reports clipboard and browser failures separately for manual recovery", async () => {
    await expect(openCreatorLucidaHandoff(firstUrl, {
      copy: () => { throw new Error("Clipboard unavailable"); },
      open: () => undefined,
    })).resolves.toMatchObject({ copied: false, openRequested: true });
    await expect(openCreatorLucidaHandoff(firstUrl, {
      copy: async () => undefined,
      open: () => { throw new Error("Browser unavailable"); },
    })).resolves.toMatchObject({ copied: true, openRequested: false });
  });

  it("never copies or opens an unsupported project identity", async () => {
    const copy = vi.fn();
    const open = vi.fn();
    await expect(openCreatorLucidaHandoff("spotify:local:a:b:c:123", { copy, open })).rejects.toThrow("Choose a standard Spotify track");
    expect(copy).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("ignores a clipboard completion after the Creator session is replaced or closed", async () => {
    const scope = new CreatorTaskScope();
    const task = scope.begin();
    let completeClipboard!: () => void;
    const report = vi.fn();
    const pending = openCreatorLucidaHandoff(firstUrl, {
      copy: () => new Promise<void>((resolve) => { completeClipboard = resolve; }),
      open: () => undefined,
    }).then((result) => {
      if (task.isCurrent()) report(result);
    });
    scope.cancel();
    completeClipboard();
    await pending;
    expect(report).not.toHaveBeenCalled();
  });
});
