// CSS Imports
import "./css/tokens.css";
import "./css/primitives.css";
import "./css/default.css";
import "./css/default.scss";
import "./css/Simplebar.css";
import "./css/ContentBox.css";
import "./css/DynamicBG/icy-dynamic-bg.css";
import "./css/Lyrics/main.css";
import "./css/Lyrics/Mixed.css";
import "./css/Loaders/LoaderContainer.css";
import "./css/font-pack/font-pack.css";

import ApplyDynamicBackground, {
  GetStaticBackground,
  KawarpMap,
} from "./components/DynamicBG/dynamicBackground.ts";
import {
  $currentLyricsData,
  $showNpvDynamicBg,
  $popupLyricsAllowed,
  $staticBackgroundMode,
  $developerMode,
} from "./utils/stores.ts";
import Global from "./components/Global/Global.ts";
import Platform from "./components/Global/Platform.ts";
import Session from "./components/Global/Session.ts";
import { SpotifyPlayer } from "./components/Global/SpotifyPlayer.ts";
import PageView, { GetPageRoot, PageContainer } from "./components/Pages/PageView.ts";
import LoadFonts, { ApplyFontPixel } from "./components/Styling/Fonts.ts";
import { ICY_LYRICS_BRAND_GLYPH, Icons } from "./components/Styling/Icons.ts";
import Fullscreen, {
  EnterIcyLyricsFullscreen,
  ExitFullscreenElement,
} from "./components/Utils/Fullscreen.ts";
import { UpdateNowBar } from "./components/Utils/NowBar.ts";
import { IsPlaying } from "./utils/Addons.ts";
import { requestPositionSync } from "./utils/Gets/GetProgress.ts";
import { IntervalManager } from "./utils/IntervalManager.ts";
import fetchLyrics from "./utils/Lyrics/fetchLyrics.ts";
import ApplyLyrics from "./utils/Lyrics/Global/Applyer.ts";
import { ScrollingIntervalTime } from "./utils/Lyrics/lyrics.ts";
import { ScrollToActiveLine } from "./utils/Scrolling/ScrollToActiveLine.ts";
import { ScrollSimplebar } from "./utils/Scrolling/Simplebar/ScrollSimplebar.ts";
import { $lastFetchedUri } from "./utils/uiState.ts";
import { needsMigration, showMigrationModal } from "./utils/migration/DataMigration.tsx";
import { LocalLyricsManager } from "./utils/Lyrics/manager/index.ts";
import "./css/settings-panel.css";
import "./components/ReactComponents/LyricsManager/styles.css";
import "./components/ReactComponents/LyricCreator/styles.css";
import "./css/polyfills/generic-modal-polyfill.css";
import "./css/polyfills/sonner-polyfill.css";
import "./css/NPVLyrics.css";
import { IsPIP, OpenPopupLyrics, ClosePopupLyrics } from "./components/Utils/PopupLyrics.ts";
import { GetNPVCardElement, initNPVLyrics } from "./components/Utils/NPVLyrics.ts";
import ReactDOM from "react-dom/client";
import { runThemeMatcher } from "./utils/themeMatcher.ts";
import IcyLyricsToaster from "./components/ReactComponents/SLToaster.tsx";
import { openSettingsPanel } from "./utils/settings.ts";
import { exposeToWindow } from "./utils/expose.ts";
import Logger from "./utils/Logger.ts";
import Whentil from "./modules/Whentil.ts";
import App from "./utils/app.ts";
import {
  CancelPreparedLyricCreatorFullscreen,
  CloseLyricCreator,
  IsLyricCreatorOpen,
  OpenLyricCreator,
} from "./utils/openLyricCreator.tsx";
import { isCreatorPreviewActive } from "./components/ReactComponents/LyricCreator/previewOwnership.ts";
import { bootstrapIcyLyrics } from "./utils/AutoUpdate.ts";

async function main() {
  const appLogger = new Logger("App");
  const dynamicBgLogger = new Logger("Dynamic Background");
  const playbackLogger = new Logger("Playback");

  if (App.isDev() || $developerMode.get()) {
    appLogger.debug("Boot sequence");
    exposeToWindow();
    appLogger.debug("Window helpers exposed");
  }

  await Platform.OnSpotifyReady;

  if (needsMigration()) {
    showMigrationModal();
    return;
  }

  // Open the persistent local-lyrics store before NPV or the first fetch can
  // request lyrics. init() also migrates older local TTML records.
  await LocalLyricsManager.init();

  Global.SetScope("fullscreen.open", false);

  Global.SetScope("fullscreen.onopen", (cb: any) => {
    const id = Global.Event.listen("fullscreen:open", () => {
      Global.SetScope("fullscreen.open", true);
      cb();
    });
    return () => Global.Event.unListen(id);
  });

  Global.SetScope("fullscreen.onclose", (cb: any) => {
    const id = Global.Event.listen("fullscreen:exit", () => {
      Global.SetScope("fullscreen.open", false);
      cb();
    });
    return () => Global.Event.unListen(id);
  });

  LoadFonts();
  ApplyFontPixel();

  const skeletonStyle = document.createElement("style");
  skeletonStyle.innerHTML = `
        /* This style is here to prevent the @keyframes removal in the CSS. I still don't know why that's happening. */
        /* This is a part of Icy Lyrics. */
        @keyframes skeleton {
            to {
                background-position-x: 0;
            }
        }

        @keyframes Marquee_SongName {
          0% {
            transform: translateX(calc(0px + min(-100% + 86cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 86cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 86cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 86cqw, 0px) * 1));
          }
        }

        @keyframes Marquee_SongName_SongMoreInfo {
          0% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 1));
          }
        }

        @keyframes Marquee_Artists {
          0% {
            transform: translateX(calc(0px + min(-100% + 81cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 81cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 81cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 81cqw, 0px) * 1));
          }
        }

        @keyframes Marquee_Artists_SongMoreInfo {
          0% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 98cqw, 0px) * 1));
          }
        }

        @keyframes Marquee_SongName_Compact {
          0% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 1));
          }
        }

        @keyframes Marquee_Artists_Compact {
          0% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 0));
          }
          10% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 0));
          }
          90% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 1));
          }
          100% {
            transform: translateX(calc(0px + min(-100% + 100cqw, 0px) * 1));
          }
        }

        @keyframes SLM_Animation {
          0% {
            --SLM_GradientPosition: -27.5%;
          }
          100% {
            --SLM_GradientPosition: 100%;
          }
        }

        @keyframes Pre_SLM_GradientAnimation {
          0% {
            --SLM_GradientPosition: -50%;
          }
          100% {
            --SLM_GradientPosition: -27.5%;
          }
        }

        @keyframes MB_anim_enter {
          0% {
            transform: translate(100%, 0);
          }
          100% {
            transform: translate(0, 0);
          }
        }
  `;

  skeletonStyle.id = "icyLyrics-additionalStyling";
  document.head.appendChild(skeletonStyle);

  let ButtonList: any;
  if (SpotifyPlayer.Playbar?.Button) {
    ButtonList = [
      {
        Registered: false,
        Button: new SpotifyPlayer.Playbar.Button(
          "Icy Lyrics",
          Icons.LyricsPage,
          (self) => {
            if (!self.active) {
              /* const isNewFullscreen = document.querySelector<HTMLElement>(".QdB2YtfEq0ks5O4QbtwX .WRGTOibB8qNEkgPNtMxq");
                if (isNewFullscreen) {
                  PageView.Open();
                  self.active = true;
                } else  */
              Session.Navigate({ pathname: "/IcyLyrics" });
              if (Global.Saves.shift_key_pressed) {
                const pageWhentil = Whentil.When(
                  () => document.querySelector<HTMLElement>(".Root__main-view #IcyLyricsPage"),
                  () => {
                    Fullscreen.Open(true);
                    pageWhentil?.Cancel();
                  }
                );
              }
              //}
            } else {
              Session.GoBack();
              //}
            }
          },
          false,
          false
        ),
      },
      {
        Registered: false,
        Button: new SpotifyPlayer.Playbar.Button(
          "Enter Fullscreen",
          `<svg role="img" height="16" width="16" aria-hidden="true" viewBox="0 0 16 16" data-encore-id="icon" class="Svg-sc-ytk21e-0 Svg-img-16-icon"><path d="M6.064 10.229l-2.418 2.418L2 11v4h4l-1.647-1.646 2.418-2.418-.707-.707zM11 2l1.647 1.647-2.418 2.418.707.707 2.418-2.418L15 6V2h-4z"/></svg>`,
          async (self) => {
            if (!self.active) {
              Session.Navigate({ pathname: "/IcyLyrics" });
              const pageWhentil = Whentil.When(
                () => document.querySelector<HTMLElement>(".Root__main-view #IcyLyricsPage"),
                () => {
                  Fullscreen.Open(Global.Saves.shift_key_pressed ?? false);
                  pageWhentil?.Cancel();
                }
              );
            } else {
              Session.GoBack();
            }
          },
          false,
          false
        ),
      },
      {
        Registered: false,
        Button:
          "documentPictureInPicture" in window && $popupLyricsAllowed.get()
            ? new SpotifyPlayer.Playbar.Button(
                "Icy Popup Lyrics",
                Icons.PiPMode,
                () => {
                  if (IsPIP) {
                    ClosePopupLyrics();
                  } else {
                    OpenPopupLyrics();
                  }
                },
                false,
                false
              )
            : undefined,
      },
    ];
  }

  // Add shift key tracking
  Global.Saves.shift_key_pressed = false;

  window.addEventListener("keydown", (e) => {
    if (e.key === "Shift") {
      Global.Saves.shift_key_pressed = true;
    }
  });

  window.addEventListener("keyup", (e) => {
    if (e.key === "Shift") {
      Global.Saves.shift_key_pressed = false;
    }
  });

  window.addEventListener("blur", () => {
    Global.Saves.shift_key_pressed = false;
  });

  Global.Event.listen("pagecontainer:available", () => {
    if (!ButtonList) return;
    for (const button of ButtonList) {
      if (!button.Registered) {
        if (button.Button) button.Button.register();
        button.Registered = true;
      }
    }
  });

  {
    if (!ButtonList) return;

    const fullscreenButton = ButtonList[1].Button;
    fullscreenButton.element.style.order = "100001";
    fullscreenButton.element.id = "IcyLyrics_FullscreenButton";

    const popupLyricsButton = ButtonList[2].Button;
    if (popupLyricsButton && "documentPictureInPicture" in window && $popupLyricsAllowed.get()) {
      popupLyricsButton.element.style.order = "100000";
      popupLyricsButton.element.id = "IcyLyrics_PopupLyricsButton";
    }

    const hideUnwantedButtons = (container: Element) => {
      for (const element of container.children) {
        const testId = element.attributes.getNamedItem("data-testid")?.value;

        const isFullscreen = testId === "fullscreen-mode-button";
        const isPip =
          "documentPictureInPicture" in window &&
          $popupLyricsAllowed.get() &&
          testId === "pip-toggle-button";
        const isGenericControl =
          element.classList.contains("control-button") &&
          !element.classList.contains("volume-bar__icon-button") &&
          !element.classList.contains("main-devicePicker-controlButton");

        if (
          (isFullscreen || isPip || isGenericControl) &&
          element.id !== "IcyLyrics_FullscreenButton" &&
          element.id !== "IcyLyrics_PopupLyricsButton"
        ) {
          (element as HTMLElement).style.display = "none";
        }
      }
    };

    let observer: MutationObserver | null = null;

    const startObservingDOM = () => {
      const controlsContainer = document.querySelector<HTMLElement>(
        ".main-nowPlayingBar-extraControls"
      );

      if (!controlsContainer) {
        setTimeout(startObservingDOM, 100);
        return;
      }

      hideUnwantedButtons(controlsContainer);

      const MAX_MUTATION_BATCHES = 100;
      const MAX_OBSERVE_MS = 60_000;
      let mutationBatches = 0;
      let timeoutId: ReturnType<typeof setTimeout> | undefined;

      const stopObserving = (
        obs: MutationObserver,
        _reason: "ready" | "timeout" | "max_mutations"
      ) => {
        try {
          obs.disconnect();
        } finally {
          if (timeoutId !== undefined) {
            clearTimeout(timeoutId);
            timeoutId = undefined;
          }
          if (observer === obs) observer = null;
        }
      };

      observer = new MutationObserver((mutations, obs) => {
        mutationBatches += 1;
        if (mutationBatches >= MAX_MUTATION_BATCHES) {
          stopObserving(obs, "max_mutations");
          return;
        }

        const hasNewChildren = mutations.some((mutation) => mutation.addedNodes.length > 0);
        if (!hasNewChildren) return;

        const hasFullscreen = !!controlsContainer.querySelector(
          '[data-testid="fullscreen-mode-button"]'
        );
        const needsPip = $popupLyricsAllowed.get();
        const hasPip = !!controlsContainer.querySelector('[data-testid="pip-toggle-button"]');

        const isReady = hasFullscreen && (!needsPip || hasPip);
        if (!isReady) return;

        hideUnwantedButtons(controlsContainer);
        stopObserving(obs, "ready");
      });

      observer.observe(controlsContainer, { childList: true });
      timeoutId = setTimeout(() => {
        if (observer) stopObserving(observer, "timeout");
      }, MAX_OBSERVE_MS);
    };

    startObservingDOM();
  }

  let button: any;
  if (ButtonList) {
    button = ButtonList[0];
  }

  const Hometinue = async () => {
    Whentil.When(
      () => Spicetify.Platform.PlaybackAPI,
      () => {
        requestPositionSync();
      }
    );

    {
      const div = document.createElement("div");
      div.classList.add("iltoaster");
      const reactRoot = ReactDOM.createRoot(div);

      reactRoot.render(<IcyLyricsToaster />);

      document.body.appendChild(div);
    }

    // Lets set out Dynamic Background (icy-dynamic-bg) to the now playing bar
    let lastImgUrl: string | null;
    let lastNowPlayingBarElement: HTMLElement | null = null;
    let nowPlayingBarObserver: MutationObserver | null = null;
    let nowPlayingBarMutationTimeout: ReturnType<typeof setTimeout> | null = null;

    const getNowPlayingBarElement = () =>
      document.querySelector<HTMLElement>(".Root__right-sidebar aside.NowPlayingView") ??
      document.querySelector<HTMLElement>(
        `.Root__right-sidebar aside#Desktop_PanelContainer_Id:has(.main-nowPlayingView-coverArtContainer)`
      );

    const scheduleNowPlayingBarDynamicBackgroundApply = () => {
      if (nowPlayingBarMutationTimeout) {
        clearTimeout(nowPlayingBarMutationTimeout);
      }
      nowPlayingBarMutationTimeout = setTimeout(() => {
        nowPlayingBarMutationTimeout = null;
        void applyDynamicBackgroundToNowPlayingBar(SpotifyPlayer.GetCover("large"));
      }, 50);
    };

    const startNowPlayingBarObserver = () => {
      if (nowPlayingBarObserver) return;

      const sidebar = document.querySelector(".Root__right-sidebar");
      if (!sidebar) return;

      nowPlayingBarObserver = new MutationObserver((mutations) => {
        // Resolved once per callback, not once per record.
        const card = GetNPVCardElement();
        const shouldReapply = mutations.some((mutation) => {
          // Cheap type/attribute test first — the ancestor walk below only runs
          // for records that would otherwise schedule a re-apply.
          if (mutation.type === "attributes") {
            const name = mutation.attributeName;
            if (name !== "src" && name !== "class" && name !== "inert") return false;
          } else if (mutation.type !== "childList") {
            return false;
          }
          // Ignore mutations inside the NPV lyrics card — the lyrics pipeline
          // mutates it constantly, which would reset the debounce below forever
          // and starve the npvbg apply. The card's own insertion/removal still
          // passes (that mutation targets the card's parent).
          const target = mutation.target;
          const targetElement = target instanceof Element ? target : target.parentElement;
          return !(card && targetElement && card.contains(targetElement));
        });

        if (!shouldReapply) return;
        scheduleNowPlayingBarDynamicBackgroundApply();
      });

      // `style` is deliberately absent from the filter: the lyrics animator
      // rewrites inline styles on every mounted word and letter each frame, and
      // the card lives inside this observed subtree. Including it made Blink
      // allocate a MutationRecord per write — hundreds per frame — that this
      // callback then had to walk and discard. Cover swaps already arrive via
      // the `playback:songchange` handler, and DOM-driven re-renders via
      // `childList` / `src` / `class`.
      nowPlayingBarObserver.observe(sidebar, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ["src", "class", "inert"],
      });
    };

    const CleanupNowBarDynamicBgLets = () => {
      const nowPlayingBar = getNowPlayingBarElement() ?? lastNowPlayingBarElement;

      const kawarpInstance = KawarpMap.get("npvbg");
      if (kawarpInstance) {
        kawarpInstance.dispose();
        KawarpMap.delete("npvbg");
      }
      nowPlayingBar?.querySelector<HTMLElement>(".icy-dynamic-bg")?.remove();
      nowPlayingBar?.classList.remove("icy-dynamic-bg-in-this");
      lastNowPlayingBarElement = null;
      lastImgUrl = null;
    };

    // Some Spotify views (e.g. cinema) swap the right sidebar layout.
    // When that happens, NPV dynamic background needs to be cleaned up,
    // but page backgrounds (e.g. lpagebg) must remain intact.
    let cinemaViewObserver: MutationObserver | null = null;
    let cinemaViewActive = false;

    const getTopContainerElement = () => {
      const rightSidebar = document.querySelector<HTMLElement>(".Root__right-sidebar");
      // `.Root__top-container` is expected to be the parent of `.Root__right-sidebar`.
      const parent = rightSidebar?.parentElement;
      if (parent?.classList.contains("Root__top-container")) return parent;
      return document.querySelector<HTMLElement>(".Root__top-container");
    };

    const checkCinemaViewAndMaybeCleanup = (topContainer: HTMLElement) => {
      const cinemaViewExists = Boolean(topContainer.querySelector(".Root__cinema-view"));

      if (cinemaViewExists && !cinemaViewActive) {
        cinemaViewActive = true;
        CleanupNowBarDynamicBgLets();
        return;
      }

      if (!cinemaViewExists && cinemaViewActive) {
        cinemaViewActive = false;
        // Restore NPV dynamic background after leaving cinema view.
        scheduleNowPlayingBarDynamicBackgroundApply();
      }
    };

    const startCinemaViewObserver = () => {
      if (cinemaViewObserver) return;

      const topContainer = getTopContainerElement();
      if (!topContainer) return;

      // Initial check (covers late observer start scenarios).
      checkCinemaViewAndMaybeCleanup(topContainer);

      cinemaViewObserver = new MutationObserver(() => {
        if (!topContainer.isConnected) {
          cinemaViewObserver?.disconnect();
          cinemaViewObserver = null;
          cinemaViewActive = false;
          return;
        }

        checkCinemaViewAndMaybeCleanup(topContainer);
      });

      cinemaViewObserver.observe(topContainer, {
        subtree: true,
        childList: true,
      });
    };

    Whentil.When(
      () => Boolean(getTopContainerElement()),
      () => {
        startCinemaViewObserver();
      }
    );

    async function applyDynamicBackgroundToNowPlayingBar(coverUrl: string | undefined) {
      if (!$showNpvDynamicBg.get()) return;
      if (SpotifyPlayer.GetContentType() === "unknown" || SpotifyPlayer.IsDJ()) return;
      if (!coverUrl) return;
      const nowPlayingBar = getNowPlayingBarElement();
      const topContainer = getTopContainerElement();
      const cinemaViewExists = Boolean(topContainer?.querySelector(".Root__cinema-view"));
      // Same rule as the NPV lyrics card: an inert ancestor chain
      // (.Root__right-sidebar <-> aside) means the NPV is not interactive,
      // so its dynamic background should be de-rendered too.
      const npvIsInert = Boolean(nowPlayingBar?.closest("[inert]"));

      try {
        if (!nowPlayingBar || cinemaViewExists || npvIsInert) {
          lastImgUrl = null;
          CleanupNowBarDynamicBgLets();
          return;
        }
        lastNowPlayingBarElement = nowPlayingBar;
        if (coverUrl === lastImgUrl) return;

        nowPlayingBar.classList.add("icy-dynamic-bg-in-this");

        await ApplyDynamicBackground(nowPlayingBar, "npvbg");

        lastImgUrl = coverUrl;
      } catch (error) {
        dynamicBgLogger.error("Failed applying dynamic background to now playing bar", error);
      }
    }

    $showNpvDynamicBg.listen((v) => {
      if (!v) {
        CleanupNowBarDynamicBgLets();
      } else {
        scheduleNowPlayingBarDynamicBackgroundApply();
      }
    });

    startNowPlayingBarObserver();
    scheduleNowPlayingBarDynamicBackgroundApply();

    Global.Event.listen("fullscreen:open", () => {
      CleanupNowBarDynamicBgLets();
    });

    Global.Event.listen("fullscreen:exit", () => {
      scheduleNowPlayingBarDynamicBackgroundApply();
    });

    async function onSongChange(event: any) {
      playbackLogger.debug("Song change pipeline");
      const contentType = SpotifyPlayer.GetContentType();
      playbackLogger.debug("Detected content type", contentType);

      if (contentType === "episode") {
        PageContainer?.classList.add("episode-content-type");
      } else {
        PageContainer?.classList.remove("episode-content-type");
      }

      if (!button.Registered) {
        button.Button.register();
        button.Registered = true;
      }

      if (PageContainer?.querySelector(".ContentBox .NowBar")) {
        if (Fullscreen.IsOpen) {
          UpdateNowBar(true);
        } else {
          UpdateNowBar();
        }
      }

      const songUri = event?.data?.item?.uri;
      if (songUri && !isCreatorPreviewActive()) {
        fetchLyrics(songUri).then((lyrics) => {
          if (!isCreatorPreviewActive()) return ApplyLyrics(lyrics);
        });
      }

      const _staticBgMode = $staticBackgroundMode.get();
      if (
        _staticBgMode !== "off" &&
        !SpotifyPlayer.IsDJ() &&
        (_staticBgMode === "auto" || _staticBgMode === "artistHeader")
      ) {
        const Artists = SpotifyPlayer.GetArtists();
        const Artist =
          Artists?.map((artist) => artist.uri?.replace("spotify:artist:", ""))[0] ?? undefined;
        try {
          void GetStaticBackground(Artist, SpotifyPlayer.GetId());
        } catch {
          dynamicBgLogger.error("Unable to prefetch static background");
        }
      }

      try {
        void scheduleNowPlayingBarDynamicBackgroundApply();
      } catch (err) {
        dynamicBgLogger.error("Failed applying dynamic background to now playing bar", err);
      }

      const contentBox = PageContainer?.querySelector<HTMLElement>(".ContentBox");
      if (!contentBox || $staticBackgroundMode.get() === "color") return;
      try {
        void ApplyDynamicBackground(contentBox, "lpagebg");
      } catch (err) {
        dynamicBgLogger.error("Failed applying dynamic background to page", err);
      }
    }
    Global.Event.listen("playback:songchange", onSongChange);

    const _initStaticBgMode = $staticBackgroundMode.get();
    if (
      _initStaticBgMode !== "off" &&
      !SpotifyPlayer.IsDJ() &&
      (_initStaticBgMode === "auto" || _initStaticBgMode === "artistHeader")
    ) {
      const Artists = SpotifyPlayer.GetArtists();
      const Artist =
        Artists?.map((artist) => artist.uri?.replace("spotify:artist:", ""))[0] ?? undefined;
      try {
        await GetStaticBackground(Artist, SpotifyPlayer.GetId());
      } catch {
        dynamicBgLogger.error("Unable to prefetch static background");
      }
    }

    window.addEventListener("online", () => {
      $lastFetchedUri.set(null);

      if (isCreatorPreviewActive()) return;
      fetchLyrics(Spicetify.Player.data?.item?.uri).then((lyrics) => {
        if (!isCreatorPreviewActive()) return ApplyLyrics(lyrics);
      });
    });

    new IntervalManager(ScrollingIntervalTime, () => {
      if (ScrollSimplebar) {
        ScrollToActiveLine(ScrollSimplebar);
      }
    }).Start();

    interface Location {
      pathname: string;
      [key: string]: any;
    }

    let lastLocation: Location | null = null;
    let pageLoadGeneration = 0;

    async function loadPage(location: Location) {
      const generation = ++pageLoadGeneration;
      const routeIsCurrent = () =>
        generation === pageLoadGeneration &&
        Spicetify.Platform.History.location?.pathname === location.pathname;

      appLogger.debug("Handling route change", location.pathname);
      if (location.pathname === "/IcyLyrics") {
        await CloseLyricCreator();
        if (!routeIsCurrent()) return;
        await PageView.Open();
        if (!routeIsCurrent()) return;
        if (button) button.Button.active = true;
      } else if (location.pathname === "/IcyLyrics/creator") {
        // The creator needs the normal page shell. Tear down cinema/document
        // presentation state before borrowing the singleton renderer for its
        // engine preview.
        // Creator is itself a document-fullscreen workspace. Preserve the
        // current fullscreen element while dismantling cinema/lyrics layout;
        // exiting it here would emit fullscreenchange immediately after
        // Creator mounts and make Creator interpret the handoff as Escape.
        if (Fullscreen.IsOpen) await Fullscreen.Close(false, true);
        if (!routeIsCurrent()) {
          await CancelPreparedLyricCreatorFullscreen();
          return;
        }
        // OpenPage performs the singleton hand-off when the NPV card, PiP, or
        // another bounded owner currently holds the renderer. Calling it even
        // when IsOpened is true is what lets its ownership guards run.
        await PageView.Open();
        if (!routeIsCurrent()) {
          await CancelPreparedLyricCreatorFullscreen();
          return;
        }
        OpenLyricCreator();
        if (button) button.Button.active = true;
      } else {
        await CloseLyricCreator();
        if (!routeIsCurrent()) return;
        if (
          lastLocation?.pathname === "/IcyLyrics" ||
          lastLocation?.pathname === "/IcyLyrics/creator"
        ) {
          await PageView.Destroy();
          if (!routeIsCurrent()) return;
          if (!button) return;
          button.Button.active = false;
        }
      }
      lastLocation = location;
    }

    Global.Event.listen("platform:history", loadPage);

    if (
      Spicetify.Platform.History.location.pathname === "/IcyLyrics" ||
      Spicetify.Platform.History.location.pathname === "/IcyLyrics/creator"
    ) {
      Global.Event.listen("pagecontainer:available", () => {
        loadPage(Spicetify.Platform.History.location);
        if (!button) return;
        button.Button.active = true;
      });
    }

    if (button) {
      button.Button.tippy.setContent("Icy Lyrics");
    }

    /*
    // This probably won't be added

    let wasPageViewTippyShown = false;
    button.Button.tippy.setProps({
      ...Spicetify.TippyProps,
      content: `Icy Lyrics`,
      allowHTML: true,
      onShow(instance: any) {
        // Spotify's Code
        instance.popper.firstChild.classList.add("main-contextMenu-tippyEnter");
      },
      onMount(instance: any) {
          // Spotify's Code
          requestAnimationFrame(() => {
            instance.popper.firstChild.classList.remove("main-contextMenu-tippyEnter");
            instance.popper.firstChild.classList.add("main-contextMenu-tippyEnterActive");
          });

          const TippyElement = instance.popper;

          //TippyElement.style.removeProperty("pointer-events");

          const TippyElementContent = TippyElement.querySelector(".main-contextMenu-tippy");


          if (!PageView.IsTippyCapable) {
            TippyElementContent.style.width = "";
            TippyElementContent.style.height = "";
            TippyElementContent.style.maxWidth = "";
            TippyElementContent.style.maxHeight = "";

            TippyElement.style.setProperty("--section-border-radius", "");
            TippyElement.style.borderRadius = "";
            TippyElementContent.style.borderRadius = "";

            TippyElementContent.innerHTML = ""
            instance.setContent("Icy Lyrics");

            return;
          };

          TippyElementContent.innerHTML = "";
          TippyElementContent.style.width = "470px";
          TippyElementContent.style.height = "540px";
          TippyElementContent.style.maxWidth = "none";
          TippyElementContent.style.maxHeight = "none";

          TippyElement.style.setProperty("--section-border-radius", "8px");
          TippyElement.style.borderRadius = "var(--section-border-radius, 8px)";
          TippyElementContent.style.borderRadius = "var(--section-border-radius, 8px)";

          if (!wasPageViewTippyShown) {
            PageView.Destroy();
            instance.unmount();
            wasPageViewTippyShown = true;
            setTimeout(() => instance.show(), 75);
            return;
          }

          PageView.Open(TippyElementContent, true);
      },
      onHide(instance: any) {
          if (PageView.IsTippyCapable) {
            PageView.Destroy();
          };
          // Spotify's Code
          requestAnimationFrame(() => {
              instance.popper.firstChild.classList.remove("main-contextMenu-tippyEnterActive");
              instance.unmount();
          });
      },
    }); */

    {
      type LoopType = "context" | "track" | "none";
      let lastLoopType: LoopType | null = null;
      // These interval managers are intentionally not stored in variables that are used elsewhere
      // They are self-running background processes that continue to run throughout the app lifecycle
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      new IntervalManager(Infinity, () => {
        const LoopState = Spicetify.Player.getRepeat();
        const LoopType: LoopType = LoopState === 1 ? "context" : LoopState === 2 ? "track" : "none";
        SpotifyPlayer.LoopType = LoopType;
        if (lastLoopType !== LoopType) {
          Global.Event.evoke("playback:loop", LoopType);
        }
        lastLoopType = LoopType;
      }).Start();
    }

    {
      type ShuffleType = "smart" | "normal" | "none";
      let lastShuffleType: ShuffleType | null = null;
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      new IntervalManager(Infinity, () => {
        const ShuffleType: ShuffleType = (Spicetify.Player as any).origin._state.smartShuffle
          ? "smart"
          : (Spicetify.Player as any).origin._state.shuffle
            ? "normal"
            : "none";
        SpotifyPlayer.ShuffleType = ShuffleType;
        if (lastShuffleType !== ShuffleType) {
          Global.Event.evoke("playback:shuffle", ShuffleType);
        }
        lastShuffleType = ShuffleType;
      }).Start();
    }

    {
      // Volume changes from anywhere (Spotify's own slider, media keys, another
      // device, our own setVolume) arrive on this native emitter, so there's nothing
      // to poll. `_events` is an undocumented internal — if Spotify ever drops it the
      // guard degrades us to "the volume slider doesn't auto-update" rather than
      // throwing during startup.
      Whentil.When(
        () => Spicetify.Platform?.PlaybackAPI,
        () => {
          try {
            Spicetify.Platform.PlaybackAPI?._events?.addListener?.(
              "volume",
              (e: { data?: { volume?: number } }) => {
                const volume = e?.data?.volume;
                if (typeof volume !== "number") return;
                Global.Event.evoke("playback:volume", volume);
              }
            );
          } catch (err) {
            console.error("Icy Lyrics: couldn't listen for volume changes", err);
          }
        }
      );
    }

    {
      let lastPosition = 0;
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      new IntervalManager(0.5, () => {
        const pos = SpotifyPlayer.GetPosition();
        if (pos !== lastPosition) {
          Global.Event.evoke("playback:position", pos);
        }
        lastPosition = pos;
      }).Start();
    }

    /* {
      let lastPosition = 0;
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      new IntervalManager(Infinity, () => {
        const pos = SpotifyPlayer.GetPosition();
        if (pos !== lastPosition) {
          Global.Event.evoke("playback:position_smooth", pos);
        }
        lastPosition = pos;
      }).Start();
    } */

    {
      let lastTimeout: any;
      Global.Event.listen("lyrics:apply", () => {
        if (lastTimeout !== undefined) {
          clearTimeout(lastTimeout);
          lastTimeout = undefined;
        }
        lastTimeout = setTimeout(async () => {
          const currentSongLyrics = $currentLyricsData.get();
          if (currentSongLyrics && currentSongLyrics !== `NO_LYRICS:${SpotifyPlayer.GetUri()}`) {
            const parsedLyrics = JSON.parse(currentSongLyrics);
            if (parsedLyrics?.uri !== SpotifyPlayer.GetUri()) {
              const refetchUri = SpotifyPlayer.GetUri();
              if (refetchUri && !isCreatorPreviewActive()) {
                fetchLyrics(refetchUri).then((lyrics) => {
                  if (!isCreatorPreviewActive()) return ApplyLyrics(lyrics);
                });
              }
            }
          }
        }, 1000);
      });
    }

    SpotifyPlayer.IsPlaying = IsPlaying();

    // Events
    {
      Spicetify.Player.addEventListener("onplaypause", (e) => {
        SpotifyPlayer.IsPlaying = !e?.data?.isPaused;
        Global.Event.evoke("playback:playpause", e);
      });
      Spicetify.Player.addEventListener("onprogress", (e) =>
        Global.Event.evoke("playback:progress", e)
      );
      Spicetify.Player.addEventListener("songchange", (e) =>
        Global.Event.evoke("playback:songchange", e)
      );

      Whentil.When(GetPageRoot, () => {
        Global.Event.evoke("pagecontainer:available", GetPageRoot());
      });

      Spicetify.Platform.History.listen((e: Location) => {
        Global.Event.evoke("platform:history", e);
      });
      Spicetify.Platform.History.listen(Session.RecordNavigation);
      Session.RecordNavigation(Spicetify.Platform.History.location);
    }
  };

  Whentil.When(
    () => SpotifyPlayer.GetContentType(),
    () => {
      const IsSomethingElseThanTrack = SpotifyPlayer.GetContentType() !== "track";

      if (IsSomethingElseThanTrack) {
        if (!button) return;
        button.Button.deregister();
        button.Registered = false;
      } else {
        if (!button) return;
        if (!button.Registered) {
          button.Button.register();
          button.Registered = true;
        }
      }
    }
  );

  initNPVLyrics();

  Hometinue();

  runThemeMatcher();

  Spicetify.Keyboard.registerImportantShortcut(Spicetify.Keyboard.KEYS.ESCAPE, async () => {
    if (IsLyricCreatorOpen()) return;
    if (IsPIP) return;
    if (Fullscreen.CinemaViewOpen) {
      await Fullscreen.Close();
      Session.GoBack();
    }
  });

  document.addEventListener("fullscreenchange", async () => {
    if (!document.fullscreenElement && Fullscreen.IsOpen && !Fullscreen.CinemaViewOpen) {
      Fullscreen.CinemaViewOpen = true;
      await ExitFullscreenElement();
      PageView.AppendViewControls(true);
    }
  });

  Spicetify.Keyboard.registerImportantShortcut(Spicetify.Keyboard.KEYS.F11, async () => {
    if (IsLyricCreatorOpen()) return;
    if (IsPIP) return;
    if (Fullscreen.IsOpen) {
      if (!Fullscreen.CinemaViewOpen) {
        Fullscreen.CinemaViewOpen = true;
        await ExitFullscreenElement();
        PageView.AppendViewControls(true);
      } else {
        Fullscreen.CinemaViewOpen = false;
        await EnterIcyLyricsFullscreen();
        PageView.AppendViewControls(true);
      }
    }
  });

  new Spicetify.Menu.Item(
    "Icy Lyrics Settings",
    false,
    () => {
      openSettingsPanel();
    },
    `<svg version="1.0" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" preserveAspectRatio="xMidYMid meet">${ICY_LYRICS_BRAND_GLYPH}</svg>`
  ).register();
}

void bootstrapIcyLyrics(main).catch((error) => {
  console.error("[Icy Lyrics] Startup failed.", error);
});
