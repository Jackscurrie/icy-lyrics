import type { CreatorProject } from "./model.ts";

export interface CreatorTrackLoadOptions {
  startPlayback?: boolean;
}

export function creatorProjectCheckpoint(project: CreatorProject): string {
  return JSON.stringify(project);
}

export function isCreatorProjectDirty(project: CreatorProject, checkpoint: string): boolean {
  return creatorProjectCheckpoint(project) !== checkpoint;
}

export function maybeStartCreatorTrackPlayback(
  uri: string,
  hasLocalAudio: boolean,
  options: CreatorTrackLoadOptions,
  playUri: (uri: string) => unknown
): boolean {
  if (hasLocalAudio || options.startPlayback === false) return false;
  void playUri(uri);
  return true;
}

export function requestCreatorSourceSwitch({
  project,
  checkpoint,
  onDirty,
  reload,
}: {
  project: CreatorProject;
  checkpoint: string;
  onDirty: () => void;
  reload: (options: CreatorTrackLoadOptions) => void;
}): boolean {
  if (isCreatorProjectDirty(project, checkpoint)) {
    onDirty();
    return false;
  }
  reload({ startPlayback: false });
  return true;
}
