import React from "react";
import { flushSync } from "react-dom";
import ReactDOM from "react-dom/client";
import { PopupModal } from "../../components/Modal.ts";
import { SETTINGS_KEY } from "../stores.ts";
import { UI_STATE_KEY } from "../uiState.ts";
import {
  buildMigratedData,
  type JsonObject,
  type MigrationOutput,
} from "./DataMigrationTransform.ts";

export { buildMigratedData } from "./DataMigrationTransform.ts";
export type { MigrationInput, MigrationOutput } from "./DataMigrationTransform.ts";

export const DATA_MIGRATION_VERSION = 2;
export const DATA_MIGRATION_MARKER = "IcyLyrics:dataMigrationVersion";

const LEGACY_PREFIXES = ["IcyLyrics-", "SpicyLyrics-"] as const;
const LEGACY_KEYS = [
  "simpleLyricsMode",
  "simpleLyricsModeRenderingType",
  "minimalLyricsMode",
  "lockedMediaBox",
  "viewControlsPosition",
  "developerMode",
  "rememberLocalTTMLFiles",
  "skip-icy-font",
  "skip-spicy-font",
  "skipIcyFont",
  "skipSpicyFont",
  "disablePopupLyrics",
  "staticBackground",
  "staticBackgroundType",
  "hide_npv_bg",
  "IsNowBarOpen",
  "NowBarSide",
  "ForceCompactMode",
  "romanization",
  "lastFetchedUri",
  "sidebar-status",
] as const;

function parseStoredValue(raw: string): any {
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function readJsonObject(key: string): JsonObject | undefined {
  const raw = Spicetify.LocalStorage.get(key);
  if (raw === null || raw === undefined) return undefined;
  const parsed = parseStoredValue(raw);
  return parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)
    ? parsed
    : undefined;
}

function readLegacyPrefix(prefix: (typeof LEGACY_PREFIXES)[number]): JsonObject {
  const values: JsonObject = {};
  for (const key of LEGACY_KEYS) {
    const raw = Spicetify.LocalStorage.get(`${prefix}${key}`);
    if (raw !== null && raw !== undefined) values[key] = parseStoredValue(raw);
  }
  return values;
}

function hasLegacyData(): boolean {
  return LEGACY_PREFIXES.some((prefix) =>
    LEGACY_KEYS.some((key) => Spicetify.LocalStorage.get(`${prefix}${key}`) !== null)
  );
}

function sameJson(left: JsonObject | undefined, right: JsonObject): boolean {
  return JSON.stringify(left ?? {}) === JSON.stringify(right);
}

/** Write both blobs, verify them, then and only then mark migration complete. */
export function executeDataMigration(): MigrationOutput {
  const output = buildMigratedData({
    newSettings: readJsonObject(SETTINGS_KEY),
    newUiState: readJsonObject(UI_STATE_KEY),
    icyLegacy: readLegacyPrefix("IcyLyrics-"),
    spicyLegacy: readLegacyPrefix("SpicyLyrics-"),
  });

  Spicetify.LocalStorage.set(SETTINGS_KEY, JSON.stringify(output.settings));
  Spicetify.LocalStorage.set(UI_STATE_KEY, JSON.stringify(output.uiState));

  if (
    !sameJson(readJsonObject(SETTINGS_KEY), output.settings) ||
    !sameJson(readJsonObject(UI_STATE_KEY), output.uiState)
  ) {
    throw new Error("Spotify did not retain the migrated Icy Lyrics settings.");
  }

  Spicetify.LocalStorage.set(DATA_MIGRATION_MARKER, String(DATA_MIGRATION_VERSION));
  if (Spicetify.LocalStorage.get(DATA_MIGRATION_MARKER) !== String(DATA_MIGRATION_VERSION)) {
    throw new Error("Spotify did not retain the migration marker.");
  }
  return output;
}

export function needsMigration(): boolean {
  const marker = Number(Spicetify.LocalStorage.get(DATA_MIGRATION_MARKER) ?? 0);
  const hasSettings = readJsonObject(SETTINGS_KEY) !== undefined;
  const hasUiState = readJsonObject(UI_STATE_KEY) !== undefined;
  if (marker >= DATA_MIGRATION_VERSION && hasSettings && hasUiState) return false;

  if (!hasLegacyData()) {
    // Fresh installs and partial new blobs are repaired without interrupting startup.
    executeDataMigration();
    return false;
  }
  return true;
}

export function showMigrationModal() {
  const div = document.createElement("div");
  const reactRoot = ReactDOM.createRoot(div);

  function renderMigrate(errorMessage?: string) {
    flushSync(() => {
      reactRoot.render(
        <div className="update-card-wrapper migration-card">
          <div className="udc-icon-wrap" aria-hidden="true">
            <svg className="udc-migrate-svg" viewBox="0 0 24 24" fill="none">
              <ellipse cx="12" cy="5" rx="8" ry="3" stroke="currentColor" strokeWidth="1.75" />
              <path d="M4 5v5c0 1.657 3.582 3 8 3s8-1.343 8-3V5" stroke="currentColor" strokeWidth="1.75" />
              <path d="M4 10v5c0 1.657 3.582 3 8 3" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" />
              <path d="M16 17l2.5 2.5L22 16" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h2 className="uc-title">Settings Migration Required</h2>
          <p className="uc-subtitle udc-desc">
            Icy Lyrics needs to move your existing settings into its 1.0.0 storage format. Your old keys are retained for rollback.
          </p>
          {errorMessage && <p className="uc-subtitle udc-desc">{errorMessage}</p>}
          <div className="uc-divider" />
          <button
            className="btn-update"
            onClick={() => {
              try {
                executeDataMigration();
                renderSuccess();
                setTimeout(() => location.reload(), 1000);
              } catch (error) {
                renderMigrate(error instanceof Error ? error.message : String(error));
              }
            }}
          >
            Migrate Settings
          </button>
        </div>
      );
    });
  }

  function renderSuccess() {
    flushSync(() => {
      reactRoot.render(
        <div className="update-card-wrapper migration-card">
          <div className="udc-icon-wrap" aria-hidden="true">
            <svg className="udc-migrate-svg" viewBox="0 0 24 24" fill="none" style={{ color: "#1db954" }}>
              <path d="M5 13l4 4L19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <h2 className="uc-title">Migration Complete</h2>
          <p className="uc-subtitle udc-desc">Your Icy Lyrics settings were verified. Reloading Spotify…</p>
        </div>
      );
    });
  }

  renderMigrate();
  PopupModal.display({
    title: "Icy Lyrics",
    content: div,
    onClose: () => reactRoot.unmount(),
    closeBtn: false,
    closeOnOutsideClick: false,
  });
}
