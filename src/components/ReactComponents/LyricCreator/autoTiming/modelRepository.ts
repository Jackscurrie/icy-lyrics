import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { AutoTimingModelManifestUrl } from "../../../../../project/config.ts";
import { dbPromise, ensurePersistence, ObjectStores } from "../../../../utils/db.ts";
import { autoTimingAssetContentType } from "./runtimeResponse.ts";
import type {
  AutoTimingInstallProgress,
  AutoTimingManifest,
  AutoTimingManifestPayload,
  AutoTimingModelId,
  AutoTimingModuleDefinition,
  AutoTimingModuleFile,
  AutoTimingModuleStatus,
} from "./types.ts";

export const AUTO_TIMING_CACHE_NAME = "icylyrics-auto-timing-models-v1";
export const AUTO_TIMING_REGISTRY_KEY = "creatorAutoTimingModels";
export const AUTO_TIMING_MANIFEST_KEY = "creatorAutoTimingManifest";
export const AUTO_TIMING_MANIFEST_PUBLIC_KEY =
  "6g6ErCPwqR2YqkHQ1iNmGz5bENhjxY9TIekaiUNeBGI=";

interface AutoTimingRegistry {
  schemaVersion: 1;
  runtimeVersion: string | null;
  modules: Partial<Record<AutoTimingModelId, {
    version: string;
    installedAt: number;
    runtimeVersion?: string;
    /** Inventory survives manifest updates so old installs can still be removed. */
    files?: string[];
  }>>;
}

// Settings and Creator share storage, including across Spotify windows. Serialize
// mutation/verification so cancelling an install cannot delete another install's
// shared runtime. Web Locks coordinates windows; the queue also covers tests and
// older embedded browsers without Web Locks.
let mutationQueue: Promise<unknown> = Promise.resolve();
function withRepositoryLock<T>(operation: () => Promise<T>): Promise<T> {
  const run = async (): Promise<T> => globalThis.navigator?.locks
    ? await navigator.locks.request("icylyrics-auto-timing-models", operation)
    : await operation();
  const result = mutationQueue.then(run, run);
  mutationQueue = result.catch(() => undefined);
  return result;
}

interface RepositoryDependencies {
  fetchImpl?: typeof fetch;
  cacheStorage?: CacheStorage;
  manifestUrl?: string;
  publicKeyBase64?: string;
  verifySignature?: (payload: Uint8Array, signature: Uint8Array) => Promise<boolean>;
}

function defaultRegistry(): AutoTimingRegistry {
  return { schemaVersion: 1, runtimeVersion: null, modules: {} };
}

function base64Bytes(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export function canonicalAutoTimingJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalAutoTimingJson).join(",")}]`;
  const record = value as Record<string, unknown>;
  return `{${Object.keys(record)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalAutoTimingJson(record[key])}`)
    .join(",")}}`;
}

async function verifyEd25519(
  payload: Uint8Array,
  signature: Uint8Array,
  publicKeyBase64: string
): Promise<boolean> {
  const key = await crypto.subtle.importKey(
    "raw",
    base64Bytes(publicKeyBase64).buffer as ArrayBuffer,
    { name: "Ed25519" },
    false,
    ["verify"]
  );
  return crypto.subtle.verify(
    { name: "Ed25519" },
    key,
    signature.buffer as ArrayBuffer,
    payload.buffer as ArrayBuffer
  );
}

function manifestPayload(manifest: AutoTimingManifest): AutoTimingManifestPayload {
  const { signature: _signature, ...payload } = manifest;
  return payload;
}

function assertString(value: unknown, label: string): asserts value is string {
  if (typeof value !== "string" || !value.trim()) throw new TypeError(`${label} is missing.`);
}

function validateFile(
  input: unknown,
  manifestUrl: URL,
  allowedOrigin: string
): AutoTimingModuleFile {
  if (!input || typeof input !== "object") throw new TypeError("A model file is invalid.");
  const value = input as Partial<AutoTimingModuleFile>;
  assertString(value.path, "Model file path");
  if (!/^[a-z\d_.-]+(?:\/[a-z\d_.-]+)*$/iu.test(value.path) || value.path.split("/").some((part) => part === "." || part === "..")) {
    throw new TypeError("A model file path is invalid.");
  }
  assertString(value.url, "Model file URL");
  assertString(value.sha256, "Model file checksum");
  if (!/^[a-f\d]{64}$/iu.test(value.sha256)) throw new TypeError("A model checksum is invalid.");
  if (!Number.isSafeInteger(value.size) || value.size! <= 0 || value.size! > 1_000_000_000) {
    throw new TypeError("A model file size is invalid.");
  }
  const url = new URL(value.url, manifestUrl);
  if (url.origin !== allowedOrigin || url.username || url.password || url.search || url.hash || !url.pathname.startsWith("/downloads/auto-timing/")) {
    throw new TypeError("A model file points outside the approved Icy Lyrics download path.");
  }
  if (value.parts !== undefined && (!Array.isArray(value.parts) || !value.parts.length)) {
    throw new TypeError("A model file's chunks are invalid.");
  }
  const parts = value.parts?.map((part) => {
    if (!part || typeof part !== "object") throw new TypeError("A model file part is invalid.");
    assertString(part.url, "Model file part URL");
    assertString(part.sha256, "Model file part checksum");
    if (!/^[a-f\d]{64}$/iu.test(part.sha256)) {
      throw new TypeError("A model file part checksum is invalid.");
    }
    if (!Number.isSafeInteger(part.size) || part.size <= 0 || part.size > 24_000_000) {
      throw new TypeError("A model file part size is invalid.");
    }
    const partUrl = new URL(part.url, manifestUrl);
    if (partUrl.origin !== allowedOrigin || partUrl.username || partUrl.password || partUrl.search || partUrl.hash || !partUrl.pathname.startsWith("/downloads/auto-timing/")) {
      throw new TypeError("A model file part points outside the approved Icy Lyrics download path.");
    }
    return { url: partUrl.href, size: part.size, sha256: part.sha256.toLowerCase() };
  });
  if (parts && parts.reduce((sum, part) => sum + part.size, 0) !== value.size) {
    throw new TypeError("A chunked model file size is invalid.");
  }
  return {
    path: value.path,
    url: url.href,
    size: value.size,
    sha256: value.sha256.toLowerCase(),
    ...(parts?.length ? { parts } : {}),
  };
}

export async function validateAutoTimingManifest(
  input: unknown,
  options: {
    manifestUrl: string;
    publicKeyBase64?: string;
    verifySignature?: (payload: Uint8Array, signature: Uint8Array) => Promise<boolean>;
  }
): Promise<AutoTimingManifest> {
  if (!input || typeof input !== "object") throw new TypeError("The model manifest is invalid.");
  // Never normalize the signed input in place. Its original representation is
  // what the publisher signed, and what we retain for offline verification.
  const original = input as AutoTimingManifest;
  const manifest = structuredClone(original);
  if (manifest.schemaVersion !== 1) throw new TypeError("The model manifest version is unsupported.");
  assertString(manifest.release, "Model release");
  assertString(manifest.generatedAt, "Model manifest date");
  if (!Number.isFinite(Date.parse(manifest.generatedAt))) {
    throw new TypeError("The model manifest date is invalid.");
  }
  const manifestUrl = new URL(options.manifestUrl);
  const production = manifestUrl.hostname === "jackscurrie.com";
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(manifestUrl.hostname);
  if ((!production && !loopback) || (production && manifestUrl.protocol !== "https:") || !["https:", "http:"].includes(manifestUrl.protocol)) {
    throw new TypeError("The model manifest host or protocol is not approved.");
  }
  const allowedOrigin = manifestUrl.origin;
  const remoteHost = new URL(manifest.remoteHost, manifestUrl);
  if (remoteHost.origin !== allowedOrigin) throw new TypeError("The model host is not approved.");
  assertString(manifest.remotePathTemplate, "Model path template");
  if (!/^downloads\/auto-timing\/models\/[a-z\d_./{}-]+\/$/iu.test(manifest.remotePathTemplate) || !manifest.remotePathTemplate.includes("{model}") || manifest.remotePathTemplate.includes("..")) {
    throw new TypeError("The model path template is invalid.");
  }
  if (!manifest.runtime || typeof manifest.runtime !== "object") {
    throw new TypeError("The model runtime is missing.");
  }
  assertString(manifest.runtime.version, "Model runtime version");
  const wasmBaseUrl = new URL(manifest.runtime.wasmBaseUrl, manifestUrl);
  if (wasmBaseUrl.origin !== allowedOrigin || !wasmBaseUrl.pathname.startsWith("/downloads/auto-timing/runtime/")) throw new TypeError("The runtime host is not approved.");
  manifest.runtime.wasmBaseUrl = wasmBaseUrl.href;
  manifest.runtime.files = (manifest.runtime.files ?? []).map((file) =>
    validateFile(file, manifestUrl, allowedOrigin)
  );
  if (!manifest.runtime.files.length) throw new TypeError("The model runtime files are missing.");
  const runtimePaths = new Set<string>();
  for (const file of manifest.runtime.files) {
    if (runtimePaths.has(file.path) || file.url !== new URL(file.path, wasmBaseUrl).href) {
      throw new TypeError("A runtime file is duplicated or does not match its declared path.");
    }
    runtimePaths.add(file.path);
  }
  if (!Array.isArray(manifest.modules) || manifest.modules.length !== 2) {
    throw new TypeError("The model manifest must describe Fast and Accurate modules.");
  }
  const ids = new Set<AutoTimingModelId>();
  manifest.modules = manifest.modules.map((module) => {
    if (!module || typeof module !== "object") throw new TypeError("A model module is invalid.");
    if (module.id !== "fast" && module.id !== "accurate") {
      throw new TypeError("A model module ID is invalid.");
    }
    if (ids.has(module.id)) throw new TypeError("A model module is duplicated.");
    ids.add(module.id);
    assertString(module.displayName, "Model display name");
    assertString(module.description, "Model description");
    assertString(module.version, "Model version");
    assertString(module.modelId, "Model ID");
    assertString(module.revision, "Model revision");
    if (!/^[a-z\d_-]+(?:\/[a-z\d_-]+)*$/iu.test(module.modelId) || !/^[a-z\d_.-]+$/iu.test(module.revision) || module.revision === "." || module.revision === "..") {
      throw new TypeError("The model identity is invalid.");
    }
    if (module.dtype !== "q4") throw new TypeError("The model data type is unsupported.");
    const files = (module.files ?? []).map((file) =>
      validateFile(file, manifestUrl, allowedOrigin)
    );
    const size = files.reduce((sum, file) => sum + file.size, 0);
    if (!files.length || size !== module.size) throw new TypeError(`The ${module.displayName} size is invalid.`);
    const paths = new Set<string>();
    for (const file of files) {
      if (paths.has(file.path)) throw new TypeError("A model file is duplicated.");
      paths.add(file.path);
      const base = manifest.remotePathTemplate.replace("{model}", module.modelId).replace("{revision}", module.revision);
      if (file.url !== new URL(`${base}${file.path}`, remoteHost).href) {
        throw new TypeError("A model file does not match its declared model path.");
      }
    }
    return { ...module, files };
  });
  if (!ids.has("fast") || !ids.has("accurate")) {
    throw new TypeError("The model manifest is incomplete.");
  }
  if (
    !manifest.signature ||
    manifest.signature.algorithm !== "Ed25519" ||
    manifest.signature.keyId !== "icy-auto-timing-1"
  ) {
    throw new TypeError("The model manifest signature metadata is invalid.");
  }
  const payload = new TextEncoder().encode(canonicalAutoTimingJson(manifestPayload(original)));
  const signature = base64Bytes(manifest.signature.value);
  const valid = options.verifySignature
    ? await options.verifySignature(payload, signature)
    : await verifyEd25519(
        payload,
        signature,
        options.publicKeyBase64 ?? AUTO_TIMING_MANIFEST_PUBLIC_KEY
      );
  if (!valid) throw new TypeError("The model manifest signature is invalid.");
  return manifest;
}

async function readRegistry(): Promise<AutoTimingRegistry> {
  try {
    const value = await (await dbPromise).get(ObjectStores.Metadata, AUTO_TIMING_REGISTRY_KEY);
    if (
      value &&
      typeof value === "object" &&
      (value as AutoTimingRegistry).schemaVersion === 1 &&
      (value as AutoTimingRegistry).modules &&
      typeof (value as AutoTimingRegistry).modules === "object"
    ) {
      const input = value as AutoTimingRegistry;
      const registry = defaultRegistry();
      registry.runtimeVersion = typeof input.runtimeVersion === "string" ? input.runtimeVersion : null;
      for (const id of ["fast", "accurate"] as const) {
        const record = input.modules[id];
        if (record && typeof record.version === "string" && Number.isFinite(record.installedAt)) {
          registry.modules[id] = {
            version: record.version,
            installedAt: record.installedAt,
            ...(typeof record.runtimeVersion === "string" ? { runtimeVersion: record.runtimeVersion } : {}),
            ...(Array.isArray(record.files) && record.files.every((url) => typeof url === "string") ? { files: record.files } : {}),
          };
        }
      }
      return registry;
    }
  } catch {
    // A missing/corrupt registry simply means no optional modules are installed.
  }
  return defaultRegistry();
}

async function writeRegistry(registry: AutoTimingRegistry): Promise<void> {
  await (await dbPromise).put(ObjectStores.Metadata, registry, AUTO_TIMING_REGISTRY_KEY);
}

async function hashResponse(response: Response, signal?: AbortSignal): Promise<{ hex: string; size: number }> {
  signal?.throwIfAborted();
  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    return { hex: bytesToHex(sha256(bytes)), size: bytes.length };
  }
  const reader = response.body.getReader();
  const hash = sha256.create();
  let size = 0;
  const cancel = () => { void reader.cancel(signal?.reason).catch(() => undefined); };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      signal?.throwIfAborted();
      const { done, value } = await reader.read();
      if (done) break;
      hash.update(value);
      size += value.byteLength;
    }
    signal?.throwIfAborted();
  } finally {
    signal?.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
  return { hex: bytesToHex(hash.digest()), size };
}

function cacheRequest(file: AutoTimingModuleFile): Request {
  return new Request(file.url, { method: "GET", mode: "cors", credentials: "omit" });
}

function requestFailure(error: unknown, label: string, url: string): Error {
  if (error instanceof Error && error.name === "AbortError") return error;
  if (error instanceof Error && error.name === "TimeoutError") {
    const timeout = new Error(`${label} took too long to download. Please try again.`, { cause: error });
    timeout.name = "TimeoutError";
    return timeout;
  }
  // Fetch deliberately does not distinguish offline, CORS, DNS and blocked
  // redirects. Avoid claiming that we know which of these caused the failure.
  return new Error(
    `Could not reach Auto-time downloads on ${new URL(url).hostname}. Check your connection; if the website loads, download access from Spotify may be blocked.`,
    { cause: error }
  );
}

function httpFailure(response: Response, label: string, url: string): Error {
  const host = new URL(url).hostname;
  if (response.status === 404 || response.status === 410) {
    return new Error(`${label} is not available on ${host} (${response.status}). The required download may not have been published yet.`);
  }
  if (response.status === 401 || response.status === 403) {
    return new Error(`${host} denied access to ${label.toLowerCase()} (${response.status}). These downloads should be public; please try again later.`);
  }
  if (response.status === 429) {
    return new Error(`${host} is limiting Auto-time downloads (429). Wait a moment, then try again.`);
  }
  return new Error(`${label} could not be downloaded from ${host} (${response.status}). Please try again later.`);
}

export class AutoTimingModelRepository {
  private readonly fetchImpl: typeof fetch;
  private readonly cacheStorage: CacheStorage;
  private readonly manifestUrl: string;
  private readonly publicKeyBase64: string;
  private readonly verifySignatureOverride?: RepositoryDependencies["verifySignature"];
  private manifestPromise: Promise<AutoTimingManifest> | null = null;

  constructor(dependencies: RepositoryDependencies = {}) {
    this.fetchImpl = dependencies.fetchImpl ?? fetch.bind(globalThis);
    this.cacheStorage = dependencies.cacheStorage ?? globalThis.caches;
    this.manifestUrl =
      dependencies.manifestUrl ??
      (typeof __ILdev__m !== "undefined" && __ILdev__m
        ? "http://localhost:3000/downloads/auto-timing/manifest.local.json"
        : AutoTimingModelManifestUrl);
    this.publicKeyBase64 = dependencies.publicKeyBase64 ?? AUTO_TIMING_MANIFEST_PUBLIC_KEY;
    this.verifySignatureOverride = dependencies.verifySignature;
  }

  async init(): Promise<void> {
    await ensurePersistence();
    await this.getManifest();
  }

  private async request(url: string, options: RequestInit, label: string): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetchImpl(url, options);
    } catch (error) {
      const reason: unknown = options.signal?.aborted ? options.signal.reason : error;
      if (!(reason instanceof Error && reason.name === "TimeoutError")) options.signal?.throwIfAborted();
      throw requestFailure(reason, label, url);
    }
    if (!response.ok) throw httpFailure(response, label, url);
    return response;
  }

  async getManifest(force = false): Promise<AutoTimingManifest> {
    if (force) this.manifestPromise = null;
    this.manifestPromise ??= (async () => {
      const response = await this.request(this.manifestUrl, {
        cache: "no-cache",
        redirect: "error",
        credentials: "omit",
        headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      }, "The Auto-time model list");
      let raw: unknown;
      try {
        raw = await response.json();
      } catch (error) {
        if (error instanceof Error && error.name === "AbortError") throw error;
        if (error instanceof SyntaxError) {
          throw new Error("The Icy Lyrics download service returned an invalid model list. Please try again later.", { cause: error });
        }
        throw requestFailure(error, "The Auto-time model list", this.manifestUrl);
      }
      const manifest = await validateAutoTimingManifest(raw, {
        manifestUrl: this.manifestUrl,
        publicKeyBase64: this.publicKeyBase64,
        verifySignature: this.verifySignatureOverride,
      });
      await (await dbPromise).put(ObjectStores.Metadata, raw, AUTO_TIMING_MANIFEST_KEY);
      return manifest;
    })().catch(async (error: unknown) => {
      if (error instanceof Error && error.name === "AbortError") throw error;
      const cached = await (await dbPromise).get(ObjectStores.Metadata, AUTO_TIMING_MANIFEST_KEY);
      if (cached) {
        return validateAutoTimingManifest(cached, {
          manifestUrl: this.manifestUrl,
          publicKeyBase64: this.publicKeyBase64,
          verifySignature: this.verifySignatureOverride,
        });
      }
      throw error;
    });
    try {
      return await this.manifestPromise;
    } catch (error) {
      this.manifestPromise = null;
      throw error;
    }
  }

  async list(): Promise<AutoTimingModuleStatus[]> {
    const [manifest, registry] = await Promise.all([this.getManifest(), readRegistry()]);
    const cache = await this.cacheStorage.open(AUTO_TIMING_CACHE_NAME);
    return Promise.all(
      manifest.modules.map(async (definition) => {
        const record = registry.modules[definition.id];
        const required = [...manifest.runtime.files, ...definition.files];
        const installedFiles = record?.files ?? required.map((file) => file.url);
        const present = (
          await Promise.all(installedFiles.map((url) => cache.match(url)))
        ).every(Boolean);
        const installed = Boolean(record && present);
        const current = record?.version === definition.version &&
          (record.runtimeVersion ?? registry.runtimeVersion) === manifest.runtime.version &&
          required.every((file) => installedFiles.includes(file.url)) && required.length === installedFiles.length;
        return {
          definition,
          installed,
          verified: installed && current,
          installedVersion: installed ? record?.version ?? null : null,
          updateAvailable: installed && !current,
        };
      })
    );
  }

  async install(
    id: AutoTimingModelId,
    onProgress?: (progress: AutoTimingInstallProgress) => void,
    signal?: AbortSignal
  ): Promise<void> {
    return withRepositoryLock(() => this.installUnlocked(id, onProgress, signal));
  }

  private async installUnlocked(
    id: AutoTimingModelId,
    onProgress?: (progress: AutoTimingInstallProgress) => void,
    signal?: AbortSignal
  ): Promise<void> {
    signal?.throwIfAborted();
    const manifest = await this.getManifest();
    const definition = manifest.modules.find((module) => module.id === id);
    if (!definition) throw new Error(`Unknown automatic timing module: ${id}`);
    const files = [...manifest.runtime.files, ...definition.files];
    const total = files.reduce((sum, file) => sum + file.size, 0);
    const cache = await this.cacheStorage.open(AUTO_TIMING_CACHE_NAME);
    const cachedFiles = await Promise.all(
      files.map(async (file) => ({ file, present: Boolean(await cache.match(cacheRequest(file))) }))
    );
    const downloadBytes = cachedFiles.reduce(
      (sum, entry) => sum + (entry.present ? 0 : entry.file.size),
      0
    );
    const estimate = await navigator.storage?.estimate?.();
    if (
      estimate?.quota !== undefined &&
      estimate.usage !== undefined &&
      estimate.quota - estimate.usage < downloadBytes * 1.05
    ) {
      throw new Error("There is not enough browser storage to install this timing module.");
    }
    const newlyCached: Request[] = [];
    const downloadController = new AbortController();
    const abortDownload = () => downloadController.abort(signal?.reason);
    signal?.addEventListener("abort", abortDownload, { once: true });
    let completed = 0;
    try {
      for (const file of files) {
        if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
        const request = cacheRequest(file);
        const existing = await cache.match(request);
        if (existing) {
          const checked = await hashResponse(existing, signal);
          if (checked.size === file.size && checked.hex === file.sha256) {
            completed += file.size;
            onProgress?.({
              moduleId: id,
              file: file.path,
              loaded: completed,
              total,
              progress: completed / total,
            });
            continue;
          }
          await cache.delete(request);
        }
        const sources = file.parts?.length
          ? file.parts
          : [{ url: file.url, size: file.size, sha256: file.sha256 }];
        const fullHash = sha256.create();
        let fileLoaded = 0;
        let downloadFailure: unknown;
        const stream = new ReadableStream<Uint8Array>({
          start: async (controller) => {
            try {
              for (const source of sources) {
                downloadController.signal.throwIfAborted();
                const response = await this.request(source.url, {
                  redirect: "error",
                  credentials: "omit",
                  signal: downloadController.signal,
                }, `The ${definition.displayName} timing module`);
                // Verify decoded stream length, never HTTP Content-Length. CDNs
                // compress these files and Content-Encoding may be hidden by CORS.
                if (!response.body) throw new Error(`${file.path} returned an empty download.`);
                const partHash = sha256.create();
                let partLoaded = 0;
                const reader = response.body.getReader();
                while (true) {
                  downloadController.signal.throwIfAborted();
                  let result: ReadableStreamReadResult<Uint8Array>;
                  try {
                    result = await reader.read();
                  } catch (error) {
                    downloadController.signal.throwIfAborted();
                    throw requestFailure(error, `The ${definition.displayName} timing module`, source.url);
                  }
                  const { done, value } = result;
                  if (done) break;
                  partHash.update(value);
                  fullHash.update(value);
                  partLoaded += value.byteLength;
                  fileLoaded += value.byteLength;
                  if (partLoaded > source.size || fileLoaded > file.size) {
                    await reader.cancel();
                    throw new Error(`${file.path} exceeded its signed download size.`);
                  }
                  controller.enqueue(value);
                  onProgress?.({
                    moduleId: id,
                    file: file.path,
                    loaded: completed + fileLoaded,
                    total,
                    progress: (completed + fileLoaded) / total,
                  });
                }
                if (partLoaded !== source.size || bytesToHex(partHash.digest()) !== source.sha256) {
                  throw new Error(`${file.path} failed part integrity verification.`);
                }
              }
              if (fileLoaded !== file.size || bytesToHex(fullHash.digest()) !== file.sha256) {
                throw new Error(`${file.path} failed integrity verification.`);
              }
              controller.close();
            } catch (error) {
              downloadFailure = error;
              controller.error(error);
            }
          },
          cancel: () => downloadController.abort(),
        });
        try {
          await cache.put(
            request,
            new Response(stream, {
              status: 200,
              headers: {
                "Content-Type": autoTimingAssetContentType(file.path),
                "Content-Length": String(file.size),
              },
            })
          );
        } catch (error) {
          signal?.throwIfAborted();
          if (error instanceof Error && error.name === "QuotaExceededError") {
            throw new Error("There is not enough browser storage to install this timing module.", { cause: error });
          }
          // Chromium may replace a response-stream failure with "Failed to
          // fetch" inside Cache.put. Keep the actionable original error.
          if (downloadFailure !== undefined) throw downloadFailure;
          throw new Error("The timing module could not be saved on this device. Please try again.", { cause: error });
        }
        newlyCached.push(request);
        completed += file.size;
        onProgress?.({
          moduleId: id,
          file: file.path,
          loaded: completed,
          total,
          progress: completed / total,
        });
      }
      signal?.throwIfAborted();
      const registry = await readRegistry();
      const replacedFiles = registry.modules[id]?.files ?? [];
      registry.runtimeVersion = manifest.runtime.version;
      registry.modules[id] = {
        version: definition.version,
        installedAt: Date.now(),
        runtimeVersion: manifest.runtime.version,
        files: files.map((file) => file.url),
      };
      await writeRegistry(registry);
      // Commit first; a failed update keeps the previous installation intact.
      const retained = new Set(Object.values(registry.modules).flatMap((record) => record?.files ?? []));
      const legacySharesRuntime = Object.values(registry.modules).some((record) => !record?.files);
      await Promise.all(replacedFiles.filter((url) => !retained.has(url) && !(legacySharesRuntime && new URL(url).pathname.startsWith("/downloads/auto-timing/runtime/"))).map((url) => cache.delete(url).catch(() => false)));
    } catch (error) {
      downloadController.abort();
      await Promise.all(newlyCached.map((request) => cache.delete(request)));
      throw error;
    } finally {
      signal?.removeEventListener("abort", abortDownload);
    }
  }

  async verify(id: AutoTimingModelId): Promise<boolean> {
    return withRepositoryLock(() => this.verifyUnlocked(id));
  }

  private async verifyUnlocked(id: AutoTimingModelId): Promise<boolean> {
    const manifest = await this.getManifest();
    const definition = manifest.modules.find((module) => module.id === id);
    if (!definition) return false;
    const cache = await this.cacheStorage.open(AUTO_TIMING_CACHE_NAME);
    for (const file of [...manifest.runtime.files, ...definition.files]) {
      const response = await cache.match(cacheRequest(file));
      if (!response) return false;
      const checked = await hashResponse(response);
      if (checked.size !== file.size || checked.hex !== file.sha256) return false;
    }
    return true;
  }

  async remove(id: AutoTimingModelId): Promise<void> {
    return withRepositoryLock(() => this.removeUnlocked(id));
  }

  private async removeUnlocked(id: AutoTimingModelId): Promise<void> {
    const cache = await this.cacheStorage.open(AUTO_TIMING_CACHE_NAME);
    const registry = await readRegistry();
    const removed = registry.modules[id];
    delete registry.modules[id];
    const empty = Object.keys(registry.modules).length === 0;
    if (empty) registry.runtimeVersion = null;
    // Removing modules must work offline even if the manifest disappeared.
    // Prefix cleanup also migrates inventories created before file tracking.
    const retained = new Set(Object.values(registry.modules).flatMap((record) => record?.files ?? []));
    const legacySharesRuntime = Object.values(registry.modules).some((record) => !record?.files);
    const removable = new Set(removed?.files ?? []);
    const requests = await cache.keys();
    const deletionTargets = requests.filter((request) => empty ||
      (!retained.has(request.url) && !(legacySharesRuntime && new URL(request.url).pathname.startsWith("/downloads/auto-timing/runtime/")) && (removable.has(request.url) || new URL(request.url).pathname.startsWith(`/downloads/auto-timing/models/icy/${id}/`))));
    await writeRegistry(registry);
    await Promise.all(deletionTargets.map((request) => cache.delete(request)));
  }

  async getInstalledDefinition(id: AutoTimingModelId): Promise<{
    manifest: AutoTimingManifest;
    definition: AutoTimingModuleDefinition;
  }> {
    const [manifest, statuses] = await Promise.all([this.getManifest(), this.list()]);
    const status = statuses.find((candidate) => candidate.definition.id === id);
    if (!status?.installed || !status.verified) {
      throw new Error(`${status?.definition.displayName ?? id} is not installed or needs an update.`);
    }
    if (!await this.verify(id)) {
      throw new Error(`${status.definition.displayName} failed integrity verification. Reinstall the timing module in Settings.`);
    }
    return { manifest, definition: status.definition };
  }
}

export const autoTimingModelRepository = new AutoTimingModelRepository();
