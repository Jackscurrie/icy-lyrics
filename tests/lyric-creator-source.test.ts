import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type * as CreatorDataExports from "../src/components/ReactComponents/LyricCreator/data.ts";

const mocks = vi.hoisted(() => ({
  getSpotifyAccessToken: vi.fn(),
  query: vi.fn(),
  currentLyricsData: vi.fn(),
  useLocalTtmlLyrics: vi.fn(),
  getCachedLyrics: vi.fn(),
  getLocalRecord: vi.fn(),
  decodeLyricsPayload: vi.fn(),
  processLyrics: vi.fn(),
  isLyricsObject: vi.fn(),
}));

vi.mock("../src/components/Global/Platform.ts", () => ({
  default: { GetSpotifyAccessToken: mocks.getSpotifyAccessToken },
}));
vi.mock("../src/utils/API/Query.ts", () => ({ Query: mocks.query }));
vi.mock("../src/utils/stores.ts", () => ({
  $currentLyricsData: { get: mocks.currentLyricsData },
  $useLocalTtmlLyrics: { get: mocks.useLocalTtmlLyrics },
}));
vi.mock("../src/utils/Lyrics/fetchLyrics.ts", () => ({
  LyricsStore: { GetItem: mocks.getCachedLyrics },
}));
vi.mock("../src/utils/Lyrics/manager/index.ts", () => ({
  LocalLyricsManager: { getRecord: mocks.getLocalRecord },
}));
vi.mock("../src/utils/Lyrics/payload.ts", () => ({
  decodeLyricsPayload: mocks.decodeLyricsPayload,
}));
vi.mock("../src/utils/Lyrics/ProcessLyrics.ts", () => ({
  ProcessLyrics: mocks.processLyrics,
}));
vi.mock("../src/utils/Lyrics/schema.ts", () => ({
  isLyricsObject: mocks.isLyricsObject,
  normalizeLyricsSchema: vi.fn((value) => value),
}));

const TRACK_ID = "0123456789ABCDEFGHIJKL";
const TRACK_URI = `spotify:track:${TRACK_ID}`;
type CreatorDataModule = typeof CreatorDataExports;

let creatorData: CreatorDataModule;

beforeAll(async () => {
  vi.stubGlobal("Spicetify", {
    Player: { data: null },
    Platform: {},
  });
  creatorData = await import("../src/components/ReactComponents/LyricCreator/data.ts");
});

beforeEach(() => {
  vi.clearAllMocks();
  mocks.currentLyricsData.mockReturnValue(null);
  mocks.useLocalTtmlLyrics.mockReturnValue(false);
  mocks.getLocalRecord.mockResolvedValue(null);
  mocks.getCachedLyrics.mockResolvedValue(null);
  mocks.getSpotifyAccessToken.mockResolvedValue("creator-token");
  mocks.isLyricsObject.mockReturnValue(false);
  mocks.processLyrics.mockResolvedValue(undefined);
});

afterAll(() => {
  vi.unstubAllGlobals();
});

function apiResult(data: unknown, httpStatus = 200) {
  return {
    get: vi.fn(() => ({ data, httpStatus, format: "json" as const })),
  };
}

describe("Lyric Creator source preference", () => {
  it("keeps Auto on the existing query contract", () => {
    expect(creatorData.buildCreatorLyricsQueryVariables(TRACK_ID, "auto")).toEqual({
      id: TRACK_ID,
      auth: "SpicyLyrics-WebAuth",
    });
  });

  it("adds a source hint only to explicit Creator remote requests", () => {
    expect(creatorData.buildCreatorLyricsQueryVariables(TRACK_ID, "aml")).toEqual({
      id: TRACK_ID,
      auth: "SpicyLyrics-WebAuth",
      source: "aml",
    });
    expect(creatorData.buildCreatorLyricsQueryVariables(TRACK_ID, "ldb")).toEqual({
      id: TRACK_ID,
      auth: "SpicyLyrics-WebAuth",
    });
  });

  it("loads saved TTML explicitly even when normal local playback is disabled", async () => {
    const localLyrics = { Type: "Static", Lines: [{ Text: "Saved locally" }], source: "spl" };
    mocks.getLocalRecord.mockResolvedValue({ parsed: { lyrics: localLyrics } });
    mocks.isLyricsObject.mockReturnValue(true);

    const loaded = await creatorData.loadLyricsForCreator(TRACK_URI, undefined, "ldb");

    expect(mocks.getLocalRecord).toHaveBeenCalledWith(TRACK_URI);
    expect(mocks.query).not.toHaveBeenCalled();
    expect(loaded.lyrics).toEqual(expect.objectContaining({ uri: TRACK_URI, source: "ldb" }));
    expect(loaded.source).toEqual(expect.objectContaining({ code: "ldb", label: "Local TTML" }));
  });

  it("bypasses local, memory, and expiring caches for an explicit remote source", async () => {
    const appleLyrics = { Type: "Static", Lines: [{ Text: "Apple" }], source: "aml" };
    mocks.useLocalTtmlLyrics.mockReturnValue(true);
    mocks.currentLyricsData.mockReturnValue(
      JSON.stringify({ ...appleLyrics, uri: TRACK_URI, source: "spl" })
    );
    mocks.getLocalRecord.mockResolvedValue({ parsed: { lyrics: appleLyrics } });
    mocks.getCachedLyrics.mockResolvedValue(appleLyrics);
    mocks.decodeLyricsPayload.mockResolvedValue(appleLyrics);
    mocks.query.mockResolvedValue(apiResult(appleLyrics));

    const loaded = await creatorData.loadLyricsForCreator(TRACK_URI, undefined, "aml");

    expect(mocks.getLocalRecord).not.toHaveBeenCalled();
    expect(mocks.getCachedLyrics).not.toHaveBeenCalled();
    expect(mocks.query).toHaveBeenCalledWith(
      [
        {
          operation: "lyrics",
          variables: { id: TRACK_ID, auth: "SpicyLyrics-WebAuth", source: "aml" },
        },
      ],
      { "SpicyLyrics-WebAuth": "Bearer creator-token" }
    );
    expect(loaded.lyrics).toEqual(expect.objectContaining({ source: "aml", uri: TRACK_URI }));
    expect(loaded.source.code).toBe("aml");
  });

  it("rejects a different backend response instead of mislabeling it", async () => {
    const appleLyrics = { Type: "Static", Lines: [{ Text: "Apple" }], source: "aml" };
    mocks.decodeLyricsPayload.mockResolvedValue(appleLyrics);
    mocks.query.mockResolvedValue(apiResult(appleLyrics));

    const loaded = await creatorData.loadLyricsForCreator(TRACK_URI, undefined, "spt");

    expect(loaded.lyrics).toBeNull();
    expect(loaded.source).toEqual({
      code: "draft",
      label: "Spotify was not available for this song",
    });
  });
});
