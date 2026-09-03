let activeOwner: symbol | null = null;

type PreviewTurnWaiter = () => Promise<void>;

export interface CreatorPreviewPageResolution {
  page: HTMLElement;
  createdPage: boolean;
}

interface ResolveCreatorPreviewPageOptions {
  getPage: () => HTMLElement | null;
  isPageOpen: () => boolean;
  openPage: () => Promise<void>;
  waitForTurn?: PreviewTurnWaiter;
  maxAttempts?: number;
}

export type CreatorPreviewSchedule = (callback: () => void) => number;
export type CreatorPreviewCancelSchedule = (handle: number) => void;

interface CreatorPreviewRenderQueueOptions<T> {
  render: (value: T) => boolean | Promise<boolean>;
  schedule: CreatorPreviewSchedule;
  cancelSchedule: CreatorPreviewCancelSchedule;
  maxAttempts?: number;
}

export function isCreatorPreviewActive(): boolean {
  return activeOwner !== null;
}

export function acquireCreatorPreviewOwnership(): () => void {
  const owner = Symbol("IcyLyricCreatorPreview");
  activeOwner = owner;
  return () => {
    if (activeOwner === owner) activeOwner = null;
  };
}

/**
 * PageView is a singleton and its async hand-offs can briefly report an open
 * page before the exported PageContainer has been installed (or while an old
 * owner is finishing teardown). Resolve across that transient state instead
 * of accepting a null container and leaving Preview permanently blank.
 */
export async function resolveCreatorPreviewPage({
  getPage,
  isPageOpen,
  openPage,
  waitForTurn = () =>
    new Promise<void>((resolve) => {
      requestAnimationFrame(() => resolve());
    }),
  maxAttempts = 90,
}: ResolveCreatorPreviewPageOptions): Promise<CreatorPreviewPageResolution | null> {
  let createdPage = false;

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const existingPage = getPage();
    if (existingPage) return { page: existingPage, createdPage };

    if (!isPageOpen()) {
      createdPage = true;
      await openPage();
      const openedPage = getPage();
      if (openedPage) return { page: openedPage, createdPage };
    }

    await waitForTurn();
  }

  return null;
}

/**
 * Latest-wins render queue for the borrowed lyrics renderer. A render can be
 * skipped while PageView/SimpleBar is settling; bounded retries make that
 * temporary failure recover without letting an older project overwrite a
 * newer edit.
 */
export class CreatorPreviewRenderQueue<T> {
  private readonly render: (value: T) => boolean | Promise<boolean>;
  private readonly scheduleCallback: CreatorPreviewSchedule;
  private readonly cancelScheduledCallback: CreatorPreviewCancelSchedule;
  private readonly maxAttempts: number;
  private revision = 0;
  private scheduledHandle: number | null = null;
  private disposed = false;
  private latestValue: T | null = null;

  constructor({
    render,
    schedule,
    cancelSchedule,
    maxAttempts = 8,
  }: CreatorPreviewRenderQueueOptions<T>) {
    this.render = render;
    this.scheduleCallback = schedule;
    this.cancelScheduledCallback = cancelSchedule;
    this.maxAttempts = Math.max(1, maxAttempts);
  }

  update(value: T): void {
    if (this.disposed) return;
    this.latestValue = value;
    this.revision += 1;
    this.cancelPendingSchedule();
    this.schedule(this.revision, 0);
  }

  dispose(): void {
    this.disposed = true;
    this.revision += 1;
    this.latestValue = null;
    this.cancelPendingSchedule();
  }

  private cancelPendingSchedule(): void {
    if (this.scheduledHandle === null) return;
    this.cancelScheduledCallback(this.scheduledHandle);
    this.scheduledHandle = null;
  }

  private schedule(revision: number, attempt: number): void {
    this.scheduledHandle = this.scheduleCallback(() => {
      this.scheduledHandle = null;
      void this.run(revision, attempt);
    });
  }

  private async run(revision: number, attempt: number): Promise<void> {
    if (this.disposed || revision !== this.revision || this.latestValue === null) return;

    const value = this.latestValue;
    let rendered = false;
    try {
      rendered = await this.render(value);
    } catch {
      rendered = false;
    }

    if (this.disposed || revision !== this.revision) return;
    if (!rendered && attempt + 1 < this.maxAttempts) {
      this.schedule(revision, attempt + 1);
    }
  }
}

export function creatorPreviewHasRenderedLyrics(
  page: HTMLElement,
  expectsVisibleText: boolean
): boolean {
  const renderedContainer = page.querySelector<HTMLElement>(
    ".LyricsContainer .LyricsContent .IcyLyricsScrollContainer"
  );
  if (!renderedContainer) return false;
  if (!expectsVisibleText) return true;
  return renderedContainer.querySelector(".line") !== null;
}

/**
 * A borrowed page can still carry the no-lyrics/fullscreen visibility classes
 * from the song that was on screen before Creator opened. ApplyLyrics builds
 * the new DOM but does not clear those outer classes itself, which made a
 * perfectly valid Creator render remain invisible for that previous state.
 */
export function prepareCreatorPreviewSurface(page: HTMLElement): void {
  page.querySelector<HTMLElement>(".ContentBox")?.classList.remove("LyricsHidden");
  page.querySelector<HTMLElement>(".ContentBox .LyricsContainer")?.classList.remove("Hidden");
  page
    .querySelector<HTMLElement>(".LyricsContainer .LyricsContent")
    ?.classList.remove("HiddenTransitioned", "offline");
}

/**
 * The shared PageView normally occupies Spotify's whole viewport. Preview
 * borrows it, so enforce a hard containing block and make the renderer a
 * visual-only surface. This prevents it from covering Creator tabs/actions.
 */
export function constrainCreatorPreviewPage(page: HTMLElement, host: HTMLElement): () => void {
  const pageStyle = page.style.cssText;
  const hostStyle = host.style.cssText;
  const ariaHidden = page.getAttribute("aria-hidden");

  host.style.setProperty("position", "relative", "important");
  host.style.setProperty("overflow", "hidden", "important");
  host.style.setProperty("contain", "strict", "important");
  host.style.setProperty("isolation", "isolate", "important");

  page.style.setProperty("position", "absolute", "important");
  page.style.setProperty("inset", "0", "important");
  page.style.setProperty("width", "100%", "important");
  page.style.setProperty("height", "100%", "important");
  page.style.setProperty("min-width", "0", "important");
  page.style.setProperty("min-height", "0", "important");
  page.style.setProperty("overflow", "hidden", "important");
  page.style.setProperty("pointer-events", "none", "important");
  page.style.setProperty("z-index", "0", "important");
  page.style.setProperty("contain", "strict", "important");
  page.style.setProperty("transform", "translateZ(0)", "important");
  page.setAttribute("aria-hidden", "true");

  return () => {
    page.style.cssText = pageStyle;
    host.style.cssText = hostStyle;
    if (ariaHidden === null) page.removeAttribute("aria-hidden");
    else page.setAttribute("aria-hidden", ariaHidden);
  };
}
