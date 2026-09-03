import { SLObjPack } from "../objpack.ts";
import { looksLikeTTML, ParseTTML } from "./manager/parseTTML.ts";
import { isLyricsObject, normalizeLyricsSchema } from "./schema.ts";

const packer = new SLObjPack();

function unpackPayload(data: unknown): unknown {
  if (looksLikeTTML(data) || isLyricsObject(data)) return data;
  if (
    data !== null &&
    typeof data === "object" &&
    !Array.isArray(data) &&
    (isLyricsObject((data as Record<string, unknown>).Result) ||
      looksLikeTTML((data as Record<string, unknown>).Result))
  ) {
    return data;
  }
  return packer.unpack(data);
}

/** Decode an X-mode 2 object-pack payload, including raw XML/TTML responses. */
export async function decodeLyricsPayload(data: unknown): Promise<Record<string, any> | null> {
  let unpacked: unknown;
  try {
    unpacked = unpackPayload(data);
  } catch {
    return null;
  }

  if (
    unpacked !== null &&
    typeof unpacked === "object" &&
    !Array.isArray(unpacked)
  ) {
    const wrapped = (unpacked as Record<string, unknown>).Result;
    if (looksLikeTTML(wrapped)) unpacked = wrapped;
  }

  if (typeof unpacked === "string" && !looksLikeTTML(unpacked)) {
    try {
      unpacked = JSON.parse(unpacked);
    } catch {
      return null;
    }
  }

  if (looksLikeTTML(unpacked)) {
    const parsed = await ParseTTML(unpacked);
    if (!parsed?.Result) return null;
    parsed.Result.source = "spl";
    return normalizeLyricsSchema(parsed.Result);
  }

  if (
    unpacked !== null &&
    typeof unpacked === "object" &&
    !Array.isArray(unpacked) &&
    isLyricsObject((unpacked as Record<string, unknown>).Result)
  ) {
    unpacked = (unpacked as Record<string, unknown>).Result;
  }

  if (!isLyricsObject(unpacked)) return null;
  return normalizeLyricsSchema(unpacked as Record<string, any>);
}
