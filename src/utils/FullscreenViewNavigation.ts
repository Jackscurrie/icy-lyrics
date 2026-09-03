import {
  FULLSCREEN_VIEW_LABELS,
  getFullscreenViewNeighbours,
  type FullscreenView,
} from "./FullscreenPresentation.ts";

export const FULLSCREEN_VIEW_TOOLBAR_HTML = `
  <div class="FullscreenViewToolbar" role="toolbar" aria-label="Fullscreen view navigation">
    <button class="FullscreenViewEdge FullscreenViewEdge--previous" type="button">
      <span aria-hidden="true">&#x2039;</span>
    </button>
    <span class="FullscreenViewStatus" aria-live="polite"></span>
    <button class="FullscreenViewEdge FullscreenViewEdge--next" type="button">
      <span aria-hidden="true">&#x203A;</span>
    </button>
  </div>
`;

export interface FullscreenViewNavigationOptions {
  getCurrentView: () => FullscreenView;
  isActive: () => boolean;
  onStep: (direction: -1 | 1) => void;
}

export interface FullscreenViewNavigationHandle {
  toolbar: HTMLElement;
  update: (view?: FullscreenView) => void;
}

/**
 * The toolbar belongs to the page, not to an individual fullscreen host or
 * NowBar session. Reuse that stable node and only recreate it as a recovery
 * path for an already-open page created by an older build.
 */
export function ensureFullscreenViewToolbar(page: HTMLElement): HTMLElement {
  let toolbar = page.querySelector<HTMLElement>(".FullscreenViewToolbar");
  if (!toolbar) {
    page.insertAdjacentHTML("beforeend", FULLSCREEN_VIEW_TOOLBAR_HTML);
    toolbar = page.querySelector<HTMLElement>(".FullscreenViewToolbar");
  }

  if (!toolbar) {
    throw new Error("Failed to create fullscreen view navigation");
  }

  return toolbar;
}

export function syncFullscreenViewNavigation(toolbar: HTMLElement, view: FullscreenView): void {
  const { previous, next } = getFullscreenViewNeighbours(view);
  const previousButton = toolbar.querySelector<HTMLButtonElement>(".FullscreenViewEdge--previous");
  const nextButton = toolbar.querySelector<HTMLButtonElement>(".FullscreenViewEdge--next");
  const status = toolbar.querySelector<HTMLElement>(".FullscreenViewStatus");

  if (previousButton) {
    previousButton.disabled = previous === null;
    previousButton.tabIndex = previous === null ? -1 : 0;
    previousButton.setAttribute(
      "aria-label",
      previous ? `Show ${FULLSCREEN_VIEW_LABELS[previous]}` : "No previous fullscreen view"
    );
  }

  if (nextButton) {
    nextButton.disabled = next === null;
    nextButton.tabIndex = next === null ? -1 : 0;
    nextButton.setAttribute(
      "aria-label",
      next ? `Show ${FULLSCREEN_VIEW_LABELS[next]}` : "No next fullscreen view"
    );
  }

  if (status) {
    status.textContent = FULLSCREEN_VIEW_LABELS[view];
  }
}

const isEditableKeyboardTarget = (target: EventTarget | null): boolean => {
  const element = target as Element | null;
  return !!element?.matches?.(
    "input, textarea, select, [contenteditable='true'], [contenteditable='']"
  );
};

export function bindFullscreenViewNavigation(
  page: HTMLElement,
  keyboardTarget: Document | Window,
  signal: AbortSignal,
  options: FullscreenViewNavigationOptions
): FullscreenViewNavigationHandle {
  const toolbar = ensureFullscreenViewToolbar(page);

  const step = (direction: -1 | 1) => {
    if (!options.isActive()) return;
    const neighbours = getFullscreenViewNeighbours(options.getCurrentView());
    if (direction === -1 ? neighbours.previous === null : neighbours.next === null) {
      return;
    }
    options.onStep(direction);
  };

  toolbar
    .querySelector(".FullscreenViewEdge--previous")
    ?.addEventListener("click", () => step(-1), { signal });
  toolbar
    .querySelector(".FullscreenViewEdge--next")
    ?.addEventListener("click", () => step(1), { signal });

  keyboardTarget.addEventListener(
    "keydown",
    (event: KeyboardEvent) => {
      if (
        !options.isActive() ||
        event.defaultPrevented ||
        event.altKey ||
        event.ctrlKey ||
        event.metaKey ||
        isEditableKeyboardTarget(event.target)
      ) {
        return;
      }

      if (event.key === "ArrowLeft") {
        event.preventDefault();
        step(-1);
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        step(1);
      }
    },
    { capture: true, signal }
  );

  const handle: FullscreenViewNavigationHandle = {
    toolbar,
    update: (view = options.getCurrentView()) => {
      syncFullscreenViewNavigation(toolbar, view);
    },
  };
  handle.update();
  return handle;
}
