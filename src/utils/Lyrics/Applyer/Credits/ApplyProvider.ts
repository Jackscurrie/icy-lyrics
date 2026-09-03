/**
 * Provider codes remain available on the lyric payload for diagnostics and
 * source-aware behavior. Icy intentionally does not render the generic
 * "Provided by" footer; community maker/uploader credits are handled by their
 * separate applyers and remain visible.
 */
export function ApplyLyricsProvider(_data: any, LyricsContainer: HTMLElement): void {
  LyricsContainer?.querySelector(".LyricsProvider")?.remove();
}
