import { useState } from "react";
import AboutLegalSection from "./AboutLegalSection.tsx";
import AppearanceSection from "./AppearanceSection.tsx";
import BackgroundSection from "./BackgroundSection.tsx";
import CacheSection from "./CacheSection.tsx";
import DeveloperSection from "./DeveloperSection.tsx";
import ExperimentsSection from "./ExperimentsSection.tsx";
import InterfaceSection from "./InterfaceSection.tsx";
import LyricsSection from "./LyricsSection.tsx";
import PlaybackSection from "./PlaybackSection.tsx";
import { FilterDropdown, SearchBar } from "./components.tsx";

const SECTIONS = [
  "Background",
  "Lyrics Display",
  "Playback",
  "Appearance",
  "Interface",
  "Experiments",
  "Developer",
  "Cache",
  "About & legal",
];

export default function SettingsPanel({ onOpenExperiments }: { onOpenExperiments: () => void }) {
  const [query, setQuery] = useState("");
  const [sectionFilter, setSectionFilter] = useState("All");

  return (
    <div style={{ padding: "8px 0" }} className="slm w-40">
      <div className="il-sp-toolbar">
        <SearchBar value={query} onChange={setQuery} />
        <FilterDropdown sections={SECTIONS} value={sectionFilter} onChange={setSectionFilter} />
      </div>

      <BackgroundSection query={query} sectionFilter={sectionFilter} />
      <LyricsSection query={query} sectionFilter={sectionFilter} />
      <PlaybackSection query={query} sectionFilter={sectionFilter} />
      <AppearanceSection query={query} sectionFilter={sectionFilter} />
      <InterfaceSection query={query} sectionFilter={sectionFilter} />
      <ExperimentsSection query={query} sectionFilter={sectionFilter} onOpen={onOpenExperiments} />
      <DeveloperSection query={query} sectionFilter={sectionFilter} />
      <CacheSection query={query} sectionFilter={sectionFilter} />
      <AboutLegalSection query={query} sectionFilter={sectionFilter} />
    </div>
  );
}
