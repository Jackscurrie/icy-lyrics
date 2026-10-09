/** Resolve only Spotify's main-view container; never borrow an unrelated <main>. */
export function resolveSpotifyPageHost(root: ParentNode = document): HTMLElement | null {
  const container = root.querySelector<HTMLElement>(".main-view-container");
  if (!container?.isConnected) return null;

  // This stable content marker survives the hashed outer/root class names.
  // Prefer its parent so a nested carousel's viewport cannot win the lookup.
  const content = container.querySelector<HTMLElement>(".main-view-container__scroll-node-child");
  const parent = content?.parentElement;
  if (parent?.isConnected && (parent === container || container.contains(parent))) return parent;

  for (const selector of [
    "[data-overlayscrollbars-viewport]",
    ".main-view-container__scroll-node",
    ".os-host",
    ".uGZUPBPcDpzSYqKcQT8r > div",
  ]) {
    const host = container.querySelector<HTMLElement>(selector);
    if (host?.isConnected) return host;
  }
  return null;
}

/** Validate before allocating the singleton page or setting its opened state. */
export function requireConnectedPageHost(host: HTMLElement | null | undefined): HTMLElement {
  if (!host?.isConnected) {
    throw new Error("Spotify's lyrics page container is not ready.");
  }
  return host;
}

export function pageBelongsToHost(
  page: HTMLElement | null | undefined,
  host: HTMLElement | null | undefined
): boolean {
  return !!(page?.isConnected && host?.isConnected && host.contains(page));
}
