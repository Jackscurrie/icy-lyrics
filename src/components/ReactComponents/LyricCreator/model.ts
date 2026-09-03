export type CreatorSource = "spt" | "aml" | "spl" | "ldb" | "ttml" | "draft";

export interface CreatorSourceProvenance {
  code: CreatorSource;
  label: string;
  maker?: string;
  uploader?: string;
  raw?: Record<string, unknown>;
}

export interface CreatorMetadata {
  name: string;
  artists: string[];
  songwriters: string[];
  albums: string[];
  spotifyTrackId: string;
  appleMusicTrackId: string;
  isrc: string;
  language: string;
  raw: Record<string, string[]>;
}

export interface CreatorFragment {
  id: string;
  text: string;
  startTimeMs: number | null;
  endTimeMs: number | null;
}

export interface CreatorToken {
  id: string;
  fragments: CreatorFragment[];
  boundaryAfter: string;
}

export interface CreatorLine {
  id: string;
  tokens: CreatorToken[];
  isBackground: boolean;
  isSecondSpeaker: boolean;
  attachedToLineId: string | null;
  startTimeMs: number | null;
  endTimeMs: number | null;
}

export interface CreatorProject {
  version: 1;
  uri: string;
  source: CreatorSourceProvenance;
  metadata: CreatorMetadata;
  lines: CreatorLine[];
}

export const CREATOR_SOURCE_LABELS: Partial<Record<CreatorSource, string>> = {
  spt: "Spotify",
  aml: "Apple Music",
  spl: "Lyrics database",
  ldb: "Local TTML",
  ttml: "Local TTML",
  draft: "Draft",
};

export function normalizeCreatorSource(source: CreatorSourceProvenance): CreatorSourceProvenance {
  return source.code === "ldb" || source.code === "ttml"
    ? { ...source, label: "Local TTML" }
    : source;
}

let idSequence = 0;

export function createCreatorId(prefix: string): string {
  const randomUuid = globalThis.crypto?.randomUUID?.();
  if (randomUuid) return `${prefix}-${randomUuid}`;

  idSequence += 1;
  const suffix = idSequence.toString(16).padStart(12, "0").slice(-12);
  return `${prefix}-00000000-0000-4000-8000-${suffix}`;
}

export function emptyCreatorMetadata(): CreatorMetadata {
  return {
    name: "",
    artists: [],
    songwriters: [],
    albums: [],
    spotifyTrackId: "",
    appleMusicTrackId: "",
    isrc: "",
    language: "",
    raw: {},
  };
}

export function createFragment(text = ""): CreatorFragment {
  return {
    id: createCreatorId("fragment"),
    text,
    startTimeMs: null,
    endTimeMs: null,
  };
}

export function createToken(text = ""): CreatorToken {
  return {
    id: createCreatorId("token"),
    fragments: [createFragment(text)],
    boundaryAfter: "",
  };
}

export function createLine(tokens: CreatorToken[] = [createToken()]): CreatorLine {
  return {
    id: createCreatorId("line"),
    tokens,
    isBackground: false,
    isSecondSpeaker: false,
    attachedToLineId: null,
    startTimeMs: null,
    endTimeMs: null,
  };
}

export function createEmptyProject(uri = ""): CreatorProject {
  return {
    version: 1,
    uri,
    source: { code: "draft", label: "New draft" },
    metadata: emptyCreatorMetadata(),
    lines: [createLine()],
  };
}

function tokensFromPlainLine(line: string): CreatorToken[] {
  const parts = line.split("\\");
  return parts
    .map((part, index) => {
      const trailing = part.match(/\s+$/u)?.[0] ?? "";
      const text = trailing ? part.slice(0, -trailing.length) : part;
      if (!text && !trailing) return null;
      const token = createToken(text);
      token.boundaryAfter = trailing || (index < parts.length - 1 ? " " : "");
      return token;
    })
    .filter((token): token is CreatorToken => token !== null);
}

export function importPlainText(text: string): CreatorLine[] {
  return text
    .replace(/\r\n?/gu, "\n")
    .split("\n")
    .map((line) => tokensFromPlainLine(line))
    .filter((tokens) => tokens.length > 0)
    .map((tokens) => createLine(tokens));
}

function tokenText(token: CreatorToken): string {
  return token.fragments.map((fragment) => fragment.text).join("");
}

export function lineText(line: CreatorLine): string {
  return line.tokens.map((token) => `${tokenText(token)}${token.boundaryAfter}`).join("");
}

/**
 * Moves one written word while preserving its fragments. Separators belong to
 * the gaps between written words, rather than to the word which used to occupy
 * that position: after a drag, retain those gaps in their original slots.
 */
export function moveCreatorTokenWithinLine(
  line: CreatorLine,
  fromIndex: number,
  toIndex: number
): boolean {
  const from = Math.trunc(fromIndex);
  const to = Math.trunc(toIndex);
  if (from === to || from < 0 || to < 0 || from >= line.tokens.length || to >= line.tokens.length) {
    return false;
  }

  const separators = line.tokens.map((token) => token.boundaryAfter);
  const [token] = line.tokens.splice(from, 1);
  if (!token) return false;
  line.tokens.splice(to, 0, token);
  line.tokens.forEach((nextToken, index) => {
    nextToken.boundaryAfter = separators[index] ?? "";
  });
  return true;
}

function secondsToMilliseconds(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, Math.round(value * 1000))
    : null;
}

function syllablesToTokens(syllables: any[]): CreatorToken[] {
  const tokens: CreatorToken[] = [];
  let fragments: CreatorFragment[] = [];

  for (const syllable of syllables ?? []) {
    fragments.push({
      id: createCreatorId("fragment"),
      text: String(syllable?.Text ?? ""),
      startTimeMs: secondsToMilliseconds(syllable?.StartTime),
      endTimeMs: secondsToMilliseconds(syllable?.EndTime),
    });

    if (syllable?.IsPartOfWord !== true) {
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

function textToTokens(text: unknown): CreatorToken[] {
  const words = String(text ?? "")
    .trim()
    .split(/\s+/u)
    .filter(Boolean);
  const tokens = words.length > 0 ? words.map((word) => createToken(word)) : [createToken()];
  tokens.forEach((token, index) => {
    token.boundaryAfter = index < tokens.length - 1 ? " " : "";
  });
  return tokens;
}

function lineFromVocal(
  vocal: any,
  options: { background?: boolean; secondSpeaker?: boolean } = {}
): CreatorLine {
  const syllables = Array.isArray(vocal?.Syllables) ? vocal.Syllables : null;
  const line = createLine(syllables ? syllablesToTokens(syllables) : textToTokens(vocal?.Text));
  line.isBackground = options.background === true;
  line.isSecondSpeaker = options.secondSpeaker === true;
  line.startTimeMs = secondsToMilliseconds(vocal?.StartTime);
  line.endTimeMs = secondsToMilliseconds(vocal?.EndTime);
  syncLineTiming(line);
  return line;
}

export function creatorProjectFromLyrics(
  lyrics: Record<string, any>,
  metadata: Partial<CreatorMetadata> = {}
): CreatorProject {
  const uri = typeof lyrics?.uri === "string" ? lyrics.uri : "";
  const project = createEmptyProject(uri);
  const sourceCode: CreatorSource = ["spt", "aml", "spl", "ldb"].includes(lyrics?.source)
    ? lyrics.source
    : "draft";
  project.source = {
    code: sourceCode,
    label: CREATOR_SOURCE_LABELS[sourceCode] ?? "Draft",
    maker:
      lyrics?.TTMLUploadMetadata?.Maker?.username ??
      lyrics?.Creator?.Name ??
      lyrics?.Maker?.Username,
    uploader: lyrics?.TTMLUploadMetadata?.Uploader?.username ?? lyrics?.Uploader?.Username,
    raw: lyrics?.TTMLUploadMetadata ?? lyrics?.sourceInfo,
  };
  project.metadata = {
    ...emptyCreatorMetadata(),
    ...metadata,
    artists: metadata.artists ?? [],
    songwriters: metadata.songwriters ?? lyrics?.SongWriters ?? [],
    albums: metadata.albums ?? [],
    raw: metadata.raw ?? {},
  };

  if (lyrics?.Type === "Syllable") {
    project.lines = [];
    for (const group of lyrics.Content ?? []) {
      if (!group?.Lead) continue;
      project.lines.push(
        lineFromVocal(group.Lead, { secondSpeaker: group.OppositeAligned === true })
      );
      const leadLine = project.lines[project.lines.length - 1];
      for (const background of group.Background ?? []) {
        const backgroundLine = lineFromVocal(background, {
          background: true,
          secondSpeaker: background?.OppositeAligned === true,
        });
        backgroundLine.attachedToLineId = leadLine.id;
        project.lines.push(backgroundLine);
      }
    }
  } else if (lyrics?.Type === "Line") {
    project.lines = (lyrics.Content ?? [])
      .filter((line: any) => line?.Type === "Vocal")
      .map((line: any) => lineFromVocal(line, { secondSpeaker: line.OppositeAligned === true }));
  } else if (lyrics?.Type === "Static") {
    project.lines = (lyrics.Lines ?? []).map((line: any) => lineFromVocal({ Text: line?.Text }));
  }

  if (project.lines.length === 0) project.lines = [createLine()];
  return project;
}

function fragmentToSyllable(
  fragment: CreatorFragment,
  isPartOfWord: boolean,
  fallbackStartTimeMs = 0,
  fallbackEndTimeMs = fallbackStartTimeMs
): Record<string, any> {
  const startTimeMs = fragment.startTimeMs ?? fallbackStartTimeMs;
  const endTimeMs = Math.max(startTimeMs, fragment.endTimeMs ?? fallbackEndTimeMs);
  return {
    Text: fragment.text,
    StartTime: startTimeMs / 1000,
    EndTime: endTimeMs / 1000,
    IsPartOfWord: isPartOfWord,
  };
}

function lineSyllables(line: CreatorLine): Record<string, any>[] {
  const fragments = line.tokens.flatMap((token) => token.fragments);
  const lineStart = line.startTimeMs ?? 0;
  const lineEnd = Math.max(lineStart, line.endTimeMs ?? lineStart);
  const fallbackDuration = Math.max(0, lineEnd - lineStart);
  let fragmentIndex = 0;
  const result: Record<string, any>[] = [];
  for (const token of line.tokens) {
    token.fragments.forEach((fragment, index) => {
      const fallbackStart =
        lineStart + (fallbackDuration * fragmentIndex) / Math.max(1, fragments.length);
      const fallbackEnd =
        lineStart + (fallbackDuration * (fragmentIndex + 1)) / Math.max(1, fragments.length);
      result.push(
        fragmentToSyllable(fragment, index < token.fragments.length - 1, fallbackStart, fallbackEnd)
      );
      fragmentIndex += 1;
    });
  }
  return result;
}

function projectHasPreviewTiming(project: CreatorProject): boolean {
  return project.lines.some((line) => {
    if (line.startTimeMs !== null && line.endTimeMs !== null && line.endTimeMs > line.startTimeMs) {
      return true;
    }
    return line.tokens.some((token) =>
      token.fragments.some(
        (fragment) =>
          fragment.startTimeMs !== null &&
          fragment.endTimeMs !== null &&
          fragment.endTimeMs > fragment.startTimeMs
      )
    );
  });
}

export function creatorProjectToLyrics(project: CreatorProject): Record<string, any> {
  if (!projectHasPreviewTiming(project)) {
    return {
      Type: "Static",
      Lines: project.lines.map((line) => ({ Text: lineText(line) })),
      uri: project.uri,
      source: project.source.code,
      ...(project.metadata.songwriters.length > 0
        ? { SongWriters: project.metadata.songwriters }
        : {}),
    };
  }

  const content: Record<string, any>[] = [];
  let lastLead: Record<string, any> | null = null;
  const leadById = new Map<string, Record<string, any>>();

  for (const line of project.lines) {
    const vocal = {
      StartTime: (line.startTimeMs ?? 0) / 1000,
      EndTime: (line.endTimeMs ?? line.startTimeMs ?? 0) / 1000,
      Syllables: lineSyllables(line),
      ...(line.isSecondSpeaker ? { OppositeAligned: true } : {}),
    };

    const backgroundTarget = line.attachedToLineId ? leadById.get(line.attachedToLineId) : lastLead;
    if (line.isBackground && backgroundTarget) {
      (backgroundTarget.Background ??= []).push(vocal);
      continue;
    }

    const group: Record<string, any> = { Type: "Vocal", Lead: vocal };
    if (line.isSecondSpeaker) group.OppositeAligned = true;
    content.push(group);
    lastLead = group;
    leadById.set(line.id, group);
  }

  const result: Record<string, any> = {
    Type: "Syllable",
    Content: content,
    uri: project.uri,
    source: project.source.code,
  };
  if (project.metadata.songwriters.length > 0) {
    result.SongWriters = project.metadata.songwriters;
  }
  if (content.length > 0) result.StartTime = content[0].Lead.StartTime;
  return result;
}

export function syncLineTiming(line: CreatorLine): CreatorLine {
  const fragments = line.tokens.flatMap((token) => token.fragments);
  const timed = fragments.filter(
    (fragment) => fragment.startTimeMs !== null || fragment.endTimeMs !== null
  );
  if (timed.length === 0) return line;

  const starts = timed
    .map((fragment) => fragment.startTimeMs)
    .filter((time): time is number => time !== null);
  const ends = timed
    .map((fragment) => fragment.endTimeMs)
    .filter((time): time is number => time !== null);
  line.startTimeMs = starts.length > 0 ? Math.min(...starts) : null;
  line.endTimeMs = ends.length > 0 ? Math.max(...ends) : null;
  return line;
}

export function cloneCreatorProject(project: CreatorProject): CreatorProject {
  return JSON.parse(JSON.stringify(project)) as CreatorProject;
}
