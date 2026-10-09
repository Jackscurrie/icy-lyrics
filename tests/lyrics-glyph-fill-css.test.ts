import { readFileSync } from "node:fs";
import postcss from "postcss";
import { describe, expect, it } from "vitest";

const css = postcss.parse(readFileSync(new URL("../src/css/Lyrics/Mixed.css", import.meta.url), "utf8"));
const root = "#IcyLyricsPage.IcyRenderer .LyricsContainer .LyricsContent";

function gradientSelectors(): string[] {
  const selectors: string[] = [];
  css.walkRules((rule) => {
    if (!rule.nodes.some((node) => node.type === "decl" &&
        node.prop === "background-image" && node.value.startsWith("linear-gradient("))) return;
    selectors.push(...rule.selectors);
  });
  return selectors;
}

describe("single-owner lyric glyph fills", () => {
  it("never paints active child glyphs a second time through their parent row", () => {
    const selectors = gradientSelectors();
    expect(selectors).not.toContain(`${root} .line.Active`);
    expect(selectors).toContain(`${root} .line.Active:not(:has(.word, .letter))`);
    expect(selectors.filter((selector) => selector.endsWith(".line.Active"))).toEqual([]);
  });

  it("keeps the animated word and emphasized letter gradients", () => {
    const selectors = gradientSelectors();
    expect(selectors).toContain(`${root} .line.Active .word`);
    expect(selectors).toContain(`${root} .line.Active .letter`);
  });

  it("preserves plain line/static text and applies equally to every renderer host", () => {
    const selectors = gradientSelectors();
    expect(selectors).toContain(`${root} .line.static`);
    expect(selectors.every((selector) => selector.startsWith(root))).toBe(true);
    expect(selectors.some((selector) => /Fullscreen|Preview|NPV|pip/i.test(selector))).toBe(false);
  });

  it.each(["Line", "Syllable"])("covers intro and between-line dots in the %s renderer", (type) => {
    const source = readFileSync(new URL(`../src/utils/Lyrics/Applyer/Synced/${type}.ts`, import.meta.url), "utf8");
    // Both constructors (intro + interlude) give every dot the word class.
    // The parent :has(.word) exclusion therefore also covers nested dotGroup
    // children, while the existing .line.Active .word rule owns their fill.
    const dotDeclarations = [...source.matchAll(/\b(musicalDots[123])\.classList\.add\("dot"\)/g)];
    const ownedDotDeclarations = [...source.matchAll(/\b(musicalDots[123])\.classList\.add\("word"\);\s*\1\.classList\.add\("dot"\)/g)];
    expect(dotDeclarations).toHaveLength(6);
    expect(ownedDotDeclarations).toHaveLength(dotDeclarations.length);
    expect(gradientSelectors()).toContain(`${root} .line.Active:not(:has(.word, .letter))`);
    expect(gradientSelectors()).toContain(`${root} .line.Active .word`);
  });
});
