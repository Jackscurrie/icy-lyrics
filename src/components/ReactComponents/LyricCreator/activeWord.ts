import type { CreatorProject } from "./model.ts";

export interface CreatorPlaybackActivity {
  fragmentIds: ReadonlySet<string>;
  tokenIds: ReadonlySet<string>;
  lineIds: ReadonlySet<string>;
}

/**
 * Creator timing intervals are start-inclusive and end-exclusive. This keeps
 * adjacent fragments from both appearing active at their shared boundary.
 * Incomplete or invalid timings never produce a playback highlight.
 */
export function isCreatorPlaybackIntervalActive(
  startTimeMs: number | null,
  endTimeMs: number | null,
  positionMs: number
): boolean {
  if (
    startTimeMs === null ||
    endTimeMs === null ||
    !Number.isFinite(startTimeMs) ||
    !Number.isFinite(endTimeMs) ||
    !Number.isFinite(positionMs) ||
    endTimeMs <= startTimeMs
  ) {
    return false;
  }

  return positionMs >= startTimeMs && positionMs < endTimeMs;
}

/**
 * Returns every lyric-editor entity currently covered by the playhead. More
 * than one lane can be active at once (for example lead and background vocals),
 * while all fragments belonging to the same written word collapse to one token.
 */
export function creatorPlaybackActivity(
  project: Pick<CreatorProject, "lines">,
  positionMs: number
): CreatorPlaybackActivity {
  const fragmentIds = new Set<string>();
  const tokenIds = new Set<string>();
  const lineIds = new Set<string>();

  if (!Number.isFinite(positionMs) || positionMs < 0) {
    return { fragmentIds, tokenIds, lineIds };
  }

  for (const line of project.lines) {
    for (const token of line.tokens) {
      for (const fragment of token.fragments) {
        if (
          !isCreatorPlaybackIntervalActive(fragment.startTimeMs, fragment.endTimeMs, positionMs)
        ) {
          continue;
        }

        fragmentIds.add(fragment.id);
        tokenIds.add(token.id);
        lineIds.add(line.id);
      }
    }
  }

  return { fragmentIds, tokenIds, lineIds };
}
