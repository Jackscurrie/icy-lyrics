import {
  IcyLyricsDatabaseApiUrl,
  ProjectVersion,
} from "../../../project/config.ts";

export const ICY_DATABASE_API_VERSION = 1;
export const ICY_DATABASE_REQUEST_TIMEOUT_MS = 12_000;
// The API permits 2 MB of raw TTML. JSON escaping can nearly double that
// representation, so bound the complete response at 5 MiB.
export const ICY_DATABASE_MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
export const ICY_DATABASE_DEFAULT_RETRY_AFTER_MS = 60_000;
// The website enforces both short and daily buckets. Honor its full daily
// Retry-After instead of repeatedly probing during the same rate-limit window.
export const ICY_DATABASE_MAX_RETRY_AFTER_MS = 24 * 60 * 60_000;

let rateLimitedUntil = 0;

export type IcyDatabaseLookupResult =
  | {
      kind: "found";
      rawTtml: string;
      recordId: string | null;
      revision: number | null;
      contentSha256: string | null;
    }
  | { kind: "miss"; httpStatus: 404 }
  | { kind: "rate-limited"; httpStatus: 429; retryAfterMs: number | null }
  | { kind: "unavailable"; httpStatus: number; reason: string }
  | { kind: "aborted" };

export interface IcyDatabaseLookupOptions {
  signal?: AbortSignal;
  fetchImpl?: typeof fetch;
  apiUrl?: string;
  timeoutMs?: number;
}

type UnknownRecord = Record<string, unknown>;

function asRecord(value: unknown): UnknownRecord | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as UnknownRecord)
    : null;
}

function isSpotifyTrackUri(uri: string): boolean {
  return /^spotify:track:[A-Za-z0-9]{22}$/u.test(uri);
}

export function buildIcyDatabaseLookupBody(uri: string) {
  return {
    apiVersion: ICY_DATABASE_API_VERSION,
    track: { uri },
    client: {
      name: "Icy Lyrics Desktop",
      version: ProjectVersion,
    },
  };
}

export function parseRetryAfterMs(value: string | null, now = Date.now()): number | null {
  if (!value) return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.round(seconds * 1_000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - now) : null;
}

export function clearIcyDatabaseRateLimitCooldown(): void {
  rateLimitedUntil = 0;
}

function activeRateLimitDelay(now = Date.now()): number | null {
  return rateLimitedUntil > now ? rateLimitedUntil - now : null;
}

function rememberRateLimit(retryAfterMs: number | null, now = Date.now()): number {
  const delay = Math.min(
    Math.max(retryAfterMs ?? ICY_DATABASE_DEFAULT_RETRY_AFTER_MS, 1_000),
    ICY_DATABASE_MAX_RETRY_AFTER_MS
  );
  rateLimitedUntil = Math.max(rateLimitedUntil, now + delay);
  return rateLimitedUntil - now;
}

function responseByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

/**
 * Looks up one approved TTML record by its exact Spotify URI.
 *
 * The caller deliberately falls through to Spicy Lyrics for every outcome
 * except `found`. A rate limit or temporary Icy outage must never make lyrics
 * unavailable when the existing provider can still answer.
 */
export async function lookupIcyLyricsDatabase(
  uri: string,
  options: IcyDatabaseLookupOptions = {}
): Promise<IcyDatabaseLookupResult> {
  if (!isSpotifyTrackUri(uri)) {
    return { kind: "unavailable", httpStatus: 0, reason: "unsupported-uri" };
  }
  if (options.signal?.aborted) return { kind: "aborted" };

  // Honor a server Retry-After across track changes. This runtime-only gate is
  // shared by playback and Lyric Creator so neither path amplifies a 429.
  const cooldownDelay = activeRateLimitDelay();
  if (cooldownDelay !== null) {
    return { kind: "rate-limited", httpStatus: 429, retryAfterMs: cooldownDelay };
  }

  const fetchImpl = options.fetchImpl ?? fetch;
  const controller = new AbortController();
  const parentSignal = options.signal;
  let timedOut = false;
  const abortFromParent = () => controller.abort(parentSignal?.reason);
  parentSignal?.addEventListener("abort", abortFromParent, { once: true });

  const timeoutMs = options.timeoutMs ?? ICY_DATABASE_REQUEST_TIMEOUT_MS;
  const timeoutHandle =
    timeoutMs > 0
      ? setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : null;

  try {
    const response = await fetchImpl(options.apiUrl ?? IcyLyricsDatabaseApiUrl, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(buildIcyDatabaseLookupBody(uri)),
      signal: controller.signal,
    });

    if (response.status === 404) return { kind: "miss", httpStatus: 404 };
    if (response.status === 429) {
      const retryAfterMs = rememberRateLimit(
        parseRetryAfterMs(response.headers.get("Retry-After"))
      );
      return {
        kind: "rate-limited",
        httpStatus: 429,
        retryAfterMs,
      };
    }
    if (!response.ok) {
      return {
        kind: "unavailable",
        httpStatus: response.status,
        reason: response.status >= 500 ? "server-error" : "request-rejected",
      };
    }

    const contentLength = Number(response.headers.get("Content-Length"));
    if (Number.isFinite(contentLength) && contentLength > ICY_DATABASE_MAX_RESPONSE_BYTES) {
      return { kind: "unavailable", httpStatus: 200, reason: "response-too-large" };
    }

    const responseText = await response.text();
    if (responseByteLength(responseText) > ICY_DATABASE_MAX_RESPONSE_BYTES) {
      return { kind: "unavailable", httpStatus: 200, reason: "response-too-large" };
    }

    let payload: UnknownRecord | null = null;
    try {
      payload = asRecord(JSON.parse(responseText));
    } catch {
      // Handled by the validation branch below.
    }
    const track = asRecord(payload?.track);
    if (
      payload?.apiVersion !== ICY_DATABASE_API_VERSION ||
      payload?.schemaVersion !== 1 ||
      payload?.format !== "ttml" ||
      typeof payload?.rawTtml !== "string" ||
      payload.rawTtml.length === 0 ||
      track?.uri !== uri
    ) {
      return { kind: "unavailable", httpStatus: 200, reason: "invalid-response" };
    }

    return {
      kind: "found",
      rawTtml: payload.rawTtml,
      recordId: typeof payload.recordId === "string" ? payload.recordId : null,
      revision: typeof payload.revision === "number" ? payload.revision : null,
      contentSha256:
        typeof payload.contentSha256 === "string" ? payload.contentSha256 : null,
    };
  } catch (error) {
    if (parentSignal?.aborted) return { kind: "aborted" };
    return {
      kind: "unavailable",
      httpStatus: 0,
      reason: timedOut
        ? "timeout"
        : error instanceof Error
          ? error.name || "network-error"
          : "network-error",
    };
  } finally {
    if (timeoutHandle !== null) clearTimeout(timeoutHandle);
    parentSignal?.removeEventListener("abort", abortFromParent);
  }
}
