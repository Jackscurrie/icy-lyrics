import { describe, expect, it, vi } from "vitest";
import { createCreatorPlaybackClock, sampleCreatorPlayback, type CreatorPlaybackInputs } from "../src/components/ReactComponents/LyricCreator/playbackClock.ts";

const track = { uri: "spotify:track:aaaaaaaaaaaaaaaaaaaaaa", durationMs: 180_000 };
const spotify: NonNullable<CreatorPlaybackInputs["spotify"]> = {
  uri: track.uri, positionMs: 1000, durationMs: track.durationMs, playing: true,
  anchor: { positionMs: 1000, timestampMs: 10_000, paused: false, uri: track.uri },
};

describe("Creator shared raw playback clock", () => {
  it("samples the media clock exactly without polling lag, rounding, lead or speed multiplication", () => {
    const audio = { src: "blob:song", currentTime: 1.23456, duration: 180, paused: false };
    const first = sampleCreatorPlayback({ localAudio: audio, spotify }, 50_000);
    expect(first.positionMs).toBeCloseTo(1234.56);
    expect(first.source).toBe("local:blob:song");
    audio.currentTime = 1.235;
    expect(sampleCreatorPlayback({ localAudio: audio, spotify }, 50_010).positionMs).toBe(1235);
    audio.paused = true;
    expect(sampleCreatorPlayback({ localAudio: audio, spotify }, 60_000)).toMatchObject({ positionMs: 1235, playing: false });
  });

  it("uses Spotify's timestamp and playback rate without the lyric animation lead", () => {
    expect(sampleCreatorPlayback({ spotify }, 10_120).positionMs).toBe(1120);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, rate: 0.75 } }, 10_120).positionMs).toBe(1090);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, rate: 2 } }, 10_120).positionMs).toBe(1240);
  });

  it("takes seeks and repeated playback directly from the new anchor", () => {
    expect(sampleCreatorPlayback({ spotify }, 11_000).positionMs).toBe(2000);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, anchor: { positionMs: 200, timestampMs: 11_000 } } }, 11_040).positionMs).toBe(240);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, anchor: { positionMs: 0, timestampMs: 11_050 } } }, 11_060).positionMs).toBe(10);
  });

  it("never extrapolates paused, stale, mismatched or invalid anchors", () => {
    expect(sampleCreatorPlayback({ spotify: { ...spotify, playing: false, positionMs: 1423 } }, 90_000).positionMs).toBe(1423);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, anchor: { positionMs: 1000, timestampMs: 10_000, paused: true }, positionMs: 1450 } }, 90_000).positionMs).toBe(1450);
    expect(sampleCreatorPlayback({ spotify }, 500_000).positionMs).toBe(1000);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, anchor: { positionMs: Number.NaN, timestampMs: 10_000 } } }, 10_100).positionMs).toBe(1000);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, anchor: { positionMs: 5000, timestampMs: 10_000, uri: "other-track" } } }, 10_100).positionMs).toBe(0);
  });

  it("hides the previous track's clock during selection and clamps at audio boundaries", () => {
    expect(sampleCreatorPlayback({ expectedTrack: { ...track, uri: "spotify:track:bbbbbbbbbbbbbbbbbbbbbb" }, spotify }, 10_100)).toMatchObject({ positionMs: 0, durationMs: 180_000, playing: false });
    expect(sampleCreatorPlayback({ spotify: { ...spotify, positionMs: -100, anchor: null } }, 10_100).positionMs).toBe(0);
    expect(sampleCreatorPlayback({ spotify: { ...spotify, positionMs: 190_000, anchor: null } }, 10_100).positionMs).toBe(180_000);
  });

  it("shares one frame loop, reads hotkeys immediately, and stops after the final subscriber", () => {
    const scheduled = new Map<number, FrameRequestCallback>();
    let nextId = 0;
    const scheduler = {
      request: (callback: FrameRequestCallback) => { scheduled.set(++nextId, callback); return nextId; },
      cancel: (id: number) => { scheduled.delete(id); },
    };
    let position = 0;
    const clock = createCreatorPlaybackClock(() => ({ positionMs: position, durationMs: 1000, source: "local:audio", playing: true }), scheduler);
    const first = vi.fn();
    const second = vi.fn();
    const stopFirst = clock.subscribe(first);
    const stopSecond = clock.subscribe(second);
    expect(scheduled.size).toBe(1);
    position = 16.7;
    expect(clock.getPosition()).toBe(16.7);
    expect(clock.getReading()).toEqual({ positionMs: 16.7, durationMs: 1000, source: "local:audio", playing: true });
    expect(clock.getSnapshot().positionMs).toBe(0);
    const tick = () => {
      const [id, callback] = scheduled.entries().next().value!;
      scheduled.delete(id);
      callback(0);
    };
    tick();
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);
    expect(clock.getSnapshot().positionMs).toBe(16.7);
    tick();
    expect(first).toHaveBeenCalledTimes(1);
    stopFirst();
    expect(scheduled.size).toBe(1);
    stopSecond();
    expect(scheduled.size).toBe(0);
  });
});
