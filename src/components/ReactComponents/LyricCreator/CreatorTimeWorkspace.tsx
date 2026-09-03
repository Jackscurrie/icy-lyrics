import React, { useEffect, useMemo, useRef } from "react";
import {
  autoFollowCreatorTimingTarget,
  classifyCreatorTimingTarget,
  creatorTimingErrorIndexes,
  creatorTimingTargetDomId,
  creatorTimingTargets,
  formatCreatorTime,
  type CreatorTimingOptions,
} from "./timing.ts";
import type { CreatorProject } from "./model.ts";
import { creatorPlaybackActivity } from "./activeWord.ts";

interface CreatorTimeWorkspaceProps {
  project: CreatorProject;
  targetIndex: number;
  onTargetIndex: (index: number) => void;
  positionMs: number;
  options: CreatorTimingOptions;
  onOptionsChange: (options: CreatorTimingOptions) => void;
}

function displayTime(timeMs: number | null): string {
  return timeMs === null ? "--:--.---" : formatCreatorTime(timeMs);
}

function Toggle({
  checked,
  label,
  onChange,
}: {
  checked: boolean;
  label: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="il-creator-toggle">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.currentTarget.checked)}
      />
      <span aria-hidden="true" />
      {label}
    </label>
  );
}

export default function CreatorTimeWorkspace({
  project,
  targetIndex,
  onTargetIndex,
  positionMs,
  options,
  onOptionsChange,
}: CreatorTimeWorkspaceProps) {
  const targets = useMemo(
    () => creatorTimingTargets(project, { ignoreBackground: options.ignoreBackground }),
    [options.ignoreBackground, project]
  );
  const activeTarget = targets[targetIndex];
  const sheetRef = useRef<HTMLDivElement>(null);
  const targetIndexByFragment = useMemo(
    () => new Map(targets.map((target, index) => [target.fragment.id, index])),
    [targets]
  );
  const playbackActivity = useMemo(
    () => creatorPlaybackActivity(project, positionMs),
    [positionMs, project]
  );
  const timedCount = targets.filter(
    ({ fragment }) => fragment.startTimeMs !== null && fragment.endTimeMs !== null
  ).length;
  const errorIndexes = useMemo(
    () => creatorTimingErrorIndexes(project, { ignoreBackground: options.ignoreBackground }),
    [options.ignoreBackground, project]
  );

  useEffect(() => {
    if (targets.length === 0 || targetIndex < targets.length) return;
    onTargetIndex(Math.max(0, targets.length - 1));
  }, [onTargetIndex, targetIndex, targets.length]);

  useEffect(() => {
    if (!options.autoFollow) return;
    autoFollowCreatorTimingTarget(sheetRef.current, targetIndex, options.autoFollow);
  }, [options.autoFollow, options.ignoreBackground, targetIndex, targets]);

  const updateOption = <K extends keyof CreatorTimingOptions>(
    key: K,
    value: CreatorTimingOptions[K]
  ) => onOptionsChange({ ...options, [key]: value });

  return (
    <main className="il-creator-time" aria-label="Timing workspace">
      <section className="il-creator-timing-toolbar" aria-label="Timing controls">
        <div className="il-creator-timing-toolbar__group il-creator-timing-session">
          <span className="il-creator-toolbar-label">Timing progress</span>
          <strong>
            {timedCount} / {targets.length}
          </strong>
          <div
            className="il-creator-timing-progress"
            role="progressbar"
            aria-label={`${timedCount} of ${targets.length} lyric fragments timed`}
            aria-valuemin={0}
            aria-valuemax={targets.length}
            aria-valuenow={timedCount}
          >
            <span
              style={{ width: `${targets.length ? (timedCount / targets.length) * 100 : 0}%` }}
            />
          </div>
          <small>
            {errorIndexes.size
              ? `${errorIndexes.size} timing error${errorIndexes.size === 1 ? "" : "s"}`
              : "No timing errors"}
          </small>
        </div>

        <div className="il-creator-timing-toolbar__group il-creator-timing-adjustment">
          <label>
            <span className="il-creator-toolbar-label">Timing offset</span>
            <span className="il-creator-offset-input">
              <input
                type="number"
                step={10}
                value={options.offsetMs}
                onChange={(event) =>
                  updateOption("offsetMs", Number(event.currentTarget.value) || 0)
                }
                aria-label="Timing offset in milliseconds"
              />
              <span>ms</span>
            </span>
          </label>
          <small>Applied when F, G, or H is pressed</small>
        </div>

        <div className="il-creator-timing-toolbar__group il-creator-timing-options">
          <span className="il-creator-toolbar-label">Display &amp; assistant</span>
          <div>
            <Toggle
              checked={options.showTimestamps}
              label="Show timestamps"
              onChange={(value) => updateOption("showTimestamps", value)}
            />
            <Toggle
              checked={options.highlightActive}
              label="Highlight timing selection"
              onChange={(value) => updateOption("highlightActive", value)}
            />
            <Toggle
              checked={options.highlightErrors}
              label="Highlight errors"
              onChange={(value) => updateOption("highlightErrors", value)}
            />
            <Toggle
              checked={options.ignoreBackground}
              label="Skip background vocals"
              onChange={(value) => updateOption("ignoreBackground", value)}
            />
            <Toggle
              checked={options.autoFollow}
              label="Auto-follow selection"
              onChange={(value) => updateOption("autoFollow", value)}
            />
          </div>
        </div>

        <div className="il-creator-timing-toolbar__group il-creator-hotkeys">
          <span className="il-creator-toolbar-label">Hotkey cheatsheet</span>
          <div>
            <span>
              <kbd>F</kbd> Mark start
            </span>
            <span>
              <kbd>G</kbd> Commit + next
            </span>
            <span>
              <kbd>H</kbd> Mark end
            </span>
          </div>
          <small>Playhead {formatCreatorTime(positionMs)}</small>
        </div>
      </section>

      <div
        ref={sheetRef}
        className={`il-creator-timing-sheet${options.showTimestamps ? " show-timestamps" : ""}${options.highlightActive ? " highlight-active" : ""}${options.highlightErrors ? " highlight-errors" : ""}`}
        role="listbox"
        aria-label="Lyric timing fragments"
      >
        {project.lines.map((line, lineIndex) => {
          if (options.ignoreBackground && line.isBackground) return null;
          const lineTargetIndexes = line.tokens
            .flatMap((token) => token.fragments)
            .map((fragment) => targetIndexByFragment.get(fragment.id))
            .filter((index): index is number => index !== undefined);
          const lineIsActive = lineTargetIndexes.includes(targetIndex);

          return (
            <section
              className={`il-creator-timing-line${lineIsActive ? " is-active" : ""}${line.isBackground ? " is-background" : ""}`}
              aria-label={`Line ${lineIndex + 1}`}
              key={line.id}
            >
              <div className="il-creator-timing-line__number">
                <span>{lineIndex + 1}</span>
                {(line.isBackground || line.isSecondSpeaker) && (
                  <small>
                    {line.isBackground ? "BG" : ""}
                    {line.isBackground && line.isSecondSpeaker ? " · " : ""}
                    {line.isSecondSpeaker ? "S2" : ""}
                  </small>
                )}
              </div>
              <div className="il-creator-timing-words">
                {line.tokens.map((token) => (
                  <div className="il-creator-timing-token" key={token.id}>
                    {token.fragments.map((fragment, fragmentIndex) => {
                      const index = targetIndexByFragment.get(fragment.id);
                      if (index === undefined) return null;
                      const state = classifyCreatorTimingTarget(targets, index);
                      const isLive = playbackActivity.fragmentIds.has(fragment.id);
                      return (
                        <button
                          type="button"
                          role="option"
                          id={creatorTimingTargetDomId(index)}
                          aria-selected={index === targetIndex}
                          aria-label={`${fragment.text || "Empty fragment"}, start ${displayTime(fragment.startTimeMs)}, end ${displayTime(fragment.endTimeMs)}`}
                          className={`il-creator-timing-cell is-${state}${isLive ? " is-playing" : ""}`}
                          data-time-state={state}
                          onClick={() => onTargetIndex(index)}
                          key={fragment.id}
                        >
                          <time className="il-creator-timing-cell__start">
                            {displayTime(fragment.startTimeMs)}
                          </time>
                          <span className="il-creator-timing-cell__word">
                            {fragment.text || "Empty"}
                          </span>
                          <time className="il-creator-timing-cell__end">
                            {displayTime(fragment.endTimeMs)}
                          </time>
                          {token.fragments.length > 1 && (
                            <span className="il-creator-timing-cell__part" aria-hidden="true">
                              {fragmentIndex + 1}/{token.fragments.length}
                            </span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                ))}
              </div>
              <div className="il-creator-timing-line__summary">
                <time>{displayTime(line.startTimeMs)}</time>
                <time>{displayTime(line.endTimeMs)}</time>
              </div>
            </section>
          );
        })}
        {targets.length === 0 && (
          <div className="il-creator-empty-state">
            <strong>No lyrics to time</strong>
            <span>Add lyric words in Set up lyrics, then return here.</span>
          </div>
        )}
      </div>

      {activeTarget && (
        <div className="il-creator-timing-status" aria-live="polite">
          <span>
            Line {activeTarget.lineIndex + 1} · word {activeTarget.tokenIndex + 1}
            {activeTarget.fragmentIndex > 0 ? ` · part ${activeTarget.fragmentIndex + 1}` : ""}
          </span>
          <strong>{activeTarget.label}</strong>
          <span>
            {targetIndex + 1} of {targets.length}
          </span>
        </div>
      )}
    </main>
  );
}
