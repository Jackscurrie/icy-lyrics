import React, { useRef, useState } from "react";
import { toast } from "sonner";
import { SpotifyPlayer } from "../../../../components/Global/SpotifyPlayer.ts";
import fetchLyrics from "../../../../utils/Lyrics/fetchLyrics.ts";
import ApplyLyrics from "../../../../utils/Lyrics/Global/Applyer.ts";
import {
  InvalidLocalTtmlError,
  LocalLyricsManager,
  LocalTtmlPersistenceError,
} from "../../../../utils/Lyrics/manager/index.ts";
import {
  $currentLyricsData,
  $useLocalTtmlLyrics,
} from "../../../../utils/stores.ts";
import { IconButton } from "./IconButton.tsx";
import { ArrowLeftIcon, UploadIcon } from "./Icons.tsx";

type UploadTTMLModalProps = {
  onBack: () => void;
  onDone: () => void;
};

export default function UploadTTMLModal({ onBack, onDone }: UploadTTMLModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const songName = SpotifyPlayer.GetName() ?? "Unknown Song";

  async function applySavedLyrics(uri: string): Promise<void> {
    if (!$useLocalTtmlLyrics.get() || SpotifyPlayer.GetUri() !== uri) return;
    $currentLyricsData.set("");
    await ApplyLyrics(await fetchLyrics(uri));
  }

  async function handleUpload() {
    if (!file || uploading) return;
    const uri = SpotifyPlayer.GetUri();
    if (!uri) {
      toast.error("No track is currently playing.", { duration: 5000 });
      return;
    }

    setUploading(true);
    try {
      const ttml = await file.text();
      await LocalLyricsManager.put(uri, ttml);
      await applySavedLyrics(uri);
      toast.success(
        $useLocalTtmlLyrics.get()
          ? "TTML saved permanently and applied."
          : "TTML saved permanently. Enable saved local TTML to display it.",
        { duration: 5000 }
      );
      onDone();
    } catch (error) {
      if (error instanceof InvalidLocalTtmlError) {
        toast.error(error.message, { duration: 5000 });
      } else if (error instanceof LocalTtmlPersistenceError) {
        const canApply = $useLocalTtmlLyrics.get() && SpotifyPlayer.GetUri() === uri;
        if (canApply) {
          const sessionLyrics = { ...error.lyrics, uri, source: "ldb" };
          $currentLyricsData.set(JSON.stringify(sessionLyrics));
          await ApplyLyrics([sessionLyrics, 200]);
        }
        toast.error(
          canApply
            ? "Lyrics were applied for this session, but could not be saved permanently."
            : "Lyrics were parsed, but could not be saved permanently.",
          { duration: 7000 }
        );
      } else {
        toast.error("TTML upload failed. Check the console for details.", { duration: 5000 });
        console.error("Icy Lyrics TTML upload error:", error);
      }
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="il-ldb-upload-root">
      <div className="il-ldb-upload-header">
        <h2 className="il-ldb-upload-title">Upload TTML</h2>
        <p className="il-ldb-upload-subtitle">For: {songName}</p>
      </div>

      <div className="il-ldb-upload-file-section">
        <input
          ref={fileInputRef}
          type="file"
          accept=".ttml,application/ttml+xml,application/xml,text/xml"
          id="il-ldb-file-input"
          className="il-ldb-file-input"
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
        />
        <label htmlFor="il-ldb-file-input" className="il-ldb-file-label">
          {file ? file.name : "Choose .ttml file…"}
        </label>
        <span className="il-ldb-upload-mode-desc">
          Valid files are saved permanently for this exact Spotify track.
        </span>
      </div>

      <div className="il-ldb-upload-actions">
        <IconButton
          icon={<ArrowLeftIcon size={14} />}
          label="Back"
          variant="default"
          onClick={onBack}
          disabled={uploading}
        />
        <IconButton
          icon={<UploadIcon size={14} />}
          label={uploading ? "Saving…" : "Save TTML"}
          variant="primary"
          onClick={() => void handleUpload()}
          disabled={!file || uploading}
        />
      </div>
    </div>
  );
}
