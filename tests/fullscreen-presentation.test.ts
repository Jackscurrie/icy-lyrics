import { describe, expect, it } from "vitest";
import {
  FULLSCREEN_OUTRO_POP_DURATION_MS,
  FULLSCREEN_VIEW_ORDER,
  FullscreenLineTransitionTracker,
  FullscreenDeferredWorkGate,
  FullscreenTransitionGate,
  computeFullscreenFlipGeometry,
  computeFullscreenOutroState,
  computeFullscreenRevealFit,
  getFullscreenAudibleLineEnd,
  getFullscreenLineTransitionKind,
  getFullscreenLineTransitionPlan,
  getFullscreenLyricsContextTransition,
  getFullscreenVocalSilenceAtPosition,
  getFullscreenVocalSilenceIntervals,
  getGuaranteedFullscreenOutroStart,
  getFullscreenViewNeighbours,
  getFullscreenViewScrollHandoff,
  getLyricsPlaybackClockPosition,
  getOffsetAdjustedDuration,
  getRawPlaybackTimeForLyricsAnimation,
  isFullscreenInterludeFullySilent,
  mergeFullscreenTransitionTargets,
  resolveFullscreenLineTransitionRoles,
  resolveKawarpBlurPasses,
  shouldAdvanceCompletedFullscreenInterlude,
  shouldHideTimedElementInReveal,
  stepFullscreenView,
  timedElementProgress,
  timedElementStatus,
} from "../src/utils/FullscreenPresentation.ts";
import { getLyricsAnimationPosition } from "../src/utils/Lyrics/Animator/Shared.ts";

describe("fullscreen view ordering", () => {
  it("keeps the requested bounded order without wrapping", () => {
    expect(FULLSCREEN_VIEW_ORDER).toEqual(["artwork-only", "artwork-titles", "mixed", "lyrics"]);
    expect(stepFullscreenView("artwork-only", -1)).toBe("artwork-only");
    expect(stepFullscreenView("artwork-only", 1)).toBe("artwork-titles");
    expect(stepFullscreenView("mixed", 1)).toBe("lyrics");
    expect(stepFullscreenView("lyrics", 1)).toBe("lyrics");
    expect(getFullscreenViewNeighbours("mixed")).toEqual({
      previous: "artwork-titles",
      next: "lyrics",
    });
  });

  it("invalidates stale transition completions", () => {
    const gate = new FullscreenTransitionGate();
    const first = gate.begin();
    const second = gate.begin();
    expect(gate.isCurrent(first)).toBe(false);
    expect(gate.isCurrent(second)).toBe(true);
    gate.cancel();
    expect(gate.isCurrent(second)).toBe(false);
  });

  it("coalesces deferred layout work and leaves no stale work after interruption", () => {
    const gate = new FullscreenDeferredWorkGate();
    let flushes = 0;

    gate.defer();
    gate.defer();
    expect(gate.isPending).toBe(true);
    expect(gate.flush(() => flushes++)).toBe(true);
    expect(gate.flush(() => flushes++)).toBe(false);
    expect(flushes).toBe(1);

    gate.defer();
    gate.cancel();
    expect(gate.isPending).toBe(false);
    expect(gate.flush(() => flushes++)).toBe(false);
    expect(flushes).toBe(1);
  });

  it("pins the virtual list only while lyrics focus returns to a regular view", () => {
    expect(getFullscreenViewScrollHandoff("lyrics", "mixed")).toBe("pin-then-smooth");
    expect(getFullscreenViewScrollHandoff("lyrics", "artwork-titles")).toBe("pin-then-smooth");
    expect(getFullscreenViewScrollHandoff("mixed", "lyrics")).toBe("reset-then-force");
    expect(getFullscreenViewScrollHandoff("mixed", "artwork-titles")).toBe("reset-then-force");
  });

  it("keeps surrounding lyric rows moving across the focus boundary", () => {
    expect(getFullscreenLyricsContextTransition("mixed", "lyrics")).toBe("hide");
    expect(getFullscreenLyricsContextTransition("lyrics", "mixed")).toBe("show");
    expect(getFullscreenLyricsContextTransition("artwork-titles", "lyrics")).toBe("hide");
    expect(getFullscreenLyricsContextTransition("mixed", "artwork-titles")).toBeNull();
  });

  it("preserves in-flight lyric leaves when a rapid reversal discovers new targets", () => {
    const previous = { id: "previous" };
    const current = { id: "current" };
    const next = { id: "next" };
    const newlyActive = { id: "newly-active" };

    expect(
      mergeFullscreenTransitionTargets([current, next, newlyActive], [previous, current, next])
    ).toEqual([previous, current, next, newlyActive]);
  });

  it("can settle final list geometry before a FLIP and freeze it until completion", () => {
    const gate = new FullscreenDeferredWorkGate();
    const phases: string[] = [];

    // Presentation exit first defers observer-driven virtualizer work. The
    // transition preflight consumes it to establish the true mixed-list boxes.
    gate.defer();
    expect(
      gate.flush(() => {
        phases.push("measure-final-list");
      })
    ).toBe(true);

    // Recenter against those fresh measurements, then freeze observer work so
    // the captured Last boxes cannot change under the compositor animation.
    phases.push("recenter");
    gate.defer();
    expect(gate.isPending).toBe(true);
    expect(phases).toEqual(["measure-final-list", "recenter"]);

    expect(
      gate.flush(() => {
        phases.push("post-flip-reconcile");
      })
    ).toBe(true);
    expect(phases).toEqual(["measure-final-list", "recenter", "post-flip-reconcile"]);
  });
});

describe("fullscreen reveal", () => {
  it("hides only not-yet-sung timed words and reclassifies backward seeks", () => {
    expect(timedElementStatus(900, 1_000, 2_000)).toBe("NotSung");
    expect(timedElementStatus(1_500, 1_000, 2_000)).toBe("Active");
    expect(timedElementStatus(2_000, 1_000, 2_000)).toBe("Sung");
    expect(shouldHideTimedElementInReveal("NotSung")).toBe(true);
    expect(shouldHideTimedElementInReveal("Active")).toBe(false);
    expect(shouldHideTimedElementInReveal("Sung")).toBe(false);
  });

  it("drives a continuous reveal from the same playback clock", () => {
    expect(timedElementProgress(900, 1_000, 2_000)).toBe(0);
    expect(timedElementProgress(1_250, 1_000, 2_000)).toBe(0.25);
    expect(timedElementProgress(2_000, 1_000, 2_000)).toBe(1);
    expect(timedElementProgress(2_500, 1_000, 2_000)).toBe(1);

    // A backward seek re-computes the value; there is no one-way CSS animation
    // state that can leave a future word visible.
    expect(timedElementProgress(1_100, 1_000, 2_000)).toBe(0.1);
    expect(getLyricsAnimationPosition(1_000, false)).toBe(1_000);
    expect(getLyricsAnimationPosition(1_000, true)).toBe(966.5);
  });
});

describe("fullscreen reveal line fitting", () => {
  it("only scales oversized content and always fits extreme lines", () => {
    expect(computeFullscreenRevealFit(800, 300, 800, 300)).toBe(1);
    expect(computeFullscreenRevealFit(1_600, 300, 800, 300)).toBe(0.5);
    expect(computeFullscreenRevealFit(800, 600, 800, 300)).toBe(0.5);
    expect(computeFullscreenRevealFit(8_000, 3_000, 800, 300)).toBe(0.1);
  });
});

describe("fullscreen lyric-group transition", () => {
  it("uses stable normal-mode endpoints in both playback directions", () => {
    expect(resolveFullscreenLineTransitionRoles(false, 1)).toEqual({
      outgoing: "FocusPreviousLine",
      incoming: "FocusCurrentLine",
    });
    expect(resolveFullscreenLineTransitionRoles(false, -1)).toEqual({
      outgoing: "FocusNextLine",
      incoming: "FocusCurrentLine",
    });
    expect(resolveFullscreenLineTransitionRoles(true, 1)).toEqual({
      outgoing: "FocusCurrentLine",
      incoming: "FocusCurrentLine",
    });
  });

  it("retains the previous anchor through a timing gap and crossfades the next group", () => {
    const tracker = new FullscreenLineTransitionTracker(450);

    expect(tracker.update(2, 1_000)).toMatchObject({
      anchorIndex: 2,
      active: false,
    });
    expect(tracker.update(null, 1_200)).toMatchObject({
      anchorIndex: 2,
      active: false,
    });

    expect(tracker.update(4, 1_300)).toEqual({
      anchorIndex: 4,
      active: true,
      fromIndex: 2,
      toIndex: 4,
      direction: 1,
      progress: 0,
    });
    expect(tracker.update(4, 1_525).progress).toBe(0.5);
    expect(tracker.update(4, 1_750)).toMatchObject({
      anchorIndex: 4,
      active: false,
      fromIndex: null,
      progress: 1,
    });
  });

  it("keeps the current Reveal line visible through a short untimed gap", () => {
    const tracker = new FullscreenLineTransitionTracker(450);
    tracker.update(2, 1_000);

    expect(tracker.update(null, 1_200)).toMatchObject({
      anchorIndex: 2,
      active: false,
      fromIndex: null,
    });
    expect(tracker.update(4, 1_300)).toMatchObject({
      anchorIndex: 4,
      active: true,
      fromIndex: 2,
    });
  });

  it("classifies dot boundaries and promotes the parked line when dots finish", () => {
    expect(getFullscreenLineTransitionKind(false, false)).toBe("line");
    expect(getFullscreenLineTransitionKind(false, true)).toBe("enter-interlude");
    expect(getFullscreenLineTransitionKind(true, false)).toBe("exit-interlude");

    expect(shouldAdvanceCompletedFullscreenInterlude(2_999, 3_000, true)).toBe(false);
    expect(shouldAdvanceCompletedFullscreenInterlude(3_000, 3_000, true)).toBe(true);
    expect(shouldAdvanceCompletedFullscreenInterlude(3_500, 3_000, false)).toBe(false);
  });

  it("stages interludes without leaking context lines into Reveal mode", () => {
    expect(getFullscreenLineTransitionPlan("line", false)).toEqual({
      stageInterlude: false,
      showDepartingPrevious: true,
      showEnteringNext: true,
      keepCompletedLeadAsPrevious: false,
    });
    expect(getFullscreenLineTransitionPlan("enter-interlude", false)).toEqual({
      stageInterlude: true,
      showDepartingPrevious: true,
      showEnteringNext: false,
      keepCompletedLeadAsPrevious: true,
    });
    expect(getFullscreenLineTransitionPlan("exit-interlude", false)).toEqual({
      stageInterlude: true,
      showDepartingPrevious: false,
      showEnteringNext: true,
      keepCompletedLeadAsPrevious: true,
    });
    expect(getFullscreenLineTransitionPlan("enter-interlude", true)).toEqual({
      stageInterlude: true,
      showDepartingPrevious: false,
      showEnteringNext: false,
      keepCompletedLeadAsPrevious: false,
    });
    expect(getFullscreenLineTransitionPlan("exit-interlude", true)).toEqual({
      stageInterlude: true,
      showDepartingPrevious: false,
      showEnteringNext: false,
      keepCompletedLeadAsPrevious: false,
    });
  });

  it("snaps on backward and large forward seeks instead of replaying stale transitions", () => {
    const tracker = new FullscreenLineTransitionTracker(450, 1_000);
    tracker.update(8, 10_000);
    tracker.update(10, 10_100);

    expect(tracker.update(3, 5_000)).toMatchObject({
      anchorIndex: 3,
      active: false,
      fromIndex: null,
      direction: 0,
      progress: 1,
    });
    expect(tracker.update(14, 8_000)).toMatchObject({
      anchorIndex: 14,
      active: false,
      fromIndex: null,
    });

    tracker.reset();
    expect(tracker.update(null, 8_100)).toMatchObject({
      anchorIndex: null,
      active: false,
      fromIndex: null,
    });
  });
});

describe("fullscreen outro", () => {
  it("starts from the final audible syllable instead of a padded line bound", () => {
    const paddedLead = {
      EndTime: 10_000,
      Syllables: { Lead: [{ EndTime: 7_900 }, { EndTime: 8_000 }] },
    };
    const paddedBackground = {
      EndTime: 10_000,
      Syllables: { Lead: [{ EndTime: 8_200 }] },
    };
    const groupEnd = Math.max(
      getFullscreenAudibleLineEnd(paddedLead, true),
      getFullscreenAudibleLineEnd(paddedBackground, true)
    );

    expect(groupEnd).toBe(8_200);
    expect(computeFullscreenOutroState(8_300, groupEnd, 10_000).active).toBe(true);
    expect(computeFullscreenOutroState(8_300, paddedLead.EndTime, 10_000).active).toBe(false);
    expect(getFullscreenAudibleLineEnd(paddedLead, false)).toBe(10_000);
  });

  it("models the maximum shifted lyric clock with its shared forward lead", () => {
    // Play and pause both use this transform, so pausing freezes rather than
    // moving every animation 100ms backward.
    expect(getLyricsPlaybackClockPosition(5_000, 500)).toBe(4_600);
    expect(getOffsetAdjustedDuration(10_000, -500)).toBe(10_000);
    expect(getOffsetAdjustedDuration(10_000, 0)).toBe(10_000);
    expect(getOffsetAdjustedDuration(10_000, 500)).toBe(9_600);
  });

  it("uses raw audio time for the tail and guarantees a smooth final pop", () => {
    const tokenEnd = 8_200;
    const normalAdjustment = getLyricsAnimationPosition(0, false);
    expect(getRawPlaybackTimeForLyricsAnimation(tokenEnd, -5_000, normalAdjustment)).toBe(3_100);
    expect(getRawPlaybackTimeForLyricsAnimation(tokenEnd, 0, normalAdjustment)).toBe(8_100);
    const delayedRawEnd = getRawPlaybackTimeForLyricsAnimation(tokenEnd, 5_000, normalAdjustment);
    expect(delayedRawEnd).toBe(13_100);
    const guaranteedStart = getGuaranteedFullscreenOutroStart(
      delayedRawEnd,
      10_000,
      FULLSCREEN_OUTRO_POP_DURATION_MS
    );
    expect(guaranteedStart).toBe(9_625);

    const start = computeFullscreenOutroState(guaranteedStart, guaranteedStart, 10_000);
    const middle = computeFullscreenOutroState(9_812.5, guaranteedStart, 10_000);
    expect(start.rotationDeg).toBe(0);
    expect(start.popProgress).toBe(0);
    expect(middle.rotationDeg).toBeCloseTo(45, 5);
    expect(middle.popProgress).toBeCloseTo(0.5, 5);
  });

  it("spins to 90 degrees and 0.78 scale before the final pop", () => {
    const start = computeFullscreenOutroState(8_000, 8_000, 10_000);
    expect(start.active).toBe(true);
    expect(start.rotationDeg).toBe(0);
    expect(start.scale).toBe(1);

    expect(computeFullscreenOutroState(8_000, 8_000, 10_000, 375, 1.18).scale).toBe(1.18);
    expect(computeFullscreenOutroState(8_000, 8_000, 10_000, 375, 1.3).scale).toBe(1.3);

    const popStart = computeFullscreenOutroState(9_625, 8_000, 10_000);
    expect(popStart.spinProgress).toBe(1);
    expect(popStart.rotationDeg).toBe(90);
    expect(popStart.scale).toBeCloseTo(0.78, 5);

    const end = computeFullscreenOutroState(10_000, 8_000, 10_000);
    expect(end.popProgress).toBe(1);
    expect(end.scale).toBe(0);
    expect(end.opacity).toBe(0);

    const predictorOvershoot = computeFullscreenOutroState(10_125, 8_000, 10_000);
    expect(predictorOvershoot.active).toBe(true);
    expect(predictorOvershoot.popProgress).toBe(1);
    expect(predictorOvershoot.scale).toBe(0);
    expect(predictorOvershoot.opacity).toBe(0);
  });

  it("resets deterministically before the final line end or after a seek", () => {
    expect(computeFullscreenOutroState(7_999, 8_000, 10_000).active).toBe(false);
    expect(computeFullscreenOutroState(1_000, 8_000, 10_000).spinProgress).toBe(0);

    const lateFrame = computeFullscreenOutroState(9_800, 8_000, 10_000);
    const afterBackwardSeek = computeFullscreenOutroState(8_500, 8_000, 10_000);
    expect(lateFrame.popProgress).toBeGreaterThan(0);
    expect(afterBackwardSeek.popProgress).toBe(0);
    expect(afterBackwardSeek.rotationDeg).toBeLessThan(lateFrame.rotationDeg);
  });
});

describe("fullscreen Kawarp blur", () => {
  it("removes blur only for fullscreen when its toggle is off", () => {
    expect(resolveKawarpBlurPasses(true, false)).toBe(0);
    expect(resolveKawarpBlurPasses(true, true)).toBe(8);
    expect(resolveKawarpBlurPasses(false, false)).toBe(8);
    expect(resolveKawarpBlurPasses(false, true)).toBe(8);
  });
});

describe("fullscreen view FLIP geometry", () => {
  it("moves and resizes stable lyric leaves without unbounded stale scales", () => {
    expect(
      computeFullscreenFlipGeometry(
        { left: 400, top: 300, width: 600, height: 120 },
        { left: 100, top: 80, width: 300, height: 240 }
      )
    ).toEqual({ deltaX: 300, deltaY: 220, scaleX: 2, scaleY: 0.5 });

    expect(
      computeFullscreenFlipGeometry(
        { left: Number.NaN, top: 0, width: 10_000, height: 0 },
        { left: 0, top: 0, width: 10, height: 10 }
      )
    ).toEqual({ deltaX: 0, deltaY: 0, scaleX: 4, scaleY: 1 });
  });
});

describe("fullscreen all-vocal interlude eligibility", () => {
  const candidate = { startTime: 10_000, endTime: 20_000 };

  it("keeps a full lead-to-lead silence window eligible", () => {
    const vocals = [
      { startTime: 0, endTime: 10_000 },
      { startTime: 20_000, endTime: 24_000 },
    ];
    expect(getFullscreenVocalSilenceIntervals(candidate, vocals, 3_000)).toEqual([candidate]);
    expect(getFullscreenVocalSilenceAtPosition(candidate, vocals, 14_000, 3_000)).toEqual(
      candidate
    );
    expect(isFullscreenInterludeFullySilent(candidate, vocals, 3_000)).toBe(true);
  });

  it("subtracts overlapping duet and second-speaker groups regardless of row order", () => {
    const vocals = [
      { startTime: 12_000, endTime: 16_000 },
      { startTime: 8_000, endTime: 11_000 },
      { startTime: 15_000, endTime: 16_500 },
    ];
    expect(getFullscreenVocalSilenceIntervals(candidate, vocals, 3_000)).toEqual([
      { startTime: 16_500, endTime: 20_000 },
    ]);
    expect(getFullscreenVocalSilenceAtPosition(candidate, vocals, 14_000, 3_000)).toBeNull();
    expect(getFullscreenVocalSilenceAtPosition(candidate, vocals, 18_000, 3_000)).toEqual({
      startTime: 16_500,
      endTime: 20_000,
    });
    expect(isFullscreenInterludeFullySilent(candidate, vocals, 3_000)).toBe(false);
  });

  it("rejects a candidate when background vocals leave no long-enough silence", () => {
    const backgroundVocals = [{ startTime: 10_500, endTime: 18_000 }];
    expect(getFullscreenVocalSilenceIntervals(candidate, backgroundVocals, 3_000)).toEqual([]);
    expect(
      getFullscreenVocalSilenceAtPosition(candidate, backgroundVocals, 19_000, 3_000)
    ).toBeNull();
    expect(isFullscreenInterludeFullySilent(candidate, backgroundVocals, 3_000)).toBe(false);
  });

  it("rejects a partial-overlap candidate instead of starting its dot animation midway", () => {
    const backgroundVocal = [{ startTime: 10_000, endTime: 13_000 }];
    expect(getFullscreenVocalSilenceIntervals(candidate, backgroundVocal, 3_000)).toEqual([
      { startTime: 13_000, endTime: 20_000 },
    ]);
    expect(isFullscreenInterludeFullySilent(candidate, backgroundVocal, 3_000)).toBe(false);
  });

  it("merges chained vocal overlaps and hands off at the exact next-vocal boundary", () => {
    const allVoices = [
      { startTime: 10_000, endTime: 13_000 },
      { startTime: 12_000, endTime: 17_000 },
      { startTime: 16_000, endTime: 19_000 },
    ];
    expect(getFullscreenVocalSilenceIntervals(candidate, allVoices, 1_000)).toEqual([
      { startTime: 19_000, endTime: 20_000 },
    ]);
    expect(getFullscreenVocalSilenceAtPosition(candidate, [], 19_999, 3_000)).toEqual(candidate);
    expect(getFullscreenVocalSilenceAtPosition(candidate, [], 20_000, 3_000)).toBeNull();
  });

  it("ignores invalid vocal rows and accepts an exact minimum-duration window", () => {
    expect(
      getFullscreenVocalSilenceIntervals(
        { startTime: 1_000, endTime: 4_000 },
        [
          { startTime: Number.NaN, endTime: 2_000 },
          { startTime: 3_000, endTime: 2_000 },
        ],
        3_000
      )
    ).toEqual([{ startTime: 1_000, endTime: 4_000 }]);
  });
});
