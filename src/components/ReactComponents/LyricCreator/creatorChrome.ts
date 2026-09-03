export type CreatorFullscreenDocument = Pick<Document, "documentElement" | "fullscreenElement">;

export interface CreatorNativeDialogEnvironment {
  addFocusListener: (listener: EventListener) => void;
  removeFocusListener: (listener: EventListener) => void;
  defer: (callback: () => void) => void;
}

const nativeDialogOwners = new Set<symbol>();
const nativeDialogListeners = new Set<(active: boolean) => void>();

function creatorNativeDialogEnvironment(): CreatorNativeDialogEnvironment {
  return {
    addFocusListener: (listener) => window.addEventListener("focus", listener),
    removeFocusListener: (listener) => window.removeEventListener("focus", listener),
    // Let the file input's change/cancel event run before attempting to regain
    // fullscreen after Chromium closes its native dialog.
    defer: (callback) => window.setTimeout(callback, 0),
  };
}

function emitCreatorNativeDialogState(): void {
  const active = nativeDialogOwners.size > 0;
  for (const listener of nativeDialogListeners) listener(active);
}

export function isCreatorNativeDialogActive(): boolean {
  return nativeDialogOwners.size > 0;
}

export function listenForCreatorNativeDialog(listener: (active: boolean) => void): () => void {
  nativeDialogListeners.add(listener);
  listener(isCreatorNativeDialogActive());
  return () => nativeDialogListeners.delete(listener);
}

/**
 * Keep Creator mounted while Chromium temporarily leaves fullscreen for a
 * native file dialog. The returned release is idempotent; focus is a fallback
 * for platforms that do not dispatch the file input `cancel` event.
 */
export function acquireCreatorNativeDialog(
  environment: CreatorNativeDialogEnvironment = creatorNativeDialogEnvironment()
): () => void {
  const owner = Symbol("IcyLyricCreatorNativeDialog");
  nativeDialogOwners.add(owner);
  emitCreatorNativeDialogState();

  let released = false;
  const release = () => {
    if (released) return;
    released = true;
    environment.removeFocusListener(onFocus);
    nativeDialogOwners.delete(owner);
    emitCreatorNativeDialogState();
  };
  const onFocus: EventListener = () => environment.defer(release);
  environment.addFocusListener(onFocus);
  return release;
}

/**
 * Enter Chromium document fullscreen using the same document root as Icy's
 * regular fullscreen view. Calling this from the launching pointer gesture
 * also hides Spotify's native desktop titlebar.
 */
export async function requestCreatorDocumentFullscreen(
  ownerDocument: CreatorFullscreenDocument = document
): Promise<boolean> {
  if (ownerDocument.fullscreenElement === ownerDocument.documentElement) return true;
  const requestFullscreen = ownerDocument.documentElement.requestFullscreen;
  if (typeof requestFullscreen !== "function") return false;

  try {
    await requestFullscreen.call(ownerDocument.documentElement, { navigationUI: "hide" });
    return ownerDocument.fullscreenElement === ownerDocument.documentElement;
  } catch {
    return false;
  }
}

/**
 * Creator is mounted beside Spotify's normal `#main` shell. Suppress that
 * shell while Creator owns the viewport so its desktop drag/titlebar layer
 * cannot cover Creator controls. The exact previous state is restored.
 */
export function suppressSpotifyShellForCreator(shell: HTMLElement | null): () => void {
  if (!shell) return () => undefined;

  const display = shell.style.getPropertyValue("display");
  const displayPriority = shell.style.getPropertyPriority("display");
  const ariaHidden = shell.getAttribute("aria-hidden");
  const hadInert = shell.hasAttribute("inert");
  const wasInert = shell.inert;
  const suppressionMarker = shell.getAttribute("data-icy-creator-suppressed");

  shell.style.setProperty("display", "none", "important");
  shell.inert = true;
  shell.setAttribute("aria-hidden", "true");
  shell.setAttribute("data-icy-creator-suppressed", "true");

  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (display) shell.style.setProperty("display", display, displayPriority);
    else shell.style.removeProperty("display");
    shell.inert = wasInert;
    if (!hadInert) shell.removeAttribute("inert");
    if (ariaHidden === null) shell.removeAttribute("aria-hidden");
    else shell.setAttribute("aria-hidden", ariaHidden);
    if (suppressionMarker === null) shell.removeAttribute("data-icy-creator-suppressed");
    else shell.setAttribute("data-icy-creator-suppressed", suppressionMarker);
  };
}

/** Keep Spotify's document-level pointer gestures outside the Creator app. */
export function installCreatorPointerIsolation(host: HTMLElement): () => void {
  const stopAtCreator = (event: Event) => event.stopPropagation();
  const eventTypes = [
    "pointerdown",
    "pointerup",
    "mousedown",
    "mouseup",
    "click",
    "dblclick",
    "contextmenu",
  ] as const;
  for (const eventType of eventTypes) host.addEventListener(eventType, stopAtCreator);
  return () => {
    for (const eventType of eventTypes) host.removeEventListener(eventType, stopAtCreator);
  };
}
