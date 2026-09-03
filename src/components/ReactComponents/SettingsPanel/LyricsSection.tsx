import { useStore } from "@nanostores/react";
import React from "react";
import { SpotifyPlayer } from "../../Global/SpotifyPlayer.ts";
import fetchLyrics, { invalidateLyricsRequests } from "../../../utils/Lyrics/fetchLyrics.ts";
import ApplyLyrics from "../../../utils/Lyrics/Global/Applyer.ts";
import { isCreatorPreviewActive } from "../LyricCreator/previewOwnership.ts";
import {
  $currentLyricsData,
  $fullscreenOutroAnimation,
  $fullscreenRevealMode,
  $lineHoverBackground,
  $minimalLyricsMode,
  $simpleLyricsMode,
  $simpleLyricsModeRenderingType,
  $useLocalTtmlLyrics,
} from "../../../utils/stores.ts";
import { matches, Row, Select, SectionTitle, Toggle } from "./components.tsx";

const SECTION_NAME = "Lyrics Display";
const renderingTypeOptions = ["calculate", "animate"];

interface Props {
  query: string;
  sectionFilter: string;
}

export default function LyricsSection({ query, sectionFilter }: Props) {
  const simpleLyricsMode = useStore($simpleLyricsMode);
  const simpleLyricsModeRenderingType = useStore($simpleLyricsModeRenderingType);
  const minimalLyricsMode = useStore($minimalLyricsMode);
  const lineHoverBackground = useStore($lineHoverBackground);
  const useLocalTtmlLyrics = useStore($useLocalTtmlLyrics);
  const fullscreenRevealMode = useStore($fullscreenRevealMode);
  const fullscreenOutroAnimation = useStore($fullscreenOutroAnimation);

  if (sectionFilter !== "All" && sectionFilter !== SECTION_NAME) return null;

  const r1 = matches(query, "Simple Lyrics Mode", "Remove extra visual effects from lyrics");
  const r2 = matches(
    query,
    "Simple Mode: Text Animation Style",
    "How lyrics text transitions are rendered in Simple Lyrics Mode."
  );
  const r3 = matches(
    query,
    "Minimal Lyrics Mode",
    "Hides sung lyrics lines in Fullscreen and Cinema Mode"
  );
  const r4 = matches(
    query,
    "Line Hover Background",
    "Shows a highlight box behind a lyrics line when you hover over it"
  );
  const r5 = matches(
    query,
    "Use saved local TTML lyrics",
    "Prefer permanently saved TTML for the exact Spotify track URI."
  );
  const r6 = matches(
    query,
    "Fullscreen Reveal Mode",
    "Hide surrounding lyrics and reveal timed words only as they are sung."
  );
  const r7 = matches(
    query,
    "End-of-song lyric outro",
    "Shrink and rotate the final line through the remaining song time."
  );

  if (!r1 && !r2 && !r3 && !r4 && !r5 && !r6 && !r7) return null;

  function setUseLocalTtml(value: boolean) {
    $useLocalTtmlLyrics.set(value);
    if (isCreatorPreviewActive()) return;
    const uri = SpotifyPlayer.GetUri();
    $currentLyricsData.set("");
    invalidateLyricsRequests(uri ?? null);
    if (uri) void fetchLyrics(uri).then(ApplyLyrics);
  }

  return (
    <>
      <SectionTitle>Lyrics Display</SectionTitle>

      {r1 && (
        <Row label="Simple Lyrics Mode" description="Remove extra visual effects from lyrics">
          <Toggle checked={simpleLyricsMode} onChange={(v) => $simpleLyricsMode.set(v)} />
        </Row>
      )}

      {r2 && (
        <Row
          label="Simple Mode: Text Animation Style"
          description="How lyrics text transitions are rendered in Simple Lyrics Mode."
          disabled={!simpleLyricsMode}
          disabledReason="Enable Simple Lyrics Mode to modify this setting"
        >
          <Select
            value={simpleLyricsModeRenderingType}
            options={renderingTypeOptions}
            onChange={(v) => $simpleLyricsModeRenderingType.set(v)}
            disabled={!simpleLyricsMode}
          />
        </Row>
      )}

      {r3 && (
        <Row
          label="Minimal Lyrics Mode"
          description="Hides sung lyrics lines in Fullscreen and Cinema Mode"
        >
          <Toggle checked={minimalLyricsMode} onChange={(v) => $minimalLyricsMode.set(v)} />
        </Row>
      )}

      {r4 && (
        <Row
          label="Line Hover Background"
          description="Shows a highlight box behind a lyrics line when you hover over it"
        >
          <Toggle checked={lineHoverBackground} onChange={(v) => $lineHoverBackground.set(v)} />
        </Row>
      )}

      {r5 && (
        <Row
          label="Use saved local TTML lyrics"
          description="Prefer permanently saved TTML for the exact Spotify track. Turning this off keeps saved files but immediately reloads remote lyrics."
        >
          <Toggle checked={useLocalTtmlLyrics} onChange={setUseLocalTtml} />
        </Row>
      )}

      {r6 && (
        <Row
          label="Fullscreen Reveal Mode"
          description="In lyrics-only fullscreen, show the active line and background vocals while timed words reveal as they are sung."
        >
          <Toggle
            checked={fullscreenRevealMode}
            onChange={(value) => $fullscreenRevealMode.set(value)}
          />
        </Row>
      )}

      {r7 && (
        <Row
          label="End-of-song lyric outro"
          description="Animate the final timed lyric line through the remaining song time, then pop it away before the next track."
        >
          <Toggle
            checked={fullscreenOutroAnimation}
            onChange={(value) => $fullscreenOutroAnimation.set(value)}
          />
        </Row>
      )}
    </>
  );
}
