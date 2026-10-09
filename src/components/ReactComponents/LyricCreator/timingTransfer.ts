import type { CreatorFragment, CreatorProject } from "./model.ts";

export function canCopyCreatorFragmentTiming(fragment: CreatorFragment | undefined): boolean {
  if (!fragment || (fragment.startTimeMs === null && fragment.endTimeMs === null)) return false;
  const valid = (value: number | null) => value === null || (Number.isFinite(value) && value >= 0);
  return valid(fragment.startTimeMs) && valid(fragment.endTimeMs) &&
    (fragment.startTimeMs === null || fragment.endTimeMs === null ||
      fragment.endTimeMs >= fragment.startTimeMs);
}

/** Copy only a fragment's timing pair, retaining text, identities, and the source. */
export function copyCreatorFragmentTiming(
  project: CreatorProject,
  sourceFragmentId: string,
  targetFragmentId: string
): CreatorProject | null {
  if (sourceFragmentId === targetFragmentId) return null;
  let source: CreatorFragment | undefined;
  let targetLineIndex = -1;
  let targetTokenIndex = -1;
  let targetFragmentIndex = -1;
  project.lines.forEach((line, lineIndex) => {
    line.tokens.forEach((token, tokenIndex) => {
      token.fragments.forEach((fragment, fragmentIndex) => {
        if (fragment.id === sourceFragmentId) source = fragment;
        if (fragment.id === targetFragmentId) {
          targetLineIndex = lineIndex;
          targetTokenIndex = tokenIndex;
          targetFragmentIndex = fragmentIndex;
        }
      });
    });
  });
  if (!canCopyCreatorFragmentTiming(source) || targetLineIndex < 0) return null;
  const line = project.lines[targetLineIndex];
  const token = line.tokens[targetTokenIndex];
  const target = token.fragments[targetFragmentIndex];
  if (target.startTimeMs === source.startTimeMs && target.endTimeMs === source.endTimeMs) return null;

  const fragments = [...token.fragments];
  fragments[targetFragmentIndex] = {
    ...target,
    startTimeMs: source.startTimeMs,
    endTimeMs: source.endTimeMs,
  };
  const tokens = [...line.tokens];
  tokens[targetTokenIndex] = { ...token, fragments };
  const lineFragments = tokens.flatMap((nextToken) => nextToken.fragments);
  const starts = lineFragments.map((fragment) => fragment.startTimeMs)
    .filter((time): time is number => time !== null && Number.isFinite(time));
  const ends = lineFragments.map((fragment) => fragment.endTimeMs)
    .filter((time): time is number => time !== null && Number.isFinite(time));
  const lines = [...project.lines];
  lines[targetLineIndex] = {
    ...line,
    tokens,
    startTimeMs: starts.length ? Math.min(...starts) : null,
    endTimeMs: ends.length ? Math.max(...ends) : null,
  };
  return { ...project, lines };
}

const CELL_SELECTOR = "[data-creator-timing-fragment]";
const DRAG_THRESHOLD_PX = 6;

interface TimingTransferCallbacks {
  getFragment: (id: string) => CreatorFragment | undefined;
  onCopyTiming: (sourceId: string, targetId: string) => void;
  formatTime: (timeMs: number | null) => string;
}

/**
 * Pointer positions and temporary feedback stay outside React's render loop.
 * Delegation also keeps this working as playback updates the word highlights.
 */
export function attachCreatorTimingTransfer(
  sheet: HTMLElement,
  callbacks: TimingTransferCallbacks
): () => void {
  const doc = sheet.ownerDocument;
  const view = doc.defaultView;
  if (!view) return () => undefined;
  let drag: {
    pointerId: number;
    source: HTMLElement;
    sourceId: string;
    fragment: CreatorFragment;
    startX: number;
    startY: number;
    x: number;
    y: number;
    active: boolean;
  } | null = null;
  let ghost: HTMLElement | null = null;
  let hover: HTMLElement | null = null;
  let frame = 0;
  let suppressClick = false;
  let suppressionTimer = 0;
  const departingGhosts = new Map<HTMLElement, number>();
  const feedback = new Set<Animation>();
  const reducedMotion = () => view.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;

  const cellAt = (target: EventTarget | null): HTMLElement | null => {
    const candidate = (target as Element | null)?.closest?.<HTMLElement>(CELL_SELECTOR);
    return candidate && sheet.contains(candidate) ? candidate : null;
  };
  const updateHover = (next: HTMLElement | null) => {
    if (hover === next) return;
    hover?.removeAttribute("data-timing-drop-target");
    hover = next;
    hover?.setAttribute("data-timing-drop-target", "");
  };
  const updatePosition = () => {
    frame = 0;
    if (!drag?.active || !ghost) return;
    ghost.style.transform = `translate3d(${drag.x + 15}px, ${drag.y + 15}px, 0)`;
    const target = cellAt(doc.elementFromPoint(drag.x, drag.y));
    updateHover(target === drag.source ? null : target);
  };
  const makeGhost = () => {
    if (!drag) return;
    ghost = doc.createElement("div");
    ghost.className = "il-creator-timing-transfer";
    ghost.setAttribute("aria-hidden", "true");
    const card = doc.createElement("div");
    card.className = "il-creator-timing-transfer__card";
    const label = doc.createElement("strong");
    label.textContent = drag.fragment.text || "Empty fragment";
    const range = doc.createElement("span");
    range.textContent = `${callbacks.formatTime(drag.fragment.startTimeMs)} → ${callbacks.formatTime(drag.fragment.endTimeMs)}`;
    card.append(label, range);
    ghost.append(card);
    // Creator requests document fullscreen; keeping the ghost under that host
    // also works if the fullscreen owner changes in a future Spotify version.
    (doc.fullscreenElement ?? doc.body).append(ghost);
    drag.source.setAttribute("data-timing-drag-source", "");
    updatePosition();
  };
  const suppressDragClick = () => {
    suppressClick = true;
    view.clearTimeout(suppressionTimer);
    suppressionTimer = view.setTimeout(() => { suppressClick = false; }, 500);
  };
  const finish = (target: HTMLElement | null, cancelled = false) => {
    if (!drag) return;
    const completed = drag;
    drag = null;
    if (frame) view.cancelAnimationFrame(frame);
    frame = 0;
    completed.source.removeAttribute("data-timing-drag-source");
    updateHover(null);
    try {
      if (completed.source.hasPointerCapture?.(completed.pointerId)) {
        completed.source.releasePointerCapture(completed.pointerId);
      }
    } catch { /* The source can unmount if the creator changes stages. */ }
    if (completed.active) suppressDragClick();
    const targetId = !cancelled && completed.active && target !== completed.source
      ? target?.getAttribute("data-creator-timing-fragment") : null;
    if (ghost) {
      const departing = ghost;
      ghost = null;
      if (targetId && !reducedMotion()) {
        const bounds = target.getBoundingClientRect();
        departing.style.transition = "transform 160ms ease-out, opacity 160ms ease-out";
        departing.style.transform = `translate3d(${bounds.left + 12}px, ${bounds.top + 8}px, 0)`;
        departing.style.opacity = "0";
        departingGhosts.set(departing, view.setTimeout(() => {
          departing.remove();
          departingGhosts.delete(departing);
        }, 170));
      } else departing.remove();
    }
    if (targetId) {
      if (!reducedMotion() && typeof target.animate === "function") {
        const animation = target.animate(
          [{ transform: "scale(1.035)", opacity: 0.72 }, { transform: "scale(1)", opacity: 1 }],
          { duration: 230, easing: "cubic-bezier(.2,.8,.2,1)" }
        );
        feedback.add(animation);
        animation.onfinish = () => feedback.delete(animation);
        animation.oncancel = () => feedback.delete(animation);
      }
      callbacks.onCopyTiming(completed.sourceId, targetId);
    }
  };
  const onPointerDown = (event: PointerEvent) => {
    if (event.button !== 0 || event.isPrimary === false) return;
    finish(null, true);
    suppressClick = false;
    const source = cellAt(event.target);
    const sourceId = source?.getAttribute("data-creator-timing-fragment");
    const fragment = sourceId ? callbacks.getFragment(sourceId) : undefined;
    if (!source || !sourceId || !canCopyCreatorFragmentTiming(fragment)) return;
    drag = {
      pointerId: event.pointerId, source, sourceId, fragment: { ...fragment },
      startX: event.clientX, startY: event.clientY, x: event.clientX, y: event.clientY,
      active: false,
    };
    try { source.setPointerCapture?.(event.pointerId); } catch { /* Synthetic pointer events. */ }
  };
  const onPointerMove = (event: PointerEvent) => {
    if (!drag || drag.pointerId !== event.pointerId) return;
    drag.x = event.clientX;
    drag.y = event.clientY;
    if (!drag.active) {
      if (Math.hypot(drag.x - drag.startX, drag.y - drag.startY) < DRAG_THRESHOLD_PX) return;
      drag.active = true;
      makeGhost();
    }
    event.preventDefault();
    if (!frame) frame = view.requestAnimationFrame(updatePosition);
  };
  const onPointerUp = (event: PointerEvent) => {
    if (!drag || drag.pointerId !== event.pointerId) return;
    const target = cellAt(doc.elementFromPoint(event.clientX, event.clientY));
    finish(target);
  };
  const onPointerCancel = (event: PointerEvent) => {
    if (drag?.pointerId === event.pointerId) finish(null, true);
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (!drag || event.key !== "Escape") return;
    event.preventDefault();
    event.stopImmediatePropagation();
    finish(null, true);
  };
  const onClick = (event: MouseEvent) => {
    if (!suppressClick || event.detail === 0) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    suppressClick = false;
  };
  const onNativeDrag = (event: DragEvent) => {
    if (drag) event.preventDefault();
  };
  const onBlur = () => finish(null, true);
  sheet.addEventListener("pointerdown", onPointerDown);
  sheet.addEventListener("click", onClick, true);
  sheet.addEventListener("dragstart", onNativeDrag);
  view.addEventListener("pointermove", onPointerMove, { capture: true, passive: false });
  view.addEventListener("pointerup", onPointerUp, true);
  view.addEventListener("pointercancel", onPointerCancel, true);
  view.addEventListener("keydown", onKeyDown, true);
  view.addEventListener("blur", onBlur);
  return () => {
    finish(null, true);
    view.clearTimeout(suppressionTimer);
    for (const [element, timer] of departingGhosts) {
      view.clearTimeout(timer);
      element.remove();
    }
    departingGhosts.clear();
    for (const animation of feedback) animation.cancel();
    feedback.clear();
    sheet.removeEventListener("pointerdown", onPointerDown);
    sheet.removeEventListener("click", onClick, true);
    sheet.removeEventListener("dragstart", onNativeDrag);
    view.removeEventListener("pointermove", onPointerMove, true);
    view.removeEventListener("pointerup", onPointerUp, true);
    view.removeEventListener("pointercancel", onPointerCancel, true);
    view.removeEventListener("keydown", onKeyDown, true);
    view.removeEventListener("blur", onBlur);
  };
}
