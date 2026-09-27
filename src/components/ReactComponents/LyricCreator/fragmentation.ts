import {
  cloneCreatorProject,
  createFragment,
  syncLineTiming,
  type CreatorProject,
  type CreatorToken,
} from "./model.ts";
import { creatorProjectCheckpoint } from "./sourceSwitch.ts";

export interface CreatorFragmentationProposal {
  checkpoint: string;
  word: string;
  parts: string[];
  matchingTokenIds: string[];
}

function writtenWord(token: CreatorToken): string {
  return token.fragments.map((fragment) => fragment.text.replaceAll("\\", "")).join("");
}

function sameParts(token: CreatorToken, parts: string[]): boolean {
  return token.fragments.length === parts.length &&
    token.fragments.every((fragment, index) => fragment.text === parts[index]);
}

/** Retain only existing timing boundaries, without inventing new inner timings. */
function partitionToken(token: CreatorToken, parts: string[]): void {
  const starts = new Map<number, number>();
  const ends = new Map<number, number>();
  const existing = new Map<string, typeof token.fragments[number]>();
  let position = 0;
  for (const fragment of token.fragments) {
    const text = fragment.text.replaceAll("\\", "");
    if (fragment.startTimeMs !== null) starts.set(position, fragment.startTimeMs);
    const end = position + text.length;
    if (fragment.endTimeMs !== null) ends.set(end, fragment.endTimeMs);
    if (text === fragment.text) existing.set(`${position}:${end}`, fragment);
    position = end;
  }
  position = 0;
  token.fragments = parts.map((text) => {
    const end = position + text.length;
    const old = existing.get(`${position}:${end}`);
    const fragment = old ? { ...old } : createFragment(text);
    fragment.startTimeMs = starts.get(position) ?? null;
    fragment.endTimeMs = ends.get(end) ?? null;
    position = end;
    return fragment;
  });
}

export function splitCreatorWordOnBackslashes(
  project: CreatorProject,
  tokenId: string
): { project: CreatorProject; proposal: CreatorFragmentationProposal | null } | null {
  const source = project.lines.flatMap((line) => line.tokens).find((token) => token.id === tokenId);
  if (!source?.fragments.some((fragment) => fragment.text.includes("\\"))) return null;
  const parts = source.fragments.flatMap((fragment) => fragment.text.split("\\").filter(Boolean));
  if (!parts.length) return null;
  const next = cloneCreatorProject(project);
  const line = next.lines.find((candidate) => candidate.tokens.some((token) => token.id === tokenId))!;
  const token = line.tokens.find((candidate) => candidate.id === tokenId)!;
  partitionToken(token, parts);
  syncLineTiming(line);
  const word = parts.join("");
  const matchingTokenIds = next.lines.flatMap((candidate) => candidate.tokens)
    .filter((candidate) => candidate.id !== tokenId && writtenWord(candidate) === word && !sameParts(candidate, parts))
    .map((candidate) => candidate.id);
  return {
    project: next,
    proposal: parts.length > 1 && matchingTokenIds.length > 0
      ? { checkpoint: creatorProjectCheckpoint(next), word, parts, matchingTokenIds }
      : null,
  };
}

export function applyCreatorFragmentationProposal(
  project: CreatorProject,
  proposal: CreatorFragmentationProposal
): CreatorProject | null {
  if (creatorProjectCheckpoint(project) !== proposal.checkpoint) return null;
  const next = cloneCreatorProject(project);
  const selected = new Set(proposal.matchingTokenIds);
  for (const line of next.lines) {
    let changed = false;
    for (const token of line.tokens) {
      if (!selected.has(token.id) || writtenWord(token) !== proposal.word) continue;
      partitionToken(token, proposal.parts);
      changed = true;
    }
    if (changed) syncLineTiming(line);
  }
  return next;
}
