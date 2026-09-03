import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import type * as CreatorDataExports from "../src/components/ReactComponents/LyricCreator/data.ts";
import type {
  CreatorSpotifyInternalApi,
  CreatorTrack,
} from "../src/components/ReactComponents/LyricCreator/data.ts";

type CreatorDataModule = typeof CreatorDataExports;

vi.mock("../src/components/Global/Platform.ts", () => ({
  default: { GetSpotifyAccessToken: vi.fn() },
}));
vi.mock("../src/utils/API/Query.ts", () => ({ Query: vi.fn() }));
vi.mock("../src/utils/stores.ts", () => ({
  $currentLyricsData: { get: vi.fn(() => null) },
  $useLocalTtmlLyrics: { get: vi.fn(() => false) },
}));
vi.mock("../src/utils/Lyrics/fetchLyrics.ts", () => ({
  LyricsStore: { GetItem: vi.fn() },
}));
vi.mock("../src/utils/Lyrics/manager/index.ts", () => ({
  LocalLyricsManager: { getRecord: vi.fn() },
}));
vi.mock("../src/utils/Lyrics/payload.ts", () => ({ decodeLyricsPayload: vi.fn() }));
vi.mock("../src/utils/Lyrics/ProcessLyrics.ts", () => ({ ProcessLyrics: vi.fn() }));
vi.mock("../src/utils/Lyrics/schema.ts", () => ({
  isLyricsObject: vi.fn(() => false),
  normalizeLyricsSchema: vi.fn((value) => value),
}));

const TRACK_ID = "0123456789ABCDEFGHIJKL";
const TRACK_URI = `spotify:track:${TRACK_ID}`;

const searchPayload = {
  data: {
    searchV2: {
      topResultsV2: {
        itemsV2: [
          {
            item: {
              data: {
                __typename: "Track",
                uri: TRACK_URI,
                name: "A Search Result",
                artists: {
                  items: [{ uri: "spotify:artist:one", profile: { name: "First Artist" } }],
                },
                albumOfTrack: {
                  name: "The Album",
                  coverArt: {
                    sources: [
                      { url: "https://image/small", width: 64, height: 64 },
                      { url: "https://image/large", width: 640, height: 640 },
                    ],
                  },
                },
                duration: { totalMilliseconds: 123_456 },
                externalIds: { isrc: "US-ICY-26-00001" },
              },
            },
          },
          {
            item: {
              data: {
                __typename: "Artist",
                uri: "spotify:artist:0123456789ABCDEFGHIJKL",
                name: "Not a track",
              },
            },
          },
          {
            item: {
              data: {
                __typename: "Track",
                uri: TRACK_URI,
                name: "Duplicate",
              },
            },
          },
        ],
      },
    },
  },
};

let creatorData: CreatorDataModule;

beforeAll(async () => {
  vi.stubGlobal("Spicetify", {
    Platform: {},
    CosmosAsync: {},
    Player: { data: null },
  });
  creatorData = await import("../src/components/ReactComponents/LyricCreator/data.ts");
});

afterAll(() => {
  vi.unstubAllGlobals();
});

function internalApi(
  request: CreatorSpotifyInternalApi["request"],
  definitions: Record<string, unknown> = { searchModalResults: { operation: "search" } }
): CreatorSpotifyInternalApi {
  return { definitions, request };
}

describe("Lyric Creator internal Spotify search", () => {
  it("maps Spotify's current searchModalResults wrappers and ignores non-tracks and duplicates", () => {
    expect(creatorData.creatorTracksFromSearchResponse(searchPayload)).toEqual<CreatorTrack[]>([
      {
        uri: TRACK_URI,
        name: "A Search Result",
        artists: ["First Artist"],
        album: "The Album",
        coverUrl: "https://image/large",
        durationMs: 123_456,
        isrc: "US-ICY-26-00001",
      },
    ]);
  });

  it("accepts unwrapped and legacy track-section field variants", () => {
    const alternateId = "ZYXWVUTSRQPONMLKJIHGFE";
    expect(
      creatorData.creatorTracksFromSearchResponse({
        searchV2: {
          tracksV2: {
            items: [
              {
                data: {
                  id: alternateId,
                  name: "Alternate Shape",
                  artists: [{ name: "Alternate Artist" }],
                  album: {
                    name: "Alternate Album",
                    images: [{ url: "https://image/alternate", width: 300 }],
                  },
                  duration_ms: 98_765,
                  external_ids: { isrc: "US-ICY-26-00002" },
                },
              },
            ],
          },
        },
      })
    ).toEqual([
      {
        uri: `spotify:track:${alternateId}`,
        name: "Alternate Shape",
        artists: ["Alternate Artist"],
        album: "Alternate Album",
        coverUrl: "https://image/alternate",
        durationMs: 98_765,
        isrc: "US-ICY-26-00002",
      },
    ]);
  });

  it("uses the client-owned GraphQL definition and never calls Spotify's public Web API", async () => {
    const fetchSpy = vi.fn(() => {
      throw new Error("Direct Web API fetch must not be used");
    });
    vi.stubGlobal("fetch", fetchSpy);
    const request = vi.fn().mockResolvedValue(searchPayload);

    const results = await creatorData.searchSpotifyTracks(
      "  A Search Result  ",
      undefined,
      internalApi(request)
    );

    expect(results).toHaveLength(1);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(request).toHaveBeenCalledWith(
      { operation: "search" },
      {
        limit: 12,
        numberOfTopResults: 12,
        offset: 0,
        searchTerm: "A Search Result",
        includeAuthors: false,
      },
      { persistCache: true }
    );
    vi.unstubAllGlobals();
    vi.stubGlobal("Spicetify", {
      Platform: {},
      CosmosAsync: {},
      Player: { data: null },
    });
  });

  it("resolves direct Spotify URIs through internal detail definitions when search has no match", async () => {
    const searchDefinition = { operation: "search" };
    const nameDefinition = { operation: "name" };
    const artistsDefinition = { operation: "artists" };
    const request = vi.fn(async (definition: unknown) => {
      if (definition === searchDefinition) {
        return { data: { searchV2: { topResultsV2: { itemsV2: [] } } } };
      }
      if (definition === nameDefinition) {
        return { data: { trackUnion: { __typename: "Track", name: "Direct Track" } } };
      }
      return {
        data: {
          trackUnion: {
            __typename: "Track",
            artists: { items: [{ profile: { name: "Direct Artist" } }] },
          },
        },
      };
    });
    const api = internalApi(request, {
      searchModalResults: searchDefinition,
      getTrackName: nameDefinition,
      queryTrackArtists: artistsDefinition,
    });

    await expect(creatorData.getSpotifyTrack(TRACK_URI, undefined, api)).resolves.toEqual(
      expect.objectContaining({
        uri: TRACK_URI,
        name: "Direct Track",
        artists: ["Direct Artist"],
      })
    );
    expect(request).toHaveBeenCalledWith(nameDefinition, { uri: TRACK_URI });
    expect(request).toHaveBeenCalledWith(artistsDefinition, { trackUri: TRACK_URI });
  });

  it("turns a 429-like internal error into a useful state without falling back to fetch", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const api = internalApi(vi.fn().mockRejectedValue(new Error("Request failed (429)")));

    await expect(creatorData.searchSpotifyTracks("rate limited", undefined, api)).rejects.toThrow(
      "Spotify search is temporarily busy. Wait a moment and try again."
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
    vi.stubGlobal("Spicetify", {
      Platform: {},
      CosmosAsync: {},
      Player: { data: null },
    });
  });

  it("rejects an in-flight result after cancellation instead of exposing stale tracks", async () => {
    let resolveRequest: (payload: unknown) => void = () => undefined;
    const request = vi.fn(
      () =>
        new Promise<unknown>((resolve) => {
          resolveRequest = resolve;
        })
    );
    const controller = new AbortController();
    const pending = creatorData.searchSpotifyTracks(
      "cancel this",
      controller.signal,
      internalApi(request)
    );
    controller.abort();
    resolveRequest(searchPayload);

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
});
