import {
  TTMLGenerator,
  TTMLParser,
  type GeneratorOptions,
  type LyricBase,
  type LyricLine,
  type Syllable,
  type TTMLParserOptions,
  type TTMLResult,
} from "@applemusic-like-lyrics/ttml";
import {
  createCreatorId,
  createEmptyProject,
  createLine,
  createToken,
  emptyCreatorMetadata,
  type CreatorFragment,
  type CreatorLine,
  type CreatorMetadata,
  type CreatorProject,
  type CreatorToken,
} from "./model.ts";

export interface CreatorValidationIssue {
  lineIndex: number;
  tokenIndex?: number;
  fragmentIndex?: number;
  message: string;
}

export class CreatorValidationError extends Error {
  constructor(public readonly issues: CreatorValidationIssue[]) {
    super(issues[0]?.message ?? "The lyric project is not ready to export.");
    this.name = "CreatorValidationError";
  }
}

export function validateCreatorProject(project: CreatorProject): CreatorValidationIssue[] {
  const issues: CreatorValidationIssue[] = [];
  const backgroundsByLead = new Map<string, number>();
  const earlierLeadIds = new Set<string>();
  let lastLeadId: string | null = null;
  project.lines.forEach((line, lineIndex) => {
    if (line.isBackground) {
      const targetId = line.attachedToLineId ?? lastLeadId;
      if (!targetId) {
        issues.push({
          lineIndex,
          message: `Background line ${lineIndex + 1} is not attached to a lead line.`,
        });
      } else if (!earlierLeadIds.has(targetId)) {
        issues.push({
          lineIndex,
          message: `Background line ${lineIndex + 1} must reference an earlier lead line.`,
        });
      } else {
        const count = (backgroundsByLead.get(targetId) ?? 0) + 1;
        backgroundsByLead.set(targetId, count);
        if (count > 1) {
          issues.push({
            lineIndex,
            message:
              `Line ${lineIndex + 1} is a second background row for the same lead. ` +
              "Split the lead into another line before exporting.",
          });
        }
      }
    } else {
      lastLeadId = line.id;
      earlierLeadIds.add(line.id);
    }

    line.tokens.forEach((token, tokenIndex) => {
      token.fragments.forEach((fragment, fragmentIndex) => {
        if (!fragment.text) return;
        if (fragment.startTimeMs === null || fragment.endTimeMs === null) {
          issues.push({
            lineIndex,
            tokenIndex,
            fragmentIndex,
            message: `Line ${lineIndex + 1}, word ${tokenIndex + 1} has not been fully timed.`,
          });
        } else if (fragment.endTimeMs <= fragment.startTimeMs) {
          issues.push({
            lineIndex,
            tokenIndex,
            fragmentIndex,
            message: `Line ${lineIndex + 1}, word ${tokenIndex + 1} must end after it starts.`,
          });
        }
      });
    });
  });
  return issues;
}

function normalizedFragment(fragment: CreatorFragment): Syllable {
  if (fragment.startTimeMs === null || fragment.endTimeMs === null) {
    throw new CreatorValidationError([
      { lineIndex: -1, message: "Every lyric fragment must be timed before export." },
    ]);
  }
  const startTime = Math.max(0, Math.round(fragment.startTimeMs));
  const endTime = Math.round(fragment.endTimeMs);
  return { text: fragment.text, startTime, endTime };
}

function creatorLineWords(line: CreatorLine): Syllable[] {
  const words: Syllable[] = [];
  line.tokens.forEach((token) => {
    token.fragments.forEach((fragment, index) => {
      words.push({
        ...normalizedFragment(fragment),
        endsWithSpace: index === token.fragments.length - 1 && token.boundaryAfter.length > 0,
      });
    });
  });
  if (words.length > 0) words[words.length - 1].endsWithSpace = false;
  return words;
}

function lineRange(line: CreatorLine, words: Syllable[]): { startTime: number; endTime: number } {
  if (words.length === 0) {
    if (line.startTimeMs === null || line.endTimeMs === null) {
      throw new CreatorValidationError([
        { lineIndex: -1, message: "Every lyric line must be timed before export." },
      ]);
    }
    const startTime = Math.max(0, Math.round(line.startTimeMs));
    return {
      startTime,
      endTime: Math.round(line.endTimeMs),
    };
  }
  return {
    startTime: Math.min(...words.map((word) => word.startTime)),
    endTime: Math.max(...words.map((word) => word.endTime)),
  };
}

function creatorLineToBase(line: CreatorLine): LyricBase {
  const words = creatorLineWords(line);
  return {
    text: words.map((word) => `${word.text}${word.endsWithSpace ? " " : ""}`).join(""),
    ...lineRange(line, words),
    words,
  };
}

function mergeBackground(target: LyricLine, background: LyricBase): void {
  if (!target.backgroundVocal) {
    target.backgroundVocal = background;
    return;
  }
  throw new CreatorValidationError([
    { lineIndex: -1, message: "Only one background row can be exported per lead line." },
  ]);
}

function metadataToTTML(metadata: CreatorMetadata): TTMLResult["metadata"] {
  const platformIds: TTMLResult["metadata"]["platformIds"] = {};
  if (metadata.spotifyTrackId) platformIds.spotifyId = [metadata.spotifyTrackId];
  if (metadata.appleMusicTrackId) platformIds.appleMusicId = [metadata.appleMusicTrackId];

  return {
    language: metadata.language || undefined,
    timingMode: "Word",
    title: metadata.name ? [metadata.name] : [],
    artist: metadata.artists,
    songwriters: metadata.songwriters,
    album: metadata.albums,
    isrc: metadata.isrc ? [metadata.isrc] : [],
    platformIds,
    agents: {
      v1: { id: "v1", type: "person" },
      v2: { id: "v2", type: "person" },
    },
    rawProperties: metadata.raw,
  };
}

export function creatorProjectToTTMLResult(project: CreatorProject): TTMLResult {
  const lines: LyricLine[] = [];
  let lastLead: LyricLine | null = null;
  const leadById = new Map<string, LyricLine>();

  project.lines.forEach((line, index) => {
    const base = creatorLineToBase(line);
    const backgroundTarget = line.attachedToLineId ? leadById.get(line.attachedToLineId) : lastLead;
    if (line.isBackground && backgroundTarget) {
      mergeBackground(backgroundTarget, base);
      return;
    }
    if (line.isBackground) {
      throw new CreatorValidationError([
        { lineIndex: index, message: `Background line ${index + 1} has no valid lead line.` },
      ]);
    }

    const lyricLine: LyricLine = {
      ...base,
      id: `L${index + 1}`,
      agentId: line.isSecondSpeaker ? "v2" : "v1",
    };
    lines.push(lyricLine);
    lastLead = lyricLine;
    leadById.set(line.id, lyricLine);
  });

  return { metadata: metadataToTTML(project.metadata), lines };
}

export function serializeCreatorTTML(project: CreatorProject, options?: GeneratorOptions): string {
  const issues = validateCreatorProject(project);
  if (issues.length > 0) throw new CreatorValidationError(issues);

  let ttml = TTMLGenerator.generate(creatorProjectToTTMLResult(project), options);
  const backgroundByLead = new Map<string, CreatorLine>();
  let lastLeadId: string | null = null;
  for (const line of project.lines) {
    if (line.isBackground) {
      const targetId = line.attachedToLineId ?? lastLeadId;
      if (targetId) backgroundByLead.set(targetId, line);
    } else {
      lastLeadId = line.id;
    }
  }
  // TTMLGenerator writes paragraphs in lead order, which can differ from the
  // editor row order when a background row is explicitly attached to an
  // earlier lead. Match x-bg spans to that output order, not global BG order.
  const backgroundLines = project.lines
    .filter((line) => !line.isBackground)
    .map((line) => backgroundByLead.get(line.id))
    .filter((line): line is CreatorLine => line !== undefined);
  let backgroundIndex = 0;
  ttml = ttml.replace(/<span\b([^>]*\bttm:role=["']x-bg["'][^>]*)>/giu, (tag, attributes) => {
    const line = backgroundLines[backgroundIndex];
    backgroundIndex += 1;
    if (!line) return tag;
    const agent = line.isSecondSpeaker ? "v2" : "v1";
    const withoutAgent = String(attributes).replace(/\s+ttm:agent=["'][^"']*["']/giu, "");
    return `<span${withoutAgent} ttm:agent="${agent}">`;
  });
  return ttml;
}

function syllablesToTokens(words: Syllable[] | undefined, fallbackText: string): CreatorToken[] {
  if (!words || words.length === 0) {
    const tokens = fallbackText.trim().split(/\s+/u).filter(Boolean).map(createToken);
    return tokens.length > 0 ? tokens : [createToken()];
  }

  const tokens: CreatorToken[] = [];
  let fragments: CreatorFragment[] = [];
  for (const word of words) {
    fragments.push({
      id: createCreatorId("fragment"),
      text: word.text,
      startTimeMs: Math.max(0, Math.round(word.startTime)),
      endTimeMs: Math.max(0, Math.round(word.endTime)),
    });
    if (word.endsWithSpace) {
      tokens.push({ id: createCreatorId("token"), fragments, boundaryAfter: " " });
      fragments = [];
    }
  }
  if (fragments.length > 0) {
    tokens.push({ id: createCreatorId("token"), fragments, boundaryAfter: "" });
  }
  if (tokens.length > 0) tokens[tokens.length - 1].boundaryAfter = "";
  return tokens.length > 0 ? tokens : [createToken()];
}

function lyricBaseToCreatorLine(
  base: LyricBase,
  options: { background?: boolean; secondSpeaker?: boolean } = {}
): CreatorLine {
  const line = createLine(syllablesToTokens(base.words, base.text));
  line.startTimeMs = Number.isFinite(base.startTime)
    ? Math.max(0, Math.round(base.startTime))
    : null;
  line.endTimeMs = Number.isFinite(base.endTime) ? Math.max(0, Math.round(base.endTime)) : null;
  line.isBackground = options.background === true;
  line.isSecondSpeaker = options.secondSpeaker === true;
  return line;
}

function first(values: string[] | undefined): string {
  return values?.[0] ?? "";
}

function metadataFromTTML(result: TTMLResult): CreatorMetadata {
  const metadata = result.metadata;
  return {
    ...emptyCreatorMetadata(),
    name: first(metadata.title),
    artists: metadata.artist ?? [],
    songwriters: metadata.songwriters ?? [],
    albums: metadata.album ?? [],
    spotifyTrackId: first(metadata.platformIds?.spotifyId),
    appleMusicTrackId: first(metadata.platformIds?.appleMusicId),
    isrc: first(metadata.isrc),
    language: metadata.language ?? "",
    raw: metadata.rawProperties ?? {},
  };
}

function backgroundSecondSpeakerFlags(ttml: string, options?: TTMLParserOptions): boolean[] {
  try {
    const parser = options?.domParser ?? new DOMParser();
    const document = parser.parseFromString(ttml, "application/xml") as any;
    const elements = Array.from(document.getElementsByTagName("*")) as Element[];
    const body = elements.find((element) => element.localName === "body");
    if (!body) return [];
    const flags: boolean[] = [];
    const paragraphs = (Array.from(body.getElementsByTagName("*")) as Element[]).filter(
      (element) => element.localName === "p"
    );
    for (const paragraph of paragraphs) {
      const paragraphAgent =
        paragraph.getAttribute("ttm:agent") ??
        paragraph.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent");
      const spans = (Array.from(paragraph.getElementsByTagName("*")) as Element[]).filter(
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
          paragraphAgent;
        flags.push(agent === "v2");
        // AMLL TTML has at most one background row per lyric paragraph. Keep
        // the flag aligned one-to-one with the parser's backgroundVocal row.
        break;
      }
    }
    return flags;
  } catch {
    return [];
  }
}

export function parseCreatorTTML(ttml: string, options?: TTMLParserOptions): CreatorProject {
  const result = TTMLParser.parse(ttml, options);
  const backgroundSpeakerFlags = backgroundSecondSpeakerFlags(ttml, options);
  let backgroundIndex = 0;
  const project = createEmptyProject();
  project.source = { code: "ttml", label: "Local TTML" };
  project.metadata = metadataFromTTML(result);
  project.lines = [];

  for (const line of result.lines) {
    const lead = lyricBaseToCreatorLine(line, { secondSpeaker: line.agentId === "v2" });
    project.lines.push(lead);
    if (line.backgroundVocal) {
      const background = lyricBaseToCreatorLine(line.backgroundVocal, {
        background: true,
        secondSpeaker: backgroundSpeakerFlags[backgroundIndex] === true,
      });
      backgroundIndex += 1;
      background.attachedToLineId = lead.id;
      project.lines.push(background);
    }
  }

  if (project.metadata.spotifyTrackId) {
    project.uri = `spotify:track:${project.metadata.spotifyTrackId}`;
  }
  if (project.lines.length === 0) project.lines = [createLine()];
  return project;
}
