import { DOMParser } from "@xmldom/xmldom";
import { beforeAll, describe, expect, it } from "vitest";
import { ProjectVersion, SpicyLyricsApiVersion } from "../project/config.ts";
import {
  API_COMPATIBILITY_VERSION,
  buildQueryBody,
  buildQueryHeaders,
} from "../src/utils/API/Query.ts";
import { SLObjPack } from "../src/utils/objpack.ts";
import { decodeLyricsPayload } from "../src/utils/Lyrics/payload.ts";
import { normalizeLyricsSchema } from "../src/utils/Lyrics/schema.ts";

const WORD_TTML = `<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
  <head><metadata><itunes:timing>Word</itunes:timing></metadata></head>
  <body><div>
    <p begin="00:00:01.000" end="00:00:03.000">
      <span begin="00:00:01.000" end="00:00:01.500">Hel</span><span begin="00:00:01.500" end="00:00:03.000">lo</span>
    </p>
  </div></body>
</tt>`;

beforeAll(() => {
  Object.defineProperty(globalThis, "DOMParser", {
    value: DOMParser,
    configurable: true,
  });
});

describe("Icy and Spicy Lyrics version compatibility", () => {
  it("keeps Icy's public version separate from the Spicy API compatibility version", () => {
    expect(ProjectVersion).toBe("1.0.0");
    expect(SpicyLyricsApiVersion).toBe("6.3.12");
    expect(API_COMPATIBILITY_VERSION).toBe(SpicyLyricsApiVersion);
  });

  it("always sends X-mode 2 and Spicy Lyrics 6.3.12 in the API headers and body", () => {
    expect(
      buildQueryHeaders({
        "SpicyLyrics-WebAuth": "Bearer secret",
        "SpicyLyrics-Version": ProjectVersion,
        "X-mode": "1",
      })
    ).toMatchObject({
      "Content-Type": "application/json",
      "SpicyLyrics-Version": SpicyLyricsApiVersion,
      "X-mode": "2",
      "SpicyLyrics-WebAuth": "Bearer secret",
    });
    expect(buildQueryBody([{ operation: "getLyrics", variables: { id: "abc" } }])).toEqual({
      queries: [{ operation: "getLyrics", variables: { id: "abc" } }],
      client: { version: SpicyLyricsApiVersion },
    });
  });

  it("decodes an SLObjPack payload and normalizes old romanization fields", async () => {
    const packed = new SLObjPack().pack({
      Type: "Line",
      IncludesRomanization: true,
      source: "Apple Music",
      Content: [
        {
          Type: "Vocal",
          Text: "Привет",
          RomanizedText: "Privet",
          StartTime: 1,
          EndTime: 2,
        },
      ],
    });

    const lyrics = await decodeLyricsPayload(packed);
    expect(lyrics?.HasTransliterations).toBe(true);
    expect(lyrics?.source).toBe("aml");
    expect(lyrics?.Content[0].TransliteratedText).toBe("Privet");
  });

  it("keeps current transliteration fields while accepting legacy aliases", () => {
    const value = normalizeLyricsSchema({
      Type: "Static",
      HasTransliterations: false,
      Lines: [{ Text: "x", RomanizedText: "old", TransliteratedText: "new" }],
    });
    expect(value.HasTransliterations).toBe(true);
    expect(value.Lines[0].TransliteratedText).toBe("new");
  });

  it("parses a real raw TTML response locally with millisecond-to-second conversion", async () => {
    const lyrics = await decodeLyricsPayload(WORD_TTML);
    expect(lyrics?.Type).toBe("Syllable");
    expect(lyrics?.source).toBe("spl");
    expect(lyrics?.Content[0].Lead.StartTime).toBe(1);
    expect(lyrics?.Content[0].Lead.EndTime).toBe(3);
    expect(lyrics?.Content[0].Lead.Syllables[0]).toMatchObject({
      Text: "Hel",
      StartTime: 1,
      EndTime: 1.5,
    });
  });

  it("accepts packed raw TTML and Result-wrapped raw TTML", async () => {
    const packer = new SLObjPack();
    expect((await decodeLyricsPayload(packer.pack(WORD_TTML)))?.Type).toBe("Syllable");
    expect((await decodeLyricsPayload({ Result: WORD_TTML }))?.source).toBe("spl");
  });

  it("handles malformed object-pack data without throwing", async () => {
    await expect(decodeLyricsPayload([["value"], [-1, 999]])).resolves.toBeNull();
  });
});
