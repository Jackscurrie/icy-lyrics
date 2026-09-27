/** Spotify image URIs are not portable image URLs across app/browser restarts. */
export function normalizeCreatorArtworkUrl(value: unknown): string {
  if (typeof value !== "string") return "";
  const url = value.trim();
  if (/^spotify:image:[a-f\d]+$/iu.test(url)) {
    return `https://i.scdn.co/image/${url.slice("spotify:image:".length)}`;
  }
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.href : "";
  } catch {
    return "";
  }
}

/** Deduplicates recovery and limits requests while opening a library of old drafts. */
export function createCreatorDraftArtworkResolver(services: {
  resolveTrack: (uri: string) => Promise<{ coverUrl: string } | null>;
  persist: (uri: string, coverUrl: string) => Promise<void>;
}) {
  const pending = new Map<string, Promise<string>>();
  const covers = new Map<string, string>();
  const waiting: Array<() => void> = [];
  let active = 0;

  return (uri: string, failedUrl = ""): Promise<string> => {
    if (!/^spotify:track:[A-Za-z\d]{22}$/u.test(uri)) return Promise.resolve("");
    const cached = covers.get(uri);
    if (cached && cached !== failedUrl) return Promise.resolve(cached);
    if (cached === failedUrl) covers.delete(uri);
    const existing = pending.get(uri);
    if (existing) return existing;

    const request = (async () => {
      if (active >= 2) await new Promise<void>((resolve) => waiting.push(resolve));
      else active += 1;
      try {
        const track = await services.resolveTrack(uri);
        const cover = normalizeCreatorArtworkUrl(track?.coverUrl);
        if (!cover || cover === failedUrl) return "";
        covers.set(uri, cover);
        // Artwork recovery must not make the draft inaccessible if optional
        // presentation metadata cannot be persisted on this device.
        try { await services.persist(uri, cover); } catch { /* Retry on a future opening. */ }
        return cover;
      } finally {
        const next = waiting.shift();
        if (next) next();
        else active -= 1;
      }
    })().finally(() => pending.delete(uri));
    pending.set(uri, request);
    return request;
  };
}
