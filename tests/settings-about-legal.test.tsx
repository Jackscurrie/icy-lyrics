import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import AboutLegalSection, {
  ICY_LYRICS_LEGAL_URL,
  SPICY_LYRICS_SOURCE_URL,
} from "../src/components/ReactComponents/SettingsPanel/AboutLegalSection.tsx";

describe("desktop Settings legal attribution", () => {
  it("shows both copyright notices, license terms, disclaimer, and links", () => {
    const markup = renderToStaticMarkup(<AboutLegalSection query="" sectionFilter="All" />);

    expect(markup).toContain("Copyright © 2026 Jackscurrie");
    expect(markup).toContain("Modified in 2026 from Spicy Lyrics");
    expect(markup).toContain("GNU AGPL v3-or-later");
    expect(markup).toContain("provided without warranty");
    expect(markup).toContain("independent");
    expect(markup).toContain("not affiliated with or endorsed by");
    expect(markup).toContain("Spotify, Apple, LRCLIB, or their respective owners");
    expect(markup).toContain("Spicy Lyrics by Spikerko");
    expect(markup).toContain("Copyright © 2026 Spikerko");
    expect(markup).toContain(`href="${ICY_LYRICS_LEGAL_URL}"`);
    expect(markup).toContain(`href="${SPICY_LYRICS_SOURCE_URL}"`);
    expect(markup).toContain('rel="noopener noreferrer"');
  });

  it("participates in Settings search and section filtering", () => {
    const searchMatch = renderToStaticMarkup(
      <AboutLegalSection query="Spikerko" sectionFilter="All" />
    );
    const searchMiss = renderToStaticMarkup(
      <AboutLegalSection query="Bluetooth timing" sectionFilter="All" />
    );
    const filteredOut = renderToStaticMarkup(
      <AboutLegalSection query="" sectionFilter="Playback" />
    );

    expect(searchMatch).toContain("Spicy Lyrics by Spikerko");
    expect(searchMiss).toBe("");
    expect(filteredOut).toBe("");
  });
});
