import React, { useMemo } from "react";
import type { CreatorLine } from "./model.ts";
import { creatorLineTimelineGeometry, type CreatorWaveform } from "./waveform.ts";

interface CreatorWaveformTimelineProps {
  waveform: CreatorWaveform;
  durationMs: number;
  positionMs: number;
  selectedLine: CreatorLine | null;
  selectedLineNumber: number;
  onSeek: (positionMs: number) => void;
}

function waveformPath(samples: readonly number[]): string {
  if (samples.length === 0) return "";
  const top = samples.map((sample, index) => {
    const amplitude = Math.max(0.055, sample) * 44;
    return `${index === 0 ? "M" : "L"}${index + 0.5},${50 - amplitude}`;
  });
  const bottom = [...samples].reverse().map((sample, reverseIndex) => {
    const index = samples.length - 1 - reverseIndex;
    const amplitude = Math.max(0.055, sample) * 44;
    return `L${index + 0.5},${50 + amplitude}`;
  });
  return `${top.join(" ")} ${bottom.join(" ")} Z`;
}

export default function CreatorWaveformTimeline({
  waveform,
  durationMs,
  positionMs,
  selectedLine,
  selectedLineNumber,
  onSeek,
}: CreatorWaveformTimelineProps) {
  const duration = Math.max(1, durationMs || waveform.durationMs);
  const progress = Math.min(100, Math.max(0, (positionMs / duration) * 100));
  const geometry = useMemo(
    () => (selectedLine ? creatorLineTimelineGeometry(selectedLine, duration) : null),
    [duration, selectedLine]
  );
  const path = useMemo(() => waveformPath(waveform.samples), [waveform.samples]);

  const seekFromPointer = (event: React.PointerEvent<HTMLDivElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    if (bounds.width <= 0) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width));
    onSeek(Math.round(ratio * duration));
  };

  return (
    <div
      className={`il-creator-waveform${waveform.available ? " is-available" : " is-fallback"}`}
      role="slider"
      tabIndex={0}
      aria-label="Song waveform and playback position"
      aria-valuemin={0}
      aria-valuemax={duration}
      aria-valuenow={Math.min(duration, Math.max(0, positionMs))}
      aria-valuetext={`${Math.round(positionMs / 100) / 10} seconds`}
      onPointerDown={(event) => {
        event.currentTarget.setPointerCapture?.(event.pointerId);
        seekFromPointer(event);
      }}
      onPointerMove={(event) => {
        if (event.currentTarget.hasPointerCapture?.(event.pointerId)) seekFromPointer(event);
      }}
      onPointerUp={(event) => event.currentTarget.releasePointerCapture?.(event.pointerId)}
      onKeyDown={(event) => {
        let next: number | null = null;
        if (event.key === "ArrowLeft") next = positionMs - (event.shiftKey ? 10_000 : 1_000);
        if (event.key === "ArrowRight") next = positionMs + (event.shiftKey ? 10_000 : 1_000);
        if (event.key === "Home") next = 0;
        if (event.key === "End") next = duration;
        if (next === null) return;
        event.preventDefault();
        onSeek(Math.min(duration, Math.max(0, next)));
      }}
    >
      <svg
        className="il-creator-waveform__shape"
        viewBox={`0 0 ${Math.max(1, waveform.samples.length)} 100`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <path d={path} />
      </svg>
      <div
        className="il-creator-waveform__played"
        style={{ clipPath: `inset(0 ${100 - progress}% 0 0)` }}
      >
        <svg
          viewBox={`0 0 ${Math.max(1, waveform.samples.length)} 100`}
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <path d={path} />
        </svg>
      </div>
      {geometry?.visible && (
        <div
          className={`il-creator-waveform__line-range is-${geometry.status}`}
          style={{ left: `${geometry.leftPercent}%`, width: `${geometry.widthPercent}%` }}
          title={`Selected lyric line ${selectedLineNumber}`}
        >
          <span>Line {selectedLineNumber}</span>
        </div>
      )}
      <span className="il-creator-waveform__playhead" style={{ left: `${progress}%` }} />
    </div>
  );
}
