import { describe, expect, it } from "vitest";
import {
  pageBelongsToHost,
  requireConnectedPageHost,
  resolveSpotifyPageHost,
} from "../src/utils/SpotifyPageHost.ts";

class FakeElement {
  isConnected = true;
  parentElement: FakeElement | null = null;
  readonly selectors = new Map<string, FakeElement>();
  readonly lookups: string[] = [];

  querySelector<T>(selector: string): T | null {
    this.lookups.push(selector);
    return (this.selectors.get(selector) as T | undefined) ?? null;
  }
  contains(element: FakeElement): boolean {
    let current: FakeElement | null = element;
    while (current) {
      if (current === this) return true;
      current = current.parentElement;
    }
    return false;
  }
}

const element = (value: FakeElement) => value as unknown as HTMLElement;
const root = (value: FakeElement) => value as unknown as ParentNode;

function setup() {
  const doc = new FakeElement();
  const container = new FakeElement();
  doc.selectors.set(".main-view-container", container);
  return { doc, container };
}

describe("Spotify main-view host compatibility", () => {
  it("resolves the observed stable content parent without the retired Root__main-view class", () => {
    const { doc, container } = setup();
    const host = new FakeElement();
    const content = new FakeElement();
    host.parentElement = container;
    content.parentElement = host;
    container.selectors.set(".main-view-container__scroll-node-child", content);
    expect(resolveSpotifyPageHost(root(doc))).toBe(host);
    expect(doc.lookups).toEqual([".main-view-container"]);
  });

  it("prefers the actual content parent to an unrelated nested carousel viewport", () => {
    const { doc, container } = setup();
    const host = new FakeElement();
    const content = new FakeElement();
    host.parentElement = container;
    content.parentElement = host;
    container.selectors.set(".main-view-container__scroll-node-child", content);
    container.selectors.set("[data-overlayscrollbars-viewport]", new FakeElement());
    expect(resolveSpotifyPageHost(root(doc))).toBe(host);
  });

  it.each([
    "[data-overlayscrollbars-viewport]",
    ".main-view-container__scroll-node",
    ".os-host",
    ".uGZUPBPcDpzSYqKcQT8r > div",
  ])("keeps the container-scoped %s fallback", (selector) => {
    const { doc, container } = setup();
    const host = new FakeElement();
    container.selectors.set(selector, host);
    expect(resolveSpotifyPageHost(root(doc))).toBe(host);
  });

  it("never falls back to an arbitrary main element or a global viewport", () => {
    const doc = new FakeElement();
    doc.selectors.set("main", new FakeElement());
    doc.selectors.set("[data-overlayscrollbars-viewport]", new FakeElement());
    expect(resolveSpotifyPageHost(root(doc))).toBeNull();
    expect(doc.lookups).toEqual([".main-view-container"]);
  });

  it("returns no host for an empty or disconnected Spotify container", () => {
    const { doc, container } = setup();
    expect(resolveSpotifyPageHost(root(doc))).toBeNull();
    container.isConnected = false;
    container.selectors.set(".os-host", new FakeElement());
    expect(resolveSpotifyPageHost(root(doc))).toBeNull();
  });

  it("rejects missing/detached hosts before a caller can allocate its page", () => {
    expect(() => requireConnectedPageHost(null)).toThrow(/not ready/);
    const host = new FakeElement();
    host.isConnected = false;
    expect(() => requireConnectedPageHost(element(host))).toThrow(/not ready/);
    host.isConnected = true;
    expect(requireConnectedPageHost(element(host))).toBe(host);
  });

  it("only reports readiness for a connected page owned by the current host", () => {
    const host = new FakeElement();
    const page = new FakeElement();
    page.parentElement = host;
    expect(pageBelongsToHost(element(page), element(host))).toBe(true);
    expect(pageBelongsToHost(element(page), element(new FakeElement()))).toBe(false);
    page.isConnected = false;
    expect(pageBelongsToHost(element(page), element(host))).toBe(false);
    expect(pageBelongsToHost(null, element(host))).toBe(false);
  });
});
