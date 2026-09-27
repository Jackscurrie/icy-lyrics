export interface LyricsClockReading {
  positionMs: number;
  rawPositionMs?: number;
  isPlaying?: boolean;
}

interface LyricsClockFallback {
  positionMs: () => number;
  rawPositionMs: () => number;
  isPlaying: () => boolean;
}

/** One clock owner shared by animation and scrolling, with lazy Spotify fallback. */
export function createLyricsClock(fallback: LyricsClockFallback) {
  let active: {
    owner: symbol;
    provider: () => LyricsClockReading | null;
  } | null = null;

  return {
    acquire(provider: () => LyricsClockReading | null): () => void {
      const owner = Symbol("IcyLyricsClockOverride");
      active = { owner, provider };
      return () => {
        // A delayed cleanup from an older preview must not remove its successor.
        if (active?.owner === owner) active = null;
      };
    },
    read(): Required<LyricsClockReading> {
      const override = active?.provider();
      return {
        positionMs: override?.positionMs ?? fallback.positionMs(),
        rawPositionMs: override?.rawPositionMs ?? fallback.rawPositionMs(),
        isPlaying: override?.isPlaying ?? fallback.isPlaying(),
      };
    },
  };
}
