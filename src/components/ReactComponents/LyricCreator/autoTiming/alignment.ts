import {
  cloneCreatorProject,
  syncLineTiming,
  type CreatorFragment,
  type CreatorProject,
} from "../model.ts";
import type {
  AutoTimingApplyOptions,
  AutoTimingCandidate,
  AutoTimingConfidence,
  AutoTimingEnergyFrame,
  AutoTimingFragmentCandidate,
  AutoTimingRunInput,
  AutoTimingWord,
} from "./types.ts";

interface LyricTokenTarget {
  lineId: string;
  lineIndex: number;
  tokenIndex: number;
  text: string;
  fragments: Array<{ fragment: CreatorFragment; fragmentIndex: number }>;
  lane: string;
  anchorStartMs: number | null;
  anchorEndMs: number | null;
  windowStartMs: number | null;
  windowEndMs: number | null;
  lineStartAnchorMs: number | null;
  attachedToLineId: string | null;
}

interface AlignmentWord extends AutoTimingWord {
  estimatedTiming?: boolean;
}

interface TokenMatch {
  target: LyricTokenTarget;
  word: AlignmentWord | null;
  similarity: number;
  sourceWordIndexes: number[];
}

const MIN_FRAGMENT_MS = 1;

function validTime(value: number | null): value is number {
  return value !== null && Number.isFinite(value) && value >= 0;
}

export function normalizeAutoTimingText(value: string): string {
  return value
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[’'`]/gu, "")
    .replace(/[^\p{L}\p{N}]+/gu, "");
}

function graphemeLength(value: string): number {
  const Segmenter = (Intl as typeof Intl & { Segmenter?: any }).Segmenter;
  if (Segmenter) {
    return Math.max(1, Array.from(new Segmenter(undefined, { granularity: "grapheme" }).segment(value)).length);
  }
  return Math.max(1, Array.from(value).length);
}

function editDistance(left: string, right: string): number {
  if (left === right) return 0;
  if (!left.length) return right.length;
  if (!right.length) return left.length;
  const previous = Array.from({ length: right.length + 1 }, (_, index) => index);
  const current = Array.from<number>({ length: right.length + 1 });
  for (let i = 1; i <= left.length; i += 1) {
    current[0] = i;
    for (let j = 1; j <= right.length; j += 1) {
      current[j] = Math.min(
        current[j - 1] + 1,
        previous[j] + 1,
        previous[j - 1] + (left[i - 1] === right[j - 1] ? 0 : 1)
      );
    }
    for (let j = 0; j <= right.length; j += 1) previous[j] = current[j];
  }
  return previous[right.length];
}

export function autoTimingTextSimilarity(left: string, right: string): number {
  const a = normalizeAutoTimingText(left);
  const b = normalizeAutoTimingText(right);
  if (!a || !b) return 0;
  if (a === b) return 1;
  return Math.max(0, 1 - editDistance(a, b) / Math.max(a.length, b.length));
}

function laneForLine(line: CreatorProject["lines"][number]): string {
  return `${line.isBackground ? "background" : "lead"}:${line.isSecondSpeaker ? "v2" : "v1"}`;
}

function projectTargets(project: CreatorProject, durationMs: number): LyricTokenTarget[] {
  const targets: LyricTokenTarget[] = [];
  const lineById = new Map(project.lines.map((line) => [line.id, line]));
  project.lines.forEach((line, lineIndex) => {
    const attachment = line.isBackground && line.attachedToLineId
      ? lineById.get(line.attachedToLineId)
      : undefined;
    line.tokens.forEach((token, tokenIndex) => {
      const fragments = token.fragments
        .map((fragment, fragmentIndex) => ({ fragment, fragmentIndex }))
        .filter(({ fragment }) => normalizeAutoTimingText(fragment.text).length > 0);
      if (fragments.length === 0) return;
      const start = fragments[0].fragment.startTimeMs;
      const end = fragments[fragments.length - 1].fragment.endTimeMs;
      const windowStart = line.startTimeMs ?? attachment?.startTimeMs ?? null;
      const windowEnd = line.endTimeMs ?? attachment?.endTimeMs ?? null;
      const hasWindow = validTime(windowStart) && validTime(windowEnd) &&
        windowEnd > windowStart && windowEnd <= durationMs;
      targets.push({
        lineId: line.id,
        lineIndex,
        tokenIndex,
        text: fragments.map(({ fragment }) => fragment.text).join(""),
        fragments,
        lane: laneForLine(line),
        anchorStartMs: validTime(start) ? start : null,
        anchorEndMs: validTime(end) ? end : null,
        windowStartMs: hasWindow ? windowStart : null,
        windowEndMs: hasWindow ? windowEnd : null,
        lineStartAnchorMs: hasWindow && tokenIndex === 0 && !line.isBackground ? windowStart : null,
        attachedToLineId: line.isBackground ? line.attachedToLineId : null,
      });
    });
  });
  return targets;
}

/** Existing real line timing is a search boundary, not merely a weak hint.
 * A repeated chorus elsewhere in the recording cannot replace this line.
 * Null/invalid timing from a plain-text draft is never made into an anchor. */
function fitsTrustedTiming(target: LyricTokenTarget, word: AutoTimingWord): boolean {
  const toleranceMs = 1000;
  if (target.windowStartMs !== null && word.startTimeMs < target.windowStartMs - toleranceMs) return false;
  if (target.windowEndMs !== null && word.endTimeMs > target.windowEndMs + toleranceMs) return false;
  if (target.lineStartAnchorMs !== null && Math.abs(word.startTimeMs - target.lineStartAnchorMs) > 2000) return false;
  if (target.anchorStartMs !== null && Math.abs(word.startTimeMs - target.anchorStartMs) > 3000) return false;
  if (target.anchorEndMs !== null && Math.abs(word.endTimeMs - target.anchorEndMs) > 3000) return false;
  return true;
}

function anchorBonus(target: LyricTokenTarget, word: AutoTimingWord): number {
  const anchor = target.anchorStartMs ?? target.anchorEndMs;
  const boundary = target.anchorStartMs === null ? word.endTimeMs : word.startTimeMs;
  const outsideWindow = Math.max(
    0,
    (target.windowStartMs ?? word.startTimeMs) - word.endTimeMs,
    word.startTimeMs - (target.windowEndMs ?? word.endTimeMs)
  );
  if (anchor === null) return -Math.min(4, outsideWindow / 1500);
  const distance = Math.abs(anchor - boundary);
  if (distance <= 750) return 0.8;
  if (distance <= 3000) return 0.4;
  if (distance <= 8000) return 0.1;
  return -Math.min(4, distance / 5000);
}

/** ASR occasionally returns a phrase as one timestamped chunk. Subdivisions
 * are useful for matching, but their word boundaries must remain estimates. */
function transcriptWords(words: AutoTimingWord[], durationMs: number): AlignmentWord[] {
  const result: AlignmentWord[] = [];
  const Segmenter = (Intl as typeof Intl & { Segmenter?: any }).Segmenter;
  const segmenter = Segmenter ? new Segmenter(undefined, { granularity: "word" }) : null;
  for (const word of words) {
    if (!Number.isFinite(word.startTimeMs) || !Number.isFinite(word.endTimeMs)) continue;
    const start = Math.max(0, Math.min(durationMs, Math.round(word.startTimeMs)));
    const end = Math.max(start, Math.min(durationMs, Math.round(word.endTimeMs)));
    if (end <= start || !normalizeAutoTimingText(word.text)) continue;
    const segments: string[] = segmenter
      ? Array.from(segmenter.segment(word.text))
          .filter((segment: any) => segment.isWordLike)
          .map((segment: any) => segment.segment)
      : word.text.split(/\s+/u).filter((text) => normalizeAutoTimingText(text));
    if (segments.length <= 1) {
      result.push({ ...word, startTimeMs: start, endTimeMs: end });
      continue;
    }
    const weights = segments.map(graphemeLength);
    const total = weights.reduce((sum, weight) => sum + weight, 0);
    let consumed = 0;
    segments.forEach((text, index) => {
      const segmentStart = start + Math.round((end - start) * consumed / total);
      consumed += weights[index];
      result.push({
        text,
        startTimeMs: segmentStart,
        endTimeMs: start + Math.round((end - start) * consumed / total),
        estimatedTiming: true,
      });
    });
  }
  return result.sort((left, right) => left.startTimeMs - right.startTimeMs);
}

function normalizedSimilarity(left: string, right: string): number {
  if (!left || !right) return 0;
  if (left === right) return 1;
  // Short function words otherwise match almost any other two-letter word.
  if (Math.min(left.length, right.length) <= 2) return 0;
  const maxLength = Math.max(left.length, right.length);
  if (Math.min(left.length, right.length) / maxLength < 0.6) return 0;
  return Math.max(0, 1 - editDistance(left, right) / maxLength);
}

/**
 * Monotonic sequence alignment. Lyrics may omit ad-libs and ASR may omit
 * sung words, so both axes permit gaps. Existing manual timing acts only as a
 * anchor and never forces a bad textual match. Trusted line windows constrain
 * the search so a later repeated lyric cannot consume the earlier occurrence.
 */
export function alignAutoTimingTokens(
  targets: LyricTokenTarget[],
  words: AlignmentWord[]
): TokenMatch[] {
  const n = targets.length;
  const m = words.length;
  const targetText = targets.map((target) => normalizeAutoTimingText(target.text));
  const lineTokenCounts = new Map<string, number>();
  targets.forEach((target) => lineTokenCounts.set(target.lineId, (lineTokenCounts.get(target.lineId) ?? 0) + 1));
  const wordText = words.map((word) => normalizeAutoTimingText(word.text));
  const similarityRows = new Map<string, Float32Array>();
  let previous = new Float64Array(m + 1);
  let current = new Float64Array(m + 1);
  // Only backtracking needs a matrix. Scores need two rows, reducing a long
  // song's allocation by roughly eight times over the original full matrix.
  const steps = Array.from({ length: n + 1 }, () => new Uint8Array(m + 1));
  for (let i = 1; i <= n; i += 1) {
    steps[i][0] = 1;
  }
  for (let j = 1; j <= m; j += 1) {
    previous[j] = previous[j - 1] - 0.28;
    steps[0][j] = 2;
  }

  for (let i = 1; i <= n; i += 1) {
    const text = targetText[i - 1];
    let similarities = similarityRows.get(text);
    if (!similarities) {
      const byText = new Map<string, number>();
      similarities = new Float32Array(m);
      wordText.forEach((word, index) => {
        let similarity = byText.get(word);
        if (similarity === undefined) {
          similarity = normalizedSimilarity(text, word);
          byText.set(word, similarity);
        }
        similarities![index] = similarity;
      });
      if (similarityRows.size >= 32) similarityRows.delete(similarityRows.keys().next().value!);
      similarityRows.set(text, similarities);
    }
    current[0] = -0.72 * i;
    for (let j = 1; j <= m; j += 1) {
      const skipTarget = previous[j] - 0.72;
      const skipWord = current[j - 1] - 0.28;
      current[j] = Math.max(skipTarget, skipWord);
      steps[i][j] = skipTarget >= skipWord ? 1 : 2;
      let combined = "";
      for (let count = 1; count <= Math.min(8, j); count += 1) {
        // A joined token must not bridge an instrumental gap at any point.
        if (count > 1 && words[j - count + 1].startTimeMs - words[j - count].endTimeMs > 2000) break;
        combined = wordText[j - count] + combined;
        if (combined.length > targetText[i - 1].length * 1.7) break;
        // Joined ASR words cover hyphenation, CJK segmentation and a creator
        // token containing a phrase. Fuzzy matches apply only to single words.
        const similarity = count === 1
          ? similarities[j - 1]
          : targetText[i - 1] === combined ? 1 : 0;
        if (similarity < 0.6) continue;
        const first = words[j - count];
        const last = words[j - 1];
        if (!fitsTrustedTiming(targets[i - 1], { ...first, endTimeMs: last.endTimeMs })) continue;
        const match = previous[j - count] + similarity * 3 - 0.55 +
          anchorBonus(targets[i - 1], count === 1 ? first : { ...first, endTimeMs: last.endTimeMs });
        if (match > current[j]) {
          current[j] = match;
          steps[i][j] = count + 2;
        }
      }
    }
    [previous, current] = [current, previous];
  }

  const matches = new Map<number, { word: AlignmentWord; similarity: number; sourceWordIndexes: number[] }>();
  let i = n;
  let j = m;
  while (i > 0 || j > 0) {
    const step = steps[i]?.[j] ?? (i > 0 ? 1 : 2);
    if (step >= 3 && i > 0 && j > 0) {
      const count = step - 2;
      const source = words.slice(j - count, j);
      const text = source.map((word) => word.text).join(" ");
      const similarity = normalizedSimilarity(targetText[i - 1], normalizeAutoTimingText(text));
      matches.set(i - 1, {
        word: {
          text,
          startTimeMs: source[0].startTimeMs,
          endTimeMs: source[source.length - 1].endTimeMs,
          estimatedTiming: source.some((word) => word.estimatedTiming),
        },
        similarity,
        sourceWordIndexes: Array.from({ length: count }, (_, offset) => j - count + offset),
      });
      i -= 1;
      j -= count;
    } else if (step === 1 && i > 0) {
      i -= 1;
    } else if (j > 0) {
      j -= 1;
    } else {
      break;
    }
  }

  // A lone "I", "a" or "to" in otherwise unrecognized lyrics is not useful
  // evidence: music hallucinations contain these words constantly. Keep short
  // words when another matched word supports the same phrase, or when a real
  // manual/line anchor already identifies the region.
  for (const [index, match] of matches) {
    const target = targets[index];
    if (targetText[index].length > 3 || target.windowStartMs !== null || target.anchorStartMs !== null) continue;
    const contextualMatch = [index - 1, index + 1].some((neighbor) => {
      const other = matches.get(neighbor);
      return other && targets[neighbor]?.lineId === target.lineId &&
        Math.max(other.word.startTimeMs, match.word.startTimeMs) -
          Math.min(other.word.endTimeMs, match.word.endTimeMs) <= 4000;
    });
    if (!contextualMatch && (lineTokenCounts.get(target.lineId) ?? 0) > 1) {
      matches.delete(index);
    }
  }

  return targets.map((target, index) => ({
    target,
    word: matches.get(index)?.word ?? null,
    similarity: matches.get(index)?.similarity ?? 0,
    sourceWordIndexes: matches.get(index)?.sourceWordIndexes ?? [],
  }));
}

function snapToQuietFrame(timeMs: number, frames: AutoTimingEnergyFrame[], radiusMs = 130): number {
  let bestTime = timeMs;
  let bestEnergy = Number.POSITIVE_INFINITY;
  let low = 0;
  let high = frames.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (frames[middle].timeMs < timeMs - radiusMs) low = middle + 1;
    else high = middle;
  }
  for (let index = low; index < frames.length; index += 1) {
    const frame = frames[index];
    if (frame.timeMs > timeMs + radiusMs) break;
    if (frame.energy < bestEnergy ||
      (frame.energy === bestEnergy && Math.abs(frame.timeMs - timeMs) < Math.abs(bestTime - timeMs))) {
      bestEnergy = frame.energy;
      bestTime = frame.timeMs;
    }
  }
  return bestTime;
}

function interpolateLane(
  matches: TokenMatch[],
  durationMs: number,
  energyFrames: AutoTimingEnergyFrame[]
): Array<{ startTimeMs: number; endTimeMs: number; similarity: number; recognized: boolean }> {
  // Every start/end is a boundary. Manual boundaries and line-level timing
  // constrain unmatched regions, including a completely unrecognized lane.
  const boundaries: Array<number | null> = [];
  const weights: number[] = [];
  matches.forEach((match, index) => {
    const target = match.target;
    const matchedStart = match.word?.startTimeMs;
    const matchedEnd = match.word?.endTimeMs;
    boundaries.push(
      (matchedStart === undefined ? null : Math.max(target.windowStartMs ?? 0, matchedStart)) ?? target.anchorStartMs ??
        (matches[index - 1]?.target.lineId !== target.lineId ? target.windowStartMs : null),
      (matchedEnd === undefined ? null : Math.min(target.windowEndMs ?? durationMs, matchedEnd)) ?? target.anchorEndMs ??
        (matches[index + 1]?.target.lineId !== target.lineId ? target.windowEndMs : null)
    );
    weights.push(graphemeLength(target.text), 0);
  });
  if (boundaries.length === 0) return [];
  const clamp = (time: number) => Math.max(0, Math.min(durationMs, Math.round(time)));
  boundaries[0] ??= 0;
  boundaries[boundaries.length - 1] ??= durationMs;
  let left = 0;
  while (left < boundaries.length - 1) {
    let right = left + 1;
    while (boundaries[right] === null) right += 1;
    const start = clamp(boundaries[left]!);
    const end = Math.max(start, clamp(boundaries[right]!));
    boundaries[left] = start;
    boundaries[right] = end;
    let totalWeight = 0;
    for (let index = left; index < right; index += 1) totalWeight += weights[index];
    let consumed = 0;
    for (let index = left + 1; index < right; index += 1) {
      consumed += weights[index - 1];
      const estimate = start + (end - start) * (totalWeight ? consumed / totalWeight : 0);
      boundaries[index] = Math.max(
        boundaries[index - 1]!,
        Math.min(end, clamp(snapToQuietFrame(estimate, energyFrames)))
      );
    }
    left = right;
  }
  return matches.map((match, index) => {
    const startTimeMs = clamp(boundaries[index * 2]!);
    const endTimeMs = Math.max(startTimeMs, clamp(boundaries[index * 2 + 1]!));
    const unchanged = match.word && startTimeMs === match.word.startTimeMs &&
      endTimeMs === match.word.endTimeMs;
    return {
      startTimeMs,
      endTimeMs,
      similarity: match.similarity,
      recognized: Boolean(unchanged && !match.word?.estimatedTiming),
    };
  });
}

function confidenceFor(similarity: number, recognized: boolean): AutoTimingConfidence {
  if (!recognized) return "estimated";
  if (similarity >= 0.92) return "high";
  if (similarity >= 0.7) return "medium";
  return "low";
}

function fragmentRanges(
  target: LyricTokenTarget,
  startTimeMs: number,
  endTimeMs: number
): Array<{ fragment: CreatorFragment; fragmentIndex: number; startTimeMs: number; endTimeMs: number }> {
  const duration = Math.max(0, endTimeMs - startTimeMs);
  const weights = target.fragments.map(({ fragment }) => graphemeLength(fragment.text));
  const totalWeight = weights.reduce((sum, weight) => sum + weight, 0);
  let consumed = 0;
  return target.fragments.map(({ fragment, fragmentIndex }, index) => {
    const start = startTimeMs + Math.round(duration * consumed / totalWeight);
    consumed += weights[index];
    const end = startTimeMs + Math.round(duration * consumed / totalWeight);
    const range = {
      fragment,
      fragmentIndex,
      startTimeMs: start,
      endTimeMs: end,
    };
    return range;
  });
}

export function buildAutoTimingCandidate(input: AutoTimingRunInput): AutoTimingCandidate {
  const durationMs = Number.isFinite(input.durationMs) ? Math.max(0, Math.round(input.durationMs)) : 0;
  const words = transcriptWords(input.words, durationMs);
  const energyFrames = (input.energyFrames ?? [])
    .filter((frame) => Number.isFinite(frame.timeMs) && Number.isFinite(frame.energy))
    .slice()
    .sort((left, right) => left.timeMs - right.timeMs);
  const targets = projectTargets(input.project, durationMs);
  const lanes = new Map<string, LyricTokenTarget[]>();
  for (const target of targets) {
    const lane = lanes.get(target.lane) ?? [];
    lane.push(target);
    lanes.set(target.lane, lane);
  }
  const fragments: Record<string, AutoTimingFragmentCandidate> = {};
  const supportedLineIds = new Set<string>();
  const lineRanges = new Map<string, { startTimeMs: number; endTimeMs: number }>();
  const claimedTranscriptWords = new Map<number, string>();
  const orderedLanes = Array.from(lanes.values()).sort((left, right) =>
    Number(left[0].lane.startsWith("background")) - Number(right[0].lane.startsWith("background"))
  );
  for (const laneTargets of orderedLanes) {
    for (const target of laneTargets) {
      const attachment = target.attachedToLineId ? lineRanges.get(target.attachedToLineId) : undefined;
      if (attachment) {
        target.windowStartMs ??= attachment.startTimeMs;
        target.windowEndMs ??= attachment.endTimeMs;
      }
    }
    const matches = alignAutoTimingTokens(laneTargets, words);
    for (const match of matches) {
      if (!match.word || match.word.estimatedTiming) continue;
      const reusedAcrossVoices = match.sourceWordIndexes.some((index) => {
        const claimedLane = claimedTranscriptWords.get(index);
        return claimedLane !== undefined && claimedLane !== match.target.lane;
      });
      if (reusedAcrossVoices) {
        // Whisper has no speaker assignment here. Reusing a lead's heard word
        // for a second singer/background line is an editable overlap estimate,
        // not independent evidence that both voices sang at the same time.
        match.word.estimatedTiming = true;
      } else {
        match.sourceWordIndexes.forEach((index) => claimedTranscriptWords.set(index, match.target.lane));
      }
    }
    const ranges = interpolateLane(matches, durationMs, energyFrames);
    matches.forEach((match, index) => {
      const range = ranges[index];
      if (range.recognized) supportedLineIds.add(match.target.lineId);
      const lineRange = lineRanges.get(match.target.lineId);
      lineRanges.set(match.target.lineId, {
        startTimeMs: Math.min(lineRange?.startTimeMs ?? range.startTimeMs, range.startTimeMs),
        endTimeMs: Math.max(lineRange?.endTimeMs ?? range.endTimeMs, range.endTimeMs),
      });
      // A word model cannot hear boundaries inside a word. Fragment splits are
      // estimates even when the enclosing word has an exact transcript match.
      const recognized = range.recognized && match.target.fragments.length === 1;
      const confidence = confidenceFor(range.similarity, recognized);
      for (const fragmentRange of fragmentRanges(
        match.target,
        range.startTimeMs,
        range.endTimeMs
      )) {
        // Interpolation is not evidence against an existing hand-timed word or
        // syllable. Preserve those useful boundaries even in Replace mode when
        // the recognizer could not support a real replacement.
        const existing = fragmentRange.fragment;
        if (!recognized && validTime(existing.startTimeMs) && validTime(existing.endTimeMs) &&
          existing.endTimeMs > existing.startTimeMs && existing.endTimeMs <= durationMs) {
          fragmentRange.startTimeMs = existing.startTimeMs;
          fragmentRange.endTimeMs = existing.endTimeMs;
        }
        if (fragmentRange.endTimeMs <= fragmentRange.startTimeMs) continue;
        fragments[fragmentRange.fragment.id] = {
          fragmentId: fragmentRange.fragment.id,
          lineId: match.target.lineId,
          lineIndex: match.target.lineIndex,
          tokenIndex: match.target.tokenIndex,
          fragmentIndex: fragmentRange.fragmentIndex,
          startTimeMs: fragmentRange.startTimeMs,
          endTimeMs: fragmentRange.endTimeMs,
          confidence,
          method: recognized
            ? range.similarity >= 0.92
              ? "recognized"
              : "aligned"
            : "interpolated",
          score: range.similarity,
        };
      }
    });
  }
  return {
    projectFingerprint: input.projectFingerprint,
    modelId: input.modelId,
    modelVersion: input.modelVersion,
    durationMs,
    createdAt: Date.now(),
    fragments,
    transcriptWordCount: input.words.length,
    supportedLineIds: [...supportedLineIds],
  };
}

export function applyAutoTimingCandidate(
  project: CreatorProject,
  candidate: AutoTimingCandidate,
  options: AutoTimingApplyOptions
): CreatorProject {
  const next = cloneCreatorProject(project);
  if (options.mode === "missing") {
    const lanes = new Map<string, Array<{ fragment: CreatorFragment; lineId: string }>>();
    next.lines.forEach((line) => {
      const lane = lanes.get(laneForLine(line)) ?? [];
      line.tokens.forEach((token) => token.fragments.forEach((fragment) => lane.push({ fragment, lineId: line.id })));
      lanes.set(laneForLine(line), lane);
    });
    for (const lane of lanes.values()) {
      const followingBoundary = Array.from<number>({ length: lane.length });
      let nextBoundary = candidate.durationMs;
      for (let index = lane.length - 1; index >= 0; index -= 1) {
        followingBoundary[index] = nextBoundary;
        const fragment = lane[index].fragment;
        if (validTime(fragment.startTimeMs)) nextBoundary = fragment.startTimeMs;
        else if (validTime(fragment.endTimeMs)) nextBoundary = fragment.endTimeMs;
      }
      let previousBoundary = 0;
      lane.forEach(({ fragment, lineId }, index) => {
        const proposed = candidate.fragments[fragment.id];
        const included = !options.includedLineIds || options.includedLineIds.has(lineId);
        const hasStart = fragment.startTimeMs !== null;
        const hasEnd = fragment.endTimeMs !== null;
        const low = Math.max(0, previousBoundary);
        const high = Math.min(candidate.durationMs, followingBoundary[index]);
        if (included && validProposal(proposed, candidate.durationMs) && (!hasStart || !hasEnd)) {
          if (hasStart && !hasEnd) {
            const start = fragment.startTimeMs!;
            if (high > start) fragment.endTimeMs = Math.max(start + MIN_FRAGMENT_MS, Math.min(high, proposed.endTimeMs));
          } else if (!hasStart && hasEnd) {
            const end = fragment.endTimeMs!;
            if (end > low) fragment.startTimeMs = Math.min(end - MIN_FRAGMENT_MS, Math.max(low, proposed.startTimeMs));
          } else if (high > low) {
            const length = Math.min(high - low, proposed.endTimeMs - proposed.startTimeMs);
            const start = Math.max(low, Math.min(high - length, proposed.startTimeMs));
            fragment.startTimeMs = start;
            fragment.endTimeMs = start + length;
          }
        }
        if (validTime(fragment.endTimeMs)) previousBoundary = fragment.endTimeMs;
        else if (validTime(fragment.startTimeMs)) previousBoundary = fragment.startTimeMs;
      });
    }
    next.lines.forEach((line) => {
      if (!options.includedLineIds || options.includedLineIds.has(line.id)) syncLineTiming(line);
    });
    return next;
  }
  next.lines.forEach((line) => {
    if (options.includedLineIds && !options.includedLineIds.has(line.id)) return;
    line.tokens.forEach((token) => {
      token.fragments.forEach((fragment) => {
        const proposed = candidate.fragments[fragment.id];
        if (!validProposal(proposed, candidate.durationMs)) return;
        fragment.startTimeMs = proposed.startTimeMs;
        fragment.endTimeMs = proposed.endTimeMs;
      });
    });
    syncLineTiming(line);
  });
  return next;
}

function validProposal(
  proposed: AutoTimingFragmentCandidate | undefined,
  durationMs: number
): proposed is AutoTimingFragmentCandidate {
  return Boolean(proposed && Number.isFinite(proposed.startTimeMs) && Number.isFinite(proposed.endTimeMs) &&
    proposed.startTimeMs >= 0 && proposed.endTimeMs > proposed.startTimeMs && proposed.endTimeMs <= durationMs);
}

export function summarizeAutoTimingCandidate(candidate: AutoTimingCandidate): Record<AutoTimingConfidence, number> {
  const summary: Record<AutoTimingConfidence, number> = {
    high: 0,
    medium: 0,
    low: 0,
    estimated: 0,
  };
  Object.values(candidate.fragments).forEach((fragment) => {
    summary[fragment.confidence] += 1;
  });
  return summary;
}
