import React from "react";
import { clampAutoTimingProgress } from "./progress.ts";
import type { AutoTimingProgress } from "./types.ts";

export default function AutoTimingProgressBar({ progress }: { progress: AutoTimingProgress }) {
  const fraction = progress.phase === "review" ? 1 : Math.min(0.99, clampAutoTimingProgress(progress.progress));
  const percent = Math.round(fraction * 100);
  return (
    <div className="il-creator-autotime__progress">
      <div
        className="il-creator-autotime__progress-track"
        role="progressbar"
        aria-label="Auto-time progress"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={`${percent}% — ${progress.message}`}
      >
        <span style={{ transform: `scaleX(${fraction})` }} />
      </div>
      <small role="status">{progress.message}</small>
    </div>
  );
}
