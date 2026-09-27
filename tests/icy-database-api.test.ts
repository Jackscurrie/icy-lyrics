import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProjectVersion } from "../project/config.ts";
import {
  buildIcyDatabaseLookupBody,
  clearIcyDatabaseRateLimitCooldown,
  lookupIcyLyricsDatabase,
  parseRetryAfterMs,
} from "../src/utils/API/IcyLyricsDatabase.ts";

const TRACK_URI = "spotify:track:0123456789ABCDEFGHIJKL";
const SECOND_TRACK_URI = "spotify:track:ZYXWVUTSRQPONMLKJIHGFE";

beforeEach(clearIcyDatabaseRateLimitCooldown);
afterEach(clearIcyDatabaseRateLimitCooldown);

function fetchReturning(response: Response) {
  return vi.fn(async () => response) as unknown as typeof fetch;
}

function foundPayload(overrides: Record<string, unknown> = {}) {
  return {
    apiVersion: 1,
    schemaVersion: 1,
    format: "ttml",
    recordId: "4b88d461-e87d-4c0e-a096-9e2faf0af654",
    revision: 3,
    contentSha256: "a".repeat(64),
    track: { uri: TRACK_URI },
    rawTtml: "<tt><body><div><p>Lyrics</p></div></body></tt>",
    ...overrides,
  };
}

describe("Icy Lyrics Database API client", () => {
  it("sends the exact URI and Icy 1.3.0 identity without touching the Spicy version", async () => {
    const fetchImpl = fetchReturning(
      Response.json(foundPayload(), { status: 200 })
    );

    const result = await lookupIcyLyricsDatabase(TRACK_URI, {
      fetchImpl,
      apiUrl: "https://example.test/api/ttml",
      timeoutMs: 0,
    });

    expect(result).toMatchObject({ kind: "found", revision: 3 });
    expect(fetchImpl).toHaveBeenCalledOnce();
    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe("https://example.test/api/ttml");
    expect(init).toMatchObject({
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
    });
    expect(JSON.parse(String(init?.body))).toEqual(buildIcyDatabaseLookupBody(TRACK_URI));
    expect(buildIcyDatabaseLookupBody(TRACK_URI).client.version).toBe(ProjectVersion);
    expect(ProjectVersion).toBe("1.3.0");
  });

  it("treats a missing Icy record as a normal fallback miss", async () => {
    await expect(
      lookupIcyLyricsDatabase(TRACK_URI, {
        fetchImpl: fetchReturning(Response.json({ code: "not_found" }, { status: 404 })),
        timeoutMs: 0,
      })
    ).resolves.toEqual({ kind: "miss", httpStatus: 404 });
  });

  it("surfaces rate limiting for immediate provider fallback without retrying", async () => {
    const fetchImpl = fetchReturning(
      Response.json(
        { apiVersion: 1, code: "rate_limited" },
        { status: 429, headers: { "Retry-After": "2" } }
      )
    );
    await expect(
      lookupIcyLyricsDatabase(TRACK_URI, { fetchImpl, timeoutMs: 0 })
    ).resolves.toEqual({ kind: "rate-limited", httpStatus: 429, retryAfterMs: 2_000 });
    expect(fetchImpl).toHaveBeenCalledOnce();

    const cancelled = new AbortController();
    cancelled.abort();
    await expect(
      lookupIcyLyricsDatabase(SECOND_TRACK_URI, {
        fetchImpl,
        signal: cancelled.signal,
        timeoutMs: 0,
      })
    ).resolves.toEqual({ kind: "aborted" });

    await expect(
      lookupIcyLyricsDatabase(SECOND_TRACK_URI, { fetchImpl, timeoutMs: 0 })
    ).resolves.toMatchObject({ kind: "rate-limited", httpStatus: 429 });
    expect(fetchImpl).toHaveBeenCalledOnce();
  });

  it("falls through safely on service errors and mismatched response identity", async () => {
    await expect(
      lookupIcyLyricsDatabase(TRACK_URI, {
        fetchImpl: fetchReturning(Response.json({}, { status: 503 })),
        timeoutMs: 0,
      })
    ).resolves.toMatchObject({ kind: "unavailable", httpStatus: 503 });

    await expect(
      lookupIcyLyricsDatabase(TRACK_URI, {
        fetchImpl: fetchReturning(
          Response.json(
            foundPayload({ track: { uri: "spotify:track:AAAAAAAAAAAAAAAAAAAAAA" } })
          )
        ),
        timeoutMs: 0,
      })
    ).resolves.toEqual({ kind: "unavailable", httpStatus: 200, reason: "invalid-response" });
  });

  it("does not contact the public API for local or malformed URIs", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    await expect(
      lookupIcyLyricsDatabase("spotify:local:Artist:Album:Song:180", {
        fetchImpl,
        timeoutMs: 0,
      })
    ).resolves.toEqual({ kind: "unavailable", httpStatus: 0, reason: "unsupported-uri" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("parses both Retry-After formats", () => {
    expect(parseRetryAfterMs("1.5", 0)).toBe(1_500);
    expect(parseRetryAfterMs("Thu, 01 Jan 1970 00:00:05 GMT", 1_000)).toBe(4_000);
    expect(parseRetryAfterMs("invalid", 0)).toBeNull();
  });
});
