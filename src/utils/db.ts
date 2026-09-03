import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import Logger from "./Logger.ts";

const dbLogger = new Logger("Local TTML Database");

export const ICY_LYRICS_DB_NAME = "icylyrics";
export const ICY_LYRICS_DB_VERSION = 2;
export const LOCAL_TTML_RECORD_VERSION = 1 as const;
export const LOCAL_TTML_CACHE_VERSION = 1;

export const ObjectStores = {
  LocalTtmlByUri: "localTtmlByUri",
  Metadata: "metadata",
  CreatorDrafts: "creatorDrafts",
  // Compatibility name for integrations that exposed the upstream DB object.
  LyricsStore: "localTtmlByUri",
} as const;

export type LocalTtmlOrigin = "import" | "legacy-localstorage";

export interface LocalTtmlRecord {
  uri: string;
  recordVersion: typeof LOCAL_TTML_RECORD_VERSION;
  rawTtml: string | null;
  createdAt: number;
  updatedAt: number;
  parsed: {
    cacheVersion: number;
    parsedAt: number;
    lyrics: Record<string, any>;
  };
  origin: LocalTtmlOrigin;
}

export interface LocalTtmlMigrationStatus {
  complete: boolean;
  migrated: number;
  completedAt: number | null;
  persistenceGranted: boolean | null;
  errors: string[];
}

export interface CreatorDraftRecord {
  id: string;
  uri: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  /**
   * Optional presentation snapshot for the Creator draft library. Drafts that
   * predate this field remain readable; the library falls back to project
   * metadata when it is absent.
   */
  song?: {
    uri: string;
    name: string;
    artists: string[];
    album: string;
    coverUrl: string;
  };
  project: unknown;
}

interface IcyLyricsDB extends DBSchema {
  localTtmlByUri: {
    key: string;
    value: LocalTtmlRecord;
  };
  metadata: {
    key: string;
    value: unknown;
  };
  creatorDrafts: {
    key: string;
    value: CreatorDraftRecord;
    indexes: { "by-updated-at": number };
  };
}

export const dbPromise: Promise<IDBPDatabase<IcyLyricsDB>> = openDB<IcyLyricsDB>(
  ICY_LYRICS_DB_NAME,
  ICY_LYRICS_DB_VERSION,
  {
    upgrade(db) {
      if (!db.objectStoreNames.contains(ObjectStores.LocalTtmlByUri)) {
        db.createObjectStore(ObjectStores.LocalTtmlByUri, { keyPath: "uri" });
      }
      if (!db.objectStoreNames.contains(ObjectStores.Metadata)) {
        db.createObjectStore(ObjectStores.Metadata);
      }
      if (!db.objectStoreNames.contains(ObjectStores.CreatorDrafts)) {
        const creatorDrafts = db.createObjectStore(ObjectStores.CreatorDrafts, {
          keyPath: "id",
        });
        creatorDrafts.createIndex("by-updated-at", "updatedAt");
      }
    },
    blocked() {
      dbLogger.warn("Database upgrade is blocked by another Spotify window");
    },
    blocking() {
      dbLogger.warn("A newer database version requested this connection to close");
    },
    terminated() {
      dbLogger.error("IndexedDB connection terminated unexpectedly");
    },
  }
);

export async function ensurePersistence(): Promise<boolean> {
  try {
    const storage = globalThis.navigator?.storage;
    if (!storage?.persist || !storage?.persisted) {
      dbLogger.warn("Storage persistence API is unavailable");
      return false;
    }
    if (await storage.persisted()) return true;

    const granted = await storage.persist();
    if (!granted) {
      dbLogger.warn("Persistent storage was denied; IndexedDB still survives normal restarts");
    } else {
      dbLogger.debug("Persistent storage was granted");
    }
    return granted;
  } catch (error) {
    dbLogger.warn("Persistence request failed", error);
    return false;
  }
}
