import React, { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import {
  CREATOR_LYRICS_SOURCE_OPTIONS,
  creatorLyricsSourcePreferenceLabel,
  creatorMetadataFromTrack,
  currentSpotifyTrack,
  getSpotifyTrack,
  loadLyricsForCreator,
  searchSpotifyTracks,
  type CreatorLyricsSourcePreference,
  type CreatorTrack,
} from "./data.ts";
import {
  groupCreatorDraftsBySong,
  listCreatorDrafts,
  loadCreatorDraft,
  removeCreatorDraft,
  saveCreatorDraft,
} from "./drafts.ts";
import {
  cloneCreatorProject,
  createEmptyProject,
  createFragment,
  createLine,
  createToken,
  creatorProjectFromLyrics,
  importPlainText,
  lineText,
  moveCreatorTokenWithinLine,
  type CreatorLine,
  type CreatorMetadata,
  type CreatorProject,
} from "./model.ts";
import PreviewStage from "./PreviewStage.tsx";
import CreatorTimeWorkspace from "./CreatorTimeWorkspace.tsx";
import CreatorWaveformTimeline from "./CreatorWaveformTimeline.tsx";
import CreatorDraftLibrary from "./CreatorDraftLibrary.tsx";
import IcyLogo from "./IcyLogo.tsx";
import { creatorPlaybackActivity } from "./activeWord.ts";
import {
  applyCreatorPlaybackSpeed,
  applyTimingAction,
  creatorTimingTargets,
  DEFAULT_CREATOR_TIMING_OPTIONS,
  formatCreatorTime,
  type CreatorTimingOptions,
} from "./timing.ts";
import { creatorTimingActionFromKeyboardEvent, isCreatorPlaybackShortcut } from "./interaction.ts";
import {
  CREATOR_TTML_FILE_ACCEPT,
  downloadCreatorTTML,
  openCreatorFilePicker,
  readCreatorTextFile,
} from "./fileActions.ts";
import { CreatorSearchController, creatorSearchKeyResult } from "./searchController.ts";
import { restoreCreatorDraftPlayback } from "./draftPlayback.ts";
import { CreatorWorkspaceScrollTracker } from "./workspaceNavigation.ts";
import { CreatorValidationError, parseCreatorTTML, serializeCreatorTTML } from "./ttml.ts";
import type { CreatorDraftRecord } from "../../../utils/db.ts";
import { acquireCreatorNativeDialog } from "./creatorChrome.ts";
import { getDynamicAudioAnalysis } from "../../../utils/audioAnalysis.ts";
import {
  creatorWaveformFromPcmChannels,
  creatorWaveformFromSpotifyAnalysis,
  type CreatorWaveform,
} from "./waveform.ts";
import {
  creatorProjectCheckpoint,
  maybeStartCreatorTrackPlayback,
  requestCreatorSourceSwitch,
  type CreatorTrackLoadOptions,
} from "./sourceSwitch.ts";

type CreatorMode = "edit" | "time" | "preview";

interface LyricCreatorProps {
  onClose: () => void;
}

function usePlaybackPosition(
  localAudio: React.RefObject<HTMLAudioElement | null>,
  expectedSpotifyTrack: CreatorTrack | null
) {
  const [positionMs, setPositionMs] = useState(0);
  const [durationMs, setDurationMs] = useState(0);

  useEffect(() => {
    let frame = 0;
    let lastUpdate = 0;
    const update = () => {
      const now = performance.now();
      if (now - lastUpdate < 40) {
        frame = requestAnimationFrame(update);
        return;
      }
      lastUpdate = now;
      const audio = localAudio.current;
      if (audio?.src) {
        setPositionMs(Math.round(audio.currentTime * 1000));
        setDurationMs(Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : 0);
      } else {
        const playerUri = Spicetify.Player.data?.item?.uri;
        if (expectedSpotifyTrack && playerUri !== expectedSpotifyTrack.uri) {
          // Never draw the previous song's playhead while Spotify changes track.
          setPositionMs(0);
          setDurationMs(Math.max(0, expectedSpotifyTrack.durationMs));
        } else {
          setPositionMs(Math.max(0, Math.round(Spicetify.Player.getProgress?.() ?? 0)));
          setDurationMs(
            Math.max(
              0,
              Math.round(Spicetify.Player.getDuration?.() ?? expectedSpotifyTrack?.durationMs ?? 0)
            )
          );
        }
      }
      frame = requestAnimationFrame(update);
    };
    frame = requestAnimationFrame(update);
    return () => cancelAnimationFrame(frame);
  }, [expectedSpotifyTrack, localAudio]);

  return { positionMs, durationMs };
}

function ArrayMetadataField({
  label,
  values,
  onChange,
}: {
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <fieldset className="il-creator-array-field">
      <legend>{label}</legend>
      {(values.length > 0 ? values : [""]).map((value, index) => (
        <div className="il-creator-array-field__row" key={`${label}-${index}`}>
          <input
            value={value}
            aria-label={`${label} ${index + 1}`}
            onChange={(event) => {
              const next = values.length > 0 ? [...values] : [""];
              next[index] = event.currentTarget.value;
              onChange(next);
            }}
          />
          <button
            type="button"
            aria-label={`Remove ${label} ${index + 1}`}
            onClick={() => onChange(values.filter((_, valueIndex) => valueIndex !== index))}
          >
            −
          </button>
        </div>
      ))}
      <button
        type="button"
        className="il-creator-text-button"
        onClick={() => onChange([...values, ""])}
      >
        + Add {label.toLowerCase()}
      </button>
    </fieldset>
  );
}

function MetadataInspector({
  metadata,
  onChange,
  nameInputRef,
}: {
  metadata: CreatorMetadata;
  onChange: (metadata: CreatorMetadata) => void;
  nameInputRef?: React.RefObject<HTMLInputElement | null>;
}) {
  const set = <K extends keyof CreatorMetadata>(key: K, value: CreatorMetadata[K]) => {
    onChange({ ...metadata, [key]: value });
  };

  return (
    <div className="il-creator-metadata-editor">
      <p className="il-creator-muted">Repeatable values are kept as separate TTML entries.</p>
      <label>
        Name
        <input
          ref={nameInputRef}
          value={metadata.name}
          onChange={(event) => set("name", event.currentTarget.value)}
        />
      </label>
      <ArrayMetadataField
        label="Artists"
        values={metadata.artists}
        onChange={(value) => set("artists", value)}
      />
      <ArrayMetadataField
        label="Songwriters"
        values={metadata.songwriters}
        onChange={(value) => set("songwriters", value)}
      />
      <ArrayMetadataField
        label="Album names"
        values={metadata.albums}
        onChange={(value) => set("albums", value)}
      />
      <label>
        Spotify Track ID
        <input
          value={metadata.spotifyTrackId}
          onChange={(event) => set("spotifyTrackId", event.currentTarget.value)}
        />
      </label>
      <label>
        Apple Music Track ID
        <input
          value={metadata.appleMusicTrackId}
          onChange={(event) => set("appleMusicTrackId", event.currentTarget.value)}
        />
      </label>
      <label>
        ISRC
        <input value={metadata.isrc} onChange={(event) => set("isrc", event.currentTarget.value)} />
      </label>
      <label>
        Lyric language (BCP-47)
        <input
          value={metadata.language}
          placeholder="en, ja, ko…"
          onChange={(event) => set("language", event.currentTarget.value)}
        />
      </label>
    </div>
  );
}

function EditWorkspace({
  project,
  setProject,
  importText,
  setImportText,
  activeLine,
  setActiveLine,
  positionMs,
}: {
  project: CreatorProject;
  setProject: React.Dispatch<React.SetStateAction<CreatorProject>>;
  importText: string;
  setImportText: (value: string) => void;
  activeLine: number;
  setActiveLine: (value: number) => void;
  positionMs: number;
}) {
  const [draggedToken, setDraggedToken] = useState<{
    lineIndex: number;
    tokenIndex: number;
  } | null>(null);
  const [dropToken, setDropToken] = useState<{ lineIndex: number; tokenIndex: number } | null>(
    null
  );
  const playbackActivity = useMemo(
    () => creatorPlaybackActivity(project, positionMs),
    [project, positionMs]
  );
  const mutate = (updater: (draft: CreatorProject) => void) => {
    setProject((current) => {
      const draft = cloneCreatorProject(current);
      updater(draft);
      return draft;
    });
  };

  const attachBackground = (line: CreatorLine, lineIndex: number, enabled: boolean) => {
    line.isBackground = enabled;
    if (!enabled) {
      line.attachedToLineId = null;
      return;
    }
    line.attachedToLineId =
      project.lines
        .slice(0, lineIndex)
        .reverse()
        .find((candidate) => !candidate.isBackground)?.id ?? null;
  };

  return (
    <main className="il-creator-edit" aria-label="Lyric editor">
      <details className="il-creator-import">
        <summary>Import plain text</summary>
        <p>Use a backslash (\) between words. Each text line becomes one lyric line.</p>
        <textarea
          value={importText}
          onChange={(event) => setImportText(event.currentTarget.value)}
          placeholder={"We\\are\\the\\music\nAnd\\we\\make\\the\\dreams"}
        />
        <button
          type="button"
          className="il-creator-primary-button"
          onClick={() => {
            const lines = importPlainText(importText);
            if (lines.length === 0) {
              toast.error("Paste at least one lyric line first.");
              return;
            }
            setProject((current) => ({ ...current, lines }));
            setActiveLine(0);
          }}
        >
          Replace lyrics with text
        </button>
      </details>

      <div className="il-creator-line-editor">
        {project.lines.map((line, lineIndex) => (
          <article
            id={`il-creator-line-${line.id}`}
            key={line.id}
            className={`il-creator-line-card${activeLine === lineIndex ? " is-active" : ""}`}
            onFocus={() => setActiveLine(lineIndex)}
            onPointerDown={() => setActiveLine(lineIndex)}
          >
            <header>
              <div>
                <span>Line {lineIndex + 1}</span>
                <strong>{lineText(line) || "Empty line"}</strong>
              </div>
              <div className="il-creator-line-flags">
                <label>
                  <input
                    type="checkbox"
                    checked={line.isBackground}
                    onChange={(event) => {
                      const checked = event.currentTarget.checked;
                      mutate((draft) => {
                        attachBackground(draft.lines[lineIndex], lineIndex, checked);
                      });
                    }}
                  />
                  Background
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={line.isSecondSpeaker}
                    onChange={(event) => {
                      const checked = event.currentTarget.checked;
                      mutate((draft) => {
                        draft.lines[lineIndex].isSecondSpeaker = checked;
                      });
                    }}
                  />
                  Speaker 2
                </label>
              </div>
            </header>

            <div className="il-creator-token-row">
              {line.tokens.map((token, tokenIndex) => (
                <div
                  className={`il-creator-token${playbackActivity.tokenIds.has(token.id) ? " is-playing" : ""}${draggedToken?.lineIndex === lineIndex && draggedToken.tokenIndex === tokenIndex ? " is-dragging" : ""}${dropToken?.lineIndex === lineIndex && dropToken.tokenIndex === tokenIndex ? " is-drop-target" : ""}`}
                  key={token.id}
                  onDragOver={(event) => {
                    if (!draggedToken || draggedToken.lineIndex !== lineIndex) return;
                    event.preventDefault();
                    event.dataTransfer.dropEffect = "move";
                    setDropToken({ lineIndex, tokenIndex });
                  }}
                  onDrop={(event) => {
                    event.preventDefault();
                    if (!draggedToken || draggedToken.lineIndex !== lineIndex) return;
                    mutate((draft) => {
                      moveCreatorTokenWithinLine(
                        draft.lines[lineIndex],
                        draggedToken.tokenIndex,
                        tokenIndex
                      );
                    });
                    setActiveLine(lineIndex);
                    setDraggedToken(null);
                    setDropToken(null);
                  }}
                >
                  <button
                    type="button"
                    className="il-creator-token__drag-handle"
                    draggable
                    aria-label={`Move word ${tokenIndex + 1}`}
                    title="Drag to move this word within the line"
                    onDragStart={(event) => {
                      event.dataTransfer.effectAllowed = "move";
                      event.dataTransfer.setData("text/plain", token.id);
                      setDraggedToken({ lineIndex, tokenIndex });
                      setDropToken({ lineIndex, tokenIndex });
                    }}
                    onDragEnd={() => {
                      setDraggedToken(null);
                      setDropToken(null);
                    }}
                    onKeyDown={(event) => {
                      if (!event.altKey || !["ArrowLeft", "ArrowRight"].includes(event.key)) return;
                      event.preventDefault();
                      const nextIndex = tokenIndex + (event.key === "ArrowLeft" ? -1 : 1);
                      mutate((draft) => {
                        moveCreatorTokenWithinLine(draft.lines[lineIndex], tokenIndex, nextIndex);
                      });
                    }}
                  >
                    <span aria-hidden="true">⠿</span>
                  </button>
                  <div className="il-creator-fragments">
                    {token.fragments.map((fragment, fragmentIndex) => (
                      <div
                        className={`il-creator-fragment${playbackActivity.fragmentIds.has(fragment.id) ? " is-playing" : ""}`}
                        key={fragment.id}
                      >
                        <input
                          value={fragment.text}
                          aria-label={`Line ${lineIndex + 1}, word ${tokenIndex + 1}, fragment ${fragmentIndex + 1}`}
                          onChange={(event) => {
                            const value = event.currentTarget.value;
                            mutate((draft) => {
                              draft.lines[lineIndex].tokens[tokenIndex].fragments[
                                fragmentIndex
                              ].text = value;
                            });
                          }}
                        />
                        {token.fragments.length > 1 && (
                          <button
                            type="button"
                            title="Remove fragment"
                            aria-label="Remove word fragment"
                            onClick={() =>
                              mutate((draft) => {
                                draft.lines[lineIndex].tokens[tokenIndex].fragments.splice(
                                  fragmentIndex,
                                  1
                                );
                              })
                            }
                          >
                            ×
                          </button>
                        )}
                      </div>
                    ))}
                  </div>
                  <div className="il-creator-token__tools">
                    <button
                      type="button"
                      onClick={() =>
                        mutate((draft) => {
                          draft.lines[lineIndex].tokens[tokenIndex].fragments.push(
                            createFragment()
                          );
                        })
                      }
                    >
                      + fragment
                    </button>
                    <label title="Exact separator written after this word">
                      gap
                      <input
                        value={token.boundaryAfter}
                        aria-label={`Separator after word ${tokenIndex + 1}`}
                        onChange={(event) => {
                          const value = event.currentTarget.value;
                          mutate((draft) => {
                            draft.lines[lineIndex].tokens[tokenIndex].boundaryAfter = value;
                          });
                        }}
                      />
                    </label>
                    <button
                      type="button"
                      aria-label={`Remove word ${tokenIndex + 1}`}
                      onClick={() =>
                        mutate((draft) => {
                          const tokens = draft.lines[lineIndex].tokens;
                          tokens.splice(tokenIndex, 1);
                          if (tokens.length === 0) tokens.push(createToken());
                        })
                      }
                    >
                      Remove
                    </button>
                  </div>
                </div>
              ))}
            </div>

            <footer>
              <button
                type="button"
                onClick={() =>
                  mutate((draft) => {
                    const token = createToken();
                    const previous = draft.lines[lineIndex].tokens.at(-1);
                    if (previous) previous.boundaryAfter ||= " ";
                    draft.lines[lineIndex].tokens.push(token);
                  })
                }
              >
                + Word
              </button>
              <button
                type="button"
                onClick={() =>
                  mutate((draft) => {
                    draft.lines.splice(lineIndex + 1, 0, createLine());
                  })
                }
              >
                + Line below
              </button>
              <button
                type="button"
                className="is-danger"
                onClick={() => {
                  mutate((draft) => {
                    const removedId = draft.lines[lineIndex].id;
                    draft.lines.splice(lineIndex, 1);
                    if (draft.lines.length === 0) draft.lines.push(createLine());
                    draft.lines.forEach((candidate) => {
                      if (candidate.attachedToLineId === removedId) {
                        candidate.attachedToLineId = null;
                      }
                    });
                  });
                  setActiveLine(Math.max(0, lineIndex - 1));
                }}
              >
                Delete line
              </button>
            </footer>
          </article>
        ))}
      </div>
    </main>
  );
}

export default function LyricCreator({ onClose }: LyricCreatorProps) {
  const [mode, setMode] = useState<CreatorMode>("edit");
  const [workflowStep, setWorkflowStep] = useState(1);
  const [project, setProject] = useState(() => createEmptyProject());
  const [activeLine, setActiveLine] = useState(0);
  const [targetIndex, setTargetIndex] = useState(0);
  const [timingOptions, setTimingOptions] = useState<CreatorTimingOptions>(
    DEFAULT_CREATOR_TIMING_OPTIONS
  );
  const [importText, setImportText] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<CreatorTrack[]>([]);
  const [activeSearchIndex, setActiveSearchIndex] = useState(-1);
  const [searching, setSearching] = useState(false);
  const [loadingLyrics, setLoadingLyrics] = useState(false);
  const [sourcePreference, setSourcePreference] = useState<CreatorLyricsSourcePreference>("auto");
  const [selectedTrack, setSelectedTrack] = useState<CreatorTrack | null>(null);
  const [actionStatus, setActionStatus] = useState("Ready");
  const [draftId, setDraftId] = useState<string | undefined>();
  const [drafts, setDrafts] = useState<CreatorDraftRecord[]>([]);
  const [draftLibraryOpen, setDraftLibraryOpen] = useState(false);
  const [localAudioUrl, setLocalAudioUrl] = useState("");
  const [localAudioName, setLocalAudioName] = useState("");
  const [speed, setSpeed] = useState(1);
  const [speedMessage, setSpeedMessage] = useState("Spotify music timing uses 1× playback.");
  const [metadataDraft, setMetadataDraft] = useState<CreatorMetadata | null>(null);
  const [waveform, setWaveform] = useState<CreatorWaveform>(() =>
    creatorWaveformFromSpotifyAnalysis(null)
  );
  const searchInputRef = useRef<HTMLInputElement>(null);
  const localAudioRef = useRef<HTMLAudioElement>(null);
  const localAudioUrlRef = useRef("");
  const ttmlInputRef = useRef<HTMLInputElement>(null);
  const audioInputRef = useRef<HTMLInputElement>(null);
  const nativeDialogReleaseRef = useRef<(() => void) | null>(null);
  const lyricsAbortRef = useRef<AbortController | null>(null);
  const draftOpenRequestRef = useRef(0);
  const programmaticSearchValueRef = useRef("");
  const metadataButtonRef = useRef<HTMLButtonElement>(null);
  const metadataNameInputRef = useRef<HTMLInputElement>(null);
  const metadataDialogRef = useRef<HTMLElement>(null);
  const projectRef = useRef(project);
  const projectCheckpointRef = useRef(creatorProjectCheckpoint(project));
  const targetIndexRef = useRef(targetIndex);
  const timingOptionsRef = useRef(timingOptions);
  const modeRef = useRef(mode);
  const togglePlaybackRef = useRef<() => void>(() => undefined);
  const workspaceScrollTrackerRef = useRef(new CreatorWorkspaceScrollTracker());
  const searchController = useMemo(
    () => new CreatorSearchController(searchSpotifyTracks, { debounceMs: 260 }),
    []
  );
  const { positionMs, durationMs } = usePlaybackPosition(localAudioRef, selectedTrack);

  projectRef.current = project;
  targetIndexRef.current = targetIndex;
  timingOptionsRef.current = timingOptions;
  modeRef.current = mode;

  const currentWorkflowStep = workflowStep;
  const draftGroups = useMemo(() => groupCreatorDraftsBySong(drafts), [drafts]);

  const refreshDrafts = async () => setDrafts(await listCreatorDrafts());

  const finishNativeDialog = () => {
    nativeDialogReleaseRef.current?.();
    nativeDialogReleaseRef.current = null;
  };

  const openNativeFilePicker = (input: HTMLInputElement | null) => {
    finishNativeDialog();
    nativeDialogReleaseRef.current = acquireCreatorNativeDialog();
    if (!openCreatorFilePicker(input)) finishNativeDialog();
  };

  useEffect(() => {
    document.body.classList.add("IcyLyricCreatorOpen");
    void refreshDrafts();
    const ttmlInput = ttmlInputRef.current;
    const audioInput = audioInputRef.current;
    ttmlInput?.addEventListener("cancel", finishNativeDialog);
    audioInput?.addEventListener("cancel", finishNativeDialog);
    return () => {
      ttmlInput?.removeEventListener("cancel", finishNativeDialog);
      audioInput?.removeEventListener("cancel", finishNativeDialog);
      document.body.classList.remove("IcyLyricCreatorOpen");
      searchController.cancel();
      lyricsAbortRef.current?.abort();
      finishNativeDialog();
      if (localAudioUrlRef.current) URL.revokeObjectURL(localAudioUrlRef.current);
    };
  }, [searchController]);

  useEffect(() => {
    localAudioUrlRef.current = localAudioUrl;
  }, [localAudioUrl]);

  useEffect(() => {
    if (localAudioUrl) return;
    const uri = project.uri;
    let cancelled = false;
    if (!uri || uri.startsWith("spotify:local:")) {
      setWaveform(creatorWaveformFromSpotifyAnalysis(null, { durationMs }));
      return;
    }

    void getDynamicAudioAnalysis(uri).then((analysis) => {
      if (cancelled || localAudioUrlRef.current) return;
      setWaveform(
        creatorWaveformFromSpotifyAnalysis(analysis, {
          durationMs: selectedTrack?.durationMs || durationMs || undefined,
        })
      );
    });
    return () => {
      cancelled = true;
    };
  }, [durationMs, localAudioUrl, project.uri, selectedTrack?.durationMs]);

  useEffect(() => {
    if (programmaticSearchValueRef.current === searchQuery) {
      programmaticSearchValueRef.current = "";
      return;
    }
    searchController.update(searchQuery, {
      onPending: setSearching,
      onResults: (results) => {
        setSearchResults(results);
        setActiveSearchIndex(results.length ? 0 : -1);
      },
      onError: (error) => {
        setActionStatus(error.message);
        toast.error(error.message);
      },
    });
  }, [searchController, searchQuery]);

  const getPlaybackPosition = () => {
    const audio = localAudioRef.current;
    return audio?.src
      ? Math.round(audio.currentTime * 1000)
      : Math.round(Spicetify.Player.getProgress?.() ?? 0);
  };

  const chooseTrack = async (
    track: CreatorTrack,
    requestedSource: CreatorLyricsSourcePreference = sourcePreference,
    options: CreatorTrackLoadOptions = {}
  ) => {
    searchController.cancel();
    setSearching(false);
    lyricsAbortRef.current?.abort();
    const controller = new AbortController();
    lyricsAbortRef.current = controller;
    setSelectedTrack(track);
    setWorkflowStep(2);
    setLoadingLyrics(true);
    setSearchResults([]);
    setActiveSearchIndex(-1);
    const selectedSearchValue = `${track.name} — ${track.artists.join(", ")}`;
    programmaticSearchValueRef.current = selectedSearchValue;
    setSearchQuery(selectedSearchValue);
    const requestedSourceLabel = creatorLyricsSourcePreferenceLabel(requestedSource);
    setActionStatus(
      requestedSource === "auto"
        ? `Loading lyrics for ${track.name}…`
        : `Loading ${requestedSourceLabel} lyrics for ${track.name}…`
    );
    maybeStartCreatorTrackPlayback(
      track.uri,
      Boolean(localAudioRef.current?.src),
      options,
      (uri) => Spicetify.Player.playUri(uri)
    );

    try {
      const loaded = await loadLyricsForCreator(track.uri, controller.signal, requestedSource);
      if (controller.signal.aborted) return;
      const next = loaded.lyrics
        ? creatorProjectFromLyrics(loaded.lyrics, creatorMetadataFromTrack(track))
        : createEmptyProject(track.uri);
      next.uri = track.uri;
      next.source = loaded.source;
      next.metadata = {
        ...next.metadata,
        ...creatorMetadataFromTrack(track),
        artists: track.artists,
        albums: track.album ? [track.album] : [],
        songwriters: next.metadata.songwriters,
        raw: next.metadata.raw,
      };
      projectCheckpointRef.current = creatorProjectCheckpoint(next);
      setProject(next);
      setDraftId(undefined);
      setTargetIndex(0);
      setActiveLine(0);
      setMode("edit");
      setWorkflowStep(2);
      setActionStatus(
        loaded.lyrics
          ? `Loaded ${track.name} from ${loaded.source.label}.`
          : requestedSource === "auto"
            ? `Selected ${track.name}. No existing lyrics were found, so a blank lyric is ready.`
            : `${loaded.source.label}. A blank lyric is ready.`
      );
    } catch (error) {
      if ((error as Error)?.name !== "AbortError") {
        const message = error instanceof Error ? error.message : "Could not load lyrics.";
        setActionStatus(message);
        toast.error(message);
      }
    } finally {
      if (!controller.signal.aborted) setLoadingLyrics(false);
    }
  };

  const runSearch = () => {
    searchController.searchNow(searchQuery, {
      onPending: setSearching,
      onResults: (results) => {
        setSearchResults(results);
        setActiveSearchIndex(results.length ? 0 : -1);
        setActionStatus(
          results.length
            ? `${results.length} Spotify result${results.length === 1 ? "" : "s"} found.`
            : "No matching Spotify tracks found."
        );
      },
      onError: (error) => {
        setActionStatus(error.message);
        toast.error(error.message);
      },
    });
  };

  const saveDraft = async () => {
    try {
      const record = await saveCreatorDraft(project, draftId, selectedTrack ?? undefined);
      projectCheckpointRef.current = creatorProjectCheckpoint(project);
      setDraftId(record.id);
      await refreshDrafts();
      setActionStatus(`Saved draft “${record.name}”.`);
      toast.success("Lyric Creator draft saved.");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not save the draft.";
      setActionStatus(message);
      toast.error(message);
    }
  };

  const saveTTML = () => {
    try {
      const raw = serializeCreatorTTML(project);
      const filename = downloadCreatorTTML(raw, project.metadata.name);
      projectCheckpointRef.current = creatorProjectCheckpoint(project);
      setActionStatus(`Exported ${filename}.`);
      toast.success("TTML downloaded.");
    } catch (error) {
      if (error instanceof CreatorValidationError) {
        const message = `${error.message} Save a draft if timing is not finished.`;
        setActionStatus(message);
        toast.error(message);
      } else {
        const message = error instanceof Error ? error.message : "TTML export failed.";
        setActionStatus(message);
        toast.error(message);
      }
    }
  };

  const openTTML = async (file: File) => {
    searchController.cancel();
    setSearching(false);
    lyricsAbortRef.current?.abort();
    try {
      const next = parseCreatorTTML(await readCreatorTextFile(file));
      projectCheckpointRef.current = creatorProjectCheckpoint(next);
      setProject(next);
      setSelectedTrack(null);
      programmaticSearchValueRef.current = "";
      setSearchQuery("");
      setSearchResults([]);
      setActiveSearchIndex(-1);
      setDraftId(undefined);
      setActiveLine(0);
      setTargetIndex(0);
      setMode("edit");
      setWorkflowStep(2);
      setActionStatus(`Opened ${file.name}.`);
      toast.success(`Opened ${file.name}.`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "This TTML could not be opened.";
      setActionStatus(message);
      toast.error(message);
    }
  };

  const togglePlayback = () => {
    const audio = localAudioRef.current;
    if (audio?.src) {
      if (audio.paused) {
        Spicetify.Player.pause();
        void audio.play();
      } else audio.pause();
      return;
    }
    Spicetify.Player.togglePlay();
  };
  togglePlaybackRef.current = togglePlayback;

  useEffect(() => {
    const consume = (event: KeyboardEvent) => {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
    };
    const isTimingKey = (event: KeyboardEvent) =>
      modeRef.current === "time" && creatorTimingActionFromKeyboardEvent(event) !== null;

    const onKeyDown = (event: KeyboardEvent) => {
      if (modeRef.current === "time") {
        const action = creatorTimingActionFromKeyboardEvent(event);
        if (action) {
          consume(event);
          const options = timingOptionsRef.current;
          const result = applyTimingAction(
            projectRef.current,
            targetIndexRef.current,
            action,
            getPlaybackPosition(),
            {
              offsetMs: options.offsetMs,
              ignoreBackground: options.ignoreBackground,
            }
          );
          projectRef.current = result.project;
          targetIndexRef.current = result.targetIndex;
          const nextTarget = creatorTimingTargets(result.project, {
            ignoreBackground: options.ignoreBackground,
          })[result.targetIndex];
          if (nextTarget) setActiveLine(nextTarget.lineIndex);
          setProject(result.project);
          setTargetIndex(result.targetIndex);
          return;
        }
      }

      if (isCreatorPlaybackShortcut(event)) {
        consume(event);
        togglePlaybackRef.current();
      }
    };
    const suppressShortcutRemainder = (event: KeyboardEvent) => {
      if (
        isTimingKey(event) ||
        isCreatorPlaybackShortcut({
          code: event.code,
          repeat: false,
          isComposing: event.isComposing,
          target: event.target,
        })
      ) {
        consume(event);
      }
    };

    window.addEventListener("keydown", onKeyDown, true);
    window.addEventListener("keypress", suppressShortcutRemainder, true);
    window.addEventListener("keyup", suppressShortcutRemainder, true);
    return () => {
      window.removeEventListener("keydown", onKeyDown, true);
      window.removeEventListener("keypress", suppressShortcutRemainder, true);
      window.removeEventListener("keyup", suppressShortcutRemainder, true);
    };
  }, []);

  const seekPlayback = (nextPositionMs: number) => {
    const clamped = Math.max(0, Math.min(durationMs || nextPositionMs, nextPositionMs));
    const audio = localAudioRef.current;
    if (audio?.src) audio.currentTime = clamped / 1000;
    else Spicetify.Player.seek(clamped);
  };

  const changeSpeed = async (nextSpeed: number) => {
    setSpeed(nextSpeed);
    const audio = localAudioRef.current;
    if (audio?.src) {
      audio.playbackRate = nextSpeed;
      setSpeedMessage(`Local timing audio is playing at ${nextSpeed}×.`);
      return;
    }

    try {
      const playerApi = (Spicetify.Platform as any)?.PlayerAPI;
      const result = await applyCreatorPlaybackSpeed(nextSpeed, {
        setSpeed: (value) => {
          if (typeof playerApi?.setSpeed !== "function") {
            throw new Error("Spotify did not expose playback-speed control.");
          }
          return playerApi.setSpeed(value);
        },
        readSpeed: () =>
          (Spicetify.Player.data as any)?.playback_speed ?? Spicetify.Player.data?.speed,
        mediaType: () => Spicetify.Player.data?.item?.mediaType,
      });
      setSpeedMessage(result.message);
      if (!result.applied) setSpeed(1);
    } catch (error) {
      setSpeed(1);
      setSpeedMessage(
        `${error instanceof Error ? error.message : "Speed change failed"} ` +
          "Choose a local audio file to time music below 1×."
      );
    }
  };

  const startNewProject = () => {
    searchController.cancel();
    setSearching(false);
    lyricsAbortRef.current?.abort();
    const next = createEmptyProject();
    projectCheckpointRef.current = creatorProjectCheckpoint(next);
    setProject(next);
    setSourcePreference("auto");
    setDraftId(undefined);
    setSelectedTrack(null);
    setSearchQuery("");
    setSearchResults([]);
    setActiveSearchIndex(-1);
    setActiveLine(0);
    setTargetIndex(0);
    setMode("edit");
    setWorkflowStep(1);
    setActionStatus("Started a new lyric project.");
    toast.success("New lyric project ready.");
    window.setTimeout(() => searchInputRef.current?.focus(), 0);
  };

  const openDraft = async (id: string) => {
    const requestId = ++draftOpenRequestRef.current;
    searchController.cancel();
    setSearching(false);
    lyricsAbortRef.current?.abort();
    try {
      const loaded = await loadCreatorDraft(id);
      const draftUri = loaded.project.uri || loaded.record.song?.uri || loaded.record.uri;
      const playback = await restoreCreatorDraftPlayback(draftUri, {
        resolveTrack: getSpotifyTrack,
        playUri: (uri) => Spicetify.Player.playUri(uri),
      });
      if (requestId !== draftOpenRequestRef.current) return;
      projectCheckpointRef.current = creatorProjectCheckpoint(loaded.project);
      setProject(loaded.project);
      setDraftId(loaded.record.id);
      setSelectedTrack(playback.track);
      programmaticSearchValueRef.current = "";
      setSearchQuery("");
      setSearchResults([]);
      setActiveSearchIndex(-1);
      setTargetIndex(0);
      setActiveLine(0);
      setMode("edit");
      setWorkflowStep(2);
      const message = playback.warning
        ? `Opened draft “${loaded.record.name}”. ${playback.warning}`
        : `Opened draft “${loaded.record.name}”.`;
      setActionStatus(message);
      if (playback.warning) toast.error(message);
      else toast.success(`Opened ${loaded.record.name}.`);
      setDraftLibraryOpen(false);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not open the draft.";
      setActionStatus(message);
      toast.error(message);
    }
  };

  const deleteDraft = async (id: string) => {
    try {
      await removeCreatorDraft(id);
      if (draftId === id) setDraftId(undefined);
      await refreshDrafts();
      setActionStatus("Draft deleted.");
      toast.success("Draft deleted.");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not delete the draft.";
      setActionStatus(message);
      toast.error(message);
    }
  };

  const chooseCurrentTrack = () => {
    const track = currentSpotifyTrack();
    if (track) {
      void chooseTrack(track);
      return;
    }
    const message = "The current Spotify item is not a track.";
    setActionStatus(message);
    toast.error(message);
  };

  const changeLyricsSource = (nextSource: CreatorLyricsSourcePreference) => {
    if (selectedTrack) {
      const accepted = requestCreatorSourceSwitch({
        project,
        checkpoint: projectCheckpointRef.current,
        onDirty: () => undefined,
        reload: (options) => {
          setSourcePreference(nextSource);
          void chooseTrack(selectedTrack, nextSource, options);
        },
      });
      if (!accepted) {
        const message = "Save a draft or export the TTML before changing lyrics sources.";
        setActionStatus(message);
        toast.error(message);
      }
      return;
    }
    setSourcePreference(nextSource);
    setActionStatus(
      nextSource === "auto"
        ? "Lyric Creator will use the best available source."
        : `Lyric Creator will request ${creatorLyricsSourcePreferenceLabel(nextSource)} for the next selected song.`
    );
  };

  const onSearchKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    const result = creatorSearchKeyResult(event.key, activeSearchIndex, searchResults.length);
    const handled =
      event.key === "Escape" ||
      (searchResults.length > 0 && ["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) ||
      result.selectIndex !== null;
    if (handled) {
      event.preventDefault();
      event.stopPropagation();
    }
    setActiveSearchIndex(result.activeIndex);
    if (result.dismiss) {
      searchController.cancel();
      setSearchResults([]);
      setActiveSearchIndex(-1);
    }
    if (result.selectIndex !== null) {
      const track = searchResults[result.selectIndex];
      if (track) void chooseTrack(track);
    }
  };

  const goToWorkflowStep = (step: number) => {
    setWorkflowStep(step);
    if (step === 1) {
      setMode("edit");
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
      return;
    }
    if (step === 3) {
      const targets = creatorTimingTargets(project, {
        ignoreBackground: timingOptions.ignoreBackground,
      });
      const selectedTarget = targets.findIndex((target) => target.lineIndex === activeLine);
      if (selectedTarget >= 0) {
        targetIndexRef.current = selectedTarget;
        setTargetIndex(selectedTarget);
      }
    }
    setMode(step === 2 ? "edit" : step === 3 ? "time" : "preview");
  };

  useEffect(() => {
    if (!workspaceScrollTrackerRef.current.shouldScroll(currentWorkflowStep, mode)) return;
    const frame = requestAnimationFrame(() => {
      const element =
        mode === "edit"
          ? document.getElementById(`il-creator-line-${project.lines[activeLine]?.id ?? ""}`)
          : document.getElementById(`il-creator-timing-target-${targetIndex}`);
      element?.scrollIntoView({ block: "center", inline: "center", behavior: "auto" });
    });
    return () => cancelAnimationFrame(frame);
  }, [activeLine, currentWorkflowStep, mode, project.lines, targetIndex]);

  const openMetadataEditor = () => {
    setMetadataDraft({
      ...project.metadata,
      artists: [...project.metadata.artists],
      songwriters: [...project.metadata.songwriters],
      albums: [...project.metadata.albums],
      raw: { ...project.metadata.raw },
    });
    window.setTimeout(() => metadataNameInputRef.current?.focus(), 0);
  };

  const closeMetadataEditor = () => {
    setMetadataDraft(null);
    window.setTimeout(() => metadataButtonRef.current?.focus(), 0);
  };

  const saveMetadata = () => {
    if (!metadataDraft) return;
    setProject((current) => ({ ...current, metadata: metadataDraft }));
    setActionStatus("Track metadata updated.");
    toast.success("Metadata updated.");
    closeMetadataEditor();
  };

  return (
    <div
      className="il-creator-root"
      data-workflow-step={currentWorkflowStep}
      onPointerDownCapture={() => {
        // Focus and cancel are not consistently dispatched by every embedded
        // Chromium build. The first pointer after a native dialog is a final
        // reliable signal that the dialog has returned control to Creator.
        if (nativeDialogReleaseRef.current && document.hasFocus()) finishNativeDialog();
      }}
    >
      <header className="il-creator-topbar">
        <div className="il-creator-brand">
          <IcyLogo className="il-creator-brand__mark" />
          <span className="il-creator-brand__copy">
            <strong>Lyric Creator</strong>
            <small>ICY LYRICS</small>
          </span>
        </div>

        <nav className="il-creator-workflow" aria-label="Lyric creation workflow">
          {[
            [1, "Choose a song"],
            [2, "Set up lyrics"],
            [3, "Time lyrics"],
            [4, "Preview lyrics"],
          ].map(([step, label]) => (
            <button
              type="button"
              className={`${currentWorkflowStep === step ? "is-active" : ""}${currentWorkflowStep > Number(step) ? " is-complete" : ""}`}
              aria-current={currentWorkflowStep === step ? "step" : undefined}
              onClick={() => goToWorkflowStep(Number(step))}
              key={step}
            >
              <span className="il-creator-workflow__number">{step}</span>
              <span className="il-creator-workflow__copy">
                <strong>{label}</strong>
              </span>
            </button>
          ))}
        </nav>

        <div className="il-creator-file-actions" aria-label="Project actions">
          <button type="button" onClick={startNewProject}>
            New
          </button>
          <button type="button" onClick={() => openNativeFilePicker(ttmlInputRef.current)}>
            Open TTML
          </button>
          <button type="button" onClick={() => void saveDraft()}>
            Save draft
          </button>
          <button ref={metadataButtonRef} type="button" onClick={openMetadataEditor}>
            Metadata
          </button>
          <button type="button" className="is-primary" onClick={saveTTML}>
            Export TTML
          </button>
          <button type="button" onClick={() => setDraftLibraryOpen(true)}>
            Drafts{drafts.length ? ` (${drafts.length})` : ""}
          </button>
          <button type="button" className="il-creator-exit" onClick={onClose}>
            Exit
          </button>
          <span className="il-creator-document-name" title={project.metadata.name || "Untitled"}>
            {project.metadata.name || "Untitled"}.ttml
          </span>
          <input
            hidden
            ref={ttmlInputRef}
            type="file"
            accept={CREATOR_TTML_FILE_ACCEPT}
            onChange={(event) => {
              finishNativeDialog();
              const file = event.currentTarget.files?.[0];
              if (file) void openTTML(file);
              event.currentTarget.value = "";
            }}
          />
        </div>
      </header>

      <section className="il-creator-song-picker" aria-label="Choose a song">
        <form
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            runSearch();
          }}
        >
          <span className="il-creator-search-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <circle cx="11" cy="11" r="6.5" />
              <path d="m16 16 4 4" />
            </svg>
          </span>
          <input
            ref={searchInputRef}
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={searchResults.length > 0}
            aria-controls="il-creator-search-results"
            aria-activedescendant={
              activeSearchIndex >= 0 ? `il-creator-search-result-${activeSearchIndex}` : undefined
            }
            value={searchQuery}
            onChange={(event) => {
              const value = event.currentTarget.value;
              setSearchQuery(value);
              setSearchResults([]);
              setActiveSearchIndex(-1);
            }}
            onKeyDown={onSearchKeyDown}
            placeholder="Search Spotify or paste a track URI"
            aria-label="Search Spotify for a song"
          />
          <button type="submit" disabled={searching || searchQuery.trim().length < 2}>
            {searching ? "Searching…" : "Search"}
          </button>
          <button type="button" onClick={chooseCurrentTrack}>
            Use current song
          </button>
        </form>

        <div className="il-creator-source-panel">
          <label className="il-creator-source-picker">
            <span>Lyrics</span>
            <select
              aria-label="Lyric Creator source"
              value={sourcePreference}
              disabled={loadingLyrics}
              onChange={(event) =>
                changeLyricsSource(event.currentTarget.value as CreatorLyricsSourcePreference)
              }
            >
              {CREATOR_LYRICS_SOURCE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>

          {(selectedTrack || project.uri || project.source.code === "ttml") && (
            <div className="il-creator-source" aria-live="polite">
              {selectedTrack?.coverUrl ? (
                <img src={selectedTrack.coverUrl} alt="" />
              ) : (
                <span className="il-creator-cover-placeholder" aria-hidden="true" />
              )}
              <div>
                <strong>{selectedTrack?.name ?? (project.metadata.name || "Selected song")}</strong>
                <span>
                  {loadingLyrics
                    ? `Loading ${creatorLyricsSourcePreferenceLabel(sourcePreference)}…`
                    : `Source · ${project.source.label}`}
                </span>
              </div>
            </div>
          )}
        </div>

        <div className="il-creator-action-status" role="status" aria-live="polite">
          <span aria-hidden="true" />
          {actionStatus}
        </div>

        {searchResults.length > 0 && (
          <div
            id="il-creator-search-results"
            className="il-creator-search-results"
            role="listbox"
            aria-label="Spotify track results"
          >
            {searchResults.map((track, index) => (
              <button
                id={`il-creator-search-result-${index}`}
                type="button"
                role="option"
                aria-selected={index === activeSearchIndex}
                className={index === activeSearchIndex ? "is-active" : ""}
                key={track.uri}
                onMouseEnter={() => setActiveSearchIndex(index)}
                onClick={() => void chooseTrack(track)}
              >
                {track.coverUrl ? (
                  <img src={track.coverUrl} alt="" />
                ) : (
                  <span className="il-creator-cover-placeholder" aria-hidden="true" />
                )}
                <span>
                  <strong>{track.name}</strong>
                  <small>
                    {track.artists.join(", ")} · {track.album}
                  </small>
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      <div
        className={`il-creator-workspace il-creator-workspace--${currentWorkflowStep === 1 ? "choose" : mode}`}
      >
        {currentWorkflowStep === 1 && (
          <main className="il-creator-choose-stage" aria-label="Choose a song">
            <div className="il-creator-choose-stage__mark" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M9 18V5l10-2v13" />
                <circle cx="6" cy="18" r="3" />
                <circle cx="16" cy="16" r="3" />
              </svg>
            </div>
            <span className="il-creator-inspector__eyebrow">Step 1 of 4</span>
            <h1>Choose the song you want to time</h1>
            <p>
              Search Spotify above as you type, paste a track URI, or pull in the song that is
              playing now. Icy Lyrics will load the best available lyrics and show their source.
            </p>
            <div className="il-creator-choose-stage__actions">
              <button
                type="button"
                className="il-creator-primary-button"
                onClick={chooseCurrentTrack}
              >
                Use current song
              </button>
              <button type="button" onClick={() => searchInputRef.current?.focus()}>
                Search for a song
              </button>
            </div>
            <div className="il-creator-choose-stage__steps" aria-label="What happens next">
              <span>
                <strong>Set up</strong> Edit lines, word parts, voices, and metadata
              </span>
              <span>
                <strong>Time</strong> Mark precise starts and ends with F, G, and H
              </span>
              <span>
                <strong>Preview</strong> Review with the live Icy Lyrics renderer
              </span>
            </div>
          </main>
        )}
        {currentWorkflowStep !== 1 && mode === "edit" && (
          <EditWorkspace
            project={project}
            setProject={setProject}
            importText={importText}
            setImportText={setImportText}
            activeLine={activeLine}
            setActiveLine={setActiveLine}
            positionMs={positionMs}
          />
        )}
        {currentWorkflowStep !== 1 && mode === "time" && (
          <CreatorTimeWorkspace
            project={project}
            targetIndex={targetIndex}
            onTargetIndex={(index) => {
              targetIndexRef.current = index;
              setTargetIndex(index);
              const target = creatorTimingTargets(project, {
                ignoreBackground: timingOptions.ignoreBackground,
              })[index];
              if (target) setActiveLine(target.lineIndex);
            }}
            positionMs={positionMs}
            options={timingOptions}
            onOptionsChange={(nextOptions) => {
              setTimingOptions(nextOptions);
              if (nextOptions.ignoreBackground !== timingOptions.ignoreBackground) {
                setTargetIndex(0);
              }
            }}
          />
        )}
        {currentWorkflowStep !== 1 && mode === "preview" && (
          <PreviewStage project={project} clock={getPlaybackPosition} />
        )}
        {currentWorkflowStep !== 1 && mode === "preview" && (
          <aside className="il-creator-inspector">
            <div className="il-creator-inspector__scroll">
              <span className="il-creator-inspector__eyebrow">Step 4</span>
              <h2>Preview your lyrics</h2>
              <p className="il-creator-muted">
                This is the real Icy Lyrics renderer using your current timings. Play or seek below
                to review every transition.
              </p>
              <dl className="il-creator-summary">
                <div>
                  <dt>Lines</dt>
                  <dd>{project.lines.length}</dd>
                </div>
                <div>
                  <dt>Fragments</dt>
                  <dd>{creatorTimingTargets(project).length}</dd>
                </div>
                <div>
                  <dt>Source</dt>
                  <dd>{project.source.label}</dd>
                </div>
              </dl>
            </div>
          </aside>
        )}
      </div>

      {metadataDraft && (
        <div
          className="il-creator-metadata-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeMetadataEditor();
          }}
        >
          <section
            ref={metadataDialogRef}
            className="il-creator-metadata-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="il-creator-metadata-title"
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                closeMetadataEditor();
                return;
              }
              if (event.key !== "Tab") return;
              const focusable = Array.from(
                metadataDialogRef.current?.querySelectorAll<HTMLElement>(
                  'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'
                ) ?? []
              ).filter((element) => element.offsetParent !== null);
              const first = focusable[0];
              const last = focusable.at(-1);
              if (!first || !last) return;
              if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
              } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
              }
            }}
          >
            <header>
              <div>
                <span className="il-creator-inspector__eyebrow">TTML details</span>
                <h2 id="il-creator-metadata-title">Edit metadata</h2>
              </div>
              <button
                type="button"
                className="il-creator-metadata-modal__close"
                aria-label="Close metadata editor"
                onClick={closeMetadataEditor}
              >
                ×
              </button>
            </header>
            <div className="il-creator-metadata-modal__body">
              <MetadataInspector
                metadata={metadataDraft}
                onChange={setMetadataDraft}
                nameInputRef={metadataNameInputRef}
              />
            </div>
            <footer>
              <button type="button" onClick={closeMetadataEditor}>
                Cancel
              </button>
              <button type="button" className="is-primary" onClick={saveMetadata}>
                Save metadata
              </button>
            </footer>
          </section>
        </div>
      )}

      {draftLibraryOpen && (
        <CreatorDraftLibrary
          groups={draftGroups}
          currentDraftId={draftId}
          onOpen={openDraft}
          onRemove={deleteDraft}
          onClose={() => setDraftLibraryOpen(false)}
        />
      )}

      <footer className="il-creator-transport" aria-label="Playback transport">
        <button
          type="button"
          onClick={() => seekPlayback(positionMs - 2000)}
          aria-label="Seek back two seconds"
        >
          −2s
        </button>
        <button
          type="button"
          className="il-creator-transport__play"
          onClick={togglePlayback}
          aria-label="Play or pause timing audio"
        >
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="m6 4 9 6-9 6V4Z" />
          </svg>
          Play / Pause
        </button>
        <button
          type="button"
          onClick={() => seekPlayback(positionMs + 2000)}
          aria-label="Seek forward two seconds"
        >
          +2s
        </button>
        <span className="il-creator-transport__time">{formatCreatorTime(positionMs)}</span>
        <CreatorWaveformTimeline
          waveform={waveform}
          durationMs={durationMs}
          positionMs={positionMs}
          selectedLine={project.lines[activeLine] ?? null}
          selectedLineNumber={activeLine + 1}
          onSeek={seekPlayback}
        />
        <span className="il-creator-transport__time">{formatCreatorTime(durationMs)}</span>
        <label className="il-creator-speed">
          Speed
          <select
            value={speed}
            onChange={(event) => void changeSpeed(Number(event.currentTarget.value))}
          >
            {[0.5, 0.6, 0.75, 0.85, 1, 1.25, 1.5, 2].map((value) => (
              <option value={value} key={value}>
                {value}×
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => openNativeFilePicker(audioInputRef.current)}>
          {localAudioName ? "Change local audio" : "Load local audio"}
        </button>
        {localAudioName && (
          <button
            type="button"
            onClick={() => {
              localAudioRef.current?.pause();
              URL.revokeObjectURL(localAudioUrl);
              setLocalAudioUrl("");
              setLocalAudioName("");
              setSpeed(1);
              setSpeedMessage("Spotify music timing uses 1× playback.");
            }}
          >
            Use Spotify audio
          </button>
        )}
        <input
          hidden
          ref={audioInputRef}
          type="file"
          accept="audio/*"
          onChange={(event) => {
            finishNativeDialog();
            const file = event.currentTarget.files?.[0];
            if (!file) return;
            if (localAudioUrl) URL.revokeObjectURL(localAudioUrl);
            const url = URL.createObjectURL(file);
            Spicetify.Player.pause();
            setLocalAudioUrl(url);
            setLocalAudioName(file.name);
            setSpeed(1);
            setSpeedMessage("Local audio loaded. Variable speed is available.");
            setActionStatus(`Loaded local timing audio: ${file.name}.`);
            void (async () => {
              try {
                const AudioContextClass =
                  window.AudioContext ??
                  (window as typeof window & { webkitAudioContext?: typeof AudioContext })
                    .webkitAudioContext;
                if (!AudioContextClass) return;
                const context = new AudioContextClass();
                try {
                  const buffer = await context.decodeAudioData(await file.arrayBuffer());
                  setWaveform(
                    creatorWaveformFromPcmChannels(
                      Array.from({ length: buffer.numberOfChannels }, (_, channel) =>
                        buffer.getChannelData(channel)
                      ),
                      buffer.duration * 1000
                    )
                  );
                } finally {
                  void context.close();
                }
              } catch (error) {
                console.warn("Icy Lyrics could not decode the local timing waveform.", error);
                setWaveform(creatorWaveformFromSpotifyAnalysis(null));
              }
            })();
            event.currentTarget.value = "";
          }}
        />
        <audio ref={localAudioRef} src={localAudioUrl || undefined} preload="metadata" />
        <span className="il-creator-speed-message" title={speedMessage}>
          {speedMessage}
        </span>
      </footer>
    </div>
  );
}
