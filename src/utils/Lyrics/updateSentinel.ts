const UPDATE_SENTINEL_MARKERS = [
  "please update spicy lyrics",
  "you can do so immediately by restarting spotify",
  "the cool spicetify extension",
] as const;

/** Detect the API's valid-looking static lyric compatibility response. */
export function isSpicyUpdateSentinel(value: unknown): boolean {
  let serialized: string;
  try {
    serialized = typeof value === "string" ? value : JSON.stringify(value);
  } catch {
    return false;
  }

  const normalized = serialized.toLowerCase();
  return UPDATE_SENTINEL_MARKERS.every((marker) => normalized.includes(marker));
}
