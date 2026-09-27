import React, { useEffect, useRef, useState } from "react";
import type { CreatorFragmentationProposal } from "./fragmentation.ts";

const DISMISS_MS = 8_000;

export default function FragmentationPrompt({ proposal, onApply, onDismiss }: {
  proposal: CreatorFragmentationProposal;
  onApply: () => void;
  onDismiss: () => void;
}) {
  const hovered = useRef(false);
  const focused = useRef(false);
  const bar = useRef<HTMLSpanElement>(null);
  const dismiss = useRef(onDismiss);
  const [secondsRemaining, setSecondsRemaining] = useState(DISMISS_MS / 1000);
  dismiss.current = onDismiss;

  useEffect(() => {
    setSecondsRemaining(DISMISS_MS / 1000);
    let remaining = DISMISS_MS;
    let lastFrame = performance.now();
    let lastSeconds = DISMISS_MS / 1000;
    let frame = 0;
    const update = (now: number) => {
      const elapsed = now - lastFrame;
      lastFrame = now;
      if (!hovered.current && !focused.current && !document.hidden) remaining = Math.max(0, remaining - elapsed);
      if (bar.current) bar.current.style.transform = `scaleX(${remaining / DISMISS_MS})`;
      const seconds = Math.ceil(remaining / 1000);
      if (seconds !== lastSeconds) {
        lastSeconds = seconds;
        setSecondsRemaining(seconds);
      }
      if (remaining <= 0) dismiss.current();
      else frame = requestAnimationFrame(update);
    };
    const resetClock = () => { lastFrame = performance.now(); };
    document.addEventListener("visibilitychange", resetClock);
    frame = requestAnimationFrame(update);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", resetClock);
    };
  }, [proposal]);

  return (
    <aside
      className="il-creator-fragmentation-prompt"
      aria-label="Apply word fragmentation to matching words"
      onMouseEnter={() => { hovered.current = true; }}
      onMouseLeave={() => { hovered.current = false; }}
      onFocusCapture={() => { focused.current = true; }}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) focused.current = false;
      }}
      onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === "Escape") {
          event.preventDefault();
          onDismiss();
        }
      }}
    >
      <p aria-live="polite">
        Split the other {proposal.matchingTokenIds.length} matching
        {proposal.matchingTokenIds.length === 1 ? " word" : " words"} “{proposal.word}” the same way?
      </p>
      <small>{proposal.parts.join(" · ")} · Existing outer timings are kept.</small>
      <div className="il-creator-fragmentation-prompt__actions">
        <button type="button" className="il-creator-primary-button" onClick={onApply}>Apply to all</button>
        <button type="button" onClick={onDismiss}>Only this word</button>
      </div>
      <div
        className="il-creator-fragmentation-prompt__timer"
        role="progressbar"
        aria-label="Time before this suggestion closes; paused while hovered or focused"
        aria-valuemin={0}
        aria-valuemax={DISMISS_MS / 1000}
        aria-valuenow={secondsRemaining}
      ><span ref={bar} /></div>
    </aside>
  );
}
