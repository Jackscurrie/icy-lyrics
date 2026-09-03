import { DOMParser } from "@xmldom/xmldom";
import { beforeAll, describe, expect, it } from "vitest";
import { decodeLyricsPayload } from "../src/utils/Lyrics/payload.ts";

const BACKGROUND_TTML = `<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
    xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
  <head><metadata><itunes:timing>Word</itunes:timing></metadata></head>
  <body><div>
    <p begin="00:00:01.000" end="00:00:04.000">
      <span begin="00:00:01.000" end="00:00:01.500">Lead </span>
      <span begin="00:00:01.500" end="00:00:02.000">vocal </span>
      <span ttm:role="x-bg" begin="00:00:02.000" end="00:00:04.000">
        <span begin="00:00:02.000" end="00:00:03.000">Back</span>
      </span>
    </p>
  </div></body>
</tt>`;

beforeAll(() => {
  Object.defineProperty(globalThis, "DOMParser", {
    value: DOMParser,
    configurable: true,
  });
});

describe("raw TTML background-vocal conversion", () => {
  it("converts the PR compatibility path's background timings to seconds", async () => {
    const lyrics = await decodeLyricsPayload(BACKGROUND_TTML);

    expect(lyrics?.Type).toBe("Syllable");
    expect(lyrics?.Content[0].Background[0]).toMatchObject({
      StartTime: 2,
      EndTime: 3,
      Syllables: [{ Text: "Back", StartTime: 2, EndTime: 3 }],
    });
  });
});
