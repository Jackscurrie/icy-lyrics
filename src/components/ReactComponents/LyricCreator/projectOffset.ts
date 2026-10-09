import { cloneCreatorProject, type CreatorProject } from "./model.ts";

/** Apply only the change in offset: 100 -> 150 shifts every timestamp by 50ms.
 * Keep one uniform delta so crossing zero never collapses early word timings.
 * Timings are baked into the project, so preview, drafts and export all agree.
 */
export function applyCreatorProjectOffset(
  project: CreatorProject,
  previousOffsetMs: number,
  requestedOffsetMs: number
): { project: CreatorProject; offsetMs: number; deltaMs: number; limited: boolean } {
  if (!Number.isSafeInteger(Math.round(previousOffsetMs)) ||
      !Number.isSafeInteger(Math.round(requestedOffsetMs))) {
    return { project, offsetMs: previousOffsetMs, deltaMs: 0, limited: true };
  }
  const previous = Math.round(previousOffsetMs);
  const requested = Math.round(requestedOffsetMs);
  const times: number[] = [];
  for (const line of project.lines) {
    for (const item of [line, ...line.tokens.flatMap((token) => token.fragments)]) {
      for (const time of [item.startTimeMs, item.endTimeMs]) {
        if (time !== null && Number.isFinite(time)) times.push(time);
      }
    }
  }
  const requestedDelta = requested - previous;
  if (!Number.isSafeInteger(requestedDelta)) {
    return { project, offsetMs: previous, deltaMs: 0, limited: true };
  }
  let deltaMs = requestedDelta;
  if (times.length) {
    const earliest = times.reduce((minimum, time) => Math.min(minimum, time), Infinity);
    const latest = times.reduce((maximum, time) => Math.max(maximum, time), 0);
    deltaMs = Math.min(Math.max(deltaMs, -Math.max(0, earliest)), Number.MAX_SAFE_INTEGER - latest);
  }
  const offsetMs = previous + deltaMs;
  if (!deltaMs || !times.length) {
    return { project, offsetMs, deltaMs, limited: deltaMs !== requestedDelta };
  }
  const next = cloneCreatorProject(project);
  for (const line of next.lines) {
    for (const item of [line, ...line.tokens.flatMap((token) => token.fragments)]) {
      if (item.startTimeMs !== null) item.startTimeMs += deltaMs;
      if (item.endTimeMs !== null) item.endTimeMs += deltaMs;
    }
  }
  return { project: next, offsetMs, deltaMs, limited: deltaMs !== requestedDelta };
}
