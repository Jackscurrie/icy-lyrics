import {
  cloneCreatorProject,
  syncLineTiming,
  type CreatorFragment,
  type CreatorProject,
} from "./model.ts";

export type TimingAction = "start" | "end" | "end-and-next";

export interface TimingTarget {
  lineIndex: number;
  tokenIndex: number;
  fragmentIndex: number;
  isBackground: boolean;
  isSecondSpeaker: boolean;
  fragment: CreatorFragment;
  label: string;
}

export interface TimingResult {
  project: CreatorProject;
  targetIndex: number;
}

export interface CreatorTimingOptions {
  offsetMs: number;
  showTimestamps: boolean;
  highlightActive: boolean;
  highlightErrors: boolean;
  ignoreBackground: boolean;
  autoFollow: boolean;
}

export const DEFAULT_CREATOR_TIMING_OPTIONS: CreatorTimingOptions = {
  offsetMs: 0,
  showTimestamps: true,
  highlightActive: true,
  highlightErrors: true,
  ignoreBackground: false,
  autoFollow: true,
};

export type CreatorTimingFilter = Pick<CreatorTimingOptions, "ignoreBackground">;

export function creatorTimingTargets(
  project: CreatorProject,
  options: Partial<CreatorTimingFilter> = {}
): TimingTarget[] {
  const result: TimingTarget[] = [];
  project.lines.forEach((line, lineIndex) => {
    if (options.ignoreBackground && line.isBackground) return;
    line.tokens.forEach((token, tokenIndex) => {
      token.fragments.forEach((fragment, fragmentIndex) => {
        result.push({
          lineIndex,
          tokenIndex,
          fragmentIndex,
          isBackground: line.isBackground,
          isSecondSpeaker: line.isSecondSpeaker,
          fragment,
          label: fragment.text || "Empty fragment",
        });
      });
    });
  });
  return result;
}

function updateFragment(
  project: CreatorProject,
  target: TimingTarget,
  updater: (fragment: CreatorFragment) => void
): void {
  const line = project.lines[target.lineIndex];
  const fragment = line?.tokens[target.tokenIndex]?.fragments[target.fragmentIndex];
  if (!line || !fragment) return;
  updater(fragment);
  syncLineTiming(line);
}

export function applyTimingAction(
  project: CreatorProject,
  targetIndex: number,
  action: TimingAction,
  playbackPositionMs: number,
  options: Partial<CreatorTimingFilter & Pick<CreatorTimingOptions, "offsetMs">> = {}
): TimingResult {
  const nextProject = cloneCreatorProject(project);
  const targets = creatorTimingTargets(nextProject, options);
  if (targets.length === 0) return { project: nextProject, targetIndex: 0 };

  const index = Math.max(0, Math.min(targetIndex, targets.length - 1));
  const target = targets[index];
  const time = creatorTimingPosition(playbackPositionMs, options.offsetMs ?? 0);

  if (action === "start") {
    updateFragment(nextProject, target, (fragment) => {
      fragment.startTimeMs = time;
      if (fragment.endTimeMs !== null && fragment.endTimeMs < time) {
        fragment.endTimeMs = null;
      }
    });
    return { project: nextProject, targetIndex: index };
  }

  updateFragment(nextProject, target, (fragment) => {
    fragment.endTimeMs =
      fragment.startTimeMs === null ? time : Math.max(time, fragment.startTimeMs);
  });

  if (action !== "end-and-next") {
    return { project: nextProject, targetIndex: index };
  }

  const nextIndex = Math.min(index + 1, targets.length - 1);
  if (nextIndex !== index) {
    const refreshedTargets = creatorTimingTargets(nextProject, options);
    updateFragment(nextProject, refreshedTargets[nextIndex], (fragment) => {
      fragment.startTimeMs = time;
      if (fragment.endTimeMs !== null && fragment.endTimeMs < time) {
        fragment.endTimeMs = null;
      }
    });
  }

  return { project: nextProject, targetIndex: nextIndex };
}

export function creatorTimingPosition(playbackPositionMs: number, offsetMs: number): number {
  return Math.max(0, Math.round(playbackPositionMs + offsetMs));
}

export type CreatorTimingTargetStatus = "untimed" | "partial" | "invalid" | "overlap" | "timed";

export function classifyCreatorTimingTarget(
  targets: TimingTarget[],
  targetIndex: number
): CreatorTimingTargetStatus {
  const target = targets[targetIndex];
  if (!target) return "untimed";
  const { startTimeMs, endTimeMs } = target.fragment;
  if (startTimeMs === null && endTimeMs === null) return "untimed";
  if (startTimeMs === null || endTimeMs === null) return "partial";
  if (endTimeMs <= startTimeMs) return "invalid";
  const previousTarget = targets[targetIndex - 1];
  // Lead/background rows intentionally overlap. Treat only out-of-order words
  // within the same row as a timing error.
  if (
    previousTarget?.lineIndex === target.lineIndex &&
    previousTarget.fragment.endTimeMs !== null &&
    startTimeMs < previousTarget.fragment.endTimeMs
  ) {
    return "overlap";
  }
  return "timed";
}

export function creatorTimingErrorIndexes(
  project: CreatorProject,
  options: Partial<CreatorTimingFilter> = {}
): Set<number> {
  const targets = creatorTimingTargets(project, options);
  const errors = new Set<number>();
  targets.forEach((_, index) => {
    const status = classifyCreatorTimingTarget(targets, index);
    if (status === "partial" || status === "invalid" || status === "overlap") errors.add(index);
  });
  return errors;
}

export function creatorTimingTargetDomId(targetIndex: number): string {
  return `il-creator-timing-target-${Math.max(0, Math.round(targetIndex))}`;
}

export function autoFollowCreatorTimingTarget(
  container: ParentNode | null,
  targetIndex: number,
  enabled = true
): boolean {
  if (!enabled || !container) return false;
  const element = container.querySelector<HTMLElement>(`#${creatorTimingTargetDomId(targetIndex)}`);
  if (!element) return false;
  element.scrollIntoView({ block: "nearest", inline: "center", behavior: "smooth" });
  return true;
}

export function formatCreatorTime(timeMs: number): string {
  const normalized = Math.max(0, Math.round(timeMs));
  const minutes = Math.floor(normalized / 60_000);
  const seconds = Math.floor((normalized % 60_000) / 1000);
  const milliseconds = normalized % 1000;
  return `${minutes}:${seconds.toString().padStart(2, "0")}.${milliseconds
    .toString()
    .padStart(3, "0")}`;
}

export interface PlaybackSpeedAdapterDependencies {
  setSpeed: (speed: number) => void | Promise<void>;
  readSpeed: () => number | null | undefined;
  mediaType: () => string | null | undefined;
  wait?: (milliseconds: number) => Promise<void>;
}

export interface PlaybackSpeedResult {
  requested: number;
  observed: number | null;
  applied: boolean;
  message: string;
}

export async function applyCreatorPlaybackSpeed(
  speed: number,
  dependencies: PlaybackSpeedAdapterDependencies
): Promise<PlaybackSpeedResult> {
  const requested = Math.max(0.25, Math.min(2, speed));
  await dependencies.setSpeed(requested);
  await (
    dependencies.wait ??
    ((milliseconds) =>
      new Promise((resolve) => {
        setTimeout(resolve, milliseconds);
      }))
  )(180);

  const read = dependencies.readSpeed();
  const observed = typeof read === "number" && Number.isFinite(read) ? read : null;
  const applied = observed !== null && Math.abs(observed - requested) < 0.02;
  const mediaType = dependencies.mediaType();

  if (applied) {
    return {
      requested,
      observed,
      applied: true,
      message: `Playback speed set to ${requested}×.`,
    };
  }

  const musicNote =
    mediaType === "audio" || mediaType === "track"
      ? " Spotify currently limits its exposed speed control for music tracks."
      : " This Spotify build did not confirm the requested speed.";
  return {
    requested,
    observed,
    applied: false,
    message: `Playback stayed at ${observed ?? "its current speed"}.${musicNote}`,
  };
}
