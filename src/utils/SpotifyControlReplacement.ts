/** Only replace controls for which Icy supplies the equivalent button. */
export function shouldReplaceSpotifyControl(
  testId: string | undefined,
  popupLyricsEnabled: boolean
): boolean {
  return testId === "fullscreen-mode-button" ||
    (popupLyricsEnabled && testId === "pip-toggle-button");
}
