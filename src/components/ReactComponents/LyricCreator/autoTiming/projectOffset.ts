import type { CreatorProject } from "../model.ts";
import { applyAutoTimingCandidate, buildAutoTimingCandidate } from "./alignment.ts";
import type {
  AutoTimingApplyOptions,
  AutoTimingCandidate,
  AutoTimingFragmentCandidate,
  AutoTimingRunInput,
} from "./types.ts";

function normalizedOffset(offsetMs: number): number {
  const offset = Math.round(offsetMs);
  if (!Number.isSafeInteger(offset)) throw new RangeError("The song timing offset is invalid.");
  return offset;
}

/** Existing timestamps are already offset; acoustic alignment needs audio-time anchors. */
export function buildAutoTimingCandidateWithOffset(
  input: AutoTimingRunInput,
  offsetMs = 0
): AutoTimingCandidate {
  const offset = normalizedOffset(offsetMs);
  if (offset === 0) return buildAutoTimingCandidate(input);
  const audioTime = (time: number | null) => time === null ? null : Math.max(0, Math.round(time - offset));
  const project: CreatorProject = {
    ...input.project,
    lines: input.project.lines.map((line) => ({
      ...line,
      startTimeMs: audioTime(line.startTimeMs),
      endTimeMs: audioTime(line.endTimeMs),
      tokens: line.tokens.map((token) => ({
        ...token,
        fragments: token.fragments.map((fragment) => ({
          ...fragment,
          startTimeMs: audioTime(fragment.startTimeMs),
          endTimeMs: audioTime(fragment.endTimeMs),
        })),
      })),
    })),
  };
  // Keep the original project fingerprint and raw audio coordinates for review
  // and seek buttons. Only the temporary alignment anchors are translated.
  return buildAutoTimingCandidate({ ...input, project });
}

/** Offset only proposals, never the retained/manual endpoints of a partial application. */
export function applyAutoTimingCandidateWithOffset(
  project: CreatorProject,
  candidate: AutoTimingCandidate,
  options: AutoTimingApplyOptions,
  offsetMs = 0
): CreatorProject {
  const offset = normalizedOffset(offsetMs);
  if (offset === 0) return applyAutoTimingCandidate(project, candidate, options);
  const durationMs = Math.max(0, candidate.durationMs + offset);
  if (!Number.isSafeInteger(Math.ceil(durationMs))) {
    throw new RangeError("The song timing offset is outside the supported range.");
  }
  const fragments: Record<string, AutoTimingFragmentCandidate> = {};
  for (const [id, fragment] of Object.entries(candidate.fragments)) {
    // Do not turn an invalid raw proposal into a valid one by translating it.
    if (!Number.isFinite(fragment.startTimeMs) || !Number.isFinite(fragment.endTimeMs) ||
        fragment.startTimeMs < 0 || fragment.endTimeMs <= fragment.startTimeMs ||
        fragment.endTimeMs > candidate.durationMs) continue;
    const startTimeMs = Math.max(0, Math.round(fragment.startTimeMs + offset));
    const endTimeMs = Math.max(0, Math.round(fragment.endTimeMs + offset));
    // A negative offset can move a proposal entirely before the song. Leave
    // that word alone instead of writing a zero-duration or negative interval.
    if (endTimeMs <= startTimeMs) continue;
    fragments[id] = { ...fragment, startTimeMs, endTimeMs };
  }
  return applyAutoTimingCandidate(project, { ...candidate, durationMs, fragments }, options);
}
