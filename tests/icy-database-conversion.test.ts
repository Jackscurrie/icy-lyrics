import { DOMParser } from "@xmldom/xmldom";
import { beforeAll, describe, expect, it } from "vitest";
import type { IcyDatabaseLookupResult } from "../src/utils/API/IcyLyricsDatabase.ts";
import { lyricsFromIcyDatabaseLookup } from "../src/utils/Lyrics/IcyLyricsDatabase.ts";
import { normalizeLyricsSchema } from "../src/utils/Lyrics/schema.ts";

const TRACK_URI = "spotify:track:0123456789ABCDEFGHIJKL";
const RAW_TTML = `<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
  <head><metadata><itunes:timing>Line</itunes:timing></metadata></head>
  <body><div><p begin="00:00:01.000" end="00:00:03.000">From the Icy database</p></div></body>
</tt>`;

beforeAll(() => {
  Object.defineProperty(globalThis, "DOMParser", {
    value: DOMParser,
    configurable: true,
  });
});

describe("Icy Lyrics Database renderer conversion", () => {
  it("converts valid raw TTML locally with exact-URI identity and Icy provenance", async () => {
    const lookup: IcyDatabaseLookupResult = {
      kind: "found",
      rawTtml: RAW_TTML,
      recordId: "4b88d461-e87d-4c0e-a096-9e2faf0af654",
      revision: 7,
      contentSha256: "a".repeat(64),
    };

    const lyrics = await lyricsFromIcyDatabaseLookup(lookup, TRACK_URI);

    expect(lyrics).toMatchObject({
      Type: "Line",
      uri: TRACK_URI,
      source: "icy",
      IcyLyricsDatabase: {
        recordId: lookup.recordId,
        revision: lookup.revision,
        contentSha256: lookup.contentSha256,
      },
    });
    expect(lyrics?.Content[0]).toMatchObject({
      Type: "Vocal",
      Text: "From the Icy database",
      StartTime: 1,
      EndTime: 3,
    });
  });

  it("accepts the legacy Provider field with the Icy Lyrics Database name alias", () => {
    const lyrics = normalizeLyricsSchema({
      Type: "Static",
      Provider: "Icy Lyrics Database",
      Lines: [{ Text: "Legacy source name" }],
    });

    expect((lyrics as Record<string, unknown>).source).toBe("icy");
    expect(lyrics.Provider).toBe("Icy Lyrics Database");
  });
});
