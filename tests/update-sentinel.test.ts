import { describe, expect, it } from "vitest";
import { isSpicyUpdateSentinel } from "../src/utils/Lyrics/updateSentinel.ts";

describe("Spicy Lyrics update sentinel", () => {
  it("recognizes the decoded compatibility payload", () => {
    expect(
      isSpicyUpdateSentinel({
        Type: "Static",
        Content: [
          { Text: "Please update Spicy Lyrics" },
          { Text: "You can do so immediately by restarting Spotify" },
        ],
        SongWriters: ["the cool spicetify extension"],
      })
    ).toBe(true);
  });

  it("does not reject ordinary lyrics containing a partial marker", () => {
    expect(isSpicyUpdateSentinel({ Content: [{ Text: "Please update me" }] })).toBe(false);
    expect(isSpicyUpdateSentinel(null)).toBe(false);
  });
});
