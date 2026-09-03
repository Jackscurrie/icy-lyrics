export type JsonObject = Record<string, any>;

export interface MigrationInput {
  newSettings?: JsonObject;
  newUiState?: JsonObject;
  icyLegacy?: JsonObject;
  spicyLegacy?: JsonObject;
}

export interface MigrationOutput {
  settings: JsonObject;
  uiState: JsonObject;
}

function staticBackgroundMode(source: JsonObject): string | undefined {
  if (typeof source.staticBackgroundMode === "string") return source.staticBackgroundMode;
  if (source.staticBackground === undefined && source.staticBackgroundType === undefined) {
    return undefined;
  }
  if (!source.staticBackground) return "off";
  return (
    {
      Auto: "auto",
      "Artist Header Visual": "artistHeader",
      "Cover Art": "coverArt",
      Color: "color",
    } as Record<string, string>
  )[source.staticBackgroundType] ?? "auto";
}

function legacySource(source: JsonObject): MigrationOutput {
  const settings: JsonObject = {};
  const uiState: JsonObject = {};
  for (const key of [
    "simpleLyricsMode",
    "simpleLyricsModeRenderingType",
    "minimalLyricsMode",
    "lockedMediaBox",
    "viewControlsPosition",
    "developerMode",
  ]) {
    if (source[key] !== undefined) settings[key] = source[key];
  }

  const font =
    source.skipIcyFont ??
    source["skip-icy-font"] ??
    source.skipSpicyFont ??
    source["skip-spicy-font"];
  if (font !== undefined) settings.skipIcyFont = font;
  if (source.rememberLocalTTMLFiles !== undefined) {
    settings.useLocalTtmlLyrics = source.rememberLocalTTMLFiles;
  }
  if (source.disablePopupLyrics !== undefined) {
    settings.popupLyricsAllowed = !source.disablePopupLyrics;
  }
  if (source.hide_npv_bg !== undefined) settings.showNpvDynamicBg = !source.hide_npv_bg;
  const background = staticBackgroundMode(source);
  if (background !== undefined) settings.staticBackgroundMode = background;

  if (source.NowBarSide !== undefined) uiState.nowBarSide = source.NowBarSide;
  if (source.ForceCompactMode !== undefined) uiState.forceCompactMode = source.ForceCompactMode;
  if (source.romanization !== undefined) uiState.romanization = source.romanization;
  if (source.lastFetchedUri !== undefined) uiState.lastFetchedUri = source.lastFetchedUri;

  const sidebarStatus = source["sidebar-status"];
  if (sidebarStatus === "open" || sidebarStatus === "closed") {
    const open = sidebarStatus === "open";
    uiState.npvLyricsOpen = open;
    uiState.npvLyricsExpanded = open;
  } else if (typeof source.IsNowBarOpen === "boolean") {
    uiState.npvLyricsOpen = source.IsNowBarOpen;
    uiState.npvLyricsExpanded = source.IsNowBarOpen;
  }
  return { settings, uiState };
}

function currentSettings(source: JsonObject): JsonObject {
  const output = { ...source };
  const font =
    output.skipIcyFont ??
    output["skip-icy-font"] ??
    output.skipSpicyFont ??
    output["skip-spicy-font"];
  if (font !== undefined) output.skipIcyFont = font;
  delete output["skip-icy-font"];
  delete output["skip-spicy-font"];
  delete output.skipSpicyFont;
  delete output.settingsOnTop;
  return output;
}

function currentUiState(source: JsonObject): JsonObject {
  const output = { ...source };
  if (output.nowBarSide === undefined && output.NowBarSide !== undefined) {
    output.nowBarSide = output.NowBarSide;
  }
  if (output.forceCompactMode === undefined && output.ForceCompactMode !== undefined) {
    output.forceCompactMode = output.ForceCompactMode;
  }
  delete output.NowBarSide;
  delete output.ForceCompactMode;
  delete output.fromVersion;
  delete output.previousVersion;
  delete output["previous-version"];
  return output;
}

/** Existing new Icy blob > Icy per-key values > Spicy fallback values. */
export function buildMigratedData(input: MigrationInput): MigrationOutput {
  const spicy = legacySource(input.spicyLegacy ?? {});
  const icy = legacySource(input.icyLegacy ?? {});
  return {
    settings: {
      ...spicy.settings,
      ...icy.settings,
      ...currentSettings(input.newSettings ?? {}),
    },
    uiState: {
      ...spicy.uiState,
      ...icy.uiState,
      ...currentUiState(input.newUiState ?? {}),
    },
  };
}
