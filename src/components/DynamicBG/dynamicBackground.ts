import {
  $fullscreenBackgroundBlurEnabled,
  $showNpvDynamicBg,
  $staticBackgroundBlur,
  $staticBackgroundMode,
} from "../../utils/stores.ts";
import BlobURLMaker from "../../utils/BlobURLMaker.ts";
import Global from "../Global/Global.ts";
import { SpotifyPlayer } from "../Global/SpotifyPlayer.ts";
import ArtistVisuals from "./ArtistVisuals/Main.ts";
import { PageContainer } from "../Pages/PageView.ts";
import type { KawarpOptions } from "@kawarp/core";
import { KawarpRegistry, ManagedKawarp, type KawarpSource } from "./ManagedKawarp.ts";
import { BackgroundAnimationController, type AudioAnalysisData } from "./BackgroundAnimationController.ts";
import { getDynamicAudioAnalysis } from "../../utils/audioAnalysis.ts";
import Logger from "../../utils/Logger.ts";
import { resolveKawarpBlurPasses } from "../../utils/FullscreenPresentation.ts";

export { resolveKawarpBlurPasses } from "../../utils/FullscreenPresentation.ts";

const dynamicBgLogger = new Logger("Dynamic Background");

const KawarpTransitionDuration = 1000;
export const KawarpOptionsStatic: KawarpOptions = {
  warpIntensity: 1,
  blurPasses: 8,
  animationSpeed: 0.1,
  saturation: 1.5,
  dithering: 0.008,
  transitionDuration: 500,
  // tintColor: [0.16, 0.16, 0.24],
  tintIntensity: 0, // 0.15
  scale: 1,
}

const getPageKawarpOptions = (): KawarpOptions => ({
  ...KawarpOptionsStatic,
  blurPasses: resolveKawarpBlurPasses(
    PageContainer?.classList.contains("Fullscreen") === true,
    $fullscreenBackgroundBlurEnabled.get()
  ),
});

const COLOR_BG_FALLBACK_RGB = "18, 18, 18, 1";
let cachedColorBackgroundEl: HTMLElement | null = null;
let npvBackgroundElement: HTMLElement | null = null;
const backgroundModes = new WeakMap<HTMLElement, string>();

export const KawarpMap = new KawarpRegistry();
const animSpeedController = new BackgroundAnimationController();

const clearBackground = (element: HTMLElement, key: HTMLElement | string) => {
  const canvas = KawarpMap.get(key)?.canvas;
  KawarpMap.delete(key); // Invalidates pending work even before an instance exists.
  canvas?.remove();
  element.querySelectorAll<HTMLElement>(":scope > .icy-dynamic-bg").forEach((background) => background.remove());
};

const npvBackgroundAllowed = (element: HTMLElement) => $showNpvDynamicBg.get() &&
  !element.closest("[inert]") && !PageContainer?.classList.contains("Fullscreen") &&
  !document.querySelector(".Root__cinema-view");

interface ApplyDynamicBackgroundOpts {
  doTransitionDurationAppendWithPromise?: boolean;
}

/** How long to wait for a local cover to decode before giving up on the dynamic background. */
const LOCAL_COVER_DECODE_TIMEOUT_MS = 8000;

/**
 * Rasterize Spotify local-file artwork into a Blob.
 *
 * Local covers are served through the client's `spotify:local:` scheme: they
 * render in `<img>`/CSS but can't be `fetch()`ed (which is how `Kawarp.loadImage`
 * resolves a URL), and WebGL can't sample a raw cross-scheme `<img>` either. We
 * draw the decoded image to a canvas and export it as a same-origin Blob, which
 * `Kawarp.loadBlob` can then sample cleanly.
 *
 * Returns `null` if the art can't be decoded or the canvas is tainted.
 */
async function rasterizeLocalCover(coverUrl: string): Promise<Blob | null> {
  if (!coverUrl) return null;

  const img = new Image();
  img.decoding = "async";
  img.src = coverUrl;

  try {
    let timeoutId: ReturnType<typeof setTimeout> | undefined;
    await Promise.race([
      img.decode(),
      new Promise<never>((_, reject) => {
        timeoutId = setTimeout(() => reject(new Error("decode timed out")), LOCAL_COVER_DECODE_TIMEOUT_MS);
      }),
    ]).finally(() => clearTimeout(timeoutId));
  } catch (err) {
    dynamicBgLogger.error("Local cover failed to decode for dynamic background", err);
    return null;
  }

  const width = img.naturalWidth;
  const height = img.naturalHeight;
  if (!width || !height) return null;

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.drawImage(img, 0, 0);

  try {
    return await new Promise<Blob | null>((resolve, reject) => {
      canvas.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error("toBlob produced no data"))),
        "image/png"
      );
    });
  } catch (err) {
    // Thrown when the canvas is tainted (cross-origin art served without CORS).
    dynamicBgLogger.error("Local cover could not be exported to a blob", err);
    return null;
  }
}

/**
 * Resolve a cover into something Kawarp can load. Remote covers pass through as a
 * URL; local-file covers are rasterized to a Blob (see {@link rasterizeLocalCover}).
 */
async function resolveKawarpSource(coverUrl: string, isLocalCover: boolean): Promise<KawarpSource | null> {
  if (!isLocalCover) {
    return { kind: "url", value: coverUrl };
  }
  const blob = await rasterizeLocalCover(coverUrl);
  return blob ? { kind: "blob", value: blob } : null;
}

export default async function ApplyDynamicBackground(element: HTMLElement, tag?: string, opts: ApplyDynamicBackgroundOpts = {}) {
  if (!element) return;
  const key = tag ?? (element.closest("#IcyLyricsPage") ? "lpagebg" : element);
  // The NPV lyrics card must stay transparent (the NPV's own background shows
  // through) — covers every caller that re-applies the page bg (songchange,
  // static-bg mode changes, etc.).
  if (element.closest("#IcyLyricsPage.CardMode") || (key === "npvbg" && !npvBackgroundAllowed(element))) {
    clearBackground(element, key);
    return;
  }
  if (key === "npvbg") npvBackgroundElement = element;
  const staticBgMode = $staticBackgroundMode.get();
  const previousMode = backgroundModes.get(element);
  if ((previousMode !== undefined && previousMode !== staticBgMode) ||
    (staticBgMode !== "off" && KawarpMap.has(key))) {
    clearBackground(element, key);
  }
  backgroundModes.set(element, staticBgMode);
  const ownsRequest = KawarpMap.begin(key);
  dynamicBgLogger.debug("Applying dynamic background", { tag });
  const preCurrentImgCover = SpotifyPlayer.GetCover("large") ?? "";
  // Local-file art is served via the `spotify:local:` scheme and isn't on scdn,
  // so leave it untouched here and rasterize it to a Blob before handing it to Kawarp.
  const isLocalCover = preCurrentImgCover.startsWith("spotify:local");
  const currentImgCover = isLocalCover
    ? preCurrentImgCover
    : preCurrentImgCover.replace("spotify:image:", "https://i.scdn.co/image/");
  const IsEpisode = SpotifyPlayer.GetContentType() === "episode";

  const artists = SpotifyPlayer.GetArtists() ?? [];
  const TrackArtist =
    artists.length > 0 && artists[0]?.uri
      ? artists[0].uri.replace("spotify:artist:", "")
      : undefined;

  const TrackId = SpotifyPlayer.GetId() ?? undefined;

  const TrackUri = SpotifyPlayer.GetUri();
  const IsLocal = TrackUri?.startsWith("spotify:local:") ?? false;

  const isCurrent = () => ownsRequest() && element.isConnected &&
    $staticBackgroundMode.get() === staticBgMode && SpotifyPlayer.GetUri() === TrackUri &&
    (SpotifyPlayer.GetCover("large") ?? "") === preCurrentImgCover &&
    (key !== "npvbg" || npvBackgroundAllowed(element));
  if (staticBgMode !== "off") {
    if (staticBgMode === "color") {
      // First, create/init the background with black as a fallback
      let dynamicBg = element.querySelector<HTMLElement>(".icy-dynamic-bg.ColorBackground");
      if (!dynamicBg) {
        dynamicBg = document.createElement("div");
        dynamicBg.classList.add("icy-dynamic-bg", "ColorBackground");
        // Set initial fallback colors to black
        dynamicBg.style.setProperty("--MinContrastColor", COLOR_BG_FALLBACK_RGB);
        dynamicBg.style.setProperty("--HighContrastColor", COLOR_BG_FALLBACK_RGB);
        dynamicBg.style.setProperty("--OverlayColor", COLOR_BG_FALLBACK_RGB);
        element.appendChild(dynamicBg);
      }
      cachedColorBackgroundEl = dynamicBg;

      // Local tracks aren't hosted on Spotify, so we can't derive dynamic colors
      // from their artwork — keep the plain black background instead.
      if (IsLocal) {
        dynamicBg.style.setProperty("--MinContrastColor", COLOR_BG_FALLBACK_RGB);
        dynamicBg.style.setProperty("--HighContrastColor", COLOR_BG_FALLBACK_RGB);
        dynamicBg.style.setProperty("--OverlayColor", COLOR_BG_FALLBACK_RGB);
        return;
      }

      // Now fetch the real colors and apply them
      try {
        const colorQuery = await Spicetify.GraphQL.Request(
          Spicetify.GraphQL.Definitions.getDynamicColorsByUris,
          {
            imageUris: [SpotifyPlayer.GetCover("large") ?? ""]
          }
        );
        if (!isCurrent()) return;

        const colorResponse = colorQuery.data.dynamicColors[0];
        const colorBestFit = colorResponse.bestFit === "DARK" ? "dark" : colorResponse.bestFit === "LIGHT" ? "light" : "dark";

        const colors = colorResponse[colorBestFit];
        const fromColorObj = colors.minContrast;
        const toColorObj = colors.highContrast;
        const overlayColorObj = colors.higherContrast;

        const fromColorBgObj = fromColorObj.backgroundBase;
        const toColorBgObj = toColorObj.backgroundBase;
        const overlayColorBgObj = overlayColorObj.backgroundBase;

        const fromColor = `${fromColorBgObj.red}, ${fromColorBgObj.green}, ${fromColorBgObj.blue}, ${fromColorBgObj.alpha}`;
        const toColor = `${toColorBgObj.red}, ${toColorBgObj.green}, ${toColorBgObj.blue}, ${toColorBgObj.alpha}`;
        const overlayColor = `${overlayColorBgObj.red}, ${overlayColorBgObj.green}, ${overlayColorBgObj.blue}, ${overlayColorBgObj.alpha}`;

        dynamicBg.style.setProperty("--MinContrastColor", fromColor);
        dynamicBg.style.setProperty("--HighContrastColor", toColor);
        dynamicBg.style.setProperty("--OverlayColor", overlayColor);
      } catch (err) {
        // If the color fetch fails, just keep the black fallback
        dynamicBgLogger.error("Failed to fetch dynamic colors, using fallback black background", err);
      }
      return;
    }
    const currentImgCover = await GetStaticBackground(TrackArtist, TrackId);

    if (!isCurrent() || IsEpisode || !currentImgCover) return;
    const prevBg = element.querySelector<HTMLElement>(".icy-dynamic-bg.StaticBackground");

    if (prevBg && prevBg.getAttribute("data-cover-id") === currentImgCover) {
      return;
    }

    // `isLocalCover` (derived up top from the playing track's cover) applies to the
    // static background too: GetStaticBackground returns either this track's local art
    // or a remote `spotify:image:` header — never the opposite scheme — so reuse it
    // here instead of re-deriving and shadowing the same flag.
    const finalUrl = isLocalCover
      ? currentImgCover
      : `https://i.scdn.co/image/${currentImgCover.replace("spotify:image:", "")}`;

    const backgroundUrl = isLocalCover
      ? finalUrl
      : await BlobURLMaker(finalUrl)
          .then((blobUrl) => blobUrl ?? currentImgCover)
          .catch(() => currentImgCover);
    if (!isCurrent()) return;

    const dynamicBg = document.createElement("div");

    dynamicBg.classList.add("icy-dynamic-bg", "StaticBackground");
    if (prevBg) dynamicBg.classList.add("transition_In");

    dynamicBg.style.backgroundImage = `url("${backgroundUrl}")`;
    dynamicBg.setAttribute("data-cover-id", currentImgCover);
    element.appendChild(dynamicBg);

    if (prevBg) {
      prevBg.classList.remove("transition_In");
      prevBg.classList.add("transition_Out");

      setTimeout(() => {
        prevBg?.remove();
        dynamicBg.classList.remove("transition_In")
      }, 1000)
    }
  } else {
    const existingElement = element.querySelector<HTMLElement>(".icy-dynamic-bg");

    if (existingElement) {
      const existingBgData = existingElement.getAttribute("data-cover-id") ?? null;

      if (existingBgData === currentImgCover && KawarpMap.get(key)?.canvas === existingElement &&
        !KawarpMap.get(key)?.isDisposed) {
        return;
      }
    }

    // Resolve a Kawarp-loadable source up front (rasterizing local art if needed)
    // so we can bail before touching any instance when there's nothing to show.
    const kawarpSource = await resolveKawarpSource(currentImgCover, isLocalCover);
    if (!isCurrent()) return;
    if (!kawarpSource) {
      dynamicBgLogger.warn("No loadable cover for dynamic background; skipping", { currentImgCover });
      return;
    }

    // Resolving can block for seconds (rasterizing a local cover waits up to
    // LOCAL_COVER_DECODE_TIMEOUT_MS). If the track changed in the meantime, a newer
    // invocation already owns this tag's instance — loading our now-stale cover into it
    // would flash the previous track's art. Bail and let the newer invocation win.
    const liveImgCover = SpotifyPlayer.GetCover("large") ?? "";
    if (liveImgCover !== preCurrentImgCover) {
      dynamicBgLogger.debug("Cover changed while resolving dynamic background; skipping stale apply", { tag });
      return;
    }

    // Re-query the canvas rather than trusting the pre-await snapshot: a concurrent
    // invocation may have disposed or replaced this tag's canvas while we were resolving.
    const liveElement = element.querySelector<HTMLElement>(".icy-dynamic-bg");
    if (liveElement) {
      const kawarpInstance = KawarpMap.get(key);

      if (kawarpInstance && kawarpInstance.canvas === liveElement && !kawarpInstance.isDisposed) {
        const hadImage = kawarpInstance.hasImage;
        try {
          if (await kawarpInstance.load(kawarpSource, isCurrent)) {
            liveElement.setAttribute("data-cover-id", currentImgCover ?? "");
            kawarpInstance.start();
            // A concurrent apply can take over an instance before its first
            // image finishes. The winning first image owns this timer too.
            if (!hadImage) {
              const optionsUpdate = kawarpInstance.setOptionsAfter(
                { transitionDuration: KawarpTransitionDuration }, KawarpOptionsStatic.transitionDuration * 2
              );
              if (opts?.doTransitionDurationAppendWithPromise) await optionsUpdate;
            }
          }
        } catch (error) {
          dynamicBgLogger.error("Unable to update background artwork", error);
        }
        return;
      }
    }

    const canvas = document.createElement("canvas");
    canvas.classList.add("icy-dynamic-bg");
    let kawarpInstance: ManagedKawarp | null = null;
    try {
      kawarpInstance = new ManagedKawarp(canvas, getPageKawarpOptions());
      KawarpMap.set(key, kawarpInstance);
      liveElement?.remove();
      element.appendChild(canvas);
      if (!await kawarpInstance.load(kawarpSource, isCurrent)) {
        if (ownsRequest() && KawarpMap.get(key) === kawarpInstance && !kawarpInstance.hasImage) {
          KawarpMap.delete(key);
          canvas.remove();
        }
        return;
      }
      canvas.setAttribute("data-cover-id", currentImgCover ?? "");
      kawarpInstance.start();
      const optionsUpdate = kawarpInstance.setOptionsAfter(
        { transitionDuration: KawarpTransitionDuration }, KawarpOptionsStatic.transitionDuration * 2
      );
      if (opts?.doTransitionDurationAppendWithPromise) await optionsUpdate;
    } catch (error) {
      if (isCurrent() && KawarpMap.get(key) === kawarpInstance && !kawarpInstance?.hasImage) {
        KawarpMap.delete(key);
        canvas.remove();
      }
      dynamicBgLogger.error("Unable to initialize animated background", error);
    }
  }
}

export async function GetStaticBackground(
  TrackArtist: string | undefined,
  TrackId: string | undefined
): Promise<string | undefined> {
  if (!TrackArtist || !TrackId) return undefined;

  try {
    return await ArtistVisuals.ApplyContent(TrackArtist, TrackId);
  } catch (error) {
    dynamicBgLogger.error("Error setting static low quality dynamic background", error);
    return undefined;
  }
}

let staticColorBgTransitionTimeout = null;

const getColorBackgroundElement = (): HTMLElement | null => {
  if (cachedColorBackgroundEl?.isConnected) {
    return cachedColorBackgroundEl;
  }
  const el = PageContainer?.querySelector<HTMLElement>(".icy-dynamic-bg.ColorBackground") ?? null;
  cachedColorBackgroundEl = el;
  return el;
};

Global.Event.listen("playback:songchange", () => {
  if ($staticBackgroundMode.get() === "color" && PageContainer) {
    if (staticColorBgTransitionTimeout) {
      clearTimeout(staticColorBgTransitionTimeout);
      staticColorBgTransitionTimeout = null;

      const dynamicBg = getColorBackgroundElement();
      if (dynamicBg) {
        const min = dynamicBg.style.getPropertyValue("--MinContrastColor").trim();
        const high = dynamicBg.style.getPropertyValue("--HighContrastColor").trim();
        const overlay = dynamicBg.style.getPropertyValue("--OverlayColor").trim();
        if (
          min !== COLOR_BG_FALLBACK_RGB ||
          high !== COLOR_BG_FALLBACK_RGB ||
          overlay !== COLOR_BG_FALLBACK_RGB
        ) {
          dynamicBg.style.setProperty("--MinContrastColor", COLOR_BG_FALLBACK_RGB);
          dynamicBg.style.setProperty("--HighContrastColor", COLOR_BG_FALLBACK_RGB);
          dynamicBg.style.setProperty("--OverlayColor", COLOR_BG_FALLBACK_RGB);
        }
      }
    }

    staticColorBgTransitionTimeout = setTimeout(() => {
      const contentBox = PageContainer.querySelector<HTMLElement>(".ContentBox");
      if (contentBox) void ApplyDynamicBackground(contentBox, "lpagebg");

      clearTimeout(staticColorBgTransitionTimeout);
      staticColorBgTransitionTimeout = null;
    }, 1000);
  }
})

/** Successful analysis, or `null` once we know the track has no analysis (stops progress-handler spam). */
const audioAnalysisCache = new Map<string, AudioAnalysisData | null>();
const audioAnalysisInflightRequests = new Map<string, Promise<AudioAnalysisData | null>>();
let latestPlaybackTrackUri: string | null = null;

const pruneAudioAnalysisCache = (activeTrackUri: string) => {
  for (const cachedTrackUri of audioAnalysisCache.keys()) {
    if (cachedTrackUri !== activeTrackUri) {
      audioAnalysisCache.delete(cachedTrackUri);
    }
  }
};

const getAudioAnalysisForTrack = async (uri: string): Promise<AudioAnalysisData | null> => {
  if (audioAnalysisCache.has(uri)) {
    return audioAnalysisCache.get(uri)!;
  }

  const inflight = audioAnalysisInflightRequests.get(uri);
  if (inflight) {
    return inflight;
  }

  const request = getDynamicAudioAnalysis(uri)
    .then((analysis) => {
      audioAnalysisCache.set(uri, analysis);
      return analysis;
    })
    .finally(() => {
      audioAnalysisInflightRequests.delete(uri);
    });

  audioAnalysisInflightRequests.set(uri, request);
  return request;
};

const setDynamicBackgroundAnimationSpeed = (speed: number) => {
  KawarpMap.forEach((kawarpInstance) => {
    void kawarpInstance.setOptions({
      animationSpeed: speed
    })
  })
};

const resetDynamicBackgroundAnimationSpeed = () => {
  setDynamicBackgroundAnimationSpeed(1);
};

Global.Event.listen("playback:songchange", () => {
  latestPlaybackTrackUri = SpotifyPlayer.GetUri() ?? null;

  if (latestPlaybackTrackUri) {
    pruneAudioAnalysisCache(latestPlaybackTrackUri);
  } else {
    audioAnalysisCache.clear();
  }
});

const applyPlayPauseAnimationSpeed = (isPaused: boolean) => {
  setDynamicBackgroundAnimationSpeed(isPaused ? 0.1 : 1);
};

Global.Event.listen("playback:playpause", (e: { data?: { isPaused?: boolean } }) => {
  applyPlayPauseAnimationSpeed(!!e?.data?.isPaused);
});

const reapplyPageBackground = () => {
  const contentBox = PageContainer?.querySelector<HTMLElement>(".ContentBox");
  if (!contentBox) {
    KawarpMap.delete("lpagebg");
    return;
  }
  clearBackground(contentBox, "lpagebg");
  void ApplyDynamicBackground(contentBox, "lpagebg");
};

$staticBackgroundMode.listen(() => {
  reapplyPageBackground();
  const npv = npvBackgroundElement;
  if (!npv) {
    KawarpMap.delete("npvbg");
    return;
  }
  clearBackground(npv, "npvbg");
  if (!npv.isConnected) npvBackgroundElement = null;
  else if (npvBackgroundAllowed(npv)) void ApplyDynamicBackground(npv, "npvbg");
});

// Kawarp builds its blur passes into the render pipeline. Recreate only the
// page instance when the fullscreen-only option or fullscreen host changes;
// NPV and popup instances retain their existing blur configuration.
const reapplyFullscreenPageBackground = () => {
  if ($staticBackgroundMode.get() !== "off") return;
  if (!PageContainer?.classList.contains("Fullscreen")) return;
  const current = KawarpMap.get("lpagebg");
  if (current && !current.isDisposed && current.getOptions().blurPasses === getPageKawarpOptions().blurPasses) return;
  reapplyPageBackground();
};

$fullscreenBackgroundBlurEnabled.listen(reapplyFullscreenPageBackground);
Global.Event.listen("fullscreen:open", reapplyFullscreenPageBackground);
Global.Event.listen("fullscreen:exit", () => {
  if ($staticBackgroundMode.get() !== "off") return;
  const current = KawarpMap.get("lpagebg");
  if (current && !current.isDisposed && current.getOptions().blurPasses === getPageKawarpOptions().blurPasses) return;
  // The Fullscreen class has been removed before this event. Rebuilding now
  // restores the normal eight-pass page background without touching NPV.
  reapplyPageBackground();
});

// Blur is a pure paint change on the existing element, so push it straight into a
// CSS var rather than tearing the background down and rebuilding it.
//
// The var goes on #IcyLyricsPage itself, not just the root, because in PiP the
// page lives in the popup's own document — that document's <html> never sees
// anything we write here, so a root-only var falls back to 0px there.
const applyStaticBackgroundBlur = (blur: number) => {
  const value = `${blur}px`;
  document.documentElement.style.setProperty("--StaticBackgroundBlur", value);
  PageContainer?.style.setProperty("--StaticBackgroundBlur", value);
};

applyStaticBackgroundBlur($staticBackgroundBlur.get());
$staticBackgroundBlur.listen(applyStaticBackgroundBlur);

// A freshly opened page (PiP or otherwise) is a brand new element with no inline
// var on it, so seed it from the current setting.
Global.Event.listen("page:open", () => {
  applyStaticBackgroundBlur($staticBackgroundBlur.get());
});

Global.Event.listen("playback:progress", async () => {
  const songUri = SpotifyPlayer.GetUri();
  if (!songUri) {
    resetDynamicBackgroundAnimationSpeed();
    return;
  }

  latestPlaybackTrackUri = songUri;

  // Local tracks have no Spotify audio analysis — skip loading it and fall back
  // to the default animation speed.
  if (songUri.startsWith("spotify:local:")) {
    resetDynamicBackgroundAnimationSpeed();
    return;
  }

  const requestUri = songUri;

  const audioAnalysisData = await getAudioAnalysisForTrack(requestUri);
  if (!audioAnalysisData) {
    resetDynamicBackgroundAnimationSpeed();
    return;
  }

  // Prevent stale async results from old tracks applying after rapid song switches.
  const currentUri = SpotifyPlayer.GetUri();
  if (!currentUri || currentUri !== requestUri || latestPlaybackTrackUri !== requestUri) {
    return;
  }

  pruneAudioAnalysisCache(requestUri);

  const currentTimeMs = SpotifyPlayer.GetPosition();
  const currentTime = currentTimeMs / 1000;

  const speedMultiplier = animSpeedController.getSpeedMultiplier(currentTime, audioAnalysisData);

  KawarpMap.forEach((kawarpInstance) => {
    void kawarpInstance.setOptions({
      animationSpeed: speedMultiplier
    })
  })
})
