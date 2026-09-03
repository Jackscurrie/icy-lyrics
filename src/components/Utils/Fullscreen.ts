import { GetCurrentLyricsContainerInstance } from "../../utils/Lyrics/Applyer/CreateLyricsContainer.ts";
import {
  deferLyricsPresentationRemeasure,
  flushDeferredLyricsPresentationRemeasure,
  getLyricsLineCarrier,
} from "../../utils/Lyrics/LyricsVirtualizer.ts";
import {
  GetFullscreenLyricsTransitionTargets,
  PrepareFullscreenLyricsPresentation,
  PrepareFullscreenLyricsPresentationExit,
  QueueForceScroll,
  QueueSmoothForceScroll,
  ResetFullscreenLyricsPresentation,
  ResetLastLine,
} from "../../utils/Scrolling/ScrollToActiveLine.ts";
import { $currentLyricsData, $fullscreenRevealMode } from "../../utils/stores.ts";
import { $forceCompactMode, $isNowBarOpen } from "../../utils/uiState.ts";
import Global from "../Global/Global.ts";
import PageView, { Compactify, GetPageRoot, PageContainer, Tooltips } from "../Pages/PageView.ts";
import { EnableCompactMode, IsCompactMode } from "./CompactMode.ts";
import { CleanUpNowBarComponents, CloseNowBar, DeregisterNowBarBtn, OpenNowBar } from "./NowBar.ts";
import TransferElement from "./TransferElement.ts";
import { IsPIP } from "./PopupLyrics.ts";
import { Spring } from "../../modules/Spring.ts";
import { Maid } from "../../modules/Maid.ts";
import Scheduler from "../../modules/Scheduler.ts";
import {
  FULLSCREEN_VIEW_ORDER,
  FullscreenTransitionGate,
  computeFullscreenFlipGeometry,
  getFullscreenLyricsContextTransition,
  getFullscreenViewScrollHandoff,
  mergeFullscreenTransitionTargets,
  stepFullscreenView,
  type FullscreenHost,
  type FullscreenView,
} from "../../utils/FullscreenPresentation.ts";
import {
  bindFullscreenViewNavigation,
  ensureFullscreenViewToolbar,
  syncFullscreenViewNavigation,
  type FullscreenViewNavigationHandle,
} from "../../utils/FullscreenViewNavigation.ts";
import "../../css/fullscreen-views.css";

export type { FullscreenHost, FullscreenView } from "../../utils/FullscreenPresentation.ts";

let cinemaViewOpen = false;

const Fullscreen = {
  Open,
  Close,
  Toggle,
  SetHost,
  SetView,
  StepView,
  ResetView,
  EnsureViewControls,
  IsOpen: false,
  CurrentHost: "closed" as FullscreenHost,
  CurrentView: "mixed" as FullscreenView,
  get CinemaViewOpen() {
    return cinemaViewOpen;
  },
  set CinemaViewOpen(value: boolean) {
    SetHost(Fullscreen.IsOpen ? (value ? "cinema" : "document") : "closed");
  },
};

const ControlsMaid = new Maid();

const controlsOpacitySpring = new Spring(0, 2, 2, 0); // Goal: 0.65
const artworkBrightnessSpring = new Spring(0, 2, 2, 0); // Goal: 0.78

let animationLastTimestamp: number | undefined;

let visualsApplied = false;
let pageHover = false;
let mediaBoxHover = false;

let lastPageMouseMove: number | undefined;

const Page_MouseMove = () => {
  pageHover = true;
  lastPageMouseMove = performance.now();
  ToggleControls();
  if (!mediaBoxHover) {
    MouseMoveChecker();
  }
};

const MouseMoveChecker = () => {
  const now = performance.now();
  if (lastPageMouseMove !== undefined && now - lastPageMouseMove >= 750 && !mediaBoxHover) {
    animationLastTimestamp = now;
    ToggleControls(true);
    ControlsMaid.Clean("MouseMoveChecker");
    return;
  }
  ControlsMaid.Give(Scheduler.OnPreRender(MouseMoveChecker), "MouseMoveChecker");
};

const RunMediaBoxAnimation = () => {
  const timestampNow = performance.now();

  if (animationLastTimestamp !== undefined) {
    const deltaTime = (timestampNow - animationLastTimestamp) / 1000;
    const controlsOpacity = controlsOpacitySpring.Step(deltaTime);
    const artworkBrightness = artworkBrightnessSpring.Step(deltaTime);

    const MediaBox = PageContainer?.querySelector<HTMLElement>(
      ".ContentBox .NowBar .Header .MediaBox"
    );

    if (MediaBox) {
      MediaBox.style.setProperty("--ArtworkBrightness", artworkBrightness.toString());
      MediaBox.style.setProperty("--ControlsOpacity", controlsOpacity.toString());
    }

    if (controlsOpacitySpring.CanSleep() && artworkBrightnessSpring.CanSleep()) {
      animationLastTimestamp = undefined;
      visualsApplied = false;
      return;
    }
  }

  animationLastTimestamp = timestampNow;

  ControlsMaid.Give(Scheduler.OnPreRender(RunMediaBoxAnimation), "MediaBoxAnimation");
};

// While a slider handle is being dragged the pointer regularly leaves the MediaBox,
// and `MediaBox_MouseOut` fires unconditionally — without this latch the controls
// would fade out from under the cursor mid-drag.
let controlsDragLock = false;

export const SetControlsDragLock = (locked: boolean) => {
  if (controlsDragLock === locked) return;
  controlsDragLock = locked;
  // Re-evaluate once the drag is over so the controls settle into whatever the
  // hover state became while we were ignoring it.
  if (!locked) ToggleControls(true);
};

const ToggleControls = (force: boolean = false) => {
  if (controlsDragLock) return;

  const now = performance.now();

  const getControlsOpacityGoal = () => {
    if (lastPageMouseMove !== undefined && now - lastPageMouseMove >= 750) {
      return 0;
    } else if (pageHover && !mediaBoxHover) {
      return 0.65;
    } else if (mediaBoxHover) {
      return 0.985;
    } else {
      return 0;
    }
  };

  const getArtworkBrightnessGoal = () => {
    if (lastPageMouseMove !== undefined && now - lastPageMouseMove >= 750) {
      return 1;
    } else if (pageHover && !mediaBoxHover) {
      return 0.78;
    } else if (mediaBoxHover) {
      return 0.55;
    } else {
      return 1;
    }
  };

  controlsOpacitySpring.SetGoal(getControlsOpacityGoal());
  artworkBrightnessSpring.SetGoal(getArtworkBrightnessGoal());

  if (force || visualsApplied === false) {
    visualsApplied = true;
    RunMediaBoxAnimation();
  }
};

let MediaBoxEventAbortController: AbortController | undefined;
let ViewControlsAbortController: AbortController | undefined;
let ViewControlsHandle: FullscreenViewNavigationHandle | undefined;
let ViewControlsPage: HTMLElement | undefined;
const viewTransitionGate = new FullscreenTransitionGate();
const activeViewAnimations = new Set<Animation>();
const activeLyricsFlipElements = new Set<HTMLElement>();
const activeViewTransitionElements = new Set<HTMLElement>();
let lyricsPresentationPendingExit = false;
let fullscreenEntryAnimationGeneration = 0;

const nextAnimationFrame = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

const runFullscreenEntryAnimation = async (page: HTMLElement) => {
  const generation = ++fullscreenEntryAnimationGeneration;
  page.classList.add("frame_F_Entering");

  // Two frames guarantee the translated starting surface is painted after its
  // document/body handoff before we ask Chromium to transition it to rest.
  await nextAnimationFrame();
  await nextAnimationFrame();
  if (
    generation !== fullscreenEntryAnimationGeneration ||
    !Fullscreen.IsOpen ||
    PageContainer !== page
  ) {
    return;
  }

  page.classList.remove("frame_F_Enter");
  await new Promise((resolve) => setTimeout(resolve, 650));
  if (generation === fullscreenEntryAnimationGeneration) {
    page.classList.remove("frame_F_Entering");
  }
};

const cancelFullscreenEntryAnimation = (page?: HTMLElement | null) => {
  fullscreenEntryAnimationGeneration += 1;
  page?.classList.remove("frame_F_Enter", "frame_F_Entering");
};

const MediaBox_MouseIn = () => {
  mediaBoxHover = true;
  pageHover = true;
  ToggleControls();
  ControlsMaid.Clean("MouseMoveChecker");
};

const MediaBox_MouseOut = () => {
  mediaBoxHover = false;
  pageHover = true;
  ToggleControls();
};

const MediaBox_MouseMove = () => {
  mediaBoxHover = true;
  pageHover = true;
  ControlsMaid.Clean("MouseMoveChecker");
  ToggleControls();
};
const Page_MouseIn = () => {
  mediaBoxHover = false;
  pageHover = true;
  ToggleControls();
};

const Page_MouseOut = () => {
  mediaBoxHover = false;
  pageHover = false;
  ToggleControls();
  ControlsMaid.Clean("MouseMoveChecker");
};

export const ExitFullscreenElement = async () => {
  if (document.fullscreenElement) {
    await document.exitFullscreen();
  }
  setTimeout(Compactify, 1000);
};

export const EnterIcyLyricsFullscreen = async () => {
  const mainElement = document.querySelector<HTMLElement>("#main");
  if (mainElement) {
    mainElement.style.display = "none";
  }

  try {
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen();
    }
  } catch (err: unknown) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    console.error(`Fullscreen error: ${errorMessage}`);
  }

  document.documentElement.focus();

  setTimeout(Compactify, 1000);
};

function CleanupMediaBox() {
  MediaBoxEventAbortController?.abort();
  MediaBoxEventAbortController = undefined;

  ControlsMaid.CleanUp();

  animationLastTimestamp = undefined;
  lastPageMouseMove = undefined;

  visualsApplied = false;
  mediaBoxHover = false;
  pageHover = false;
  controlsDragLock = false;

  cancelViewAnimations(PageContainer);
  if (lyricsPresentationPendingExit && Fullscreen.CurrentView !== "lyrics") {
    PrepareFullscreenLyricsPresentationExit();
    ResetFullscreenLyricsPresentation();
    PageContainer?.classList.remove("FullscreenViewTransitionFromLyrics");
  }
  lyricsPresentationPendingExit = false;
}

function CleanupFullscreenViewControls() {
  ViewControlsAbortController?.abort();
  ViewControlsAbortController = undefined;
  ViewControlsHandle = undefined;
  ViewControlsPage = undefined;
}

function SetHost(host: FullscreenHost) {
  Fullscreen.CurrentHost = host;
  cinemaViewOpen = host === "cinema";
  if (host !== "closed" && Fullscreen.IsOpen) {
    EnsureViewControls();
  }
}

const applyViewClass = (page: HTMLElement, view: FullscreenView) => {
  page.classList.remove(...FULLSCREEN_VIEW_ORDER.map((item) => `FullscreenView--${item}`));
  page.classList.add(`FullscreenView--${view}`);
  page.dataset.fullscreenView = view;
  page.classList.toggle("FullscreenRevealMode", $fullscreenRevealMode.get());
};

const updateViewControls = (page: HTMLElement) => {
  const toolbar = ensureFullscreenViewToolbar(page);
  syncFullscreenViewNavigation(toolbar, Fullscreen.CurrentView);
};

/**
 * Keep one pair-independent set of non-overlapping compositor leaves for every
 * view switch. This is what lets a rapid second click capture the exact visual
 * midpoint of the first FLIP instead of changing from child to parent geometry
 * and snapping when the in-flight animations are cancelled.
 */
type ViewTransitionTarget = {
  element: HTMLElement;
  animationElement: HTMLElement;
  kind: "surface" | "lyrics-leaf" | "lyrics-context";
};

const readRenderedOpacity = (element: HTMLElement) => {
  const opacity = Number.parseFloat(getComputedStyle(element).opacity);
  return Number.isFinite(opacity) ? opacity : 1;
};

const readTargetOpacity = ({ element, animationElement, kind }: ViewTransitionTarget) =>
  kind === "lyrics-leaf"
    ? readRenderedOpacity(animationElement) * readRenderedOpacity(element)
    : readRenderedOpacity(animationElement);

const trackViewTransitionTargets = (targets: readonly ViewTransitionTarget[]) => {
  targets.forEach(({ element, animationElement }) => {
    activeViewTransitionElements.add(element);
    activeViewTransitionElements.add(animationElement);
  });
};

const getLyricsContextCarriers = (
  page: HTMLElement,
  lyricsLeaves: readonly HTMLElement[]
): HTMLElement[] => {
  const excluded = new Set(
    lyricsLeaves
      .map((element) => getLyricsLineCarrier(element))
      .filter((element): element is HTMLElement => element !== null)
  );
  return [
    ...page.querySelectorAll<HTMLElement>(
      ".VirtualLyricsContainer .IcyLyricsLineCarrier:has(.line)"
    ),
  ].filter((carrier) => carrier.isConnected && !excluded.has(carrier));
};

const appendFreshLyricsContextTargets = (
  page: HTMLElement,
  targets: ViewTransitionTarget[]
): ViewTransitionTarget[] => {
  const existing = new Set(targets.map(({ element }) => element));
  const lyricsLeaves = targets
    .filter(({ kind }) => kind === "lyrics-leaf")
    .map(({ element }) => element);
  const fresh = getLyricsContextCarriers(page, lyricsLeaves)
    .filter((element) => !existing.has(element))
    .map((element): ViewTransitionTarget => ({
      element,
      animationElement: element,
      kind: "lyrics-context",
    }));
  return [...targets, ...fresh];
};

const getViewTransitionTargets = (
  page: HTMLElement,
  previous: FullscreenView,
  next: FullscreenView
): ViewTransitionTarget[] => {
  const crossesLyricsBoundary = previous === "lyrics" || next === "lyrics";
  const lyricsLeaves = crossesLyricsBoundary
    ? mergeFullscreenTransitionTargets(GetFullscreenLyricsTransitionTargets(), [
        ...activeLyricsFlipElements,
      ]).filter((element) => element.isConnected)
    : [];
  const contextCarriers = crossesLyricsBoundary
    ? getLyricsContextCarriers(page, lyricsLeaves)
    : [];
  const surfaces = [
    page.querySelector<HTMLElement>(".ContentBox .NowBar .Header .MediaBox"),
    page.querySelector<HTMLElement>(".ContentBox .NowBar .Header .Metadata"),
    ...page.querySelectorAll<HTMLElement>(".ContentBox .NowBar .Header > .Timeline"),
    ...(lyricsLeaves.length > 0
      ? []
      : [page.querySelector<HTMLElement>(".ContentBox .LyricsContainer")]),
  ].filter((element): element is HTMLElement => element !== null);

  return [
    ...surfaces.map(
      (element): ViewTransitionTarget => ({
        element,
        animationElement: element,
        kind: "surface",
      })
    ),
    ...lyricsLeaves.map((element) => ({
      element,
      animationElement: getLyricsLineCarrier(element) ?? element,
      kind: "lyrics-leaf" as const,
    })),
    ...contextCarriers.map(
      (element): ViewTransitionTarget => ({
        element,
        animationElement: element,
        kind: "lyrics-context",
      })
    ),
  ];
};

const cleanupLyricsFlipTargets = (page: HTMLElement | null | undefined) => {
  activeViewTransitionElements.forEach((element) => {
    element.classList.remove(
      "FullscreenViewFlipLine",
      "FullscreenViewFlipVisual",
      "FullscreenViewFlipCarrier",
      "FullscreenViewContextCarrier"
    );
  });
  activeViewTransitionElements.clear();
  page
    ?.querySelectorAll<HTMLElement>(".FullscreenViewFlipLine, .FullscreenViewFlipVisual")
    .forEach((line) => {
      line.classList.remove("FullscreenViewFlipLine", "FullscreenViewFlipVisual");
    });
  page?.querySelectorAll<HTMLElement>(".FullscreenViewFlipCarrier").forEach((carrier) => {
    carrier.classList.remove("FullscreenViewFlipCarrier");
  });
  page?.querySelectorAll<HTMLElement>(".FullscreenViewContextCarrier").forEach((carrier) => {
    carrier.classList.remove("FullscreenViewContextCarrier");
  });
};

function cancelViewAnimations(page: HTMLElement | null | undefined) {
  viewTransitionGate.cancel();
  activeViewAnimations.forEach((animation) => animation.cancel());
  activeViewAnimations.clear();
  activeLyricsFlipElements.clear();
  page?.classList.remove("FullscreenViewTransitioning");
  cleanupLyricsFlipTargets(page);
  // A cancelled lyrics->mixed FLIP has no completion callback to perform its
  // deferred virtual-list layout pass, so consume it synchronously here.
  flushDeferredLyricsPresentationRemeasure();
}

function SetView(view: FullscreenView, animate = true) {
  const page = PageContainer;
  if (!page || !Fullscreen.IsOpen || !FULLSCREEN_VIEW_ORDER.includes(view)) return;
  if (view === Fullscreen.CurrentView && page.dataset.fullscreenView === view) {
    updateViewControls(page);
    return;
  }

  const previous = Fullscreen.CurrentView;
  Fullscreen.CurrentView = view;
  const restoresVirtualLyricsList =
    getFullscreenViewScrollHandoff(previous, view) === "pin-then-smooth";

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  let targets = getViewTransitionTargets(page, previous, view);
  const lyricsContextTransition = getFullscreenLyricsContextTransition(previous, view);

  // Capture the currently rendered frame before cancelling an in-flight FLIP,
  // so rapid edge clicks rebase smoothly from the visual midpoint.
  const first = new Map(
    targets.map((target) => [
      target.element,
      {
        rect: target.element.getBoundingClientRect(),
        opacity: readTargetOpacity(target),
      },
    ])
  );
  // A rapid second view change cancels the previous FLIP. Capture its rendered
  // midpoint first, then consume any deferred list measurement before the new
  // Last layout is committed. No paint occurs between these synchronous steps,
  // so the new inversion continues from the exact visible frame.
  flushDeferredLyricsPresentationRemeasure();
  activeViewAnimations.forEach((animation) => animation.cancel());
  activeViewAnimations.clear();
  cleanupLyricsFlipTargets(page);
  targets.forEach(({ element, animationElement, kind }) => {
    if (kind === "lyrics-leaf") {
      element.classList.add("FullscreenViewFlipLine");
      animationElement.classList.add("FullscreenViewFlipCarrier");
    } else if (kind === "lyrics-context") {
      animationElement.classList.add("FullscreenViewContextCarrier");
    }
  });
  trackViewTransitionTargets(targets);
  activeLyricsFlipElements.clear();
  targets.forEach(({ element, kind }) => {
    if (kind === "lyrics-leaf") activeLyricsFlipElements.add(element);
  });
  const token = viewTransitionGate.begin();

  if (view === "lyrics") {
    lyricsPresentationPendingExit = false;
    page.classList.remove("FullscreenViewTransitionFromLyrics");
    PrepareFullscreenLyricsPresentation();
  } else if (previous === "lyrics" || lyricsPresentationPendingExit) {
    lyricsPresentationPendingExit = true;
    page.classList.add("FullscreenViewTransitionFromLyrics");
  }

  page.classList.toggle("FullscreenViewTransitioning", animate && previous !== view);
  applyViewClass(page, view);
  // The first preparation above prevents a virtual-list flash. Refresh once
  // after the class commit so Reveal's role-change fit reads final focus-view
  // bounds rather than the mixed layout; its dataset guard makes this a no-op
  // for ordinary lines.
  if (view === "lyrics") {
    PrepareFullscreenLyricsPresentation();
    if (animate && previous !== view) deferLyricsPresentationRemeasure();
  }
  updateViewControls(page);
  // The focus-stage exit below explicitly centers the mixed virtual list before
  // its Last boxes are read. ResetLastLine would make the ordinary auto-scroller
  // write scrollTop again on the next lyrics frame, moving every carrier's base
  // coordinate underneath the compositor FLIP. Preserve the tracked line for
  // this one boundary; any playback drift is reconciled smoothly after landing.
  if (!restoresVirtualLyricsList) ResetLastLine();

  // Restore animator-owned lyric nodes before measuring the mixed-view Last
  // boxes. Their temporary class suppresses renderer transitions while WAAPI
  // moves the same stable leaves from focus back into the virtual list.
  if (lyricsPresentationPendingExit && Fullscreen.CurrentView !== "lyrics") {
    const exitAnchorIndex = PrepareFullscreenLyricsPresentationExit();
    ResetFullscreenLyricsPresentation(true);
    // Resetting presentation mode restores the real lines into their virtual
    // wrappers and measures their mixed-view sizes. Consume that measurement
    // before reading the FLIP Last boxes, then recenter once against the fresh
    // cache and re-arm the deferred gate. Previously this work ran only in the
    // finish callback: the carriers landed on stale, tightly packed wrapper
    // coordinates and visibly jumped when TanStack committed the true starts.
    flushDeferredLyricsPresentationRemeasure();
    if (exitAnchorIndex !== null) {
      PrepareFullscreenLyricsPresentationExit(exitAnchorIndex);
    } else {
      // Static/notice layouts have no focus anchor, but their settled mixed
      // geometry must still remain pinned until the container FLIP completes.
      deferLyricsPresentationRemeasure();
    }
    // The recenter can replace the mounted virtual window. Add its freshly
    // connected context carriers so the whole visible list participates in the
    // handoff rather than popping in after the three focus rows land.
    targets = appendFreshLyricsContextTargets(page, targets);
    targets
      .filter(({ kind }) => kind === "lyrics-context")
      .forEach(({ animationElement }) =>
        animationElement.classList.add("FullscreenViewContextCarrier")
      );
    trackViewTransitionTargets(targets);
  }

  // Read every Last box before starting any animation. Mixing reads with
  // `animate()` writes forces extra style/layout work during setup.
  const last = new Map(
    targets.map((target) => [
      target.element,
      {
        rect: target.element.getBoundingClientRect(),
        opacity: readTargetOpacity(target),
      },
    ])
  );

  // Target opacity must be measured before normalising the renderer-owned line
  // to opacity 1. The carrier then owns the complete crossfade without losing
  // focus-role values such as Previous=.3 or background=.58.
  targets.forEach(({ element, kind }) => {
    if (kind === "lyrics-leaf") element.classList.add("FullscreenViewFlipVisual");
  });

  const finish = () => {
    if (!viewTransitionGate.isCurrent(token)) return;
    // Consume the deferred TanStack pass while the transition marker still
    // suppresses renderer transitions and non-target rows. Removing these
    // classes first exposed one frame of post-measure geometry at the endpoint.
    if (restoresVirtualLyricsList) flushDeferredLyricsPresentationRemeasure();
    // `fill: both` holds the exact endpoint until this microtask has settled the
    // base layout. Release those effects deliberately; otherwise completed
    // Animation objects would continue overriding future carrier transforms.
    activeViewAnimations.forEach((animation) => animation.cancel());
    activeViewAnimations.clear();
    page.classList.remove("FullscreenViewTransitioning");
    cleanupLyricsFlipTargets(page);
    activeLyricsFlipElements.clear();
    if (lyricsPresentationPendingExit && Fullscreen.CurrentView !== "lyrics") {
      // The focus stage was already restored with a deferred remeasure before
      // the FLIP. Do not reset it a second time here: that would cancel the
      // deferred gate and move the list on the frame after the carriers land.
      lyricsPresentationPendingExit = false;
      page.classList.remove("FullscreenViewTransitionFromLyrics");
    }
    // Role updates were pinned while the stable lyric leaves crossed between
    // the virtual list and focus stage. Reconcile once, after the compositor
    // animation, so a line change during the FLIP cannot leave stale context.
    if (Fullscreen.CurrentView === "lyrics") PrepareFullscreenLyricsPresentation();
    if (!restoresVirtualLyricsList) flushDeferredLyricsPresentationRemeasure();
    if (restoresVirtualLyricsList) {
      // The list was centered synchronously for the FLIP. If playback crossed a
      // line while it was running, let the ordinary renderer converge from that
      // exact landing position instead of teleporting to a newly-active row.
      QueueSmoothForceScroll();
    } else {
      QueueForceScroll();
    }
    GetCurrentLyricsContainerInstance()?.Resize();
  };

  if (!animate || previous === view || typeof targets[0]?.element.animate !== "function") {
    finish();
    return;
  }

  // The class switch above establishes the Last layout. Artwork uses the
  // independent translate/scale longhands. Lyric leaves already use those
  // longhands for focus choreography, so their view FLIP is composed through
  // `transform` instead. Both paths stay on the compositor.
  const duration = reducedMotion ? 90 : 440;
  const started: Animation[] = [];
  for (const { element, animationElement, kind } of targets) {
    const before = first.get(element);
    const after = last.get(element);
    if (!after || (kind !== "lyrics-context" && !before)) continue;
    let keyframes: Keyframe[];
    if (kind === "lyrics-context" && lyricsContextTransition) {
      const show = lyricsContextTransition === "show";
      const startOpacity = show ? 0 : (before?.opacity ?? 1);
      const endOpacity = show ? after.opacity : 0;
      if (reducedMotion) {
        keyframes = [{ opacity: startOpacity }, { opacity: endOpacity }];
      } else if (before) {
        const { deltaX, deltaY, scaleX, scaleY } = computeFullscreenFlipGeometry(
          before.rect,
          after.rect
        );
        keyframes = [
          {
            opacity: startOpacity,
            transformOrigin: "top left",
            transform: `translate3d(${deltaX}px, ${deltaY}px, 0) scale(${scaleX}, ${scaleY})`,
          },
          {
            opacity: endOpacity,
            transformOrigin: "top left",
            transform: "translate3d(0, 0, 0) scale(1, 1)",
          },
        ];
      } else {
        // The exit recenter can mount a row that did not exist in the hidden
        // lyrics layout. It has no First box, so bring only that new row in with
        // a short compositor drift while every connected row uses exact FLIP.
        keyframes = [
          {
            opacity: startOpacity,
            transform: show
              ? "translate3d(0, 22px, 0) scale(0.985)"
              : "translate3d(0, 0, 0) scale(1)",
          },
          {
            opacity: endOpacity,
            transform: show
              ? "translate3d(0, 0, 0) scale(1)"
              : "translate3d(0, -22px, 0) scale(0.985)",
          },
        ];
      }
    } else if (reducedMotion) {
      keyframes = [
        { opacity: Math.min(before!.opacity, 0.7) },
        { opacity: after.opacity },
      ];
    } else if (kind === "lyrics-leaf") {
      const { deltaX, deltaY, scaleX, scaleY } = computeFullscreenFlipGeometry(
        before!.rect,
        after.rect
      );
      // The line itself owns renderer-driven translate/scale longhands, many of
      // which are !important. Animate its stable carrier instead. Setting the
      // carrier origin to the final line's top-left makes the ordinary FLIP
      // deltas exact even when the carrier fills the whole focus stage.
      const carrierRect = animationElement.getBoundingClientRect();
      const originX = after.rect.left - carrierRect.left;
      const originY = after.rect.top - carrierRect.top;
      const transformOrigin = `${originX}px ${originY}px`;
      keyframes = [
        {
          opacity: before.opacity,
          transform: `translate3d(${deltaX}px, ${deltaY}px, 0) scale(${scaleX}, ${scaleY})`,
          transformOrigin,
        },
        {
          opacity: after.opacity,
          transform: "translate3d(0, 0, 0) scale(1, 1)",
          transformOrigin,
        },
      ];
    } else {
      const { deltaX, deltaY, scaleX, scaleY } = computeFullscreenFlipGeometry(
        before!.rect,
        after.rect
      );
      keyframes = [
        {
          opacity: before!.opacity,
          transformOrigin: "top left",
          translate: `${deltaX}px ${deltaY}px`,
          scale: `${scaleX} ${scaleY}`,
        },
        {
          opacity: after.opacity,
          transformOrigin: "top left",
          translate: "0 0",
          scale: "1 1",
        },
      ];
    }
    const animation = animationElement.animate(keyframes, {
      duration,
      easing: reducedMotion ? "ease-out" : "cubic-bezier(0.16, 1, 0.3, 1)",
      fill: "both",
    });
    activeViewAnimations.add(animation);
    started.push(animation);
  }

  if (started.length === 0) finish();
  else void Promise.allSettled(started.map((animation) => animation.finished)).then(finish);
}

function StepView(direction: -1 | 1) {
  SetView(stepFullscreenView(Fullscreen.CurrentView, direction));
}

function ResetView(animate = false) {
  Fullscreen.CurrentView = "mixed";
  const page = PageContainer;
  if (!page || !Fullscreen.IsOpen) return;
  applyViewClass(page, "mixed");
  updateViewControls(page);
  if (animate) page.classList.add("FullscreenViewTransitioning");
}

function EnsureViewControls(page: HTMLElement | null = PageContainer) {
  if (!page || !Fullscreen.IsOpen || IsPIP) return;

  const toolbar = ensureFullscreenViewToolbar(page);
  if (
    ViewControlsAbortController &&
    !ViewControlsAbortController.signal.aborted &&
    ViewControlsPage === page &&
    ViewControlsHandle?.toolbar === toolbar
  ) {
    ViewControlsHandle.update();
    return;
  }

  CleanupFullscreenViewControls();
  const controller = new AbortController();
  ViewControlsAbortController = controller;
  ViewControlsPage = page;
  ViewControlsHandle = bindFullscreenViewNavigation(page, document, controller.signal, {
    getCurrentView: () => Fullscreen.CurrentView,
    isActive: () => Fullscreen.IsOpen && !IsPIP && PageContainer === page,
    onStep: StepView,
  });
}

$fullscreenRevealMode.listen((enabled) => {
  PageContainer?.classList.toggle("FullscreenRevealMode", enabled);
});

Global.Event.listen("page:destroy", CleanupFullscreenViewControls);

function Open(skipDocumentFullscreen: boolean = false, moveElement: boolean = true) {
  const IcyPage = PageContainer;
  const Root = document.body as HTMLElement;
  const mainElement = document.querySelector<HTMLElement>("#main");

  if (IcyPage) {
    // Set state first
    Fullscreen.IsOpen = true;
    SetHost(skipDocumentFullscreen ? "cinema" : "document");

    // Handle DOM changes
    if (moveElement) TransferElement(IcyPage, Root);
    IcyPage.classList.remove("frame_F_Exit");
    IcyPage.classList.add("Fullscreen", "frame_F_Enter");

    // Hide the main element
    if (mainElement && moveElement) {
      mainElement.style.display = "none";
    }

    // Safely destroy tooltip if it exists
    const nowBarToggle = Tooltips.NowBarToggle as any;
    if (nowBarToggle && typeof nowBarToggle.destroy === "function") {
      nowBarToggle.destroy();
    }

    const NowBarToggle = IcyPage.querySelector<HTMLElement>(".ViewControls #NowBarToggle");
    if (NowBarToggle) {
      NowBarToggle.remove();
    }

    CleanUpNowBarComponents();
    CleanupMediaBox();
    OpenNowBar(true);
    ResetFullscreenLyricsPresentation();
    ResetView(false);

    // Handle fullscreen state
    const handleFullscreen = async () => {
      try {
        if (!skipDocumentFullscreen) {
          await EnterIcyLyricsFullscreen();
        }
        void runFullscreenEntryAnimation(IcyPage);
        setTimeout(() => PageView.AppendViewControls(true), 50);
      } catch (err: unknown) {
        const errorMessage = err instanceof Error ? err.message : String(err);
        console.error(`Fullscreen error: ${errorMessage}`);
      }
    };

    handleFullscreen();
    ResetLastLine();

    // Setup media box interactions
    const MediaBox = IcyPage.querySelector<HTMLElement>(".ContentBox .NowBar .Header .MediaBox");
    const MediaImageContainer = IcyPage.querySelector<HTMLElement>(
      ".ContentBox .NowBar .Header .MediaBox .MediaImageContainer"
    );

    // Fullscreen navigation is page-owned and intentionally independent from
    // transient NowBar/media interaction cleanup.
    EnsureViewControls(IcyPage);

    MediaBoxEventAbortController = new AbortController();
    const signal = MediaBoxEventAbortController.signal;

    if (MediaBox && MediaImageContainer) {
      MediaBox.addEventListener("mouseenter", MediaBox_MouseIn, { signal });
      MediaBox.addEventListener("mouseleave", MediaBox_MouseOut, { signal });
      MediaBox.addEventListener("mousemove", MediaBox_MouseMove, { signal });

      IcyPage.addEventListener("mouseenter", Page_MouseIn, { signal });
      IcyPage.addEventListener("mousemove", Page_MouseMove, { signal });
      IcyPage.addEventListener("mouseleave", Page_MouseOut, { signal });
    }

    Global.Event.evoke("fullscreen:open", null);
  }
  setTimeout(() => {
    if (IsPIP) return;

    Compactify();

    if ($forceCompactMode.get() && !IsCompactMode()) {
      IcyPage?.classList.add("ForcedCompactMode");
      EnableCompactMode();
    }
  }, 750);

  setTimeout(() => {
    PageView.AppendViewControls(true);

    const NoLyrics = $currentLyricsData.get().includes("NO_LYRICS");
    if (NoLyrics && !IsCompactMode()) {
      IcyPage?.querySelector(".ContentBox .LyricsContainer")?.classList.add("Hidden");
      IcyPage?.querySelector<HTMLElement>(".ContentBox")?.classList.add("LyricsHidden");
    }
  }, 75);

  GetCurrentLyricsContainerInstance()?.Resize();
}

async function Close(isPip: boolean = false, preserveDocumentFullscreen = false) {
  const IcyPage = PageContainer;
  const mainElement = document.querySelector<HTMLElement>("#main");

  if (IcyPage) {
    Fullscreen.IsOpen = false;
    SetHost("closed");
    cancelFullscreenEntryAnimation(IcyPage);

    // Stop a rapid view switch before the page-level exit animation begins.
    // Keep focus-stage elements mounted until the exit finishes so cancelling
    // a lyrics-boundary FLIP cannot flash the full virtual list for one frame.
    cancelViewAnimations(IcyPage);

    if (isPip) {
      ResetFullscreenLyricsPresentation();
      IcyPage.classList.remove("FullscreenViewTransitionFromLyrics");
      lyricsPresentationPendingExit = false;
      IcyPage.classList.remove("Fullscreen");
      IcyPage.classList.remove(...FULLSCREEN_VIEW_ORDER.map((item) => `FullscreenView--${item}`));
      delete IcyPage.dataset.fullscreenView;
      Fullscreen.CurrentView = "mixed";

      ResetLastLine();

      if (!$isNowBarOpen.get()) {
        CloseNowBar();
      }

      CleanupFullscreenViewControls();
      CleanupMediaBox();
      CleanUpNowBarComponents();

      Global.Event.evoke("fullscreen:exit", null);
    } else {
      // Show the main element again
      if (mainElement) {
        mainElement.style.removeProperty("display");
      }

      // Apply exit animation and block all interaction for its duration
      IcyPage.classList.add("frame_F_Exit");
      document.body.style.pointerEvents = "none";

      await new Promise((r) => setTimeout(r, 650));

      ResetFullscreenLyricsPresentation();
      IcyPage.classList.remove("FullscreenViewTransitionFromLyrics");
      lyricsPresentationPendingExit = false;
      TransferElement(IcyPage, GetPageRoot() as HTMLElement);
      IcyPage.classList.remove("Fullscreen");
      IcyPage.classList.remove(...FULLSCREEN_VIEW_ORDER.map((item) => `FullscreenView--${item}`));
      delete IcyPage.dataset.fullscreenView;
      Fullscreen.CurrentView = "mixed";

      // Kick off fullscreen exit immediately (no need to wait for animation)
      if (!preserveDocumentFullscreen) {
        await ExitFullscreenElement();
      }
      setTimeout(() => PageView.AppendViewControls(true), 50);

      const NoLyrics = $currentLyricsData.get().includes("NO_LYRICS");
      if (NoLyrics) {
        IcyPage?.querySelector(".ContentBox .LyricsContainer")?.classList.remove("Hidden");
        IcyPage?.querySelector<HTMLElement>(".ContentBox")?.classList.remove("LyricsHidden");
        DeregisterNowBarBtn();
      }

      document.body.style.removeProperty("pointer-events");
      IcyPage.classList.remove("frame_F_Exit");

      ResetLastLine();

      if (!$isNowBarOpen.get()) {
        CloseNowBar();
      }

      CleanupFullscreenViewControls();
      CleanupMediaBox();
      CleanUpNowBarComponents();

      Global.Event.evoke("fullscreen:exit", null);
    }
  }
  if (!isPip) setTimeout(Compactify, 1000);
  GetCurrentLyricsContainerInstance()?.Resize();
}

function Toggle(skipDocumentFullscreen: boolean = false) {
  const IcyPage = PageContainer;

  if (IcyPage) {
    if (Fullscreen.IsOpen) {
      Close();
    } else {
      Open(skipDocumentFullscreen);
    }
  }
}

export { CleanupMediaBox };
export default Fullscreen;
