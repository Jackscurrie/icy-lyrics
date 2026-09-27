import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import CreatorTimeWorkspace from "../src/components/ReactComponents/LyricCreator/CreatorTimeWorkspace.tsx";
import { createEmptyProject } from "../src/components/ReactComponents/LyricCreator/model.ts";
import { DEFAULT_CREATOR_TIMING_OPTIONS } from "../src/components/ReactComponents/LyricCreator/timing.ts";
import type { CreatorPlaybackClock } from "../src/components/ReactComponents/LyricCreator/playbackClock.ts";

vi.mock("../src/components/ReactComponents/LyricCreator/useCreatorPlayback.tsx", () => ({
  useCreatorPlaybackActivity: () => ({ fragmentIds: new Set<string>() }),
  CreatorPlaybackTime: () => "0:00.000",
}));

describe("Lyric Creator timing hotkey hints", () => {
  it("shows Space and J as distinct alternative keycaps", () => {
    const markup = renderToStaticMarkup(
      <CreatorTimeWorkspace
        project={createEmptyProject()}
        targetIndex={0}
        onTargetIndex={() => {}}
        playbackClock={{} as CreatorPlaybackClock}
        options={DEFAULT_CREATOR_TIMING_OPTIONS}
        onOptionsChange={() => {}}
        onAutoTime={() => {}}
        onOpenLucida={() => {}}
        lucidaHandoff={null}
      />
    );

    expect(markup).toContain("<kbd>Space</kbd><span>or</span><kbd>J</kbd> Next word");
    expect(markup).not.toContain("<kbd>Space</kbd> / ");
  });

  it("lets long key names fit their keycaps without shrinking or wrapping", () => {
    const css = readFileSync(new URL(
      "../src/components/ReactComponents/LyricCreator/styles.css", import.meta.url
    ), "utf8");
    const keycap = css.match(/\.il-creator-hotkeys kbd\s*\{([^}]+)\}/)?.[1] ?? "";
    const hint = css.match(/\.il-creator-hotkeys > div > span\s*\{([^}]+)\}/)?.[1] ?? "";

    expect(keycap).toMatch(/min-width:\s*23px/);
    expect(keycap).toMatch(/padding:\s*0 5px/);
    expect(keycap).toMatch(/flex:\s*0 0 auto/);
    expect(keycap).not.toMatch(/(?:^|;)\s*width:/);
    expect(hint).toMatch(/white-space:\s*nowrap/);
  });
});
