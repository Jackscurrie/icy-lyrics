export const CREATOR_LUCIDA_URL = "https://lucida.to/";

const SPOTIFY_TRACK_ID = /^[a-zA-Z0-9]{22}$/u;
const SPOTIFY_TRACK_URI = /^spotify:track:([a-zA-Z0-9]{22})$/u;

export function canonicalSpotifyTrackUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  const uriMatch = SPOTIFY_TRACK_URI.exec(value);
  if (uriMatch) return `https://open.spotify.com/track/${uriMatch[1]}`;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.hostname !== "open.spotify.com" || url.port || url.username || url.password) return null;
    const pathMatch = /^\/track\/([a-zA-Z0-9]{22})\/?$/u.exec(url.pathname);
    return pathMatch ? `https://open.spotify.com/track/${pathMatch[1]}` : null;
  } catch {
    return null;
  }
}

/** The editor's selected project owns this action; now-playing is never a fallback. */
export function creatorAudioHandoffTrackUrl(selection: {
  projectUri?: string;
  metadataTrackId?: string;
  selectedTrackUri?: string;
}): string | null {
  const projectUrl = canonicalSpotifyTrackUrl(selection.projectUri);
  if (projectUrl) return projectUrl;
  const metadataId = selection.metadataTrackId?.trim() ?? "";
  if (SPOTIFY_TRACK_ID.test(metadataId)) return `https://open.spotify.com/track/${metadataId}`;
  return canonicalSpotifyTrackUrl(selection.selectedTrackUri);
}

export interface CreatorAudioHandoffResult {
  trackUrl: string;
  copied: boolean;
  openRequested: boolean;
}

/** Copy and browser opening start in the user gesture, without a service API call. */
export async function openCreatorLucidaHandoff(
  trackUrl: string,
  actions: { copy: (value: string) => void | Promise<unknown>; open: (url: string) => unknown }
): Promise<CreatorAudioHandoffResult> {
  const canonical = canonicalSpotifyTrackUrl(trackUrl);
  if (!canonical) throw new Error("Choose a standard Spotify track in Lyric Creator first.");
  let copy: Promise<unknown>;
  try {
    copy = Promise.resolve(actions.copy(canonical));
  } catch (error) {
    copy = Promise.reject(error);
  }
  let openRequested = false;
  try {
    actions.open(CREATOR_LUCIDA_URL);
    // Spotify delegates external URLs to the browser and can return null even
    // when successful. Do not mistake that native handoff for a blocked popup.
    openRequested = true;
  } catch {
    // The caller exposes the website and song links for a manual fallback.
  }
  const copied = await copy.then(() => true, () => false);
  return { trackUrl: canonical, copied, openRequested };
}
