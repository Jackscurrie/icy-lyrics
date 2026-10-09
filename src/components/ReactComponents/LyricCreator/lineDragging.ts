const LINE_SELECTOR = "article[data-creator-line-id]";
const HANDLE_SELECTOR = "[data-creator-line-handle]";
const DRAG_THRESHOLD = 6;

interface LineDraggingCallbacks {
  onMove: (sourceId: string, targetId: string, placement: "before" | "after") => void;
  onGrab?: () => void;
}

/** Handle-only pointer reordering; ordinary word HTML dragging is left alone. */
export function attachCreatorLineDragging(
  editor: HTMLElement,
  callbacks: LineDraggingCallbacks
): () => void {
  const doc = editor.ownerDocument;
  const view = doc.defaultView;
  if (!view) return () => undefined;
  const scrollHost = editor.closest<HTMLElement>(".il-creator-edit") ?? editor;
  let drag: {
    pointerId: number;
    handle: HTMLElement;
    source: HTMLElement;
    sourceId: string;
    startX: number;
    startY: number;
    x: number;
    y: number;
    active: boolean;
  } | null = null;
  let drop: { element: HTMLElement; placement: "before" | "after" } | null = null;
  let frame = 0;
  let suppressClick = false;
  let suppressionTimer = 0;

  const resolveTarget = (x: number, y: number) => {
    const visible = scrollHost.getBoundingClientRect();
    const editorBounds = editor.getBoundingClientRect();
    if (x < Math.max(visible.left, editorBounds.left) || x > Math.min(visible.right, editorBounds.right) ||
      y < Math.max(visible.top, editorBounds.top) || y > Math.min(visible.bottom, editorBounds.bottom)) return null;
    let element = doc.elementFromPoint(x, y)?.closest<HTMLElement>(LINE_SELECTOR) ?? null;
    if (!element || !editor.contains(element)) {
      // The small visual gap between cards belongs to its nearest line.
      let distance = Infinity;
      element = null;
      for (const line of editor.querySelectorAll<HTMLElement>(LINE_SELECTOR)) {
        const bounds = line.getBoundingClientRect();
        const nextDistance = y < bounds.top ? bounds.top - y : y > bounds.bottom ? y - bounds.bottom : 0;
        if (nextDistance < distance) { element = line; distance = nextDistance; }
      }
    }
    if (!element || element === drag?.source) return null;
    const bounds = element.getBoundingClientRect();
    return { element, placement: y < bounds.top + bounds.height / 2 ? "before" as const : "after" as const };
  };
  const setDrop = (next: typeof drop) => {
    if (drop?.element === next?.element && drop?.placement === next?.placement) return;
    drop?.element.classList.remove("is-line-drop-before", "is-line-drop-after");
    drop = next;
    drop?.element.classList.add(`is-line-drop-${drop.placement}`);
  };
  const tick = () => {
    frame = 0;
    if (!drag?.active) return;
    const bounds = scrollHost.getBoundingClientRect();
    let keepScrolling = false;
    if (drag.x >= bounds.left && drag.x <= bounds.right && drag.y >= bounds.top && drag.y <= bounds.bottom) {
      const edge = Math.min(48, bounds.height / 4);
      const velocity = drag.y < bounds.top + edge ? -12 * (1 - (drag.y - bounds.top) / edge)
        : drag.y > bounds.bottom - edge ? 12 * (1 - (bounds.bottom - drag.y) / edge) : 0;
      if (velocity) {
        const previousScroll = scrollHost.scrollTop;
        scrollHost.scrollTop += velocity;
        keepScrolling = previousScroll !== scrollHost.scrollTop;
      }
    }
    setDrop(resolveTarget(drag.x, drag.y));
    if (keepScrolling) frame = view.requestAnimationFrame(tick);
  };
  const activate = () => {
    if (!drag || drag.active) return;
    drag.active = true;
    callbacks.onGrab?.();
    drag.source.classList.add("is-line-dragging");
    tick();
  };
  const finish = (cancelled = true) => {
    if (!drag) return;
    const completed = drag;
    const destination = !cancelled && completed.active ? resolveTarget(completed.x, completed.y) : null;
    drag = null;
    if (frame) view.cancelAnimationFrame(frame);
    frame = 0;
    completed.source.classList.remove("is-line-dragging");
    setDrop(null);
    try {
      if (completed.handle.hasPointerCapture?.(completed.pointerId)) completed.handle.releasePointerCapture(completed.pointerId);
    } catch { /* The creator may unmount while this pointer is captured. */ }
    if (completed.active) {
      suppressClick = true;
      view.clearTimeout(suppressionTimer);
      suppressionTimer = view.setTimeout(() => { suppressClick = false; }, 500);
    }
    const targetId = destination?.element.getAttribute("data-creator-line-id");
    if (targetId) callbacks.onMove(completed.sourceId, targetId, destination.placement);
  };
  const onPointerDown = (event: PointerEvent) => {
    if (event.button !== 0 || event.isPrimary === false) return;
    const handle = (event.target as Element | null)?.closest?.<HTMLElement>(HANDLE_SELECTOR);
    if (!handle || !editor.contains(handle)) return;
    const source = handle.closest<HTMLElement>(LINE_SELECTOR);
    const sourceId = source?.getAttribute("data-creator-line-id");
    if (!source || !sourceId) return;
    finish();
    suppressClick = false;
    drag = { pointerId: event.pointerId, handle, source, sourceId, startX: event.clientX, startY: event.clientY,
      x: event.clientX, y: event.clientY, active: false };
    try { handle.setPointerCapture?.(event.pointerId); } catch { /* Synthetic pointer events. */ }
  };
  const onPointerMove = (event: PointerEvent) => {
    if (!drag || drag.pointerId !== event.pointerId) return;
    drag.x = event.clientX;
    drag.y = event.clientY;
    if (Math.hypot(drag.x - drag.startX, drag.y - drag.startY) >= DRAG_THRESHOLD) activate();
    if (drag.active) {
      event.preventDefault();
      if (!frame) frame = view.requestAnimationFrame(tick);
    }
  };
  const onPointerUp = (event: PointerEvent) => {
    if (!drag || drag.pointerId !== event.pointerId) return;
    drag.x = event.clientX;
    drag.y = event.clientY;
    // A very quick down/move/up may arrive before the next animation frame.
    if (Math.hypot(drag.x - drag.startX, drag.y - drag.startY) >= DRAG_THRESHOLD) activate();
    finish(false);
  };
  const onPointerCancel = (event: PointerEvent) => { if (drag?.pointerId === event.pointerId) finish(); };
  const onKeyDown = (event: KeyboardEvent) => {
    if (!drag || event.key !== "Escape") return;
    event.preventDefault();
    event.stopImmediatePropagation();
    finish();
  };
  const onClick = (event: MouseEvent) => {
    if (!suppressClick || event.detail === 0) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    suppressClick = false;
  };
  const onDragStart = (event: DragEvent) => {
    if (drag) event.preventDefault();
  };
  const onBlur = () => finish();
  editor.addEventListener("pointerdown", onPointerDown);
  editor.addEventListener("click", onClick, true);
  editor.addEventListener("dragstart", onDragStart);
  view.addEventListener("pointermove", onPointerMove, { capture: true, passive: false });
  view.addEventListener("pointerup", onPointerUp, true);
  view.addEventListener("pointercancel", onPointerCancel, true);
  view.addEventListener("keydown", onKeyDown, true);
  view.addEventListener("blur", onBlur);
  return () => {
    finish();
    view.clearTimeout(suppressionTimer);
    editor.removeEventListener("pointerdown", onPointerDown);
    editor.removeEventListener("click", onClick, true);
    editor.removeEventListener("dragstart", onDragStart);
    view.removeEventListener("pointermove", onPointerMove, true);
    view.removeEventListener("pointerup", onPointerUp, true);
    view.removeEventListener("pointercancel", onPointerCancel, true);
    view.removeEventListener("keydown", onKeyDown, true);
    view.removeEventListener("blur", onBlur);
  };
}
