import { useStore } from "@nanostores/react";
import React, { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { $creatorAutoTimingLastModel } from "../../../../utils/stores.ts";
import { lineText, type CreatorProject } from "../model.ts";
import { creatorProjectCheckpoint } from "../sourceSwitch.ts";
import type { DecodedAutoTimingAudio } from "./audio.ts";
import {
  applyAutoTimingCandidate,
  buildAutoTimingCandidate,
  summarizeAutoTimingCandidate,
} from "./alignment.ts";
import { autoTimingModelRepository } from "./modelRepository.ts";
import { transcribeAutoTimingAudio } from "./workerClient.ts";
import { CreatorTaskScope, type CreatorTask } from "./session.ts";
import { autoTimingLanguage } from "./language.ts";
import { clampAutoTimingProgress, createAutoTimingProgressTracker } from "./progress.ts";
import AutoTimingProgressBar from "./AutoTimingProgressBar.tsx";
import type {
  AutoTimingApplyMode,
  AutoTimingCandidate,
  AutoTimingInstallProgress,
  AutoTimingModelId,
  AutoTimingModuleStatus,
  AutoTimingProgress,
} from "./types.ts";

interface Props {
  project: CreatorProject;
  audioName: string;
  audio: DecodedAutoTimingAudio;
  expectedDurationMs?: number;
  onSeek: (timeMs: number) => void;
  onTogglePlayback: () => void;
  onApply: (project: CreatorProject, previous: CreatorProject) => void;
  onClose: () => void;
}

function formatBytes(bytes: number): string {
  const units = ["B", "KiB", "MiB", "GiB"];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(index >= 2 ? 1 : 0)} ${units[index]}`;
}

function formatClock(milliseconds: number): string {
  const value = Math.max(0, Math.round(milliseconds));
  const minutes = Math.floor(value / 60_000);
  const seconds = Math.floor((value % 60_000) / 1000);
  const millis = value % 1000;
  return `${minutes}:${seconds.toString().padStart(2, "0")}.${millis
    .toString()
    .padStart(3, "0")}`;
}

export default function AutoTimingDialog({
  project,
  audioName,
  audio,
  expectedDurationMs,
  onSeek,
  onTogglePlayback,
  onApply,
  onClose,
}: Props) {
  const preferredModel = useStore($creatorAutoTimingLastModel);
  const [selectedModel, setSelectedModel] = useState<AutoTimingModelId>(
    preferredModel === "accurate" ? "accurate" : "fast"
  );
  const [statuses, setStatuses] = useState<AutoTimingModuleStatus[]>([]);
  const [loadingStatuses, setLoadingStatuses] = useState(true);
  const [installProgress, setInstallProgress] = useState<AutoTimingInstallProgress | null>(null);
  const [installing, setInstalling] = useState<AutoTimingModelId | null>(null);
  const [progress, setProgress] = useState<AutoTimingProgress | null>(null);
  const [candidate, setCandidate] = useState<AutoTimingCandidate | null>(null);
  const [applyMode, setApplyMode] = useState<AutoTimingApplyMode>("missing");
  const [includedLineIds, setIncludedLineIds] = useState<Set<string>>(
    () => new Set(project.lines.map((line) => line.id))
  );
  const [showUncertainOnly, setShowUncertainOnly] = useState(false);
  const [error, setError] = useState("");
  const [replaceConfirmed, setReplaceConfirmed] = useState(false);
  const taskScopeRef = useRef(new CreatorTaskScope());
  const mountedRef = useRef(false);
  const dialogRef = useRef<HTMLElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const running = progress !== null && progress.phase !== "review";

  const refreshStatuses = async (task?: CreatorTask) => {
    setLoadingStatuses(true);
    try {
      const nextStatuses = await autoTimingModelRepository.list();
      if (!mountedRef.current || (task && !task.isCurrent())) return;
      setStatuses(nextStatuses);
      setError("");
    } catch (reason) {
      if (!mountedRef.current || (task && !task.isCurrent())) return;
      setError(reason instanceof Error ? reason.message : "Could not load timing modules.");
    } finally {
      if (mountedRef.current && (!task || task.isCurrent())) setLoadingStatuses(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    const previousFocus = document.activeElement;
    dialogRef.current?.focus();
    void refreshStatuses();
    return () => {
      mountedRef.current = false;
      taskScopeRef.current.cancel();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);

  const selectedStatus = statuses.find((status) => status.definition.id === selectedModel);
  const durationDifference = expectedDurationMs
    ? Math.abs(expectedDurationMs - audio.durationMs)
    : 0;
  const durationWarning = durationDifference > 3000;
  const summary = useMemo(
    () => (candidate ? summarizeAutoTimingCandidate(candidate) : null),
    [candidate]
  );

  const install = async (id: AutoTimingModelId, task: CreatorTask, onProgress: (next: AutoTimingProgress) => void): Promise<void> => {
    setInstalling(id);
    setInstallProgress(null);
    setError("");
    onProgress({ phase: "downloading", progress: 0, message: "Checking timing module files…" });
    try {
      await autoTimingModelRepository.install(id, (next) => {
        if (!task.isCurrent()) return;
        const fraction = clampAutoTimingProgress(next.progress);
        setInstallProgress((previous) => previous?.file === next.file &&
          Math.floor(previous.progress * 100) === Math.floor(fraction * 100)
          ? previous : { ...next, progress: fraction });
        onProgress({
          phase: "downloading",
          progress: fraction,
          message: fraction === 1 ? "Verifying and saving timing module…" : `Downloading timing module… ${Math.floor(fraction * 100)}%`,
        });
      }, task.signal);
      task.throwIfCancelled();
      await refreshStatuses(task);
      task.throwIfCancelled();
      toast.success(`${id === "fast" ? "Fast" : "Accurate"} timing module installed.`);
    } finally {
      if (task.isCurrent()) {
        setInstalling(null);
        setInstallProgress(null);
      }
    }
  };

  const run = async (requestedModel: AutoTimingModelId = selectedModel) => {
    const task = taskScopeRef.current.begin();
    const projectSnapshot = project;
    const projectFingerprint = creatorProjectCheckpoint(projectSnapshot);
    const currentStatus = statuses.find((status) => status.definition.id === requestedModel);
    const needsDownload = !currentStatus?.installed || !currentStatus.verified || currentStatus.updateAvailable;
    const trackProgress = createAutoTimingProgressTracker(needsDownload);
    let previousProgress: AutoTimingProgress | undefined;
    const reportProgress = (update: AutoTimingProgress) => {
      if (!task.isCurrent()) return;
      const next = trackProgress(update);
      // Download streams can report thousands of chunks. Update React only
      // for visible changes, without a timer that could outlive cancellation.
      if (previousProgress?.phase === next.phase && previousProgress.message === next.message &&
        Math.floor(previousProgress.progress * 1000) === Math.floor(next.progress * 1000)) return;
      previousProgress = next;
      setProgress(next);
    };
    setError("");
    setCandidate(null);
    setReplaceConfirmed(false);
    reportProgress({ phase: "preparing", progress: 1, message: "Preparing local audio…" });
    try {
      if (needsDownload) {
        await install(requestedModel, task, reportProgress);
      }
      task.throwIfCancelled();
      const { manifest, definition } =
        await autoTimingModelRepository.getInstalledDefinition(requestedModel);
      task.throwIfCancelled();
      const transcription = await transcribeAutoTimingAudio({
        pcm: audio.pcm,
        language: autoTimingLanguage(projectSnapshot.metadata.language, projectSnapshot.lines.map(lineText).join("\n")),
        manifest,
        definition,
        signal: task.signal,
        onProgress: reportProgress,
      });
      task.throwIfCancelled();
      if (transcription.words.length === 0) {
        throw new Error("The timing model could not recognize any sung words in this audio.");
      }
      reportProgress({ phase: "aligning", progress: 0, message: "Aligning the transcript to your lyrics…" });
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      task.throwIfCancelled();
      const nextCandidate = buildAutoTimingCandidate({
        project: projectSnapshot,
        projectFingerprint,
        modelId: requestedModel,
        modelVersion: definition.version,
        durationMs: audio.durationMs,
        words: transcription.words,
        energyFrames: audio.energyFrames,
      });
      const supportedLines = new Set(nextCandidate.supportedLineIds);
      if (Object.keys(nextCandidate.fragments).length === 0 || supportedLines.size === 0) {
        throw new Error("No usable timings matched these lyrics. Check the lyrics and matching audio, then try again.");
      }
      setCandidate(nextCandidate);
      // Entirely guessed lines are opt-in rather than silently spreading a
      // failed singing transcription across known instrumental sections.
      setIncludedLineIds(supportedLines);
      reportProgress({ phase: "review", progress: 1, message: "Review the proposed timings." });
      $creatorAutoTimingLastModel.set(requestedModel);
      toast.success(`Auto-time finished locally with ${transcription.device.toUpperCase()}.`);
    } catch (reason) {
      if (!task.isCurrent()) return;
      if ((reason as Error)?.name !== "AbortError") {
        const message = reason instanceof Error ? reason.message : "Automatic timing failed.";
        setError(message);
        toast.error(message);
      }
      setProgress(null);
    }
  };

  const cancelProcessing = () => {
    taskScopeRef.current.cancel();
    setProgress(null);
    setInstalling(null);
    setInstallProgress(null);
    setLoadingStatuses(false);
  };

  const close = () => {
    taskScopeRef.current.cancel();
    onCloseRef.current();
  };

  const apply = () => {
    if (!candidate) return;
    if (creatorProjectCheckpoint(project) !== candidate.projectFingerprint) {
      setError("The lyrics changed while Auto-time was running. Run Auto-time again before applying.");
      return;
    }
    if (applyMode === "replace" && !replaceConfirmed) {
      setError("Confirm that existing timings may be replaced before applying.");
      return;
    }
    const next = applyAutoTimingCandidate(project, candidate, {
      mode: applyMode,
      includedLineIds,
    });
    if (creatorProjectCheckpoint(next) === creatorProjectCheckpoint(project)) {
      setError(applyMode === "missing"
        ? "There are no missing timings to fill in the selected lines. Select other lines or choose Replace all timings."
        : "The selected lines already have these timings. Nothing needs to be applied.");
      return;
    }
    onApply(next, project);
    toast.success("Automatic timings applied. You can still edit every word normally.");
    onClose();
  };

  const visibleLines = project.lines.filter((line) => {
    if (!candidate || !showUncertainOnly) return true;
    return Object.values(candidate.fragments).some(
      (fragment) =>
        fragment.lineId === line.id &&
        (fragment.confidence === "low" || fragment.confidence === "estimated")
    );
  });

  return (
    <div className="il-creator-autotime-backdrop" role="presentation">
      <section
        ref={dialogRef}
        className="il-creator-autotime"
        role="dialog"
        aria-modal="true"
        aria-labelledby="il-creator-autotime-title"
        tabIndex={-1}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Escape") {
            event.preventDefault();
            close();
          } else if (event.key === "Tab") {
            const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
              'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]'
            ) ?? []).filter((element) => element.getClientRects().length > 0);
            const first = focusable[0];
            const last = focusable.at(-1);
            if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
              event.preventDefault();
              last?.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault();
              first?.focus();
            }
          }
        }}
      >
        <header>
          <div>
            <span className="il-creator-inspector__eyebrow">On-device timing</span>
            <h2 id="il-creator-autotime-title">Auto-time lyrics</h2>
            <p>Audio stays on this device. Only the timings you apply become part of the draft.</p>
          </div>
          <button
            type="button"
            aria-label="Close Auto-time"
            onClick={close}
          >
            ×
          </button>
        </header>

        <div className="il-creator-autotime__body">
          <aside className="il-creator-autotime__setup">
            <div className="il-creator-autotime__audio">
              <span>Local audio</span>
              <strong>{audioName}</strong>
              <small>{formatClock(audio.durationMs)}</small>
            </div>
            <button type="button" onClick={onTogglePlayback}>
              Play / Pause local audio
            </button>
            {durationWarning && (
              <div className="il-creator-autotime__warning" role="alert">
                This audio differs from the Spotify track duration by {Math.round(durationDifference / 1000)} seconds.
                Confirm that it is the same recording before applying timings.
              </div>
            )}
            <fieldset disabled={running || installing !== null}>
              <legend>Timing module</legend>
              {loadingStatuses && <p className="il-creator-muted">Checking installed modules…</p>}
              {statuses.map((status) => (
                <label
                  key={status.definition.id}
                  className={`il-creator-autotime__model${selectedModel === status.definition.id ? " is-selected" : ""}`}
                >
                  <input
                    type="radio"
                    name="auto-timing-model"
                    value={status.definition.id}
                    checked={selectedModel === status.definition.id}
                    onChange={() => {
                      setSelectedModel(status.definition.id);
                      setCandidate(null);
                      setProgress(null);
                      setReplaceConfirmed(false);
                    }}
                  />
                  <span>
                    <strong>{status.definition.displayName}</strong>
                    <small>{status.definition.description}</small>
                  </span>
                  <em>
                    {status.updateAvailable ? "Update available" : status.installed ? "Installed" : formatBytes(status.definition.size)}
                  </em>
                </label>
              ))}
            </fieldset>
            {selectedStatus && (
              <button
                type="button"
                className="il-creator-primary-button"
                disabled={running || installing !== null || loadingStatuses}
                onClick={() => void run()}
              >
                {installing !== null
                  ? (installProgress?.progress === 1 ? "Finishing installation…" : `Downloading ${Math.floor((installProgress?.progress ?? 0) * 100)}%`)
                  : running ? "Auto-timing…"
                    : !selectedStatus.installed || selectedStatus.updateAvailable
                      ? `${selectedStatus.updateAvailable ? "Update" : "Download"} ${selectedStatus.definition.displayName} and start`
                      : candidate ? "Run again" : "Start Auto-time"}
              </button>
            )}
            {(running || progress?.phase === "review") && progress && (
              <AutoTimingProgressBar progress={progress} />
            )}
            {(running || installing !== null) && (
              <button type="button" onClick={cancelProcessing}>
                {installing !== null ? "Cancel download" : "Cancel processing"}
              </button>
            )}
            {error && <div className="il-creator-autotime__error" role="alert">{error}</div>}
            {error && statuses.length === 0 && !loadingStatuses && (
              <button type="button" onClick={() => void refreshStatuses()}>Retry loading modules</button>
            )}
          </aside>

          <main className="il-creator-autotime__review">
            {!candidate ? (
              <div className="il-creator-autotime__empty">
                <strong>Nothing will change until you review and apply.</strong>
                <span>Auto-time matches the local transcription to the lyrics already in this project.</span>
              </div>
            ) : (
              <>
                <p className="il-creator-muted">
                  Confidence describes how closely recognized words match your lyrics.
                  Listen and adjust the proposed timings, especially for singing.
                </p>
                <div className="il-creator-autotime__reviewbar">
                  <div className="il-creator-autotime__confidence" aria-label="Lyric-match confidence summary">
                    {summary && Object.entries(summary).map(([confidence, count]) => (
                      <span className={`is-${confidence}`} key={confidence}>
                        {confidence} {count}
                      </span>
                    ))}
                  </div>
                  <label>
                    <input
                      type="checkbox"
                      checked={showUncertainOnly}
                      onChange={(event) => setShowUncertainOnly(event.currentTarget.checked)}
                    />
                    Low confidence only
                  </label>
                </div>
                <div className="il-creator-autotime__lines">
                  {visibleLines.map((line) => {
                    const lineIndex = project.lines.findIndex((candidateLine) => candidateLine.id === line.id);
                    const fragments = Object.values(candidate.fragments).filter(
                      (fragment) => fragment.lineId === line.id
                    );
                    const start = fragments.length
                      ? Math.min(...fragments.map((fragment) => fragment.startTimeMs))
                      : 0;
                    const end = fragments.length
                      ? Math.max(...fragments.map((fragment) => fragment.endTimeMs))
                      : 0;
                    const uncertain = fragments.some(
                      (fragment) =>
                        fragment.confidence === "low" || fragment.confidence === "estimated"
                    );
                    return (
                      <div
                        key={line.id}
                        className={`il-creator-autotime__line${uncertain ? " is-uncertain" : ""}`}
                      >
                        <input
                          type="checkbox"
                          aria-label={`Apply timings for line ${lineIndex + 1}`}
                          disabled={fragments.length === 0}
                          checked={includedLineIds.has(line.id)}
                          onChange={(event) => {
                            const next = new Set(includedLineIds);
                            if (event.currentTarget.checked) next.add(line.id);
                            else next.delete(line.id);
                            setIncludedLineIds(next);
                          }}
                        />
                        <button
                          type="button"
                          disabled={fragments.length === 0}
                          onClick={() => onSeek(start)}
                          aria-label={`Seek to line ${lineIndex + 1}`}
                          title="Seek to this proposed timing"
                        >
                          ▶
                        </button>
                        <span>
                          <strong>{lineText(line) || `Line ${lineIndex + 1}`}</strong>
                          <span className="il-creator-autotime__fragments" aria-label="Proposed word and fragment timings">
                            {line.tokens.flatMap((token) => token.fragments.map((fragment) => {
                              const timing = candidate.fragments[fragment.id];
                              if (!timing) return null;
                              const description = `${fragment.text}: ${formatClock(timing.startTimeMs)} – ${formatClock(timing.endTimeMs)}, ${timing.confidence} lyric-match confidence`;
                              return (
                                <button
                                  key={fragment.id}
                                  type="button"
                                  className={`is-${timing.confidence}`}
                                  title={description}
                                  aria-label={`Seek to ${description}`}
                                  onClick={() => onSeek(timing.startTimeMs)}
                                >
                                  {fragment.text}
                                  <small>{timing.confidence}</small>
                                </button>
                              );
                            }))}
                          </span>
                          <small>
                            {formatClock(start)} – {formatClock(end)}
                            {line.isBackground ? " · Background" : ""}
                            {line.isSecondSpeaker ? " · Speaker 2" : ""}
                          </small>
                        </span>
                        <em>{fragments.length === 0 ? "No proposal" : uncertain ? "Review" : "Matched"}</em>
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </main>
        </div>

        <footer>
          <div className="il-creator-autotime__apply-mode">
            <label>
              <input
                type="radio"
                name="auto-timing-apply-mode"
                checked={applyMode === "missing"}
                onChange={() => {
                  setApplyMode("missing");
                  setReplaceConfirmed(false);
                }}
              />
              Fill missing timings
            </label>
            <label>
              <input
                type="radio"
                name="auto-timing-apply-mode"
                checked={applyMode === "replace"}
                onChange={() => setApplyMode("replace")}
              />
              Replace all timings
            </label>
            {applyMode === "replace" && (
              <label className="il-creator-autotime__replace-confirm">
                <input
                  type="checkbox"
                  checked={replaceConfirmed}
                  onChange={(event) => setReplaceConfirmed(event.currentTarget.checked)}
                />
                I understand existing timings will be replaced
              </label>
            )}
          </div>
          <button type="button" onClick={close}>Cancel</button>
          <button
            type="button"
            className="il-creator-primary-button"
            disabled={!candidate || includedLineIds.size === 0 || running || (applyMode === "replace" && !replaceConfirmed)}
            onClick={apply}
          >
            Apply selected timings
          </button>
        </footer>
      </section>
    </div>
  );
}
