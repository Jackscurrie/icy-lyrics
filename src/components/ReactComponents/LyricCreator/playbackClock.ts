export interface CreatorPlaybackReading {
  positionMs: number;
  durationMs: number;
  playing: boolean;
  source: string;
}

export interface CreatorPlaybackInputs {
  localAudio?: {
    src: string;
    currentSrc?: string;
    currentTime: number;
    duration: number;
    paused: boolean;
    ended?: boolean;
  } | null;
  expectedTrack?: { uri: string; durationMs: number } | null;
  spotify?: {
    uri: string;
    positionMs: number;
    durationMs: number;
    playing: boolean;
    rate?: number;
    anchor?: {
      positionMs: number;
      timestampMs: number;
      paused?: boolean;
      uri?: string;
    } | null;
  } | null;
}

function nonnegative(value: number): number {
  return Number.isFinite(value) ? Math.max(0, value) : 0;
}

function clampPosition(position: number, duration: number): number {
  return duration > 0 ? Math.min(duration, nonnegative(position)) : nonnegative(position);
}

/** Raw audio coordinates: deliberately excludes the lyric renderer's 100 ms lead. */
export function sampleCreatorPlayback(inputs: CreatorPlaybackInputs, nowMs: number): CreatorPlaybackReading {
  const audio = inputs.localAudio;
  if (audio?.currentSrc || audio?.src) {
    const durationMs = nonnegative(audio.duration * 1000);
    return {
      positionMs: clampPosition(audio.currentTime * 1000, durationMs),
      durationMs,
      playing: !audio.paused && !audio.ended,
      source: `local:${audio.currentSrc || audio.src}`,
    };
  }
  const spotify = inputs.spotify;
  const expected = inputs.expectedTrack;
  if (!spotify || (expected && spotify.uri !== expected.uri)) {
    return { positionMs: 0, durationMs: nonnegative(expected?.durationMs ?? 0), playing: false, source: `waiting:${expected?.uri ?? ""}` };
  }
  const durationMs = nonnegative(spotify.durationMs) || nonnegative(expected?.durationMs ?? 0);
  const anchor = spotify.anchor;
  let positionMs = nonnegative(spotify.positionMs);
  // The state timestamp supplies a continuous clock between player events.
  // Read it again every frame so seeks, repeats and new anchors take effect
  // immediately. A paused/resuming or mismatched anchor is never extrapolated.
  if (anchor?.uri && anchor.uri !== spotify.uri) {
    positionMs = 0;
  } else if (spotify.playing && anchor && anchor.paused !== true &&
    Number.isFinite(anchor.positionMs) && Number.isFinite(anchor.timestampMs) &&
    anchor.timestampMs >= 0 && anchor.timestampMs <= nowMs &&
    (durationMs === 0 || nowMs - anchor.timestampMs <= durationMs + 5000)) {
    const rate = Number.isFinite(spotify.rate) && (spotify.rate ?? 0) > 0 ? spotify.rate! : 1;
    positionMs = anchor.positionMs + (nowMs - anchor.timestampMs) * rate;
  }
  return {
    positionMs: clampPosition(positionMs, durationMs),
    durationMs,
    playing: spotify.playing,
    source: `spotify:${spotify.uri}`,
  };
}

export interface CreatorPlaybackClock {
  /** Fresh read for hotkeys and the actual preview animation frame. */
  getPosition: () => number;
  /** Atomic media position/play state for preview animation and scrolling. */
  getReading: () => CreatorPlaybackReading;
  getSnapshot: () => CreatorPlaybackReading;
  subscribe: (listener: () => void) => () => void;
  refresh: () => void;
}

/** One sampling loop shared by the timeline and all activity subscriptions. */
export function createCreatorPlaybackClock(
  read: () => CreatorPlaybackReading,
  scheduler: {
    request: (callback: FrameRequestCallback) => number;
    cancel: (frame: number) => void;
  } = { request: (callback) => requestAnimationFrame(callback), cancel: (frame) => cancelAnimationFrame(frame) }
): CreatorPlaybackClock {
  let snapshot = read();
  const listeners = new Set<() => void>();
  let frame: number | null = null;
  let sampling = false;
  const refresh = () => {
    const next = read();
    if (next.positionMs === snapshot.positionMs && next.durationMs === snapshot.durationMs &&
      next.playing === snapshot.playing && next.source === snapshot.source) return;
    snapshot = next;
    for (const listener of listeners) listener();
  };
  const tick: FrameRequestCallback = () => {
    frame = null;
    sampling = true;
    try {
      refresh();
    } finally {
      sampling = false;
      if (listeners.size > 0 && frame === null) frame = scheduler.request(tick);
    }
  };
  return {
    getPosition: () => read().positionMs,
    getReading: read,
    getSnapshot: () => snapshot,
    refresh,
    subscribe: (listener) => {
      listeners.add(listener);
      if (frame === null && !sampling) frame = scheduler.request(tick);
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0 && frame !== null) {
          scheduler.cancel(frame);
          frame = null;
        }
      };
    },
  };
}
