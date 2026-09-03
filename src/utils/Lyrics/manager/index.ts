import {
  dbPromise,
  ensurePersistence,
  LOCAL_TTML_CACHE_VERSION,
  LOCAL_TTML_RECORD_VERSION,
  type LocalTtmlMigrationStatus,
  type LocalTtmlRecord,
  ObjectStores,
} from "../../db.ts";
import Logger from "../../Logger.ts";
import { $useLocalTtmlLyrics } from "../../stores.ts";
import { ProcessLyrics } from "../ProcessLyrics.ts";
import { isLyricsObject, normalizeLyricsSchema } from "../schema.ts";
import { ParseTTML } from "./parseTTML.ts";

const logger = new Logger("Local Lyrics Manager");
const LEGACY_MIGRATION_KEY = "legacyLocalTtmlMigrationV1";
const LEGACY_STORAGE_KEYS = [
  "IcyLyrics-rememberedTTMLLyrics",
  "SpicyLyrics-rememberedTTMLLyrics",
] as const;

let initialization: Promise<LocalTtmlMigrationStatus> | null = null;
let latestStatus: LocalTtmlMigrationStatus = {
  complete: false,
  migrated: 0,
  completedAt: null,
  persistenceGranted: null,
  errors: [],
};

export class InvalidLocalTtmlError extends Error {
  constructor() {
    super("The selected file is not valid or supported TTML.");
    this.name = "InvalidLocalTtmlError";
  }
}

export class LocalTtmlPersistenceError extends Error {
  constructor(
    message: string,
    public readonly lyrics: Record<string, any>,
    options?: ErrorOptions
  ) {
    super(message, options);
    this.name = "LocalTtmlPersistenceError";
  }
}

function getSpicetifyStorage(): typeof Spicetify.LocalStorage | null {
  return typeof Spicetify !== "undefined" && Spicetify.LocalStorage
    ? Spicetify.LocalStorage
    : null;
}

function cloneLyrics(lyrics: Record<string, any>): Record<string, any> {
  return JSON.parse(JSON.stringify(lyrics)) as Record<string, any>;
}

export function normalizeLegacyTtmlUri(key: string): string | null {
  const trimmed = key.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("spotify:")) return trimmed;
  if (/^[A-Za-z0-9]{22}$/.test(trimmed)) return `spotify:track:${trimmed}`;
  return null;
}

function legacyLyrics(value: unknown): Record<string, any> | null {
  if (value === null || typeof value !== "object") return null;
  const wrapper = value as Record<string, unknown>;
  const candidate = wrapper.Result ?? value;
  if (!isLyricsObject(candidate)) return null;
  return normalizeLyricsSchema(cloneLyrics(candidate));
}

export function legacyEntryToRecord(
  key: string,
  value: unknown,
  now = Date.now()
): LocalTtmlRecord | null {
  const uri = normalizeLegacyTtmlUri(key);
  const lyrics = legacyLyrics(value);
  if (!uri || !lyrics) return null;

  return {
    uri,
    recordVersion: LOCAL_TTML_RECORD_VERSION,
    rawTtml: null,
    createdAt: now,
    updatedAt: now,
    parsed: {
      cacheVersion: LOCAL_TTML_CACHE_VERSION,
      parsedAt: now,
      lyrics,
    },
    origin: "legacy-localstorage",
  };
}

function readLegacyMaps(): {
  records: Map<string, LocalTtmlRecord>;
  presentKeys: string[];
  errors: string[];
} {
  const storage = getSpicetifyStorage();
  const records = new Map<string, LocalTtmlRecord>();
  const presentKeys: string[] = [];
  const errors: string[] = [];
  if (!storage) return { records, presentKeys, errors };

  const parsedMaps = new Map<string, Record<string, unknown>>();
  for (const key of LEGACY_STORAGE_KEYS) {
    const raw = storage.get(key);
    if (raw === null || raw === undefined || raw === "") continue;
    presentKeys.push(key);
    try {
      const parsed = JSON.parse(String(raw));
      if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("value is not an object map");
      }
      parsedMaps.set(key, parsed as Record<string, unknown>);
    } catch (error) {
      errors.push(`${key}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  if (errors.length > 0) return { records, presentKeys, errors };

  // Spicy is the fallback; Icy is applied last and wins duplicate URI entries.
  for (const key of [...LEGACY_STORAGE_KEYS].reverse()) {
    const map = parsedMaps.get(key);
    if (!map) continue;
    for (const [legacyKey, value] of Object.entries(map)) {
      const record = legacyEntryToRecord(legacyKey, value);
      if (record) records.set(record.uri, record);
    }
  }

  return { records, presentKeys, errors };
}

async function migrateLegacyLocalStorage(): Promise<LocalTtmlMigrationStatus> {
  const db = await dbPromise;
  const previous = await db.get(ObjectStores.Metadata, LEGACY_MIGRATION_KEY);
  if (previous && typeof previous === "object") {
    return previous as LocalTtmlMigrationStatus;
  }

  const { records, presentKeys, errors } = readLegacyMaps();
  if (errors.length > 0) {
    logger.error("Legacy local TTML migration was left pending", errors);
    return {
      complete: false,
      migrated: 0,
      completedAt: null,
      persistenceGranted: latestStatus.persistenceGranted,
      errors,
    };
  }

  const completedAt = Date.now();
  const status: LocalTtmlMigrationStatus = {
    complete: true,
    migrated: records.size,
    completedAt,
    persistenceGranted: latestStatus.persistenceGranted,
    errors: [],
  };

  const tx = db.transaction(
    [ObjectStores.LocalTtmlByUri, ObjectStores.Metadata],
    "readwrite"
  );
  for (const record of records.values()) {
    const existing = await tx.objectStore(ObjectStores.LocalTtmlByUri).get(record.uri);
    if (!existing) await tx.objectStore(ObjectStores.LocalTtmlByUri).put(record);
  }
  await tx.objectStore(ObjectStores.Metadata).put(status, LEGACY_MIGRATION_KEY);
  await tx.done;

  // LocalStorage is cleared only after the complete IDB transaction commits.
  const storage = getSpicetifyStorage();
  if (storage) {
    for (const key of presentKeys) storage.remove(key);
  }

  if (records.size > 0) logger.info(`Migrated ${records.size} remembered TTML entries`);
  return status;
}

async function init(): Promise<LocalTtmlMigrationStatus> {
  if (initialization) return initialization;

  initialization = (async () => {
    await dbPromise;
    const persistenceGranted = await ensurePersistence();
    latestStatus = { ...latestStatus, persistenceGranted };
    latestStatus = await migrateLegacyLocalStorage();
    latestStatus.persistenceGranted = persistenceGranted;
    return { ...latestStatus, errors: [...latestStatus.errors] };
  })().catch((error) => {
    const message = error instanceof Error ? error.message : String(error);
    logger.error("Initialization failed", error);
    latestStatus = {
      ...latestStatus,
      complete: false,
      errors: [...latestStatus.errors, message],
    };
    return { ...latestStatus, errors: [...latestStatus.errors] };
  });

  return initialization;
}

async function buildRecord(
  uri: string,
  rawTtml: string,
  existing?: LocalTtmlRecord
): Promise<LocalTtmlRecord> {
  const parsedResult = await ParseTTML(rawTtml);
  if (!parsedResult?.Result || !isLyricsObject(parsedResult.Result)) {
    throw new InvalidLocalTtmlError();
  }

  const lyrics = normalizeLyricsSchema(cloneLyrics(parsedResult.Result));
  await ProcessLyrics(lyrics);
  lyrics.uri = uri;
  lyrics.source = "ldb";

  const now = Date.now();
  return {
    uri,
    recordVersion: LOCAL_TTML_RECORD_VERSION,
    rawTtml,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
    parsed: {
      cacheVersion: LOCAL_TTML_CACHE_VERSION,
      parsedAt: now,
      lyrics,
    },
    origin: "import",
  };
}

async function put(uri: string, rawTtml: string): Promise<LocalTtmlRecord> {
  if (!uri) throw new Error("A full Spotify URI is required");
  if (typeof rawTtml !== "string") throw new InvalidLocalTtmlError();
  await init();

  let existing: LocalTtmlRecord | undefined;
  try {
    existing = await (await dbPromise).get(ObjectStores.LocalTtmlByUri, uri);
  } catch {
    // Parsing still proceeds so a clear persistence error can carry session lyrics.
  }
  const record = await buildRecord(uri, rawTtml, existing);

  try {
    await (await dbPromise).put(ObjectStores.LocalTtmlByUri, record);
  } catch (error) {
    throw new LocalTtmlPersistenceError(
      "Lyrics were parsed, but Spotify could not save them permanently.",
      record.parsed.lyrics,
      { cause: error }
    );
  }
  return record;
}

async function get(uri: string): Promise<Record<string, any> | null> {
  if (!uri || !$useLocalTtmlLyrics.get()) return null;

  try {
    await init();
    const db = await dbPromise;
    const record = await db.get(ObjectStores.LocalTtmlByUri, uri);
    if (!record) return null;

    if (
      record.recordVersion === LOCAL_TTML_RECORD_VERSION &&
      record.parsed?.cacheVersion === LOCAL_TTML_CACHE_VERSION &&
      isLyricsObject(record.parsed.lyrics)
    ) {
      const lyrics = normalizeLyricsSchema(cloneLyrics(record.parsed.lyrics));
      lyrics.uri = uri;
      lyrics.source = "ldb";
      return lyrics;
    }

    if (!record.rawTtml) return null;
    const refreshed = await buildRecord(uri, record.rawTtml, record);
    await db.put(ObjectStores.LocalTtmlByUri, refreshed);
    return cloneLyrics(refreshed.parsed.lyrics);
  } catch (error) {
    logger.error("get failed", error, { uri });
    return null;
  }
}

async function getRecord(uri: string): Promise<LocalTtmlRecord | null> {
  try {
    await init();
    return (await (await dbPromise).get(ObjectStores.LocalTtmlByUri, uri)) ?? null;
  } catch (error) {
    logger.error("getRecord failed", error, { uri });
    return null;
  }
}

async function getRaw(uri: string): Promise<string | null> {
  const record = await getRecord(uri);
  return record?.rawTtml ?? null;
}

async function list(): Promise<LocalTtmlRecord[]> {
  try {
    await init();
    return (await (await dbPromise).getAll(ObjectStores.LocalTtmlByUri)).sort(
      (a, b) => b.updatedAt - a.updatedAt
    );
  } catch (error) {
    logger.error("list failed", error);
    return [];
  }
}

async function listKeys(): Promise<string[]> {
  return (await list()).map((record) => record.uri);
}

function removeFromLegacyMaps(uri: string): void {
  const storage = getSpicetifyStorage();
  if (!storage) return;
  const legacyId = uri.startsWith("spotify:track:") ? uri.split(":")[2] : null;

  for (const storageKey of LEGACY_STORAGE_KEYS) {
    const raw = storage.get(storageKey);
    if (raw === null || raw === undefined || raw === "") continue;
    let map: Record<string, unknown>;
    try {
      const parsed = JSON.parse(String(raw));
      if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("value is not an object map");
      }
      map = parsed as Record<string, unknown>;
    } catch (error) {
      throw new Error(`Could not update ${storageKey}`, { cause: error });
    }

    let changed = false;
    for (const key of [uri, legacyId]) {
      if (key && Object.prototype.hasOwnProperty.call(map, key)) {
        delete map[key];
        changed = true;
      }
    }
    if (!changed) continue;

    storage.set(storageKey, JSON.stringify(map));
    const verifiedRaw = storage.get(storageKey);
    const verified = verifiedRaw ? JSON.parse(String(verifiedRaw)) : null;
    if (
      verified === null ||
      typeof verified !== "object" ||
      Object.prototype.hasOwnProperty.call(verified, uri) ||
      (legacyId && Object.prototype.hasOwnProperty.call(verified, legacyId))
    ) {
      throw new Error(`Spotify did not retain the deletion in ${storageKey}`);
    }
  }
}

async function remove(uri: string): Promise<void> {
  if (!uri) return;
  await init();
  await (await dbPromise).delete(ObjectStores.LocalTtmlByUri, uri);
  removeFromLegacyMaps(uri);
}

function status(): LocalTtmlMigrationStatus {
  return { ...latestStatus, errors: [...latestStatus.errors] };
}

export const LocalLyricsManager = {
  init,
  get,
  getRecord,
  put,
  remove,
  list,
  listKeys,
  getRaw,
  status,
};
