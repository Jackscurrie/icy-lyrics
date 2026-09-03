import "fake-indexeddb/auto";
import { openDB } from "idb";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { CreatorDraftRecord } from "../src/utils/db.ts";

const DB_NAME = "icylyrics";

describe.sequential("Lyric Creator draft persistence", () => {
  beforeAll(async () => {
    const v1 = await openDB(DB_NAME, 1, {
      upgrade(database) {
        database.createObjectStore("localTtmlByUri", { keyPath: "uri" });
        database.createObjectStore("metadata");
      },
    });
    v1.close();
  });

  afterAll(async () => {
    const { dbPromise } = await import("../src/utils/db.ts");
    (await dbPromise).close();
  });

  it("upgrades an existing local-TTML database and retains drafts across connections", async () => {
    const { createEmptyProject } =
      await import("../src/components/ReactComponents/LyricCreator/model.ts");
    const {
      getCreatorDraft,
      listCreatorDrafts,
      loadCreatorDraft,
      projectFromDraft,
      removeCreatorDraft,
      saveCreatorDraft,
    } = await import("../src/components/ReactComponents/LyricCreator/drafts.ts");
    const { ICY_LYRICS_DB_VERSION, ObjectStores, dbPromise } = await import("../src/utils/db.ts");

    const project = createEmptyProject("spotify:track:bbbbbbbbbbbbbbbbbbbbbb");
    project.metadata.name = "Persistent draft";
    project.lines[0].tokens[0].fragments[0].text = "Hello";
    const saved = await saveCreatorDraft(project);

    expect((await dbPromise).objectStoreNames.contains(ObjectStores.CreatorDrafts)).toBe(true);
    expect(projectFromDraft((await getCreatorDraft(saved.id))!)).toEqual(project);
    await expect(loadCreatorDraft(saved.id)).resolves.toMatchObject({
      record: { id: saved.id, name: "Persistent draft" },
      project: { uri: project.uri },
    });
    expect((await listCreatorDrafts()).map((draft) => draft.id)).toContain(saved.id);

    const reopened = await openDB(DB_NAME, ICY_LYRICS_DB_VERSION);
    expect(await reopened.get(ObjectStores.CreatorDrafts, saved.id)).toMatchObject({
      id: saved.id,
      uri: project.uri,
      name: "Persistent draft",
    });
    reopened.close();

    await removeCreatorDraft(saved.id);
    expect(await getCreatorDraft(saved.id)).toBeNull();
  });

  it("groups legacy and artwork-aware drafts by song with newest activity first", async () => {
    const { createEmptyProject } =
      await import("../src/components/ReactComponents/LyricCreator/model.ts");
    const { creatorDraftSongIdentity, groupCreatorDraftsBySong } =
      await import("../src/components/ReactComponents/LyricCreator/drafts.ts");

    const firstProject = createEmptyProject("spotify:track:aaaaaaaaaaaaaaaaaaaaaa");
    firstProject.metadata.name = "First song";
    firstProject.metadata.artists = ["Artist One"];
    firstProject.metadata.albums = ["Album One"];
    const legacyRecord: CreatorDraftRecord = {
      id: "draft-old",
      uri: firstProject.uri,
      name: "First song",
      createdAt: 10,
      updatedAt: 20,
      project: firstProject,
    };
    const artworkRecord: CreatorDraftRecord = {
      ...legacyRecord,
      id: "draft-new",
      updatedAt: 40,
      song: {
        uri: firstProject.uri,
        name: "First song",
        artists: ["Artist One"],
        album: "Album One",
        coverUrl: "https://images.test/first.jpg",
      },
    };

    const secondProject = createEmptyProject();
    secondProject.metadata.name = "Second song";
    secondProject.metadata.artists = ["Artist Two"];
    const secondRecord: CreatorDraftRecord = {
      id: "draft-second",
      uri: "",
      name: "Second song",
      createdAt: 25,
      updatedAt: 30,
      project: secondProject,
    };

    expect(creatorDraftSongIdentity(legacyRecord)).toBe(firstProject.uri);
    const groups = groupCreatorDraftsBySong([legacyRecord, secondRecord, artworkRecord]);
    expect(groups).toHaveLength(2);
    expect(groups[0]).toMatchObject({
      id: firstProject.uri,
      name: "First song",
      artists: ["Artist One"],
      album: "Album One",
      coverUrl: "https://images.test/first.jpg",
      updatedAt: 40,
    });
    expect(groups[0].drafts.map((draft) => draft.id)).toEqual(["draft-new", "draft-old"]);
    expect(groups[1]).toMatchObject({ name: "Second song", updatedAt: 30 });
    expect(groups[1].id).toMatch(/^metadata:/u);
  });

  it("keeps artwork metadata on later saves that omit the optional song snapshot", async () => {
    const { createEmptyProject } =
      await import("../src/components/ReactComponents/LyricCreator/model.ts");
    const { getCreatorDraft, removeCreatorDraft, saveCreatorDraft } =
      await import("../src/components/ReactComponents/LyricCreator/drafts.ts");

    const project = createEmptyProject("spotify:track:cccccccccccccccccccccc");
    project.metadata.name = "Artwork song";
    project.metadata.artists = ["Artist"];
    const saved = await saveCreatorDraft(project, undefined, {
      uri: project.uri,
      name: "Artwork song",
      artists: ["Artist"],
      album: "Album",
      coverUrl: "https://images.test/art.jpg",
    });

    project.lines[0].tokens[0].fragments[0].text = "Updated";
    await saveCreatorDraft(project, saved.id);
    expect(await getCreatorDraft(saved.id)).toMatchObject({
      song: {
        uri: project.uri,
        name: "Artwork song",
        artists: ["Artist"],
        album: "Album",
        coverUrl: "https://images.test/art.jpg",
      },
    });
    await removeCreatorDraft(saved.id);
  });
});
