import { dbPromise, ObjectStores, type CreatorDraftRecord } from "../../../utils/db.ts";
import { createCreatorId, normalizeCreatorSource, type CreatorProject } from "./model.ts";

export interface CreatorDraftSongSnapshot {
  uri: string;
  name: string;
  artists: string[];
  album: string;
  coverUrl: string;
}

export interface CreatorDraftSongGroup {
  /** Stable exact Spotify URI, or a normalized metadata identity. */
  id: string;
  uri: string;
  name: string;
  artists: string[];
  album: string;
  coverUrl: string;
  updatedAt: number;
  drafts: CreatorDraftRecord[];
}

type DraftProjectMetadata = {
  name?: unknown;
  artists?: unknown;
  albums?: unknown;
  spotifyTrackId?: unknown;
};

type DraftProjectShape = {
  uri?: unknown;
  metadata?: DraftProjectMetadata;
};

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(stringValue).filter(Boolean) : [];
}

function projectShape(record: CreatorDraftRecord): DraftProjectShape {
  return record.project !== null && typeof record.project === "object"
    ? (record.project as DraftProjectShape)
    : {};
}

function spotifyTrackUri(value: unknown): string {
  const candidate = stringValue(value);
  if (/^spotify:track:[A-Za-z0-9]+$/u.test(candidate)) return candidate;
  if (/^[A-Za-z0-9]{22}$/u.test(candidate)) return `spotify:track:${candidate}`;
  return "";
}

function normalizeIdentityPart(value: string): string {
  return value.normalize("NFKC").trim().replace(/\s+/gu, " ").toLocaleLowerCase();
}

function songSnapshotForRecord(record: CreatorDraftRecord): CreatorDraftSongSnapshot {
  const project = projectShape(record);
  const metadata = project.metadata ?? {};
  const storedSong = record.song;
  const artists =
    stringArray(storedSong?.artists).length > 0
      ? stringArray(storedSong?.artists)
      : stringArray(metadata.artists);
  const albums = stringArray(metadata.albums);
  const uri =
    spotifyTrackUri(storedSong?.uri) ||
    spotifyTrackUri(record.uri) ||
    spotifyTrackUri(project.uri) ||
    spotifyTrackUri(metadata.spotifyTrackId);

  return {
    uri,
    name: stringValue(storedSong?.name) || stringValue(metadata.name) || record.name || "Untitled",
    artists,
    album: stringValue(storedSong?.album) || albums[0] || "",
    coverUrl: stringValue(storedSong?.coverUrl),
  };
}

function metadataIdentity(song: CreatorDraftSongSnapshot, recordId: string): string {
  const name = normalizeIdentityPart(song.name === "Untitled" ? "" : song.name);
  const artists = song.artists.map(normalizeIdentityPart).filter(Boolean).sort().join("\u001f");
  const album = normalizeIdentityPart(song.album);
  if (!name && !artists && !album) return `draft:${recordId}`;
  return `metadata:${JSON.stringify([name, artists, album])}`;
}

export function creatorDraftSongIdentity(record: CreatorDraftRecord): string {
  const song = songSnapshotForRecord(record);
  return song.uri || metadataIdentity(song, record.id);
}

/**
 * Builds the song-first model used by the draft-library overlay. Both groups
 * and their drafts are newest-first, while old schema-v2 draft records are
 * handled without an IndexedDB rewrite.
 */
export function groupCreatorDraftsBySong(
  records: readonly CreatorDraftRecord[]
): CreatorDraftSongGroup[] {
  const groups = new Map<string, CreatorDraftSongGroup>();

  for (const record of records) {
    const song = songSnapshotForRecord(record);
    const id = song.uri || metadataIdentity(song, record.id);
    const existing = groups.get(id);
    if (!existing) {
      groups.set(id, {
        id,
        ...song,
        updatedAt: record.updatedAt,
        drafts: [record],
      });
      continue;
    }

    existing.drafts.push(record);
    if (record.updatedAt > existing.updatedAt) {
      existing.updatedAt = record.updatedAt;
      // The newest save has the freshest user-facing metadata. Preserve an
      // older cover only when that save did not include one.
      existing.uri = song.uri || existing.uri;
      existing.name = song.name || existing.name;
      existing.artists = song.artists.length > 0 ? song.artists : existing.artists;
      existing.album = song.album || existing.album;
      existing.coverUrl = song.coverUrl || existing.coverUrl;
    } else if (!existing.coverUrl && song.coverUrl) {
      existing.coverUrl = song.coverUrl;
    }
  }

  return [...groups.values()]
    .map((group) => ({
      ...group,
      drafts: [...group.drafts].sort(
        (left, right) => right.updatedAt - left.updatedAt || left.id.localeCompare(right.id)
      ),
    }))
    .sort((left, right) => right.updatedAt - left.updatedAt || left.name.localeCompare(right.name));
}

export async function saveCreatorDraft(
  project: CreatorProject,
  draftId?: string,
  song?: Partial<CreatorDraftSongSnapshot>
): Promise<CreatorDraftRecord> {
  const database = await dbPromise;
  const id = draftId ?? createCreatorId("draft");
  const previous = await database.get(ObjectStores.CreatorDrafts, id);
  const now = Date.now();
  const record: CreatorDraftRecord = {
    id,
    uri: project.uri,
    name: project.metadata.name || "Untitled lyric draft",
    createdAt: previous?.createdAt ?? now,
    updatedAt: now,
    ...(song || previous?.song
      ? {
          song: {
            uri: stringValue(song?.uri) || previous?.song?.uri || project.uri,
            name: stringValue(song?.name) || previous?.song?.name || project.metadata.name,
            artists:
              stringArray(song?.artists).length > 0
                ? stringArray(song?.artists)
                : (previous?.song?.artists ?? project.metadata.artists),
            album:
              stringValue(song?.album) || previous?.song?.album || project.metadata.albums[0] || "",
            coverUrl: stringValue(song?.coverUrl) || previous?.song?.coverUrl || "",
          },
        }
      : {}),
    project: structuredClone(project),
  };
  await database.put(ObjectStores.CreatorDrafts, record);
  return record;
}

export async function getCreatorDraft(id: string): Promise<CreatorDraftRecord | null> {
  return (await (await dbPromise).get(ObjectStores.CreatorDrafts, id)) ?? null;
}

export async function listCreatorDrafts(): Promise<CreatorDraftRecord[]> {
  return (await (await dbPromise).getAll(ObjectStores.CreatorDrafts)).sort(
    (left, right) => right.updatedAt - left.updatedAt
  );
}

export async function removeCreatorDraft(id: string): Promise<void> {
  await (await dbPromise).delete(ObjectStores.CreatorDrafts, id);
}

export function projectFromDraft(record: CreatorDraftRecord): CreatorProject {
  const project = structuredClone(record.project) as CreatorProject;
  project.source = normalizeCreatorSource(project.source);
  return project;
}

export async function loadCreatorDraft(id: string): Promise<{
  record: CreatorDraftRecord;
  project: CreatorProject;
}> {
  if (!id) throw new Error("Choose a saved draft first.");
  const record = await getCreatorDraft(id);
  if (!record) throw new Error("That saved draft no longer exists.");
  return { record, project: projectFromDraft(record) };
}
