import "fake-indexeddb/auto";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { dbPromise, ObjectStores } from "../src/utils/db.ts";
import {
  AUTO_TIMING_CACHE_NAME,
  AUTO_TIMING_MANIFEST_KEY,
  AutoTimingModelRepository,
  canonicalAutoTimingJson,
  validateAutoTimingManifest,
} from "../src/components/ReactComponents/LyricCreator/autoTiming/modelRepository.ts";
import type { AutoTimingManifest } from "../src/components/ReactComponents/LyricCreator/autoTiming/types.ts";

const encoder = new TextEncoder();
const manifestUrl = "https://jackscurrie.com/downloads/auto-timing/manifest.json";

function file(path: string, url: string, bytes: Uint8Array) {
  return { path, url, size: bytes.length, sha256: bytesToHex(sha256(bytes)) };
}

function manifestFixture(modelBytes = encoder.encode("model"), runtimeBytes = encoder.encode("runtime")):
AutoTimingManifest {
  const runtimeFile = file(
    "ort-wasm-simd-threaded.wasm",
    "https://jackscurrie.com/downloads/auto-timing/runtime/ort-wasm-simd-threaded.wasm",
    runtimeBytes
  );
  const fastFile = file(
    "onnx/model.onnx",
    "https://jackscurrie.com/downloads/auto-timing/models/icy/fast/onnx/model.onnx",
    modelBytes
  );
  const accurateFile = file(
    "onnx/model.onnx",
    "https://jackscurrie.com/downloads/auto-timing/models/icy/accurate/onnx/model.onnx",
    modelBytes
  );
  return {
    schemaVersion: 1,
    release: "1.3.0",
    generatedAt: "2026-09-24T00:00:00.000Z",
    remoteHost: "https://jackscurrie.com/",
    remotePathTemplate: "downloads/auto-timing/models/{model}/",
    runtime: {
      version: "test-runtime",
      wasmBaseUrl: "https://jackscurrie.com/downloads/auto-timing/runtime/",
      files: [runtimeFile],
    },
    modules: [
      {
        id: "fast",
        displayName: "Fast",
        description: "Fast test module",
        version: "fast-test",
        modelId: "icy/fast",
        revision: "main",
        dtype: "q4",
        size: fastFile.size,
        files: [fastFile],
      },
      {
        id: "accurate",
        displayName: "Accurate",
        description: "Accurate test module",
        version: "accurate-test",
        modelId: "icy/accurate",
        revision: "main",
        dtype: "q4",
        size: accurateFile.size,
        files: [accurateFile],
      },
    ],
    signature: { algorithm: "Ed25519", keyId: "icy-auto-timing-1", value: "AA==" },
  };
}

class MemoryCache {
  entries = new Map<string, Response>();
  async match(request: RequestInfo | URL) {
    const key = request instanceof Request ? request.url : String(request);
    return this.entries.get(key)?.clone();
  }
  async put(request: RequestInfo | URL, response: Response) {
    const key = request instanceof Request ? request.url : String(request);
    this.entries.set(key, new Response(await response.arrayBuffer(), response));
  }
  async delete(request: RequestInfo | URL) {
    const key = request instanceof Request ? request.url : String(request);
    return this.entries.delete(key);
  }
  async keys() {
    return [...this.entries.keys()].map((url) => new Request(url));
  }
}

class MemoryCacheStorage {
  cache = new MemoryCache();
  async open(name: string) {
    expect(name).toBe(AUTO_TIMING_CACHE_NAME);
    return this.cache;
  }
}

describe.sequential("Auto-time signed module repository", () => {
  beforeEach(async () => {
    await (await dbPromise).clear(ObjectStores.Metadata);
  });
  beforeAll(() => {
    Object.defineProperty(globalThis, "navigator", {
      value: {
        storage: {
          persisted: async () => true,
          persist: async () => true,
          estimate: async () => ({ quota: 1_000_000, usage: 0 }),
        },
      },
      configurable: true,
    });
  });

  it("canonicalizes objects independently of property insertion order", () => {
    expect(canonicalAutoTimingJson({ z: 1, a: { d: 2, c: 3 } })).toBe(
      '{"a":{"c":3,"d":2},"z":1}'
    );
  });

  it("validates the fixed host, checksums, shape, and signature hook", async () => {
    const verifySignature = vi.fn(async () => true);
    const validated = await validateAutoTimingManifest(manifestFixture(), {
      manifestUrl,
      verifySignature,
    });
    expect(validated.modules.map((module) => module.id)).toEqual(["fast", "accurate"]);
    expect(verifySignature).toHaveBeenCalledOnce();

    const offHost = manifestFixture();
    offHost.modules[0].files[0].url = "https://example.test/model.onnx";
    await expect(
      validateAutoTimingManifest(offHost, { manifestUrl, verifySignature })
    ).rejects.toThrow("outside the approved Icy Lyrics download path");

    await expect(
      validateAutoTimingManifest(manifestFixture(), {
        manifestUrl,
        verifySignature: async () => false,
      })
    ).rejects.toThrow("signature is invalid");
  });

  it("assembles chunks, verifies both hashes, registers, and uninstalls a module", async () => {
    const first = encoder.encode("model-");
    const second = encoder.encode("bytes");
    const modelBytes = new Uint8Array(first.length + second.length);
    modelBytes.set(first);
    modelBytes.set(second, first.length);
    const runtimeBytes = encoder.encode("runtime");
    const manifest = manifestFixture(modelBytes, runtimeBytes);
    manifest.modules[0].files[0].parts = [
      {
        url: "https://jackscurrie.com/downloads/auto-timing/chunks/model.part000",
        size: first.length,
        sha256: bytesToHex(sha256(first)),
      },
      {
        url: "https://jackscurrie.com/downloads/auto-timing/chunks/model.part001",
        size: second.length,
        sha256: bytesToHex(sha256(second)),
      },
    ];
    const bodies = new Map<string, Uint8Array>([
      [manifest.runtime.files[0].url, runtimeBytes],
      [manifest.modules[0].files[0].parts[0].url, first],
      [manifest.modules[0].files[0].parts[1].url, second],
    ]);
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === manifestUrl) return Response.json(manifest);
      const bytes = bodies.get(url);
      return bytes
        ? new Response(bytes.slice(), {
            status: 200,
            headers: { "Content-Length": String(bytes.length) },
          })
        : new Response(null, { status: 404 });
    }) as unknown as typeof fetch;
    const cacheStorage = new MemoryCacheStorage();
    const repository = new AutoTimingModelRepository({
      fetchImpl,
      cacheStorage: cacheStorage as unknown as CacheStorage,
      manifestUrl,
      verifySignature: async () => true,
    });
    const progress: number[] = [];

    await repository.install("fast", (value) => progress.push(value.progress));
    expect(await repository.verify("fast")).toBe(true);
    expect((await repository.list()).find((status) => status.definition.id === "fast")).toMatchObject({
      installed: true,
      verified: true,
    });
    expect(progress.at(-1)).toBe(1);
    const assembled = await cacheStorage.cache.match(manifest.modules[0].files[0].url);
    expect(new Uint8Array(await assembled!.arrayBuffer())).toEqual(modelBytes);

    await repository.remove("fast");
    expect(await repository.verify("fast")).toBe(false);
  });

  it("rejects a corrupt chunk without registering a module", async () => {
    const expected = encoder.encode("expected");
    const corrupt = encoder.encode("corrupt!");
    const manifest = manifestFixture(expected);
    const modelFile = manifest.modules[0].files[0];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === manifestUrl) return Response.json(manifest);
      if (url === manifest.runtime.files[0].url) return new Response(encoder.encode("runtime"));
      if (url === modelFile.url) return new Response(corrupt);
      return new Response(null, { status: 404 });
    }) as unknown as typeof fetch;
    const repository = new AutoTimingModelRepository({
      fetchImpl,
      cacheStorage: new MemoryCacheStorage() as unknown as CacheStorage,
      manifestUrl,
      verifySignature: async () => true,
    });

    await expect(repository.install("fast")).rejects.toThrow("integrity verification");
    expect((await repository.list()).find((status) => status.definition.id === "fast")?.installed).toBe(false);
  });

  function setupRepository(initial = manifestFixture()) {
    let current = initial;
    let corrupt = false;
    const cacheStorage = new MemoryCacheStorage();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === manifestUrl) return Response.json(current);
      return new Response(encoder.encode(url.includes("/runtime/") ? "runtime" : corrupt ? "oops!" : "model"));
    }) as unknown as typeof fetch;
    const options = {
      fetchImpl,
      cacheStorage: cacheStorage as unknown as CacheStorage,
      manifestUrl,
      verifySignature: async () => true,
    };
    return {
      repository: new AutoTimingModelRepository(options),
      options,
      cacheStorage,
      fetchImpl,
      changeManifest: (value: AutoTimingManifest) => { current = value; },
      corruptDownloads: () => { corrupt = true; },
    };
  }

  it("keeps signed input unchanged and verifies its original representation", async () => {
    const original = manifestFixture();
    original.modules[0].files[0].url = "/downloads/auto-timing/models/icy/fast/onnx/model.onnx";
    const before = structuredClone(original);
    const { signature: _signature, ...payload } = original;
    await validateAutoTimingManifest(original, {
      manifestUrl,
      verifySignature: async (bytes) => {
        expect(new TextDecoder().decode(bytes)).toBe(canonicalAutoTimingJson(payload));
        return true;
      },
    });
    expect(original).toEqual(before);
  });

  it("memoizes a verified offline manifest until an explicit refresh", async () => {
    const manifest = manifestFixture();
    await (await dbPromise).put(ObjectStores.Metadata, manifest, AUTO_TIMING_MANIFEST_KEY);
    const fetchImpl = vi.fn(async () => { throw new Error("offline"); });
    const repository = new AutoTimingModelRepository({
      manifestUrl, fetchImpl, cacheStorage: new MemoryCacheStorage() as unknown as CacheStorage,
      verifySignature: async () => true,
    });
    await Promise.all([repository.getManifest(), repository.getManifest()]);
    await repository.list();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    await repository.getManifest(true);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("serializes separate repository instances and preserves the remaining shared runtime", async () => {
    const setup = setupRepository();
    const second = new AutoTimingModelRepository(setup.options);
    await Promise.all([setup.repository.install("fast"), second.install("accurate"), setup.repository.remove("fast")]);
    expect((await setup.repository.list()).map(({ installed }) => installed)).toEqual([false, true]);
    expect(await second.verify("accurate")).toBe(true);
    await second.remove("accurate");
    expect(setup.cacheStorage.cache.entries.size).toBe(0);
  });

  it("retains a working old release if its update fails, then removes it without a manifest", async () => {
    const setup = setupRepository();
    await setup.repository.install("fast");
    const updated = manifestFixture();
    updated.modules[0].version = "new-version";
    updated.modules[0].modelId = "icy/fast/new-version";
    updated.modules[0].files[0].url = updated.modules[0].files[0].url.replace("/fast/", "/fast/new-version/");
    setup.changeManifest(updated);
    await setup.repository.getManifest(true);
    expect((await setup.repository.list())[0]).toMatchObject({ installed: true, updateAvailable: true, verified: false });
    setup.corruptDownloads();
    await expect(setup.repository.install("fast")).rejects.toThrow("integrity verification");
    expect(await setup.cacheStorage.cache.match(manifestFixture().modules[0].files[0].url)).toBeDefined();
    const offline = new AutoTimingModelRepository({ ...setup.options, fetchImpl: async () => { throw new Error("offline"); } });
    await (await dbPromise).delete(ObjectStores.Metadata, AUTO_TIMING_MANIFEST_KEY);
    await offline.remove("fast");
    expect(setup.cacheStorage.cache.entries.size).toBe(0);
  });

  it("recognizes runtime-only updates and prunes obsolete assets after success", async () => {
    const setup = setupRepository();
    await setup.repository.install("fast");
    const updated = manifestFixture();
    updated.runtime.version = "runtime-v2";
    updated.runtime.wasmBaseUrl = "https://jackscurrie.com/downloads/auto-timing/runtime/v2/";
    updated.runtime.files[0].url = updated.runtime.files[0].url.replace("/runtime/", "/runtime/v2/");
    setup.changeManifest(updated);
    await setup.repository.getManifest(true);
    expect((await setup.repository.list())[0]).toMatchObject({ installed: true, updateAvailable: true, verified: false });
    await setup.repository.install("fast");
    expect((await setup.repository.list())[0]).toMatchObject({ installed: true, updateAvailable: false, verified: true });
    expect(await setup.cacheStorage.cache.match(manifestFixture().runtime.files[0].url)).toBeUndefined();
  });

  it("rejects corrupt cached bytes before returning executable runtime files", async () => {
    const setup = setupRepository();
    await setup.repository.install("fast");
    await setup.cacheStorage.cache.put(manifestFixture().runtime.files[0].url, new Response("changed"));
    await expect(setup.repository.getInstalledDefinition("fast")).rejects.toThrow("failed integrity verification");
    await setup.repository.install("fast");
    await expect(setup.repository.getInstalledDefinition("fast")).resolves.toHaveProperty("definition.id", "fast");
  });

  it("accepts compressed HTTP transfer sizes while verifying decoded bytes", async () => {
    const manifest = manifestFixture();
    const repository = new AutoTimingModelRepository({
      manifestUrl, cacheStorage: new MemoryCacheStorage() as unknown as CacheStorage,
      verifySignature: async () => true,
      fetchImpl: async (input) => String(input) === manifestUrl ? Response.json(manifest) : new Response(
        String(input).includes("/runtime/") ? "runtime" : "model",
        // Cross-origin CORS often exposes Content-Length but not Content-Encoding.
        { headers: { "content-length": "100" } }
      ),
    });
    await repository.install("fast");
    expect(await repository.verify("fast")).toBe(true);
  });

  it("cancels a queued install without deleting the successful shared module", async () => {
    const setup = setupRepository();
    const cancellation = new AbortController();
    const installingFast = setup.repository.install("fast");
    const installingAccurate = setup.repository.install("accurate", undefined, cancellation.signal);
    cancellation.abort();
    await installingFast;
    await expect(installingAccurate).rejects.toMatchObject({ name: "AbortError" });
    expect(await setup.repository.verify("fast")).toBe(true);
    expect((await setup.repository.list())[1].installed).toBe(false);
  });

  it("rejects oversized data before caching or registering it", async () => {
    const fixture = manifestFixture();
    const cacheStorage = new MemoryCacheStorage();
    const repository = new AutoTimingModelRepository({
      manifestUrl, cacheStorage: cacheStorage as unknown as CacheStorage,
      verifySignature: async () => true,
      fetchImpl: async (input) => String(input) === manifestUrl ? Response.json(fixture) : new Response("oversized-download"),
    });
    await expect(repository.install("fast")).rejects.toThrow("exceeded its signed download size");
    expect(cacheStorage.cache.entries.size).toBe(0);
    expect((await repository.list())[0].installed).toBe(false);
  });

  it.each([
    ["network or CORS", async () => { throw new TypeError("Failed to fetch"); }, "Could not reach Auto-time downloads on jackscurrie.com"],
    ["unpublished download", async () => new Response(null, { status: 404 }), "required download may not have been published yet"],
    ["denied public access", async () => new Response(null, { status: 403 }), "These downloads should be public"],
    ["rate limiting", async () => new Response(null, { status: 429 }), "Wait a moment, then try again"],
    ["timeout", async () => { throw new DOMException("raw timeout detail", "TimeoutError"); }, "took too long to download"],
    ["HTML instead of manifest", async () => new Response("<html>internal host page</html>"), "returned an invalid model list"],
  ])("explains a manifest %s failure without exposing raw browser errors", async (_label, fetchImpl, message) => {
    const repository = new AutoTimingModelRepository({
      manifestUrl,
      cacheStorage: new MemoryCacheStorage() as unknown as CacheStorage,
      fetchImpl: fetchImpl as typeof fetch,
      verifySignature: async () => true,
    });
    await expect(repository.getManifest()).rejects.toThrow(message);
  });

  it("preserves cancellation instead of treating it as an offline fallback", async () => {
    await (await dbPromise).put(ObjectStores.Metadata, manifestFixture(), AUTO_TIMING_MANIFEST_KEY);
    const aborted = new DOMException("Cancelled", "AbortError");
    const repository = new AutoTimingModelRepository({
      manifestUrl,
      cacheStorage: new MemoryCacheStorage() as unknown as CacheStorage,
      fetchImpl: async () => { throw aborted; },
      verifySignature: async () => true,
    });
    await expect(repository.getManifest()).rejects.toBe(aborted);
  });

  it.each(["missing", "network", "corrupt", "interrupted"] as const)(
    "retains the %s download error when Chromium Cache.put hides the stream's cause",
    async (failure) => {
      class BrowserLikeCache extends MemoryCache {
        override async put(request: RequestInfo | URL, response: Response) {
          try {
            return await super.put(request, response);
          } catch {
            throw new TypeError("Failed to fetch");
          }
        }
      }
      const fixture = manifestFixture();
      const cacheStorage = new MemoryCacheStorage();
      cacheStorage.cache = new BrowserLikeCache();
      const repository = new AutoTimingModelRepository({
        manifestUrl,
        cacheStorage: cacheStorage as unknown as CacheStorage,
        verifySignature: async () => true,
        fetchImpl: async (input) => {
          if (String(input) === manifestUrl) return Response.json(fixture);
          if (String(input).includes("/runtime/")) return new Response("runtime");
          if (failure === "missing") return new Response(null, { status: 404 });
          if (failure === "network") throw new TypeError("Failed to fetch");
          if (failure === "interrupted") {
            return new Response(new ReadableStream({ start: (controller) => controller.error(new TypeError("network stream interrupted")) }));
          }
          return new Response("wrong");
        },
      });
      const expected = failure === "missing" ? "required download may not have been published yet"
        : failure === "corrupt" ? "failed part integrity verification"
        : "Could not reach Auto-time downloads on jackscurrie.com";
      await expect(repository.install("fast")).rejects.toThrow(expected);
      expect(cacheStorage.cache.entries.size).toBe(0);
      expect((await repository.list())[0].installed).toBe(false);
    }
  );
});
