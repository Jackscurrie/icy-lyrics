import { gt as isNewerVersion, valid as validVersion } from "semver";

import { ProjectVersion } from "../../project/config.ts";

export const ICY_UPDATE_MANIFEST_URL = "https://jackscurrie.com/downloads/icy-lyrics-update.json";

const UPDATE_STATE_KEY = "__ICY_LYRICS_UPDATE_STATE__";
const MANIFEST_TIMEOUT_MS = 2_000;
const RUNTIME_TIMEOUT_MS = 12_000;
const HANDOFF_TIMEOUT_MS = 2_000;
const MAX_RUNTIME_BYTES = 8 * 1024 * 1024;

type UpdatePhase = "idle" | "checking" | "handoff" | "running";
type BootstrapResult = "embedded" | "remote" | "already-running";

interface StartupUpdateState {
  phase: UpdatePhase;
  targetVersion?: string;
  runningVersion?: string;
  inFlight?: Promise<BootstrapResult>;
  acknowledgement?: Promise<void>;
  acknowledge?: () => void;
  startup?: Promise<void>;
}

export interface IcyUpdateManifest {
  schemaVersion: 1;
  version: string;
  runtimeUrl: string;
  sha256: string;
}

export interface StartupUpdateDependencies {
  currentVersion?: string;
  fetch?: typeof globalThis.fetch;
  digest?: (source: string) => Promise<string>;
  execute?: (source: string, sourceUrl: string) => void | Promise<void>;
  timeoutMs?: number;
  manifestTimeoutMs?: number;
  runtimeTimeoutMs?: number;
  handoffTimeoutMs?: number;
  globalObject?: Record<string, unknown>;
  logger?: Pick<Console, "info" | "warn">;
}

const normalizeHash = (value: string) => value.trim().toLowerCase();

function updateState(globalObject: Record<string, unknown>): StartupUpdateState {
  const existing = globalObject[UPDATE_STATE_KEY];
  if (
    existing &&
    typeof existing === "object" &&
    ["idle", "checking", "handoff", "running"].includes(
      String((existing as StartupUpdateState).phase)
    )
  ) {
    return existing as StartupUpdateState;
  }

  const created: StartupUpdateState = { phase: "idle" };
  globalObject[UPDATE_STATE_KEY] = created;
  return created;
}

// `execute()` runs another copy of this bundle, which mutates the shared state
// asynchronously. Keeping the live read behind a helper avoids TypeScript
// incorrectly treating the earlier `handoff` assignment as immutable.
function isRuntimeRunning(state: StartupUpdateState, version: string) {
  return state.phase === "running" && state.runningVersion === version;
}

export function validateUpdateManifest(value: unknown): IcyUpdateManifest {
  if (!value || typeof value !== "object") {
    throw new TypeError("The Icy Lyrics update manifest is not an object.");
  }

  const manifest = value as Partial<IcyUpdateManifest>;
  if (manifest.schemaVersion !== 1) {
    throw new TypeError("The Icy Lyrics update manifest schema is unsupported.");
  }
  if (typeof manifest.version !== "string" || !validVersion(manifest.version)) {
    throw new TypeError("The Icy Lyrics update manifest has an invalid version.");
  }
  if (typeof manifest.runtimeUrl !== "string") {
    throw new TypeError("The Icy Lyrics update manifest has no runtime URL.");
  }

  const runtimeUrl = new URL(manifest.runtimeUrl, ICY_UPDATE_MANIFEST_URL);
  const trustedOrigin = new URL(ICY_UPDATE_MANIFEST_URL).origin;
  if (runtimeUrl.protocol !== "https:" || runtimeUrl.origin !== trustedOrigin) {
    throw new TypeError("The Icy Lyrics runtime must come from jackscurrie.com over HTTPS.");
  }

  if (typeof manifest.sha256 !== "string" || !/^[a-f\d]{64}$/i.test(manifest.sha256)) {
    throw new TypeError("The Icy Lyrics update manifest has an invalid checksum.");
  }

  return {
    schemaVersion: 1,
    version: manifest.version,
    runtimeUrl: runtimeUrl.href,
    sha256: normalizeHash(manifest.sha256),
  };
}

export function shouldLoadRemoteVersion(currentVersion: string, candidateVersion: string) {
  return Boolean(
    validVersion(currentVersion) &&
    validVersion(candidateVersion) &&
    isNewerVersion(candidateVersion, currentVersion)
  );
}

export async function sha256Hex(source: string) {
  const encoded = new TextEncoder().encode(source);
  const digest = await globalThis.crypto.subtle.digest("SHA-256", encoded);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function fetchAndReadWithDeadline<T>(
  fetcher: typeof globalThis.fetch,
  url: string,
  timeoutMs: number,
  read: (response: Response) => Promise<T>
): Promise<T> {
  const controller = new AbortController();
  let timeout: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timeout = setTimeout(() => {
      controller.abort();
      reject(new DOMException(`Request timed out after ${timeoutMs}ms`, "TimeoutError"));
    }, timeoutMs);
  });
  const request = (async () => {
    const response = await fetcher(url, {
      cache: "no-store",
      credentials: "omit",
      signal: controller.signal,
    });
    return read(response);
  })();

  try {
    return await Promise.race([request, deadline]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

function assertTrustedRuntimeResponse(response: Response) {
  if (!response.url) return;
  const responseUrl = new URL(response.url);
  const trustedOrigin = new URL(ICY_UPDATE_MANIFEST_URL).origin;
  if (responseUrl.protocol !== "https:" || responseUrl.origin !== trustedOrigin) {
    throw new Error("The Icy Lyrics runtime redirected outside jackscurrie.com.");
  }
}

async function waitForAcknowledgement(acknowledgement: Promise<void>, timeoutMs: number) {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timeout = setTimeout(
      () => reject(new Error("The website runtime did not acknowledge its startup handoff.")),
      timeoutMs
    );
  });
  try {
    await Promise.race([acknowledgement, deadline]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

function executeRemoteRuntime(source: string, sourceUrl: string) {
  // Spicetify already evaluates local extension bundles. Running the verified
  // website bundle through the same global JavaScript path lets an installed
  // bootstrap update without writing to the user's profile from the browser.
  const execute = new Function(`${source}\n//# sourceURL=${sourceUrl}`);
  execute();
}

export async function tryRunStartupUpdate(dependencies: StartupUpdateDependencies = {}) {
  const fetcher = dependencies.fetch ?? globalThis.fetch;
  const digest = dependencies.digest ?? sha256Hex;
  const execute = dependencies.execute ?? executeRemoteRuntime;
  const currentVersion = dependencies.currentVersion ?? ProjectVersion;
  const manifestTimeoutMs =
    dependencies.manifestTimeoutMs ?? dependencies.timeoutMs ?? MANIFEST_TIMEOUT_MS;
  const runtimeTimeoutMs =
    dependencies.runtimeTimeoutMs ?? dependencies.timeoutMs ?? RUNTIME_TIMEOUT_MS;
  const handoffTimeoutMs = dependencies.handoffTimeoutMs ?? HANDOFF_TIMEOUT_MS;
  const globalObject = dependencies.globalObject ?? (globalThis as Record<string, unknown>);
  const logger = dependencies.logger ?? console;
  const state = updateState(globalObject);

  if (state.phase === "running") return false;
  if (state.phase === "idle") state.phase = "checking";

  const manifest = validateUpdateManifest(
    await fetchAndReadWithDeadline(
      fetcher,
      ICY_UPDATE_MANIFEST_URL,
      manifestTimeoutMs,
      async (response) => {
        if (!response.ok) {
          throw new Error(`Update manifest request failed (${response.status}).`);
        }
        return response.json();
      }
    )
  );
  if (!shouldLoadRemoteVersion(currentVersion, manifest.version)) return false;

  const source = await fetchAndReadWithDeadline(
    fetcher,
    manifest.runtimeUrl,
    runtimeTimeoutMs,
    async (response) => {
      if (!response.ok) {
        throw new Error(`Update runtime request failed (${response.status}).`);
      }
      assertTrustedRuntimeResponse(response);
      const declaredLength = Number(response.headers.get("content-length") ?? 0);
      if (declaredLength > MAX_RUNTIME_BYTES) {
        throw new Error("The Icy Lyrics update runtime is unexpectedly large.");
      }
      const runtimeSource = await response.text();
      if (new TextEncoder().encode(runtimeSource).byteLength > MAX_RUNTIME_BYTES) {
        throw new Error("The Icy Lyrics update runtime is unexpectedly large.");
      }
      return runtimeSource;
    }
  );

  const actualHash = normalizeHash(await digest(source));
  if (actualHash !== manifest.sha256) {
    throw new Error("The Icy Lyrics update runtime failed its integrity check.");
  }

  let acknowledge: (() => void) | undefined;
  const acknowledgement = new Promise<void>((resolve) => {
    acknowledge = resolve;
  });
  state.phase = "handoff";
  state.targetVersion = manifest.version;
  state.acknowledgement = acknowledgement;
  state.acknowledge = acknowledge;

  try {
    await execute(source, manifest.runtimeUrl);
    await waitForAcknowledgement(acknowledgement, handoffTimeoutMs);
    if (!isRuntimeRunning(state, manifest.version)) {
      throw new Error("The website runtime did not complete its startup handoff.");
    }
  } catch (error) {
    // If the new runtime already consumed the handoff, never start a second
    // embedded copy underneath it. Otherwise the installed build may fall back.
    const websiteRuntimeIsRunning = isRuntimeRunning(state, manifest.version);
    if (!websiteRuntimeIsRunning) {
      state.phase = "checking";
      state.targetVersion = undefined;
      state.acknowledgement = undefined;
      state.acknowledge = undefined;
      throw error;
    }
  }

  state.targetVersion = undefined;
  state.acknowledgement = undefined;
  state.acknowledge = undefined;
  logger.info(`[Icy Lyrics] Started website update ${manifest.version}.`);
  return true;
}

export async function bootstrapIcyLyrics(
  startEmbeddedRuntime: () => void | Promise<void>,
  dependencies: StartupUpdateDependencies = {}
): Promise<BootstrapResult> {
  const globalObject = dependencies.globalObject ?? (globalThis as Record<string, unknown>);
  const currentVersion = dependencies.currentVersion ?? ProjectVersion;
  const logger = dependencies.logger ?? console;
  const state = updateState(globalObject);

  // Only a bundle whose public version matches the pending website version may
  // consume this one-shot handoff. A concurrent old bootstrap waits below.
  if (state.phase === "handoff" && state.targetVersion === currentVersion) {
    state.phase = "running";
    state.runningVersion = currentVersion;
    try {
      state.startup = Promise.resolve(startEmbeddedRuntime());
    } catch (error) {
      state.phase = "idle";
      state.runningVersion = undefined;
      throw error;
    }
    state.acknowledge?.();
    try {
      await state.startup;
      return "remote";
    } catch (error) {
      // The handoff was consumed, so the installed runtime must not start
      // underneath a partially initialized website runtime. Resetting the
      // state lets a later clean evaluation retry instead of being stuck.
      if (state.runningVersion === currentVersion) {
        state.phase = "idle";
        state.runningVersion = undefined;
      }
      throw error;
    }
  }

  if (state.phase === "running") return "already-running";
  if (state.phase === "checking" || state.phase === "handoff") {
    return state.inFlight ?? "already-running";
  }

  state.phase = "checking";
  const operation = (async (): Promise<BootstrapResult> => {
    try {
      if (await tryRunStartupUpdate({ ...dependencies, globalObject })) {
        return "remote";
      }
    } catch (error) {
      logger.warn("[Icy Lyrics] Startup update check failed; using the installed build.", error);
    }

    if (state.phase === "running") return "already-running";
    state.phase = "running";
    state.runningVersion = currentVersion;
    try {
      state.startup = Promise.resolve(startEmbeddedRuntime());
      await state.startup;
      return "embedded";
    } catch (error) {
      state.phase = "idle";
      state.runningVersion = undefined;
      throw error;
    }
  })();
  state.inFlight = operation;

  try {
    return await operation;
  } finally {
    state.inFlight = undefined;
  }
}
