import Platform from "../../Global/Platform.ts";
import { Query } from "../../../utils/API/Query.ts";
import { $currentLyricsData, $useLocalTtmlLyrics } from "../../../utils/stores.ts";
import { LyricsStore } from "../../../utils/Lyrics/fetchLyrics.ts";
import { LocalLyricsManager } from "../../../utils/Lyrics/manager/index.ts";
import { decodeLyricsPayload } from "../../../utils/Lyrics/payload.ts";
import { ProcessLyrics } from "../../../utils/Lyrics/ProcessLyrics.ts";
import { isLyricsObject, normalizeLyricsSchema } from "../../../utils/Lyrics/schema.ts";
import type { CreatorMetadata, CreatorSourceProvenance } from "./model.ts";

export interface CreatorTrack {
  uri: string;
  name: string;
  artists: string[];
  album: string;
  coverUrl: string;
  durationMs: number;
  isrc: string;
}

export interface CreatorLyricsLoadResult {
  lyrics: Record<string, any> | null;
  source: CreatorSourceProvenance;
}

/**
 * A runtime-only preference used by Lyric Creator. It is deliberately separate
 * from the normal Icy Lyrics settings and fetching pipeline.
 */
export type CreatorLyricsSourcePreference = "auto" | "ldb" | "spt" | "aml" | "spl";

export const CREATOR_LYRICS_SOURCE_OPTIONS: ReadonlyArray<{
  value: CreatorLyricsSourcePreference;
  label: string;
}> = [
  { value: "auto", label: "Auto (best available)" },
  { value: "ldb", label: "Saved local TTML" },
  { value: "spt", label: "Spotify" },
  { value: "aml", label: "Apple Music" },
  { value: "spl", label: "Lyrics database" },
];

export function creatorLyricsSourcePreferenceLabel(
  preference: CreatorLyricsSourcePreference
): string {
  return (
    CREATOR_LYRICS_SOURCE_OPTIONS.find((option) => option.value === preference)?.label ??
    "Auto (best available)"
  );
}

/**
 * The source hint is intentionally confined to Creator requests. Older API
 * deployments safely ignore the extra variable; Creator verifies the returned
 * source before accepting it so an ignored hint can never be mislabeled.
 */
export function buildCreatorLyricsQueryVariables(
  trackId: string,
  preference: CreatorLyricsSourcePreference
): {
  id: string;
  auth: "SpicyLyrics-WebAuth";
  source?: Exclude<CreatorLyricsSourcePreference, "auto" | "ldb">;
} {
  const variables: {
    id: string;
    auth: "SpicyLyrics-WebAuth";
    source?: Exclude<CreatorLyricsSourcePreference, "auto" | "ldb">;
  } = { id: trackId, auth: "SpicyLyrics-WebAuth" };
  if (preference !== "auto" && preference !== "ldb") variables.source = preference;
  return variables;
}

type UnknownRecord = Record<string, unknown>;

export interface CreatorSpotifyInternalApi {
  definitions: Record<string, unknown>;
  request: (
    definition: unknown,
    variables?: Record<string, unknown>,
    context?: Record<string, unknown>
  ) => Promise<unknown>;
}

function asRecord(value: unknown): UnknownRecord | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as UnknownRecord)
    : null;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function nestedRecord(record: UnknownRecord | null, key: string): UnknownRecord | null {
  return asRecord(record?.[key]);
}

function dataRoot(payload: unknown): UnknownRecord | null {
  const root = asRecord(payload);
  return nestedRecord(root, "data") ?? root;
}

function abortCreatorRequest(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
}

function defaultSpotifyInternalApi(): CreatorSpotifyInternalApi {
  if (typeof Spicetify === "undefined" || !Spicetify.GraphQL) {
    throw new Error("Spotify's internal search is unavailable in this client build.");
  }
  const graphql = Spicetify.GraphQL;
  return {
    definitions: graphql.Definitions,
    request: (definition, variables, context) => graphql.Request(definition, variables, context),
  };
}

function artistNames(track: UnknownRecord): string[] {
  const artistValue = track.artists;
  const artists = Array.isArray(artistValue) ? artistValue : asArray(asRecord(artistValue)?.items);
  return artists
    .map((artist) => {
      const item = asRecord(artist);
      return stringValue(nestedRecord(item, "profile")?.name) || stringValue(item?.name);
    })
    .filter(Boolean);
}

function bestCoverUrl(track: UnknownRecord): string {
  const album = nestedRecord(track, "albumOfTrack") ?? nestedRecord(track, "album");
  const coverArt = nestedRecord(album, "coverArt") ?? nestedRecord(track, "coverArt");
  const sources = [
    ...asArray(coverArt?.sources),
    ...asArray(album?.images),
    ...asArray(track.images),
  ];
  let best = "";
  let bestArea = -1;
  for (const sourceValue of sources) {
    const source = asRecord(sourceValue);
    const url = stringValue(source?.url);
    if (!url) continue;
    const width = finiteNumber(source?.width) ?? 0;
    const height = finiteNumber(source?.height) ?? width;
    const area = width * height;
    if (area > bestArea) {
      best = url;
      bestArea = area;
    }
  }
  return best;
}

function trackDurationMs(track: UnknownRecord): number {
  return (
    finiteNumber(nestedRecord(track, "duration")?.totalMilliseconds) ??
    finiteNumber(nestedRecord(track, "duration")?.milliseconds) ??
    finiteNumber(nestedRecord(track, "trackDuration")?.totalMilliseconds) ??
    finiteNumber(track.durationMs) ??
    finiteNumber(track.duration_ms) ??
    0
  );
}

function trackIsrc(track: UnknownRecord): string {
  const externalIds = nestedRecord(track, "externalIds") ?? nestedRecord(track, "external_ids");
  const direct = stringValue(externalIds?.isrc) || stringValue(track.isrc);
  if (direct) return direct;
  const item = asArray(externalIds?.items)
    .map(asRecord)
    .find(
      (identifier) =>
        stringValue(identifier?.type).toLocaleLowerCase() === "isrc" ||
        stringValue(identifier?.key).toLocaleLowerCase() === "isrc"
    );
  return stringValue(item?.value) || stringValue(item?.id);
}

function creatorTrackFromInternalData(track: UnknownRecord): CreatorTrack | null {
  const rawUri = stringValue(track.uri);
  const rawId = stringValue(track.id);
  const uri = normalizeSpotifyTrackUri(rawUri || rawId);
  if (!uri) return null;
  const typename = stringValue(track.__typename);
  if (typename && typename !== "Track") return null;
  const album = nestedRecord(track, "albumOfTrack") ?? nestedRecord(track, "album");
  return {
    uri,
    name: stringValue(track.name) || "Unknown track",
    artists: artistNames(track),
    album: stringValue(album?.name),
    coverUrl: bestCoverUrl(track),
    durationMs: trackDurationMs(track),
    isrc: trackIsrc(track),
  };
}

/** Maps the live Spotify client's `searchModalResults` response into Creator tracks. */
export function creatorTracksFromSearchResponse(payload: unknown): CreatorTrack[] {
  const root = dataRoot(payload);
  const searchV2 = nestedRecord(root, "searchV2");
  const topResults = nestedRecord(searchV2, "topResultsV2") ?? nestedRecord(searchV2, "topResults");
  const trackResults = nestedRecord(searchV2, "tracksV2") ?? nestedRecord(searchV2, "tracks");
  const legacyResults = nestedRecord(root, "tracks");
  const entries = [topResults, trackResults, legacyResults].flatMap((container) => [
    ...asArray(container?.itemsV2),
    ...asArray(container?.items),
  ]);
  const tracks: CreatorTrack[] = [];
  const seen = new Set<string>();
  for (const entryValue of entries) {
    const entry = asRecord(entryValue);
    const item = asRecord(entry?.item) ?? asRecord(entry?.typedEntity) ?? entry;
    const data = asRecord(item?.data) ?? item;
    if (!data) continue;
    const track = creatorTrackFromInternalData(data);
    if (!track || seen.has(track.uri)) continue;
    seen.add(track.uri);
    tracks.push(track);
  }
  return tracks;
}

function graphQlErrorMessage(payload: unknown): string {
  const root = asRecord(payload);
  return asArray(root?.errors)
    .map((error) => stringValue(asRecord(error)?.message))
    .filter(Boolean)
    .join("; ");
}

function friendlySpotifySearchError(error: unknown): Error {
  const source = error instanceof Error ? error : new Error(String(error));
  if (/\b429\b|rate.?limit|too many requests/iu.test(source.message)) {
    return new Error("Spotify search is temporarily busy. Wait a moment and try again.");
  }
  return source;
}

async function requestInternalSpotifySearch(
  query: string,
  signal: AbortSignal | undefined,
  api: CreatorSpotifyInternalApi
): Promise<CreatorTrack[]> {
  abortCreatorRequest(signal);
  const definition = api.definitions.searchModalResults;
  if (!definition) {
    throw new Error("Spotify's internal search is unavailable in this client build.");
  }
  try {
    const response = await api.request(
      definition,
      {
        limit: 12,
        numberOfTopResults: 12,
        offset: 0,
        searchTerm: query,
        includeAuthors: false,
      },
      { persistCache: true }
    );
    abortCreatorRequest(signal);
    const graphQlError = graphQlErrorMessage(response);
    if (graphQlError) throw new Error(`Spotify search failed: ${graphQlError}`);
    return creatorTracksFromSearchResponse(response).slice(0, 12);
  } catch (error) {
    abortCreatorRequest(signal);
    throw friendlySpotifySearchError(error);
  }
}

async function requestInternalSpotifyDetail(
  api: CreatorSpotifyInternalApi,
  definition: unknown,
  variables: Record<string, unknown>
): Promise<unknown> {
  const response = await api.request(definition, variables);
  const graphQlError = graphQlErrorMessage(response);
  if (graphQlError) throw new Error(`Spotify track lookup failed: ${graphQlError}`);
  return response;
}

export function normalizeSpotifyTrackUri(input: string): string | null {
  const trimmed = input.trim();
  if (/^spotify:track:[A-Za-z0-9]{22}$/u.test(trimmed)) return trimmed;
  const urlMatch = trimmed.match(/open\.spotify\.com\/track\/([A-Za-z0-9]{22})/u);
  if (urlMatch) return `spotify:track:${urlMatch[1]}`;
  if (/^[A-Za-z0-9]{22}$/u.test(trimmed)) return `spotify:track:${trimmed}`;
  return null;
}

export async function searchSpotifyTracks(
  query: string,
  signal?: AbortSignal,
  api?: CreatorSpotifyInternalApi
): Promise<CreatorTrack[]> {
  const searchTerm = query.trim();
  if (!searchTerm) return [];
  const internalApi = api ?? defaultSpotifyInternalApi();
  const directUri = normalizeSpotifyTrackUri(searchTerm);
  if (directUri) {
    const track = await getSpotifyTrack(directUri, signal, internalApi);
    return track ? [track] : [];
  }
  return requestInternalSpotifySearch(searchTerm, signal, internalApi);
}

export async function getSpotifyTrack(
  uri: string,
  signal?: AbortSignal,
  api?: CreatorSpotifyInternalApi
): Promise<CreatorTrack | null> {
  const normalized = normalizeSpotifyTrackUri(uri);
  if (!normalized) return null;
  const internalApi = api ?? defaultSpotifyInternalApi();
  abortCreatorRequest(signal);
  if (typeof Spicetify !== "undefined") {
    const current = currentSpotifyTrack();
    if (current?.uri === normalized) return current;
  }

  let searchError: unknown = null;
  try {
    const results = await requestInternalSpotifySearch(normalized, signal, internalApi);
    const exact = results.find((track) => track.uri === normalized);
    if (exact) return exact;
  } catch (error) {
    if ((error as Error)?.name === "AbortError") throw error;
    searchError = error;
  }

  const nameDefinition = internalApi.definitions.getTrackName;
  const artistsDefinition = internalApi.definitions.queryTrackArtists;
  if (!nameDefinition && !artistsDefinition) {
    if (searchError) throw friendlySpotifySearchError(searchError);
    return null;
  }

  try {
    const [nameResult, artistsResult] = await Promise.allSettled([
      nameDefinition
        ? requestInternalSpotifyDetail(internalApi, nameDefinition, { uri: normalized })
        : Promise.resolve(null),
      artistsDefinition
        ? requestInternalSpotifyDetail(internalApi, artistsDefinition, {
            trackUri: normalized,
          })
        : Promise.resolve(null),
    ]);
    abortCreatorRequest(signal);
    const nameResponse = nameResult.status === "fulfilled" ? nameResult.value : null;
    const artistsResponse = artistsResult.status === "fulfilled" ? artistsResult.value : null;
    const nameTrack = nestedRecord(dataRoot(nameResponse), "trackUnion");
    const artistsTrack = nestedRecord(dataRoot(artistsResponse), "trackUnion");
    const name = stringValue(nameTrack?.name) || stringValue(artistsTrack?.name);
    const artists = artistNames(artistsTrack ?? {});
    if (artists.length === 0 && nameTrack) artists.push(...artistNames(nameTrack));
    if (!name && artists.length === 0) {
      const detailError = [nameResult, artistsResult].find(
        (result): result is PromiseRejectedResult => result.status === "rejected"
      )?.reason;
      if (detailError) throw detailError;
      if (searchError) throw searchError;
      return null;
    }
    return {
      uri: normalized,
      name: name || "Unknown track",
      artists,
      album:
        stringValue(nestedRecord(nameTrack, "albumOfTrack")?.name) ||
        stringValue(nestedRecord(artistsTrack, "albumOfTrack")?.name),
      coverUrl: bestCoverUrl(nameTrack ?? {}) || bestCoverUrl(artistsTrack ?? {}),
      durationMs: trackDurationMs(nameTrack ?? {}) || trackDurationMs(artistsTrack ?? {}),
      isrc: trackIsrc(nameTrack ?? {}) || trackIsrc(artistsTrack ?? {}),
    };
  } catch (error) {
    abortCreatorRequest(signal);
    throw friendlySpotifySearchError(error);
  }
}

export function currentSpotifyTrack(): CreatorTrack | null {
  const item = Spicetify?.Player?.data?.item;
  if (!item?.uri?.startsWith("spotify:track:")) return null;
  const images = [...(item.images ?? [])];
  return {
    uri: item.uri,
    name: item.name ?? "Unknown track",
    artists: (item.artists ?? []).map((artist) => artist.name).filter(Boolean),
    album: item.album?.name ?? item.metadata?.album_title ?? "",
    coverUrl: images.find((image) => image.label === "xlarge")?.url ?? images[0]?.url ?? "",
    durationMs: item.duration?.milliseconds ?? 0,
    isrc: item.metadata?.isrc ?? "",
  };
}

export function creatorMetadataFromTrack(track: CreatorTrack): Partial<CreatorMetadata> {
  return {
    name: track.name,
    artists: track.artists,
    albums: track.album ? [track.album] : [],
    spotifyTrackId: track.uri.split(":")[2] ?? "",
    isrc: track.isrc,
  };
}

function sourceFromLyrics(
  lyrics: Record<string, any>,
  fallback: CreatorSourceProvenance
): CreatorSourceProvenance {
  const code = ["spt", "aml", "spl", "ldb"].includes(lyrics.source) ? lyrics.source : fallback.code;
  const labels: Record<string, string> = {
    spt: "Spotify",
    aml: "Apple Music",
    spl: "Lyrics database",
    ldb: "Local TTML",
  };
  return {
    code,
    label: labels[code] ?? fallback.label,
    maker:
      lyrics?.TTMLUploadMetadata?.Maker?.username ??
      lyrics?.Creator?.Name ??
      lyrics?.Maker?.Username ??
      fallback.maker,
    uploader:
      lyrics?.TTMLUploadMetadata?.Uploader?.username ??
      lyrics?.Uploader?.Username ??
      fallback.uploader,
    raw: lyrics?.TTMLUploadMetadata ?? lyrics?.sourceInfo ?? fallback.raw,
  };
}

function inMemoryLyrics(uri: string): Record<string, any> | null {
  const raw = $currentLyricsData.get();
  if (!raw || raw.startsWith("NO_LYRICS:")) return null;
  try {
    const lyrics = normalizeLyricsSchema(JSON.parse(raw));
    const localDisabled = lyrics?.source === "ldb" && !$useLocalTtmlLyrics.get();
    return isLyricsObject(lyrics) && lyrics.uri === uri && !localDisabled ? lyrics : null;
  } catch {
    return null;
  }
}

function unavailableCreatorSource(
  preference: CreatorLyricsSourcePreference,
  reason = "was not available for this song"
): CreatorLyricsLoadResult {
  return {
    lyrics: null,
    source: {
      code: "draft",
      label: `${creatorLyricsSourcePreferenceLabel(preference)} ${reason}`,
    },
  };
}

export async function loadLyricsForCreator(
  uri: string,
  signal?: AbortSignal,
  preference: CreatorLyricsSourcePreference = "auto"
): Promise<CreatorLyricsLoadResult> {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  if (preference === "auto" || preference === "ldb") {
    // Selecting Local TTML in Creator is an explicit request, so it may inspect
    // the saved record even when normal playback has local lyrics disabled.
    const shouldReadLocal = preference === "ldb" || $useLocalTtmlLyrics.get();
    const localRecord = shouldReadLocal ? await LocalLyricsManager.getRecord(uri) : null;
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    if (localRecord?.parsed?.lyrics && isLyricsObject(localRecord.parsed.lyrics)) {
      const lyrics = normalizeLyricsSchema(structuredClone(localRecord.parsed.lyrics));
      lyrics.uri = uri;
      lyrics.source = "ldb";
      return {
        lyrics,
        source: sourceFromLyrics(lyrics, { code: "ldb", label: "Local TTML" }),
      };
    }
    if (preference === "ldb") return unavailableCreatorSource(preference);

    const memory = inMemoryLyrics(uri);
    if (memory) {
      return {
        lyrics: memory,
        source: sourceFromLyrics(memory, { code: "spl", label: "Loaded lyrics" }),
      };
    }

    const cached = await LyricsStore.GetItem(uri);
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    if (isLyricsObject(cached)) {
      const lyrics = normalizeLyricsSchema({ ...cached, uri });
      return {
        lyrics,
        source: sourceFromLyrics(lyrics, { code: "spl", label: "Icy lyrics cache" }),
      };
    }
  }

  const trackId = uri.split(":")[2];
  if (!trackId) return { lyrics: null, source: { code: "draft", label: "No source" } };
  const accessToken = await Platform.GetSpotifyAccessToken();
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  const result = await Query(
    [{ operation: "lyrics", variables: buildCreatorLyricsQueryVariables(trackId, preference) }],
    { "SpicyLyrics-WebAuth": `Bearer ${accessToken}` }
  );
  const lyricResult = result.get("0");
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  if (!lyricResult || lyricResult.httpStatus !== 200) {
    if (preference !== "auto" && lyricResult?.httpStatus === 404) {
      return unavailableCreatorSource(preference);
    }
    return {
      lyrics: null,
      source: {
        code: "draft",
        label:
          lyricResult?.httpStatus === 503
            ? `${creatorLyricsSourcePreferenceLabel(preference)} request queued`
            : "No lyrics found",
      },
    };
  }

  const lyrics = await decodeLyricsPayload(lyricResult.data);
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  if (!lyrics) return { lyrics: null, source: { code: "draft", label: "No lyrics found" } };
  await ProcessLyrics(lyrics);
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
  lyrics.uri = uri;
  if (preference !== "auto" && lyrics.source !== preference) {
    return unavailableCreatorSource(preference);
  }
  return {
    lyrics,
    source: sourceFromLyrics(lyrics, { code: "spl", label: "Lyrics database" }),
  };
}
