import { createHash } from "node:crypto";
import { describe, expect, it, vi } from "vitest";

import {
  bootstrapIcyLyrics,
  shouldLoadRemoteVersion,
  validateUpdateManifest,
} from "../src/utils/AutoUpdate.ts";

const runtimeUrl = "https://jackscurrie.com/downloads/icy-lyrics.js?v=1.0.1";
const runtimeSource = "globalThis.__icyUpdateTest = true;";
const runtimeHash = createHash("sha256").update(runtimeSource).digest("hex");

const manifest = (overrides: Record<string, unknown> = {}) => ({
  schemaVersion: 1,
  version: "1.0.1",
  runtimeUrl,
  sha256: runtimeHash,
  ...overrides,
});

const response = (body: unknown, status = 200, headers?: HeadersInit) =>
  new Response(typeof body === "string" ? body : JSON.stringify(body), {
    status,
    headers: {
      "content-type": typeof body === "string" ? "text/javascript" : "application/json",
      ...headers,
    },
  });

const quietLogger = () => ({ info: vi.fn(), warn: vi.fn() });

describe("Icy Lyrics startup updater", () => {
  it("validates Icy semantic versions independently of the API compatibility version", () => {
    expect(shouldLoadRemoteVersion("1.0.0", "1.0.1")).toBe(true);
    expect(shouldLoadRemoteVersion("1.0.0", "1.0.0")).toBe(false);
    expect(shouldLoadRemoteVersion("1.0.0", "0.9.9")).toBe(false);
    expect(shouldLoadRemoteVersion("invalid", "1.0.1")).toBe(false);
    expect(() => validateUpdateManifest(manifest({ version: "not-a-version" }))).toThrow(
      /invalid version/
    );
  });

  it("rejects runtime URLs outside the owned HTTPS origin", () => {
    expect(() =>
      validateUpdateManifest(manifest({ runtimeUrl: "https://example.com/icy-lyrics.js" }))
    ).toThrow(/jackscurrie\.com/);
    expect(() =>
      validateUpdateManifest(manifest({ runtimeUrl: "http://jackscurrie.com/icy-lyrics.js" }))
    ).toThrow(/HTTPS/);
  });

  it("executes a verified update only after its matching bundle acknowledges the handoff", async () => {
    const globalObject: Record<string, unknown> = {};
    const installedMain = vi.fn();
    const websiteMain = vi.fn();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(manifest()))
      .mockResolvedValueOnce(response(runtimeSource));
    let websiteBootstrap: Promise<unknown> | undefined;

    const result = await bootstrapIcyLyrics(installedMain, {
      currentVersion: "1.0.0",
      fetch: fetcher,
      digest: async () => runtimeHash,
      execute: () => {
        websiteBootstrap = bootstrapIcyLyrics(websiteMain, {
          currentVersion: "1.0.1",
          globalObject,
          logger: quietLogger(),
        });
      },
      globalObject,
      logger: quietLogger(),
      handoffTimeoutMs: 50,
    });

    expect(result).toBe("remote");
    await expect(websiteBootstrap).resolves.toBe("remote");
    expect(installedMain).not.toHaveBeenCalled();
    expect(websiteMain).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("falls back to the installed runtime after a checksum mismatch", async () => {
    const installedMain = vi.fn();
    const execute = vi.fn();
    const logger = quietLogger();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(manifest()))
      .mockResolvedValueOnce(response(runtimeSource));

    await expect(
      bootstrapIcyLyrics(installedMain, {
        currentVersion: "1.0.0",
        fetch: fetcher,
        digest: async () => "0".repeat(64),
        execute,
        globalObject: {},
        logger,
      })
    ).resolves.toBe("embedded");

    expect(installedMain).toHaveBeenCalledOnce();
    expect(execute).not.toHaveBeenCalled();
    expect(logger.warn).toHaveBeenCalledOnce();
  });

  it("falls back when the update manifest request fails", async () => {
    const installedMain = vi.fn();
    const logger = quietLogger();

    await expect(
      bootstrapIcyLyrics(installedMain, {
        currentVersion: "1.0.0",
        fetch: vi.fn().mockResolvedValue(response({ error: true }, 503)),
        globalObject: {},
        logger,
      })
    ).resolves.toBe("embedded");

    expect(installedMain).toHaveBeenCalledOnce();
    expect(logger.warn).toHaveBeenCalledOnce();
  });

  it("times out a stalled response body and starts the installed runtime", async () => {
    const installedMain = vi.fn();
    let requestSignal: AbortSignal | undefined;
    const stalledResponse = {
      ok: true,
      json: () => new Promise<never>(() => undefined),
    } as unknown as Response;
    const fetcher = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      requestSignal = init?.signal ?? undefined;
      return Promise.resolve(stalledResponse);
    }) as unknown as typeof globalThis.fetch;

    await expect(
      bootstrapIcyLyrics(installedMain, {
        currentVersion: "1.0.0",
        fetch: fetcher,
        globalObject: {},
        logger: quietLogger(),
        manifestTimeoutMs: 10,
      })
    ).resolves.toBe("embedded");

    expect(requestSignal?.aborted).toBe(true);
    expect(installedMain).toHaveBeenCalledOnce();
  });

  it("shares one in-flight check and never initializes twice", async () => {
    const globalObject: Record<string, unknown> = {};
    const firstMain = vi.fn();
    const duplicateMain = vi.fn();
    let releaseManifest!: () => void;
    const manifestGate = new Promise<void>((resolve) => {
      releaseManifest = resolve;
    });
    const fetcher = vi.fn(async () => {
      await manifestGate;
      return response(manifest({ version: "1.0.0" }));
    });

    const first = bootstrapIcyLyrics(firstMain, {
      currentVersion: "1.0.0",
      fetch: fetcher,
      globalObject,
      logger: quietLogger(),
    });
    const duplicate = bootstrapIcyLyrics(duplicateMain, {
      currentVersion: "1.0.0",
      fetch: fetcher,
      globalObject,
      logger: quietLogger(),
    });
    releaseManifest();

    await expect(Promise.all([first, duplicate])).resolves.toEqual(["embedded", "embedded"]);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(firstMain).toHaveBeenCalledOnce();
    expect(duplicateMain).not.toHaveBeenCalled();

    await expect(
      bootstrapIcyLyrics(duplicateMain, {
        currentVersion: "1.0.0",
        fetch: fetcher,
        globalObject,
        logger: quietLogger(),
      })
    ).resolves.toBe("already-running");
    expect(fetcher).toHaveBeenCalledOnce();
    expect(duplicateMain).not.toHaveBeenCalled();
  });

  it("falls back when executed website code never acknowledges its handoff", async () => {
    const globalObject: Record<string, unknown> = {};
    const installedMain = vi.fn();
    const execute = vi.fn();
    const logger = quietLogger();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(manifest()))
      .mockResolvedValueOnce(response(runtimeSource));

    await expect(
      bootstrapIcyLyrics(installedMain, {
        currentVersion: "1.0.0",
        fetch: fetcher,
        digest: async () => runtimeHash,
        execute,
        globalObject,
        logger,
        handoffTimeoutMs: 10,
      })
    ).resolves.toBe("embedded");

    expect(execute).toHaveBeenCalledOnce();
    expect(installedMain).toHaveBeenCalledOnce();
    expect(logger.warn).toHaveBeenCalledOnce();
  });
});
