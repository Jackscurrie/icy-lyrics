import { DOMParser } from "@xmldom/xmldom";
import "fake-indexeddb/auto";
import { openDB } from "idb";
import { beforeAll, describe, expect, it } from "vitest";
import {
  ICY_LYRICS_DB_NAME,
  ICY_LYRICS_DB_VERSION,
  ObjectStores,
  dbPromise,
} from "../src/utils/db.ts";
import {
  InvalidLocalTtmlError,
  LocalLyricsManager,
  LocalTtmlPersistenceError,
} from "../src/utils/Lyrics/manager/index.ts";
import { $useLocalTtmlLyrics } from "../src/utils/stores.ts";

const storageValues = new Map<string, string>();

const LINE_TTML = `<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
  <head><metadata><itunes:timing>Line</itunes:timing></metadata></head>
  <body><div><p begin="00:00:01.000" end="00:00:03.000">Persistent lyric</p></div></body>
</tt>`;

describe.sequential("durable local TTML repository", () => {
  const migratedId = "aaaaaaaaaaaaaaaaaaaaaa";
  const migratedUri = `spotify:track:${migratedId}`;
  const migratedLocalUri = "spotify:local:Artist:Album:Song:180";
  const importedUri = "spotify:track:bbbbbbbbbbbbbbbbbbbbbb";

  beforeAll(() => {
    Object.defineProperty(globalThis, "DOMParser", { value: DOMParser, configurable: true });
    Object.defineProperty(globalThis, "navigator", {
      value: {
        onLine: true,
        storage: {
          persisted: async () => false,
          persist: async () => true,
        },
      },
      configurable: true,
    });
    Object.defineProperty(globalThis, "Spicetify", {
      value: {
        LocalStorage: {
          get: (key: string) => storageValues.get(key) ?? null,
          set: (key: string, value: string) => storageValues.set(key, String(value)),
          remove: (key: string) => storageValues.delete(key),
        },
      },
      configurable: true,
    });

    storageValues.set(
      "SpicyLyrics-rememberedTTMLLyrics",
      JSON.stringify({
        [migratedId]: { Type: "Static", Lines: [{ Text: "Spicy fallback" }] },
      })
    );
    storageValues.set(
      "IcyLyrics-rememberedTTMLLyrics",
      JSON.stringify({
        [migratedId]: { Type: "Static", Lines: [{ Text: "Icy wins" }] },
        [migratedLocalUri]: {
          Type: "Static",
          Lines: [{ Text: "Exact local URI survives" }],
        },
      })
    );
    $useLocalTtmlLyrics.set(true);
  });

  it("transactionally migrates the Icy map before the Spicy fallback", async () => {
    const status = await LocalLyricsManager.init();
    const lyrics = await LocalLyricsManager.get(migratedUri);
    const localLyrics = await LocalLyricsManager.get(migratedLocalUri);

    expect(status).toMatchObject({ complete: true, migrated: 2, persistenceGranted: true });
    expect(lyrics?.Lines[0].Text).toBe("Icy wins");
    expect(lyrics?.source).toBe("ldb");
    expect(localLyrics).toMatchObject({
      uri: migratedLocalUri,
      source: "ldb",
      Lines: [{ Text: "Exact local URI survives" }],
    });
    expect(storageValues.has("IcyLyrics-rememberedTTMLLyrics")).toBe(false);
    expect(storageValues.has("SpicyLyrics-rememberedTTMLLyrics")).toBe(false);
  });

  it("persists raw TTML and processed lyrics under the exact URI", async () => {
    const record = await LocalLyricsManager.put(importedUri, LINE_TTML);
    expect(record.uri).toBe(importedUri);
    expect(record.rawTtml).toBe(LINE_TTML);
    expect(record.parsed.lyrics).toMatchObject({ Type: "Line", uri: importedUri, source: "ldb" });
    expect(await LocalLyricsManager.getRaw(importedUri)).toBe(LINE_TTML);
  });

  it("keeps records when use is disabled and still persists new imports", async () => {
    $useLocalTtmlLyrics.set(false);
    expect(await LocalLyricsManager.get(importedUri)).toBeNull();
    expect(await LocalLyricsManager.getRecord(importedUri)).not.toBeNull();

    const disabledImportUri = "spotify:track:cccccccccccccccccccccc";
    await LocalLyricsManager.put(disabledImportUri, LINE_TTML);
    expect((await LocalLyricsManager.getRecord(disabledImportUri))?.rawTtml).toBe(LINE_TTML);
    $useLocalTtmlLyrics.set(true);
  });

  it("does not overwrite a valid record when a replacement is invalid", async () => {
    const before = await LocalLyricsManager.getRecord(importedUri);
    await expect(LocalLyricsManager.put(importedUri, "not TTML")).rejects.toBeInstanceOf(
      InvalidLocalTtmlError
    );
    const after = await LocalLyricsManager.getRecord(importedUri);
    expect(after).toEqual(before);
  });

  it("is readable from a newly opened database connection", async () => {
    const reopened = await openDB(ICY_LYRICS_DB_NAME, ICY_LYRICS_DB_VERSION);
    const record = await reopened.get(ObjectStores.LocalTtmlByUri, importedUri);
    expect(record.rawTtml).toBe(LINE_TTML);
    reopened.close();
  });

  it("awaits permanent deletion", async () => {
    storageValues.set(
      "IcyLyrics-rememberedTTMLLyrics",
      JSON.stringify({
        [importedUri]: { Type: "Static", Lines: [{ Text: "exact" }] },
        bbbbbbbbbbbbbbbbbbbbbb: { Type: "Static", Lines: [{ Text: "id" }] },
        untouched: { Type: "Static", Lines: [{ Text: "keep" }] },
      })
    );
    await LocalLyricsManager.remove(importedUri);
    expect(await LocalLyricsManager.getRecord(importedUri)).toBeNull();
    expect(JSON.parse(storageValues.get("IcyLyrics-rememberedTTMLLyrics")!)).toEqual({
      untouched: { Type: "Static", Lines: [{ Text: "keep" }] },
    });
  });

  it("carries session lyrics on write failure without overwriting the old record", async () => {
    const uri = "spotify:track:dddddddddddddddddddddd";
    await LocalLyricsManager.put(uri, LINE_TTML);
    (await dbPromise).close();

    const replacement = LINE_TTML.replace("Persistent lyric", "Replacement lyric");
    let thrown: unknown;
    try {
      await LocalLyricsManager.put(uri, replacement);
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(LocalTtmlPersistenceError);
    expect((thrown as LocalTtmlPersistenceError).lyrics.Content[0].Text).toContain(
      "Replacement"
    );

    const reopened = await openDB(ICY_LYRICS_DB_NAME, ICY_LYRICS_DB_VERSION);
    const retained = await reopened.get(ObjectStores.LocalTtmlByUri, uri);
    expect(retained.rawTtml).toBe(LINE_TTML);
    reopened.close();
  });
});
