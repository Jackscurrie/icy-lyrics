import { describe, expect, it } from "vitest";
import { getSpotifyPlaybar, getSpotifyPlaybarControls } from "../src/utils/SpotifyPlaybarHost";

class Node {
  parentElement: Node | null = null;
  selectors = new Map<string, Node>();
  querySelector<T>(selector: string): T | null { return (this.selectors.get(selector) as T) ?? null; }
  contains(node: Node): boolean {
    for (let current: Node | null = node; current; current = current.parentElement) if (current === this) return true;
    return false;
  }
}
const root = (node: Node) => node as unknown as ParentNode;
describe("Spotify playbar hosts", () => {
  it("prefers the native test ID to the legacy root", () => {
    const doc = new Node(), bar = new Node();
    doc.selectors.set('[data-testid="now-playing-bar"]', bar);
    doc.selectors.set('.Root__now-playing-bar', new Node());
    expect(getSpotifyPlaybar(root(doc))).toBe(bar);
  });
  it.each(['[data-testid="fullscreen-mode-button"]', '[data-testid="lyrics-button"]'])("uses %s's row when native class names change", (selector) => {
    const doc = new Node(), bar = new Node(), row = new Node(), button = new Node();
    doc.selectors.set('[data-testid="now-playing-bar"]', bar);
    bar.selectors.set(selector, button);
    row.parentElement = bar; button.parentElement = row;
    expect(getSpotifyPlaybarControls(root(doc))).toBe(row);
  });
  it("preserves the existing mapped row and rejects unrelated fullscreen controls", () => {
    const doc = new Node(), bar = new Node(), row = new Node();
    doc.selectors.set('.Root__now-playing-bar', bar);
    bar.selectors.set('.main-nowPlayingBar-extraControls', row);
    expect(getSpotifyPlaybarControls(root(doc))).toBe(row);
    bar.selectors.clear();
    doc.selectors.set('[data-testid="fullscreen-mode-button"]', new Node());
    expect(getSpotifyPlaybarControls(root(doc))).toBeNull();
  });
  it("does not mistake the bar itself for the controls row", () => {
    const doc = new Node(), bar = new Node(), button = new Node();
    doc.selectors.set('[data-testid="now-playing-bar"]', bar);
    bar.selectors.set('[data-testid="fullscreen-mode-button"]', button);
    button.parentElement = bar;
    expect(getSpotifyPlaybarControls(root(doc))).toBeNull();
  });
});
