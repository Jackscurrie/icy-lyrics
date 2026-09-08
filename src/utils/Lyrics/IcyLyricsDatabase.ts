import type { IcyDatabaseLookupResult } from "../API/IcyLyricsDatabase.ts";
import { ProcessLyrics } from "./ProcessLyrics.ts";
import { isLyricsObject, normalizeLyricsSchema } from "./schema.ts";
import { ParseTTML } from "./manager/parseTTML.ts";

function cloneLyrics(lyrics: Record<string, any>): Record<string, any> {
  return structuredClone(lyrics);
}

/** Converts an approved Icy database TTML response into the renderer schema. */
export async function lyricsFromIcyDatabaseLookup(
  lookup: IcyDatabaseLookupResult,
  uri: string,
  options: { signal?: AbortSignal } = {}
): Promise<Record<string, any> | null> {
  if (lookup.kind !== "found") return null;

  // Most approved records parse locally. Retain Icy's established remote
  // compatibility parser only for a TTML dialect the local parser rejects.
  const parsed = await ParseTTML(lookup.rawTtml, { signal: options.signal });
  if (!parsed?.Result || !isLyricsObject(parsed.Result)) return null;

  const lyrics = normalizeLyricsSchema(cloneLyrics(parsed.Result));
  await ProcessLyrics(lyrics);
  lyrics.uri = uri;
  lyrics.source = "icy";
  lyrics.IcyLyricsDatabase = {
    recordId: lookup.recordId,
    revision: lookup.revision,
    contentSha256: lookup.contentSha256,
  };
  return lyrics;
}
