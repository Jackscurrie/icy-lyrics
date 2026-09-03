import { describe, expect, it, vi } from "vitest";
import { cloneCreatorProject, createEmptyProject } from "../src/components/ReactComponents/LyricCreator/model.ts";
import {
  creatorProjectCheckpoint,
  maybeStartCreatorTrackPlayback,
  requestCreatorSourceSwitch,
} from "../src/components/ReactComponents/LyricCreator/sourceSwitch.ts";

describe("Lyric Creator source switching", () => {
  it("does not restart Spotify playback for a source-only reload", () => {
    const playUri = vi.fn();

    expect(
      maybeStartCreatorTrackPlayback(
        "spotify:track:0123456789ABCDEFGHIJKL",
        false,
        { startPlayback: false },
        playUri
      )
    ).toBe(false);
    expect(playUri).not.toHaveBeenCalled();
  });

  it("keeps source, project, and draft state when a dirty switch is blocked", () => {
    const checkpointProject = createEmptyProject("spotify:track:0123456789ABCDEFGHIJKL");
    const dirtyProject = cloneCreatorProject(checkpointProject);
    dirtyProject.metadata.name = "Unsaved edit";
    const originalProject = dirtyProject;
    let currentProject = dirtyProject;
    let currentSource = "auto";
    let currentDraft: string | undefined = "draft-1";
    const onDirty = vi.fn();
    const reload = vi.fn(() => {
      currentProject = createEmptyProject();
      currentSource = "aml";
      currentDraft = undefined;
    });

    const accepted = requestCreatorSourceSwitch({
      project: dirtyProject,
      checkpoint: creatorProjectCheckpoint(checkpointProject),
      onDirty,
      reload,
    });

    expect(accepted).toBe(false);
    expect(onDirty).toHaveBeenCalledOnce();
    expect(reload).not.toHaveBeenCalled();
    expect(currentProject).toBe(originalProject);
    expect(currentSource).toBe("auto");
    expect(currentDraft).toBe("draft-1");
  });

  it("switches a pristine project without invoking dirty handling", () => {
    const project = createEmptyProject("spotify:track:0123456789ABCDEFGHIJKL");
    const onDirty = vi.fn();
    const reload = vi.fn();

    const accepted = requestCreatorSourceSwitch({
      project,
      checkpoint: creatorProjectCheckpoint(project),
      onDirty,
      reload,
    });

    expect(accepted).toBe(true);
    expect(onDirty).not.toHaveBeenCalled();
    expect(reload).toHaveBeenCalledWith({ startPlayback: false });
  });
});
