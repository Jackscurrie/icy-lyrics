import React, { useEffect, useMemo, useRef, useSyncExternalStore } from "react";
import { createCreatorPlaybackActivityReader } from "./activeWord.ts";
import { createCreatorPlaybackClock, sampleCreatorPlayback, type CreatorPlaybackClock } from "./playbackClock.ts";
import type { CreatorTrack } from "./data.ts";
import type { CreatorProject } from "./model.ts";
import CreatorWaveformTimeline from "./CreatorWaveformTimeline.tsx";
import { formatCreatorTime } from "./timing.ts";

export type { CreatorPlaybackClock } from "./playbackClock.ts";

export function useCreatorPlaybackClock(
  localAudio: React.RefObject<HTMLAudioElement | null>,
  expectedTrack: CreatorTrack | null
): CreatorPlaybackClock {
  const sourceRef = useRef({ localAudio, expectedTrack });
  sourceRef.current = { localAudio, expectedTrack };
  const clock = useMemo(() => createCreatorPlaybackClock(() => {
    const { localAudio: audio, expectedTrack: track } = sourceRef.current;
    if (audio.current?.src || audio.current?.currentSrc) {
      return sampleCreatorPlayback({ localAudio: audio.current, expectedTrack: track }, Date.now());
    }
    const player = typeof Spicetify === "undefined" ? null : Spicetify.Player;
    const state = (player as any)?.origin?._state ??
      (typeof Spicetify === "undefined" ? null : Spicetify.Platform?.PlayerAPI?._state);
    const rate = state?.playback_speed ?? state?.speed ?? (player?.data as any)?.playback_speed ?? player?.data?.speed ?? 1;
    return sampleCreatorPlayback({
      localAudio: audio.current,
      expectedTrack: track,
      spotify: player ? {
        uri: player.data?.item?.uri ?? "",
        positionMs: player.getProgress?.() ?? 0,
        durationMs: player.getDuration?.() ?? track?.durationMs ?? 0,
        playing: player.isPlaying?.() ?? false,
        rate,
        anchor: state ? {
          positionMs: state.positionAsOfTimestamp,
          timestampMs: state.timestamp,
          paused: state.isPaused,
          uri: state.item?.uri ?? state.track?.uri,
        } : null,
      } : null,
    }, Date.now());
  }), []);
  useEffect(() => clock.refresh(), [clock, expectedTrack?.uri, expectedTrack?.durationMs]);
  return clock;
}

export function useCreatorPlaybackDuration(clock: CreatorPlaybackClock): number {
  return useSyncExternalStore(clock.subscribe, () => clock.getSnapshot().durationMs);
}

export function useCreatorPlaybackActivity(project: Pick<CreatorProject, "lines">, clock: CreatorPlaybackClock) {
  const activity = useMemo(() => {
    const read = createCreatorPlaybackActivityReader(project);
    return {
      getSnapshot: () => read(clock.getSnapshot().positionMs),
      subscribe: clock.subscribe,
    };
  }, [clock, project.lines]);
  return useSyncExternalStore(activity.subscribe, activity.getSnapshot);
}

export function CreatorPlaybackTime({ clock }: { clock: CreatorPlaybackClock }) {
  // Precise time text rerenders only this tiny component, never the editor.
  const position = useSyncExternalStore(clock.subscribe, () => Math.round(clock.getSnapshot().positionMs));
  return <>{formatCreatorTime(position)}</>;
}

export function CreatorPlaybackTimeline({ clock, ...props }: Omit<React.ComponentProps<typeof CreatorWaveformTimeline>, "positionMs"> & { clock: CreatorPlaybackClock }) {
  const positionMs = useSyncExternalStore(clock.subscribe, () => clock.getSnapshot().positionMs);
  return <CreatorWaveformTimeline {...props} positionMs={positionMs} />;
}
