import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import postcss from "postcss";

const source = readFileSync(new URL("../src/css/default.css", import.meta.url), "utf8");
const css = postcss.parse(source);
const nativeRouteMarker = /main-view-container__scroll-node-child|> header|> \.main-topBar-container/;

describe("Icy route CSS isolation", () => {
  it("cannot hide native route content or headers when no Icy page is mounted", () => {
    const hidingSelectors: string[] = [];
    css.walkRules((rule) => {
      const hides = rule.nodes.some((node) =>
        node.type === "decl" && node.prop === "display" && node.value === "none"
      );
      if (!hides) return;
      for (const selector of rule.selectors) {
        if (nativeRouteMarker.test(selector)) hidingSelectors.push(selector);
      }
    });
    expect(hidingSelectors).toHaveLength(4);
    for (const selector of hidingSelectors) {
      // Every native hiding rule positively requires an Icy-owned main-view
      // container, and excludes an ancestor containing the page itself.
      expect(
        selector.includes(".main-view-container:has(#IcyLyricsPage)") ||
        selector.includes(":has(> .main-view-container #IcyLyricsPage)")
      ).toBe(true);
      expect(selector).toMatch(/:not\(:has\(#IcyLyricsPage\)\)$/);
      expect(selector).not.toContain(".Root__main-view");
    }
  });

  it("does not nest has selectors, which Chromium rejects", () => {
    css.walkRules((rule) => {
      if (!nativeRouteMarker.test(rule.selector)) return;
      expect(rule.selector).not.toMatch(/:has\([^)]*:has\(/);
    });
  });

  it("keeps height repair scoped to the active Icy host and fixes the viewport selector", () => {
    const hostRules: string[] = [];
    css.walkRules((rule) => {
      if (!rule.selector.includes("[data-overlayscrollbars-viewport]")) return;
      if (!rule.nodes.some((node) => node.type === "decl" && node.prop === "height")) return;
      hostRules.push(...rule.selectors);
    });
    expect(hostRules).toContain(
      ".main-view-container:has(#IcyLyricsPage) [data-overlayscrollbars-viewport]:has(#IcyLyricsPage)"
    );
    expect(hostRules.every((selector) => selector.includes("#IcyLyricsPage"))).toBe(true);
    expect(source).not.toContain(".div[data-overlayscrollbars-viewport]");
  });
});
