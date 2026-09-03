import { describe, expect, it } from "vitest";
import { buildMigratedData } from "../src/utils/migration/DataMigrationTransform.ts";

describe("settings migration transform", () => {
  it("uses new Icy values over Icy per-key values over Spicy fallback values", () => {
    const output = buildMigratedData({
      spicyLegacy: {
        minimalLyricsMode: false,
        "skip-spicy-font": false,
        "sidebar-status": "closed",
      },
      icyLegacy: {
        minimalLyricsMode: true,
        "skip-icy-font": true,
        "sidebar-status": "open",
      },
      newSettings: { minimalLyricsMode: false },
      newUiState: { npvLyricsExpanded: false },
    });

    expect(output.settings.minimalLyricsMode).toBe(false);
    expect(output.settings.skipIcyFont).toBe(true);
    expect(output.uiState.npvLyricsOpen).toBe(true);
    expect(output.uiState.npvLyricsExpanded).toBe(false);
  });

  it("canonicalizes aliases and retires update-only state", () => {
    const output = buildMigratedData({
      newSettings: { skipSpicyFont: true, settingsOnTop: true },
      newUiState: { fromVersion: "5.22.3", previousVersion: "5.22.3" },
    });
    expect(output.settings).toMatchObject({ skipIcyFont: true });
    expect(output.settings).not.toHaveProperty("skipSpicyFont");
    expect(output.settings).not.toHaveProperty("settingsOnTop");
    expect(output.uiState).not.toHaveProperty("fromVersion");
    expect(output.uiState).not.toHaveProperty("previousVersion");
  });
});
