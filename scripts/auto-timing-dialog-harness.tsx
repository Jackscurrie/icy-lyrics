import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Toaster } from "sonner";
import AutoTimingDialog from "../src/components/ReactComponents/LyricCreator/autoTiming/AutoTimingDialog.tsx";
import { decodeAutoTimingAudio, type DecodedAutoTimingAudio } from "../src/components/ReactComponents/LyricCreator/autoTiming/audio.ts";
import { createEmptyProject, importPlainText } from "../src/components/ReactComponents/LyricCreator/model.ts";
import "../src/components/ReactComponents/LyricCreator/styles.css";

function DialogHarness() {
  const [project, setProject] = useState(() => {
    const initial = createEmptyProject();
    initial.metadata.name = "Auto-time browser QA";
    initial.metadata.language = "en";
    initial.lines = importPlainText(`The morning sun is shining.
We walk together through the garden.
Every word has a beginning and an ending.`);
    initial.lines[0].tokens[0].fragments[0].startTimeMs = 100;
    initial.lines[0].tokens[0].fragments[0].endTimeMs = 300;
    return initial;
  });
  const [audio, setAudio] = useState<DecodedAutoTimingAudio | null>(null);
  const [error, setError] = useState("");
  const [open, setOpen] = useState(false);
  const [applied, setApplied] = useState(false);
  const [keyLog, setKeyLog] = useState<string[]>([]);
  const audioRef = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const response = await fetch("../fixture.wav");
      if (!response.ok) throw new Error("Build the runtime harness first with the spoken WAV fixture.");
      const result = await decodeAutoTimingAudio(new File([await response.blob()], "speech.wav", { type: "audio/wav" }));
      if (!cancelled) setAudio(result);
    })().catch((reason) => {
      if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
    });
    const recordKey = (event: KeyboardEvent) => {
      const key = event.code;
      const target = (event.target as HTMLElement)?.tagName;
      setTimeout(() => {
        if (!cancelled) setKeyLog((previous) => [...previous.slice(-7), `${key} on ${target}; default prevented: ${event.defaultPrevented}`]);
      }, 0);
    };
    window.addEventListener("keydown", recordKey, true);
    return () => {
      cancelled = true;
      window.removeEventListener("keydown", recordKey, true);
    };
  }, []);

  const fragments = project.lines.flatMap((line) => line.tokens.flatMap((token) => token.fragments));
  const timed = fragments.filter((fragment) => fragment.startTimeMs !== null && fragment.endTimeMs !== null).length;
  return (
    <div className="il-creator-root" style={{ display: "block", padding: 32, overflow: "auto" }}>
      <h1>Auto-time dialog smoke test</h1>
      <p>Real dialog, local audio decoder, signed module repository and transcription worker.</p>
      <p>One existing word is timed at 100–300 ms; Fill missing must retain it.</p>
      <button type="button" disabled={!audio} onClick={() => setOpen(true)}>Open Auto-time</button>
      <p role="status">{error || (audio ? "Fixture decoded. Ready." : "Decoding fixture…")}</p>
      <p data-applied={applied}>Applied: {String(applied)} · Timed fragments: {timed}/{fragments.length}</p>
      <audio controls ref={audioRef} src="../fixture.wav" />
      <h2>Applied timings</h2>
      <pre style={{ whiteSpace: "pre-wrap" }}>{JSON.stringify(fragments, null, 2)}</pre>
      <h2>Keyboard event log</h2>
      <pre id="keyboard-log">{keyLog.join("\n")}</pre>
      {open && audio && (
        <AutoTimingDialog
          project={project}
          audioName="speech.wav"
          audio={audio}
          expectedDurationMs={audio.durationMs}
          onSeek={(milliseconds) => { if (audioRef.current) audioRef.current.currentTime = milliseconds / 1000; }}
          onTogglePlayback={() => {
            const player = audioRef.current;
            if (player?.paused) void player.play();
            else player?.pause();
          }}
          onApply={(next) => { setProject(next); setApplied(true); }}
          onClose={() => setOpen(false)}
        />
      )}
      <Toaster />
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<DialogHarness />);
