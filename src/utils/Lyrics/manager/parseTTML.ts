import {
  parseTTML as parseAppleMusicLikeTTML,
  type AmllLyricLine,
  type AmllLyricWord,
  type AmllMetadata,
} from "@applemusic-like-lyrics/ttml";
import { Query } from "../../API/Query.ts";
import Logger from "../../Logger.ts";
import { isLyricsObject, normalizeLyricsSchema } from "../schema.ts";

const logger = new Logger("TTML Parser");

const TIMING_MODE_KEY = "timingMode";
const SONGWRITERS_KEY = "songwriters";
const WORD_TIMING = "word";

export type ParsedTTMLResult = { Result: Record<string, any> };

const metadataValue = (metadata: AmllMetadata[], key: string): string[] =>
  metadata.find(([entryKey]) => entryKey.toLowerCase() === key.toLowerCase())?.[1] ?? [];

/** AMLL exposes TTML time values in milliseconds; Icy's wire schema uses seconds. */
export const ttmlMillisecondsToSeconds = (time: number): number => time / 1000;

function wordToSyllable(word: AmllLyricWord): Record<string, any> {
  const hasBoundarySpace = word.word.endsWith(" ");
  const syllable: Record<string, any> = {
    Text: hasBoundarySpace ? word.word.slice(0, -1) : word.word,
    StartTime: ttmlMillisecondsToSeconds(word.startTime),
    EndTime: ttmlMillisecondsToSeconds(word.endTime),
    IsPartOfWord: !hasBoundarySpace,
  };

  if (word.romanWord) syllable.TransliteratedText = word.romanWord;
  return syllable;
}

function lineTextFromWords(words: AmllLyricWord[]): string {
  return words
    .map((word) => word.word)
    .join("")
    .replace(/ {2,}/g, " ")
    .trim();
}

interface BodySpeakerFlags {
  lead: boolean[];
  background: boolean[];
}

function bodySpeakerFlags(ttml: string): BodySpeakerFlags {
  try {
    const document = new DOMParser().parseFromString(ttml, "application/xml");
    const body = Array.from(document.getElementsByTagName("*")).find(
      (element) => element.localName === "body"
    );
    if (!body) return { lead: [], background: [] };

    const flags: BodySpeakerFlags = { lead: [], background: [] };
    const paragraphs = Array.from(body.getElementsByTagName("*")).filter(
      (element) => element.localName === "p"
    );
    for (const paragraph of paragraphs) {
      const owningAgent =
        paragraph.getAttribute("ttm:agent") ??
        paragraph.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent");
      flags.lead.push(owningAgent === "v2");
      const spans = Array.from(paragraph.getElementsByTagName("*")).filter(
        (element) => element.localName === "span"
      );
      for (const span of spans) {
        let owner = span.parentNode as Element | null;
        while (owner && owner.localName !== "p") {
          owner = owner.parentNode as Element | null;
        }
        if (owner !== paragraph) continue;
        const role =
          span.getAttribute("ttm:role") ??
          span.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role");
        if (role !== "x-bg") continue;
        const agent =
          span.getAttribute("ttm:agent") ??
          span.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent") ??
          owningAgent;
        flags.background.push(agent === "v2");
        break;
      }
    }
    return flags;
  } catch {
    return { lead: [], background: [] };
  }
}

function buildSyllableLyrics(lines: AmllLyricLine[], songWriters: string[]): Record<string, any> {
  const content: Array<Record<string, any>> = [];
  let lastLeadGroup: Record<string, any> | null = null;

  for (const line of lines) {
    const words = line.words ?? [];
    if (words.length === 0) continue;

    const syllables = words.map(wordToSyllable);
    let startTime = ttmlMillisecondsToSeconds(line.startTime);
    let endTime = ttmlMillisecondsToSeconds(line.endTime);

    if (line.isBG && lastLeadGroup) {
      // Apple background containers can extend beyond their last timed word.
      // The lyric renderer's background range must follow the actual audible
      // syllables or it incorrectly holds the vocal active through that tail.
      startTime = syllables[0]?.StartTime ?? startTime;
      endTime = syllables.at(-1)?.EndTime ?? endTime;
      const background = (lastLeadGroup.Background ??= []) as Array<Record<string, any>>;
      background.push({
        StartTime: startTime,
        EndTime: endTime,
        Syllables: syllables,
        ...(line.isDuet ? { OppositeAligned: true } : {}),
      });
      continue;
    }

    const group: Record<string, any> = {
      Type: "Vocal",
      Lead: { StartTime: startTime, EndTime: endTime, Syllables: syllables },
    };
    if (line.isDuet) group.OppositeAligned = true;

    content.push(group);
    lastLeadGroup = group;
  }

  const lyrics: Record<string, any> = { Type: "Syllable", Content: content };
  if (songWriters.length > 0) lyrics.SongWriters = songWriters;
  if (content.length > 0) lyrics.StartTime = content[0].Lead.StartTime;
  return lyrics;
}

function buildLineLyrics(lines: AmllLyricLine[], songWriters: string[]): Record<string, any> {
  const content = lines
    .map((line) => {
      const text = lineTextFromWords(line.words ?? []);
      if (!text) return null;

      const vocal: Record<string, any> = {
        Type: "Vocal",
        Text: text,
        StartTime: ttmlMillisecondsToSeconds(line.startTime),
        EndTime: ttmlMillisecondsToSeconds(line.endTime),
      };
      if (line.isDuet) vocal.OppositeAligned = true;
      if (line.romanLyric) vocal.TransliteratedText = line.romanLyric;
      return vocal;
    })
    .filter((vocal): vocal is Record<string, any> => vocal !== null);

  const lyrics: Record<string, any> = { Type: "Line", Content: content };
  if (songWriters.length > 0) lyrics.SongWriters = songWriters;
  if (content.length > 0) lyrics.StartTime = content[0].StartTime;
  return lyrics;
}

export function looksLikeTTML(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const trimmed = value.trimStart();
  return /^(?:<\?xml[\s\S]*?\?>\s*)?<tt(?:\s|>)/i.test(trimmed);
}

/**
 * AMLL's current parser requires every lyric paragraph to have a stable key.
 * Older Apple/Spicy TTML responses did not always include one, so provide a
 * parse-only key without changing the raw file that Icy stores or exports.
 */
export function ensureTtmlParagraphKeys(ttml: string): string {
  let paragraphIndex = 0;
  return ttml.replace(
    /<((?:[A-Za-z_][\w.-]*:)?p)\b([^<>]*?)>/gi,
    (openingTag, tagName: string, attributes: string) => {
      paragraphIndex += 1;
      if (/(?:^|\s)(?:[A-Za-z_][\w.-]*:)?key\s*=/i.test(attributes)) {
        return openingTag;
      }
      return `<${tagName} key="icy-${paragraphIndex}"${attributes}>`;
    }
  );
}

/** Parse TTML without contacting the Spicy Lyrics service. */
export function ParseTTMLLocally(ttml: string): ParsedTTMLResult | null {
  if (!looksLikeTTML(ttml)) return null;

  try {
    const result = parseAppleMusicLikeTTML(ensureTtmlParagraphKeys(ttml));
    if (!Array.isArray(result.lines) || result.lines.length === 0) return null;
    const speakerFlags = bodySpeakerFlags(ttml);
    let leadIndex = 0;
    let backgroundIndex = 0;
    for (const line of result.lines) {
      if (line.isBG) {
        if (speakerFlags.background[backgroundIndex] !== undefined) {
          line.isDuet = speakerFlags.background[backgroundIndex];
        }
        backgroundIndex += 1;
        continue;
      }
      if (speakerFlags.lead[leadIndex] !== undefined) {
        line.isDuet = speakerFlags.lead[leadIndex];
      }
      leadIndex += 1;
    }

    const songWriters = metadataValue(result.metadata, SONGWRITERS_KEY);
    const timingMode = metadataValue(result.metadata, TIMING_MODE_KEY)[0]?.toLowerCase();
    const hasWordTiming =
      timingMode === WORD_TIMING || result.lines.some((line) => (line.words?.length ?? 0) > 1);
    const lyrics = hasWordTiming
      ? buildSyllableLyrics(result.lines, songWriters)
      : buildLineLyrics(result.lines, songWriters);

    if (!isLyricsObject(lyrics)) return null;
    return { Result: normalizeLyricsSchema(lyrics) };
  } catch (error) {
    logger.warn("Local TTML parse failed; the API fallback will be attempted", error);
    return null;
  }
}

async function ParseTTMLRemotely(ttml: string): Promise<ParsedTTMLResult | null> {
  try {
    const query = await Query([{ operation: "parseTTML", variables: { ttml } }]);
    const queryResult = query.get("0");
    if (
      !queryResult ||
      queryResult.httpStatus !== 200 ||
      queryResult.format !== "json" ||
      !queryResult.data ||
      queryResult.data.error
    ) {
      return null;
    }

    const candidate = queryResult.data.Result ?? queryResult.data;
    if (!isLyricsObject(candidate)) return null;
    return { Result: normalizeLyricsSchema(candidate) };
  } catch (error) {
    logger.error("Remote TTML fallback failed", error);
    return null;
  }
}

/**
 * Local-first parser used by imports and raw API payloads. The remote parser is
 * retained only for dialects that the narrow open-source parser cannot read.
 */
export async function ParseTTML(
  ttml: string,
  options: { allowRemoteFallback?: boolean } = {}
): Promise<ParsedTTMLResult | null> {
  if (!looksLikeTTML(ttml)) return null;
  const local = ParseTTMLLocally(ttml);
  if (local || options.allowRemoteFallback === false) return local;
  return ParseTTMLRemotely(ttml);
}
