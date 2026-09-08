import { isDev } from "../../components/Global/Defaults.ts";
import Global from "../../components/Global/Global.ts";
import Platform from "../../components/Global/Platform.ts";
import { SpotifyPlayer } from "../../components/Global/SpotifyPlayer.ts";
import PageView, { PageContainer } from "../../components/Pages/PageView.ts";
import { isCreatorPreviewActive } from "../../components/ReactComponents/LyricCreator/previewOwnership.ts";
import { GetExpireStore } from "../../modules/Store.ts";
import { lookupIcyLyricsDatabase } from "../API/IcyLyricsDatabase.ts";
import { Query } from "../API/Query.ts";
import Logger from "../Logger.ts";
import {
  $currentLyricsData,
  $currentLyricsType,
  $currentlyFetching,
  $useLocalTtmlLyrics,
} from "../stores.ts";
import { LyricsQueueRetry } from "./LyricsQueueRetry.ts";
import { lyricsFromIcyDatabaseLookup } from "./IcyLyricsDatabase.ts";
import { LocalLyricsManager } from "./manager/index.ts";
import { decodeLyricsPayload } from "./payload.ts";
import { ProcessLyrics } from "./ProcessLyrics.ts";
import { LyricsRequestGeneration, type LyricsRequestToken } from "./requestGeneration.ts";
import {
  ResolutionCoordinator,
  shouldSettleLyricsResolution,
  type ResolutionIntent,
} from "./ResolutionCoordinator.ts";
import { isLyricsObject, normalizeLyricsSchema } from "./schema.ts";
import { isSpicyUpdateSentinel } from "./updateSentinel.ts";

export { decodeLyricsPayload } from "./payload.ts";

const lyricsLogger = new Logger("Lyrics Pipeline");
const lyricsCacheLogger = new Logger("Lyrics Cache");

export type FetchLyricsResult = [object | string, number] | null;

export type LyricsFetchIntent = ResolutionIntent;

export interface FetchLyricsOptions {
  intent?: LyricsFetchIntent;
}

const MAX_RUNTIME_RESOLUTIONS = 128;
export const REMOTE_LYRICS_CACHE_DAYS = 7;
const REMOTE_LYRICS_CACHE_VERSION = 2;
const lyricsResolutionCoordinator = new ResolutionCoordinator<FetchLyricsResult>(
  shouldSettleLyricsResolution,
  MAX_RUNTIME_RESOLUTIONS
);
const icyDatabaseLyricsByUri = new Map<string, Record<string, any> | null>();
const activeRequestControllers = new Map<number, AbortController>();

function setBounded<K, V>(map: Map<K, V>, key: K, value: V): void {
  map.delete(key);
  map.set(key, value);
  while (map.size > MAX_RUNTIME_RESOLUTIONS) {
    const oldestKey = map.keys().next().value as K | undefined;
    if (oldestKey === undefined) break;
    map.delete(oldestKey);
  }
}

function isQueuedResult(value: Exclude<FetchLyricsResult, null>): boolean {
  return value[0] === "lyrics-queued" && value[1] === 503;
}

export const LyricsStore = GetExpireStore<any>(
  "IcyLyrics_LyricsStore_g1",
  // Version 2 prevents a pre-1.1.0 Spicy result from masking a newly
  // available higher-priority Icy database result after the upgrade.
  REMOTE_LYRICS_CACHE_VERSION,
  { Unit: "Days", Duration: REMOTE_LYRICS_CACHE_DAYS },
  isDev as true
);

const requestGuard = new LyricsRequestGeneration();

export function invalidateLyricsRequests(nextUri: string | null = null): number {
  for (const controller of activeRequestControllers.values()) controller.abort();
  activeRequestControllers.clear();
  lyricsResolutionCoordinator.invalidateInFlight();
  const generation = requestGuard.invalidate(nextUri);
  $currentlyFetching.set(false);
  return generation;
}

export function getLyricsRequestGeneration(): number {
  return requestGuard.currentGeneration();
}

function beginRequest(uri: string): LyricsRequestToken {
  for (const controller of activeRequestControllers.values()) controller.abort();
  activeRequestControllers.clear();
  $currentlyFetching.set(true);
  const token = requestGuard.begin(uri);
  activeRequestControllers.set(token.generation, new AbortController());
  return token;
}

function isCurrentRequest(token: LyricsRequestToken): boolean {
  return requestGuard.isCurrent(token, SpotifyPlayer.GetUri());
}

function finishRequest(token: LyricsRequestToken): void {
  activeRequestControllers.delete(token.generation);
  if (requestGuard.isCurrent(token)) $currentlyFetching.set(false);
}

function requestSignal(token: LyricsRequestToken): AbortSignal | undefined {
  return activeRequestControllers.get(token.generation)?.signal;
}

function setRomanizationClass(hasTransliterations: boolean | undefined): void {
  PageContainer?.classList.toggle("Lyrics_RomanizationAvailable", hasTransliterations === true);
}

function presentLyrics(lyricsData: any, token: LyricsRequestToken): boolean {
  if (!isCurrentRequest(token)) return false;
  LyricsQueueRetry.NotifyResolved(lyricsData?.uri);
  setRomanizationClass(lyricsData?.HasTransliterations);
  HideLoaderContainer();
  $currentLyricsType.set(lyricsData.Type);
  PageContainer?.querySelector<HTMLElement>(".ContentBox")?.classList.remove("LyricsHidden");
  PageContainer?.querySelector(".ContentBox .LyricsContainer")?.classList.remove("Hidden");
  PageView.AppendViewControls(true);
  finishRequest(token);
  return true;
}

function result(
  descriptor: object | string,
  status: number,
  token: LyricsRequestToken
): FetchLyricsResult {
  finishRequest(token);
  return [descriptor, status];
}

async function fetchLyricsInternal(
  uri: string,
  options: FetchLyricsOptions
): Promise<FetchLyricsResult> {
  if (!uri) return null;
  // Creator Preview owns the singleton renderer and supplies its own project
  // clock. Normal route/settings/queue fetches must not mutate that isolated
  // stage; PreviewStage releases ownership and performs one fresh fetch on exit.
  if (isCreatorPreviewActive()) return null;
  if (SpotifyPlayer.GetUri() !== uri) return null;
  const intent = options.intent ?? "ensure";
  if (intent === "refresh") {
    lyricsResolutionCoordinator.forget(uri);
    icyDatabaseLyricsByUri.delete(uri);
  }
  const token = beginRequest(uri);
  lyricsLogger.debug("Fetch requested", { uri, generation: token.generation });

  const lyricsContent = PageContainer?.querySelector(".LyricsContainer .LyricsContent");
  lyricsContent?.classList.remove("offline");
  lyricsContent?.classList.add("HiddenTransitioned");

  if (SpotifyPlayer.IsDJ()) return result("dj", 400, token);

  const mediaType = SpotifyPlayer.GetMediaType();
  if (mediaType && mediaType !== "audio") {
    if (mediaType === "video") return result("video-track", 400, token);
    if (mediaType === "mixed") return result("mixed-track", 400, token);
    return result("unknown-track", 400, token);
  }

  const contentType = SpotifyPlayer.GetContentType();
  if (contentType !== "track") {
    return result(contentType === "episode" ? "episode-track" : "unknown-track", 400, token);
  }

  const trackId = uri.split(":")[2];

  // Permanent local TTML has first priority when its use toggle is enabled.
  try {
    const localLyrics = await LocalLyricsManager.get(uri);
    if (!isCurrentRequest(token)) return null;
    if (localLyrics) {
      localLyrics.uri = uri;
      localLyrics.source = "ldb";
      $currentLyricsData.set(JSON.stringify(localLyrics));
      if (!presentLyrics(localLyrics, token)) return null;
      return [localLyrics, 200];
    }
  } catch (error) {
    lyricsLogger.error("Local TTML lookup failed; continuing with remote lyrics", error);
  }

  const savedLyricsData = $currentLyricsData.get();
  if (savedLyricsData && !isDev) {
    try {
      if (savedLyricsData.startsWith("NO_LYRICS:")) {
        const savedUri = savedLyricsData.slice("NO_LYRICS:".length);
        if (savedUri === uri) return result("lyrics-not-found", 404, token);
      } else {
        const lyricsData = normalizeLyricsSchema(JSON.parse(savedLyricsData));
        if (isSpicyUpdateSentinel(lyricsData)) {
          $currentLyricsData.set("");
        } else {
          const localDisabled = lyricsData?.source === "ldb" && !$useLocalTtmlLyrics.get();
          if (lyricsData?.uri === uri && !localDisabled) {
            if (!presentLyrics(lyricsData, token)) return null;
            return [lyricsData, 200];
          }
        }
      }
    } catch (error) {
      lyricsCacheLogger.warn("Ignoring invalid in-memory lyrics data", error);
    }
  }

  if (uri.startsWith("spotify:local:")) return result("local-track", 400, token);

  try {
    let cacheKey = uri;
    let cached = await LyricsStore.GetItem(cacheKey);
    if (!cached && trackId) {
      // One-time compatibility read for the 5.x ID-keyed cache.
      cacheKey = trackId;
      cached = await LyricsStore.GetItem(cacheKey);
      if (cached) await LyricsStore.SetItem(uri, cached);
    }
    if (!isCurrentRequest(token)) return null;
    if (isSpicyUpdateSentinel(cached)) {
      await LyricsStore.RemoveItem(cacheKey);
      if (cacheKey !== uri) await LyricsStore.RemoveItem(uri);
      cached = undefined;
    }
    if (cached === "NO_LYRICS") return result("lyrics-not-found", 404, token);
    if (isLyricsObject(cached)) {
      const lyricsFromCache = normalizeLyricsSchema({ ...cached, uri });
      $currentLyricsData.set(JSON.stringify(lyricsFromCache));
      if (!presentLyrics(lyricsFromCache, token)) return null;
      return [{ ...lyricsFromCache, fromCache: true }, 200];
    }
  } catch (error) {
    lyricsCacheLogger.warn("Ignoring unreadable lyrics cache entry", error);
  }

  const sessionResult = lyricsResolutionCoordinator.getSettled(uri);
  if (sessionResult && !(intent === "queue-retry" && isQueuedResult(sessionResult))) {
    const [descriptor, status] = sessionResult;
    if (isLyricsObject(descriptor)) {
      const lyricsFromSession = normalizeLyricsSchema(structuredClone(descriptor));
      const localDisabled = lyricsFromSession.source === "ldb" && !$useLocalTtmlLyrics.get();
      if (!localDisabled) {
        lyricsFromSession.uri = uri;
        $currentLyricsData.set(JSON.stringify(lyricsFromSession));
        if (!presentLyrics(lyricsFromSession, token)) return null;
        return [{ ...lyricsFromSession, fromCache: true }, status];
      }
      lyricsResolutionCoordinator.forget(uri);
    } else {
      return result(descriptor, status, token);
    }
  }

  if (!navigator.onLine) return result("offline", 400, token);
  ShowLoaderContainer();

  try {
    let icyLyrics: Record<string, any> | null;
    if (icyDatabaseLyricsByUri.has(uri)) {
      icyLyrics = icyDatabaseLyricsByUri.get(uri) ?? null;
    } else {
      const icyLookup = await lookupIcyLyricsDatabase(uri, {
        signal: requestSignal(token),
      });
      if (!isCurrentRequest(token)) return null;
      icyLyrics = await lyricsFromIcyDatabaseLookup(icyLookup, uri, {
        signal: requestSignal(token),
      });
      if (!isCurrentRequest(token)) return null;
      setBounded(icyDatabaseLyricsByUri, uri, icyLyrics);

      if (icyLookup.kind === "rate-limited") {
        lyricsLogger.warn("Icy Lyrics Database rate limited this lookup; using Spicy fallback", {
          uri,
          retryAfterMs: icyLookup.retryAfterMs,
        });
      } else if (icyLookup.kind === "unavailable") {
        lyricsLogger.warn("Icy Lyrics Database lookup unavailable; using Spicy fallback", {
          uri,
          status: icyLookup.httpStatus,
          reason: icyLookup.reason,
        });
      } else if (icyLookup.kind === "found" && !icyLyrics) {
        lyricsLogger.warn("Icy Lyrics Database returned unsupported TTML; using Spicy fallback", {
          uri,
          recordId: icyLookup.recordId,
        });
      }
    }

    if (icyLyrics) {
      const lyrics = normalizeLyricsSchema(structuredClone(icyLyrics));
      lyrics.uri = uri;
      lyrics.source = "icy";
      $currentLyricsData.set(JSON.stringify(lyrics));
      try {
        await LyricsStore.SetItem(uri, lyrics);
      } catch (error) {
        lyricsCacheLogger.warn("Could not save Icy database lyrics cache", error);
      }
      if (!isCurrentRequest(token)) return null;
      if (!presentLyrics(lyrics, token)) return null;
      return [{ ...lyrics, fromCache: false }, 200];
    }
  } catch (error) {
    if (!isCurrentRequest(token)) return null;
    lyricsLogger.warn("Icy Lyrics Database lookup failed; using Spicy fallback", error);
    setBounded(icyDatabaseLyricsByUri, uri, null);
  }

  try {
    const accessToken = await Platform.GetSpotifyAccessToken();
    if (!isCurrentRequest(token)) return null;

    const queries = await Query(
      [
        {
          operation: "lyrics",
          variables: { id: trackId, auth: "SpicyLyrics-WebAuth" },
        },
      ],
      { "SpicyLyrics-WebAuth": `Bearer ${accessToken}` },
      { signal: requestSignal(token) }
    );
    if (!isCurrentRequest(token)) return null;

    const lyricsQuery = queries.get("0");
    if (!lyricsQuery) {
      HideLoaderContainer();
      return result("lyrics-not-found", 404, token);
    }

    const status = lyricsQuery.httpStatus;
    if (status === 503) {
      finishRequest(token);
      LyricsQueueRetry.HandleQueued(uri);
      return ["lyrics-queued", 503];
    }

    if (status !== 200) {
      HideLoaderContainer();
      if (status === 404) {
        await LyricsStore.SetItem(uri, "NO_LYRICS");
        if (!isCurrentRequest(token)) return null;
        return result("lyrics-not-found", 404, token);
      }
      return result("status-not-200", status, token);
    }

    const lyrics = await decodeLyricsPayload(lyricsQuery.data, {
      signal: requestSignal(token),
    });
    if (!isCurrentRequest(token)) return null;
    if (!lyrics) {
      HideLoaderContainer();
      return result("lyrics-not-found", 404, token);
    }
    if (isSpicyUpdateSentinel(lyrics)) {
      HideLoaderContainer();
      return result("icy-update-required", 426, token);
    }

    await ProcessLyrics(lyrics);
    if (!isCurrentRequest(token)) return null;

    lyrics.uri = uri;
    $currentLyricsData.set(JSON.stringify(lyrics));
    try {
      await LyricsStore.SetItem(uri, lyrics);
    } catch (error) {
      lyricsCacheLogger.warn("Could not save remote lyrics cache", error);
    }
    if (!isCurrentRequest(token)) return null;

    if (!presentLyrics(lyrics, token)) return null;
    return [{ ...lyrics, fromCache: false }, 200];
  } catch (error) {
    if (!isCurrentRequest(token)) return null;
    lyricsLogger.error("Error fetching lyrics", error);
    finishRequest(token);
    HideLoaderContainer();
    return ["unknown-error", 0];
  }
}

/**
 * Coalesces every automatic request for one exact URI. Reopening a view or
 * toggling a render-only option reuses the in-flight/settled resolution rather
 * than starting another HTTP lookup. Explicit user refreshes use `refresh`,
 * while the scheduled Spicy 503 loop uses `queue-retry`.
 */
export default function fetchLyrics(
  uri: string,
  options: FetchLyricsOptions = {}
): Promise<FetchLyricsResult> {
  if (!uri) return Promise.resolve(null);
  const intent = options.intent ?? "ensure";
  return lyricsResolutionCoordinator.run(uri, intent, () =>
    fetchLyricsInternal(uri, options)
  );
}

let containerShowLoaderTimeout: ReturnType<typeof setTimeout> | null = null;

export const LYRICS_QUEUE_MESSAGE =
  "Your request is in the queue — hang tight, your lyrics are on the way!";

function ShowLoaderContainer(): void {
  if (containerShowLoaderTimeout) clearTimeout(containerShowLoaderTimeout);
  const loaderContainer = PageContainer?.querySelector<HTMLElement>(
    ".LyricsContainer .loaderContainer"
  );
  if (!loaderContainer) return;
  containerShowLoaderTimeout = setTimeout(() => {
    loaderContainer.classList.add("active");
    containerShowLoaderTimeout = null;
  }, 2000);
}

export function ShowQueueLoader(message: string = LYRICS_QUEUE_MESSAGE): void {
  const loaderContainer = PageContainer?.querySelector<HTMLElement>(
    ".LyricsContainer .loaderContainer"
  );
  if (!loaderContainer) return;

  if (containerShowLoaderTimeout) {
    clearTimeout(containerShowLoaderTimeout);
    containerShowLoaderTimeout = null;
  }

  loaderContainer.classList.add("active", "queued");
  let messageEl = loaderContainer.querySelector<HTMLElement>(".loaderMessage");
  if (!messageEl) {
    messageEl = document.createElement("div");
    messageEl.className = "loaderMessage";
    loaderContainer.appendChild(messageEl);
  }
  messageEl.textContent = message;
}

function HideLoaderContainer(): void {
  if (containerShowLoaderTimeout) {
    clearTimeout(containerShowLoaderTimeout);
    containerShowLoaderTimeout = null;
  }
  const loaderContainer = PageContainer?.querySelector<HTMLElement>(
    ".LyricsContainer .loaderContainer"
  );
  loaderContainer?.classList.remove("active", "queued");
  loaderContainer?.querySelector(".loaderMessage")?.remove();
}

export function ClearLyricsPageContainer(): void {
  const lyricsContent = PageContainer?.querySelector<HTMLElement>(
    ".LyricsContainer .LyricsContent"
  );
  if (lyricsContent) lyricsContent.innerHTML = "";
}

// Invalidate even when Spotify changes to an episode/ad/null URI. This closes
// the gap where an old network response could otherwise remain "current".
Global.Event.listen("playback:songchange", (event: any) => {
  const nextUri: string | null = event?.data?.item?.uri ?? SpotifyPlayer.GetUri() ?? null;
  // Spotify can emit duplicate songchange events for the same playback item.
  // Keep the active generation and its coalesced request intact in that case;
  // the app-level listener will simply reuse the current/cache/settled result.
  if (requestGuard.hasActiveUri(nextUri)) return;
  invalidateLyricsRequests(nextUri);
});
