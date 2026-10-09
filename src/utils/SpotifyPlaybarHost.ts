/** Resolve only Spotify's own playbar, never an Icy/fullscreen lookalike. */
export function getSpotifyPlaybar(root: ParentNode = document): HTMLElement | null {
  return root.querySelector<HTMLElement>('[data-testid="now-playing-bar"]') ??
    root.querySelector<HTMLElement>(".Root__now-playing-bar");
}

export function getSpotifyPlaybarControls(root: ParentNode = document): HTMLElement | null {
  const bar = getSpotifyPlaybar(root);
  if (!bar) return null;
  const legacy = bar.querySelector<HTMLElement>(".main-nowPlayingBar-extraControls") ??
    bar.querySelector<HTMLElement>(".main-nowPlayingBar-right > div");
  if (legacy) return legacy;
  // Spotify 1.3 moved these controls to hashed classes. Both buttons are direct
  // children of the controls row; test IDs survive the stylesheet remapping.
  const anchor = bar.querySelector<HTMLElement>('[data-testid="fullscreen-mode-button"]') ??
    bar.querySelector<HTMLElement>('[data-testid="lyrics-button"]');
  const parent = anchor?.parentElement;
  return parent && parent !== bar && bar.contains(parent) ? parent : null;
}
