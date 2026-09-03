import { matches, Row, SectionTitle } from "./components.tsx";

const SECTION_NAME = "About & legal";

export const ICY_LYRICS_LEGAL_URL = "https://jackscurrie.com/icy-lyrics/legal";
export const SPICY_LYRICS_SOURCE_URL = "https://github.com/Spikerko/spicy-lyrics";

const ICY_LABEL = "Icy Lyrics";
const ICY_DESCRIPTION =
  "Copyright © 2026 Jackscurrie. Modified in 2026 from Spicy Lyrics. Licensed under the GNU AGPL v3-or-later and provided without warranty. Icy Lyrics is independent and is not affiliated with or endorsed by Spicy Lyrics, Spikerko, Spotify, Apple, LRCLIB, or their respective owners.";
const SPICY_LABEL = "Spicy Lyrics by Spikerko";
const SPICY_DESCRIPTION = "Original work Copyright © 2026 Spikerko.";

interface Props {
  query: string;
  sectionFilter: string;
}

export default function AboutLegalSection({ query, sectionFilter }: Props) {
  if (sectionFilter !== "All" && sectionFilter !== SECTION_NAME) return null;

  const icyHit = matches(query, ICY_LABEL, `${ICY_DESCRIPTION} Legal source license`);
  const spicyHit = matches(
    query,
    SPICY_LABEL,
    `${SPICY_DESCRIPTION} Original upstream project source`
  );

  if (!icyHit && !spicyHit) return null;

  return (
    <>
      <SectionTitle>{SECTION_NAME}</SectionTitle>

      {icyHit && (
        <Row label={ICY_LABEL} description={ICY_DESCRIPTION}>
          <a
            className="il-sp-btn il-sp-legal-link"
            href={ICY_LYRICS_LEGAL_URL}
            target="_blank"
            rel="noopener noreferrer"
          >
            Legal &amp; source
          </a>
        </Row>
      )}

      {spicyHit && (
        <Row label={SPICY_LABEL} description={SPICY_DESCRIPTION}>
          <a
            className="il-sp-btn il-sp-legal-link"
            href={SPICY_LYRICS_SOURCE_URL}
            target="_blank"
            rel="noopener noreferrer"
          >
            Original project
          </a>
        </Row>
      )}
    </>
  );
}
