export type LyricsSource = "spt" | "aml" | "spl" | "ldb";

const SOURCE_ALIASES: Record<string, LyricsSource> = {
  spt: "spt",
  spotify: "spt",
  aml: "aml",
  apple: "aml",
  applemusic: "aml",
  spl: "spl",
  spicy: "spl",
  spicylyrics: "spl",
  ldb: "ldb",
  local: "ldb",
  localdb: "ldb",
};

function normalizeSource(value: unknown): LyricsSource | undefined {
  if (typeof value !== "string") return undefined;
  return SOURCE_ALIASES[value.toLowerCase().replace(/[\s_-]/g, "")];
}

function visitLyricsNode(value: unknown, state: { hasTransliterations: boolean }): void {
  if (Array.isArray(value)) {
    for (const item of value) visitLyricsNode(item, state);
    return;
  }

  if (value === null || typeof value !== "object") return;
  const node = value as Record<string, unknown>;

  if (
    typeof node.TransliteratedText !== "string" &&
    typeof node.RomanizedText === "string"
  ) {
    node.TransliteratedText = node.RomanizedText;
  }

  if (
    typeof node.TransliteratedText === "string" &&
    node.TransliteratedText.trim().length > 0
  ) {
    state.hasTransliterations = true;
  }

  for (const child of Object.values(node)) visitLyricsNode(child, state);
}

/**
 * Normalizes the pre-6.0 romanization names and provider aliases without
 * discarding the legacy fields. Keeping the old fields makes cached payloads
 * safe to downgrade while every current renderer reads the new names.
 */
export function normalizeLyricsSchema<T>(lyrics: T): T {
  if (lyrics === null || typeof lyrics !== "object") return lyrics;

  const root = lyrics as Record<string, unknown>;
  const state = {
    hasTransliterations:
      root.HasTransliterations === true || root.IncludesRomanization === true,
  };

  visitLyricsNode(root, state);
  root.HasTransliterations = state.hasTransliterations;

  const source = normalizeSource(root.source ?? root.Source ?? root.provider ?? root.Provider);
  if (source) root.source = source;

  return lyrics;
}

export function isLyricsObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const type = (value as Record<string, unknown>).Type;
  return type === "Static" || type === "Line" || type === "Syllable";
}
