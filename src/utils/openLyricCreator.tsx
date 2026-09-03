import React from "react";
import ReactDOM from "react-dom/client";
import LyricCreator from "../components/ReactComponents/LyricCreator/index.tsx";
import Session from "../components/Global/Session.ts";
import { installCreatorKeyboardIsolation } from "../components/ReactComponents/LyricCreator/interaction.ts";
import {
  installCreatorPointerIsolation,
  isCreatorNativeDialogActive,
  listenForCreatorNativeDialog,
  requestCreatorDocumentFullscreen,
  suppressSpotifyShellForCreator,
} from "../components/ReactComponents/LyricCreator/creatorChrome.ts";

let creatorHost: HTMLDivElement | null = null;
let creatorRoot: ReactDOM.Root | null = null;
let releaseKeyboardIsolation: (() => void) | null = null;
let releasePointerIsolation: (() => void) | null = null;
let releaseSpotifyShell: (() => void) | null = null;
let closePromise: Promise<void> | null = null;
let creatorOwnsDocumentFullscreen = false;
let releaseFullscreenWatch: (() => void) | null = null;
let releaseNativeDialogWatch: (() => void) | null = null;
type PreparedFullscreenRequest = {
  request: Promise<boolean>;
  requestedNewFullscreen: boolean;
};

let preparedFullscreenRequest: PreparedFullscreenRequest | null = null;
let activeFullscreenRequest: Promise<boolean> | null = null;

const CREATOR_EXIT_MS = 620;

function leaveCreatorRoute(): void {
  const history = Spicetify.Platform.History;
  if (history.location?.pathname !== "/IcyLyrics/creator") return;

  // Replace the Creator entry instead of pushing another Icy page onto the
  // stack. Spotify's fullscreen close path navigates back, so leaving the
  // Creator route behind would reopen the tool after fullscreen exits.
  if (typeof history.replace === "function") {
    history.replace({ pathname: "/IcyLyrics" });
    return;
  }

  Session.GoBack();
}

function waitForCreatorExit(host: HTMLElement): Promise<void> {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    return new Promise((resolve) => window.setTimeout(resolve, 120));
  }

  return new Promise((resolve) => {
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      host.removeEventListener("transitionend", onTransitionEnd);
      window.clearTimeout(fallback);
      resolve();
    };
    const onTransitionEnd = (event: TransitionEvent) => {
      if (
        event.target instanceof HTMLElement &&
        event.target.classList.contains("il-creator-root") &&
        event.propertyName === "transform"
      ) {
        finish();
      }
    };
    const fallback = window.setTimeout(finish, CREATOR_EXIT_MS + 120);
    host.addEventListener("transitionend", onTransitionEnd);
  });
}

function markCreatorFullscreen(owner: HTMLDivElement, acquired: boolean): void {
  if (creatorHost !== owner || !owner.isConnected) return;
  const active = acquired && document.fullscreenElement === document.documentElement;
  creatorOwnsDocumentFullscreen = active;
  owner.dataset.creatorFullscreen = active ? "active" : "pending";
}

function acquireCreatorDocumentFullscreen(owner: HTMLDivElement): Promise<boolean> {
  if (document.fullscreenElement === document.documentElement) {
    markCreatorFullscreen(owner, true);
    return Promise.resolve(true);
  }
  if (activeFullscreenRequest) return activeFullscreenRequest;

  owner.dataset.creatorFullscreen = "pending";
  const request = requestCreatorDocumentFullscreen();
  activeFullscreenRequest = request;
  void request
    .then(async (acquired) => {
      if (creatorHost === owner && owner.isConnected) {
        markCreatorFullscreen(owner, acquired);
        return;
      }

      // The request resolved after Creator was already closed. Never leave
      // Spotify fullscreen as a side-effect of a stale open request.
      if (acquired && document.fullscreenElement === document.documentElement) {
        await document.exitFullscreen();
      }
    })
    .finally(() => {
      if (activeFullscreenRequest === request) activeFullscreenRequest = null;
    });
  return request;
}

/**
 * Start fullscreen while the Lyrics Manager click still carries browser user
 * activation. The route hand-off can await PageView work, which is too late
 * for Chromium builds that strictly enforce requestFullscreen activation.
 */
export function PrepareLyricCreatorFullscreen(): void {
  if (preparedFullscreenRequest) return;
  preparedFullscreenRequest = {
    requestedNewFullscreen: document.fullscreenElement !== document.documentElement,
    request: requestCreatorDocumentFullscreen(),
  };
}

/**
 * Release an unused click-preflight request when route loading is superseded.
 * Existing Icy fullscreen is not ours to exit; only a document fullscreen that
 * this preflight newly requested is rolled back.
 */
export async function CancelPreparedLyricCreatorFullscreen(): Promise<void> {
  const prepared = preparedFullscreenRequest;
  preparedFullscreenRequest = null;
  if (!prepared?.requestedNewFullscreen) return;

  let acquired = false;
  try {
    acquired = await prepared.request;
  } catch {
    return;
  }
  if (acquired && document.fullscreenElement === document.documentElement) {
    try {
      await document.exitFullscreen();
    } catch (error) {
      console.warn("Icy Lyrics could not cancel Creator fullscreen preflight.", error);
    }
  }
}

async function restoreCreatorDocumentFullscreen(): Promise<void> {
  const shouldExit =
    creatorOwnsDocumentFullscreen && document.fullscreenElement === document.documentElement;
  creatorOwnsDocumentFullscreen = false;
  if (!shouldExit) return;

  try {
    await document.exitFullscreen();
  } catch (error) {
    console.warn("Icy Lyrics could not restore Spotify's window chrome.", error);
  }
}

export function IsLyricCreatorOpen(): boolean {
  return creatorHost?.isConnected === true;
}

export function OpenLyricCreator(): void {
  if (IsLyricCreatorOpen()) return;
  closePromise = null;
  creatorOwnsDocumentFullscreen = false;
  releaseSpotifyShell?.();
  releaseSpotifyShell = suppressSpotifyShellForCreator(
    document.querySelector<HTMLElement>("#main")
  );
  creatorHost = document.createElement("div");
  creatorHost.id = "IcyLyricCreatorHost";
  creatorHost.className = "IcyLyricCreatorHost--entering";
  creatorHost.dataset.creatorFullscreen = "pending";
  document.body.appendChild(creatorHost);
  document.body.classList.add("IcyLyricCreatorOpen");
  releaseKeyboardIsolation = installCreatorKeyboardIsolation(creatorHost);
  releasePointerIsolation = installCreatorPointerIsolation(creatorHost);
  creatorRoot = ReactDOM.createRoot(creatorHost);
  creatorRoot.render(
    <LyricCreator
      onClose={() => {
        // Commit the route hand-off as soon as the exit gesture starts while
        // the shell keeps sliding away on the shared close promise.
        const closing = CloseLyricCreator();
        leaveCreatorRoute();
        void closing;
      }}
    />
  );

  const owner = creatorHost;
  let nativeDialogRecoveryActive = false;
  let nativeDialogRecoveryTimer = 0;
  const onFullscreenChange = () => {
    if (document.fullscreenElement === document.documentElement) {
      markCreatorFullscreen(owner, true);
      document.documentElement.focus();
      return;
    }

    const lostCreatorFullscreen = creatorOwnsDocumentFullscreen;
    creatorOwnsDocumentFullscreen = false;
    if (creatorHost === owner) owner.dataset.creatorFullscreen = "pending";
    if (isCreatorNativeDialogActive() || nativeDialogRecoveryActive) {
      if (!isCreatorNativeDialogActive() && !closePromise) {
        void acquireCreatorDocumentFullscreen(owner);
      }
      return;
    }
    // Escape is the platform-level way to leave document fullscreen. Treat it
    // as leaving Creator too, so native chrome is never exposed over an active
    // Creator workspace.
    if (lostCreatorFullscreen && !closePromise && creatorHost === owner) {
      const closing = CloseLyricCreator();
      leaveCreatorRoute();
      void closing;
    }
  };
  const onPointerDown = () => {
    // Direct/reloaded Creator routes have no launch activation. The first
    // in-app pointer gesture retries fullscreen without consuming the click.
    if (
      !closePromise &&
      creatorHost === owner &&
      document.fullscreenElement !== document.documentElement
    ) {
      void acquireCreatorDocumentFullscreen(owner);
    }
  };
  document.addEventListener("fullscreenchange", onFullscreenChange);
  owner.addEventListener("pointerdown", onPointerDown, { capture: true });
  let nativeDialogWasActive = false;
  releaseNativeDialogWatch = listenForCreatorNativeDialog((active) => {
    if (creatorHost !== owner) return;
    owner.dataset.creatorNativeDialog = active ? "active" : "closed";
    if (active) {
      nativeDialogWasActive = true;
      nativeDialogRecoveryActive = true;
      window.clearTimeout(nativeDialogRecoveryTimer);
      return;
    }
    if (!nativeDialogWasActive) return;
    nativeDialogWasActive = false;
    window.clearTimeout(nativeDialogRecoveryTimer);
    nativeDialogRecoveryTimer = window.setTimeout(() => {
      nativeDialogRecoveryActive = false;
    }, 1_500);
    if (!closePromise && document.fullscreenElement !== document.documentElement) {
      void acquireCreatorDocumentFullscreen(owner);
    }
  });
  releaseFullscreenWatch = () => {
    document.removeEventListener("fullscreenchange", onFullscreenChange);
    owner.removeEventListener("pointerdown", onPointerDown, { capture: true });
    window.clearTimeout(nativeDialogRecoveryTimer);
  };
  requestAnimationFrame(() => {
    if (creatorHost !== owner) return;
    owner.classList.remove("IcyLyricCreatorHost--entering");
    owner.classList.add("IcyLyricCreatorHost--active");
  });
  const prepared = preparedFullscreenRequest;
  preparedFullscreenRequest = null;
  if (prepared) {
    void prepared.request.then(async (acquired) => {
      if (creatorHost === owner && owner.isConnected) {
        if (acquired && document.fullscreenElement === document.documentElement) {
          markCreatorFullscreen(owner, true);
        } else {
          // A route teardown may have exited the preflight fullscreen between
          // the launch gesture and mounting Creator. Retry, then leave the
          // pointerdown capture fallback armed for strict Chromium builds.
          await acquireCreatorDocumentFullscreen(owner);
        }
      } else if (acquired && document.fullscreenElement === document.documentElement) {
        await document.exitFullscreen();
      }
    });
  } else {
    void acquireCreatorDocumentFullscreen(owner);
  }
}

export function CloseLyricCreator(afterClose?: () => void): Promise<void> {
  if (closePromise) {
    if (afterClose) void closePromise.then(afterClose);
    return closePromise;
  }

  const host = creatorHost;
  if (!host) {
    afterClose?.();
    return Promise.resolve();
  }

  host.classList.remove("IcyLyricCreatorHost--entering", "IcyLyricCreatorHost--active");
  host.classList.add("IcyLyricCreatorHost--exiting");

  closePromise = (async () => {
    await waitForCreatorExit(host);
    releaseFullscreenWatch?.();
    releaseFullscreenWatch = null;
    releaseNativeDialogWatch?.();
    releaseNativeDialogWatch = null;
    await restoreCreatorDocumentFullscreen();

    if (creatorHost === host) {
      releasePointerIsolation?.();
      releasePointerIsolation = null;
      releaseKeyboardIsolation?.();
      releaseKeyboardIsolation = null;
      creatorRoot?.unmount();
      creatorRoot = null;
      host.remove();
      creatorHost = null;
      document.body.classList.remove("IcyLyricCreatorOpen");
      releaseSpotifyShell?.();
      releaseSpotifyShell = null;
    }

    afterClose?.();
  })().finally(() => {
    closePromise = null;
  });

  return closePromise;
}
