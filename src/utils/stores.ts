import { atom } from "nanostores";

export const SETTINGS_KEY = "IcyLyrics:settings";

function localStorageGet(key: string): string | null {
  if (typeof Spicetify === "undefined" || !Spicetify.LocalStorage) return null;
  return Spicetify.LocalStorage.get(key);
}

function localStorageSet(key: string, value: string): void {
  if (typeof Spicetify === "undefined" || !Spicetify.LocalStorage) return;
  Spicetify.LocalStorage.set(key, value);
}

function readSettingsBlob(): Record<string, any> {
  const raw = localStorageGet(SETTINGS_KEY);
  if (raw === null || raw === undefined) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

function saveSettingsBlob(obj: Record<string, any>) {
  localStorageSet(SETTINGS_KEY, JSON.stringify(obj));
}

function readLegacyBoolean(keys: string[], fallback: boolean): boolean {
  for (const key of keys) {
    const raw = localStorageGet(key);
    if (raw === null || raw === undefined) continue;
    if (raw === "true" || raw === "1") return true;
    if (raw === "false" || raw === "0") return false;
    try {
      const parsed = JSON.parse(raw);
      if (typeof parsed === "boolean") return parsed;
    } catch {
      // Continue to the next fallback key.
    }
  }
  return fallback;
}

function migrateSettingsKeys(blob: Record<string, any>): Record<string, any> {
  const renames: Record<string, string> = {
    show_npv_dynamic_bg: "showNpvDynamicBg",
  };
  let changed = false;
  if (blob.skipIcyFont === undefined) {
    const legacyFontValue =
      blob["skip-icy-font"] ?? blob.skipSpicyFont ?? blob["skip-spicy-font"];
    if (legacyFontValue !== undefined) {
      blob.skipIcyFont = legacyFontValue;
      changed = true;
    }
  }
  for (const key of ["skip-icy-font", "skipSpicyFont", "skip-spicy-font"]) {
    if (key in blob) {
      delete blob[key];
      changed = true;
    }
  }
  for (const [oldKey, newKey] of Object.entries(renames)) {
    if (oldKey in blob) {
      blob[newKey] = blob[oldKey];
      delete blob[oldKey];
      changed = true;
    }
  }
  if (changed) saveSettingsBlob(blob);
  return blob;
}

const _settings: Record<string, any> = migrateSettingsKeys(readSettingsBlob());

/**
 * An atom backed by the settings blob. Exported so feature modules (e.g.
 * `experiments.ts`) can register their own persisted settings without having to
 * add a line here for every one.
 */
export function persistAtom<T>(key: string, defaultValue: T) {
  const store = atom<T>(_settings[key] !== undefined ? _settings[key] : defaultValue);
  store.listen((v) => {
    _settings[key] = v;
    saveSettingsBlob(_settings);
  });
  return store;
}

// Setting atoms (persisted)
export const $staticBackgroundMode = persistAtom<string>("staticBackgroundMode", "off");
// Blur radius (px) applied to image-based static backgrounds — not the "color" mode.
export const $staticBackgroundBlur = persistAtom<number>("staticBackgroundBlur", 0);
export const $simpleLyricsMode = persistAtom<boolean>("simpleLyricsMode", false);
export const $simpleLyricsModeRenderingType = persistAtom<string>(
  "simpleLyricsModeRenderingType",
  "calculate"
);
export const $minimalLyricsMode = persistAtom<boolean>("minimalLyricsMode", false);
// Tinted box drawn behind a lyrics line while the pointer is over it.
export const $lineHoverBackground = persistAtom<boolean>("lineHoverBackground", true);
export const $skipIcyFont = persistAtom<boolean>("skipIcyFont", false);
export const $showNpvDynamicBg = persistAtom<boolean>("showNpvDynamicBg", true);
// Never inject the lyrics card into the Now Playing sidebar at all.
export const $disableNpvLyrics = persistAtom<boolean>("disableNpvLyrics", false);
// Pull the whole NPV lyrics card out of the sidebar while the current track has
// no lyrics, instead of leaving it up showing the "no lyrics" notice.
export const $hideNpvLyricsWhenUnavailable = persistAtom<boolean>(
  "hideNpvLyricsWhenUnavailable",
  true
);
export const $lockedMediaBox = persistAtom<boolean>("lockedMediaBox", false);
// $popupLyricsAllowed: stored as actual boolean "popupLyricsAllowed" in the settings blob.
export const $popupLyricsAllowed = (() => {
  const initial: boolean =
    _settings["popupLyricsAllowed"] !== undefined ? _settings["popupLyricsAllowed"] : true;
  const store = atom<boolean>(initial);
  store.listen((v) => {
    _settings["popupLyricsAllowed"] = v;
    saveSettingsBlob(_settings);
  });
  return store;
})();
export const $viewControlsPosition = persistAtom<string>("viewControlsPosition", "Top");
export const $ttmlMakerMode = persistAtom<boolean>("ttmlMakerMode", true);
export const $developerMode = persistAtom<boolean>("developerMode", false);
export const $timelineOutsideMediaContent = persistAtom<boolean>(
  "timelineOutsideMediaContent",
  true
);
// Volume band below the playback controls in Fullscreen / Cinema View / Popup Lyrics.
export const $showVolumeSlider = persistAtom<boolean>("showVolumeSlider", true);
// Playback timing offset in milliseconds (bipolar: negative = earlier, positive = later)
export const $playbackOffset = persistAtom<number>("playbackOffset", 0);

// Durable local TTML and fullscreen feature settings included in Icy Lyrics 1.0.0.
// The old per-key Icy setting wins over a Spicy fallback during migration.
export const $useLocalTtmlLyrics = persistAtom<boolean>(
  "useLocalTtmlLyrics",
  readLegacyBoolean(
    [
      "IcyLyrics-rememberLocalTTMLFiles",
      "SpicyLyrics-rememberLocalTTMLFiles",
    ],
    true
  )
);
export const $fullscreenRevealMode = persistAtom<boolean>("fullscreenRevealMode", false);
export const $fullscreenOutroAnimation = persistAtom<boolean>(
  "fullscreenOutroAnimation",
  false
);
export const $fullscreenBackgroundBlurEnabled = persistAtom<boolean>(
  "fullscreenBackgroundBlurEnabled",
  true
);

// Runtime (ephemeral) atoms
export const $currentLyricsType = atom<string>("None");
export const $lyricsContainerExists = atom<boolean>(false);
export const $currentlyFetching = atom<boolean>(false);
export const $currentLyricsData = atom<string>("");
