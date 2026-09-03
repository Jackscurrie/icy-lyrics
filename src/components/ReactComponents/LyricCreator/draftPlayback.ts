import type { CreatorTrack } from "./data.ts";

export interface CreatorDraftPlaybackResult {
  track: CreatorTrack | null;
  warning: string | null;
}

function savedSpotifyTrackUri(uri: string): string | null {
  const candidate = uri.trim();
  return /^spotify:track:[A-Za-z0-9]{22}$/u.test(candidate) ? candidate : null;
}

/**
 * Restores a draft's Spotify context before its timing UI consumes the player
 * clock. Failures are intentionally returned as warnings: the saved project
 * remains editable even when Spotify cannot load its old track.
 */
export async function restoreCreatorDraftPlayback(
  draftUri: string,
  services: {
    resolveTrack: (uri: string) => Promise<CreatorTrack | null>;
    playUri: (uri: string) => void | Promise<void>;
  }
): Promise<CreatorDraftPlaybackResult> {
  const uri = savedSpotifyTrackUri(draftUri);
  if (!uri) return { track: null, warning: null };

  try {
    const track = await services.resolveTrack(uri);
    if (!track) {
      return { track: null, warning: "Spotify could not load this draft's saved track." };
    }
    await services.playUri(track.uri);
    return { track, warning: null };
  } catch (error) {
    console.warn("Icy Lyrics could not restore the draft playback context.", error);
    return { track: null, warning: "Spotify could not restore this draft's saved track." };
  }
}
