export type LyricsRequestToken = Readonly<{ uri: string; generation: number }>;

/** Pure URI/generation guard shared by every asynchronous lyrics resolution path. */
export class LyricsRequestGeneration {
  private activeUri: string | null = null;
  private generation = 0;

  begin(uri: string): LyricsRequestToken {
    this.activeUri = uri;
    this.generation += 1;
    return { uri, generation: this.generation };
  }

  invalidate(nextUri: string | null = null): number {
    this.activeUri = nextUri;
    this.generation += 1;
    return this.generation;
  }

  isCurrent(token: LyricsRequestToken, playingUri?: string | null): boolean {
    const hasPlayingUriConstraint = arguments.length >= 2;
    return (
      this.activeUri === token.uri &&
      this.generation === token.generation &&
      (!hasPlayingUriConstraint || playingUri === token.uri)
    );
  }

  currentGeneration(): number {
    return this.generation;
  }
}
