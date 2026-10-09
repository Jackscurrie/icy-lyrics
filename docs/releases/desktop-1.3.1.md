# Icy Lyrics Desktop 1.3.1

## What's changed

- More resilient lyrics-page and fullscreen entry when Spotify changes its layout.
- Drag entire lines in Lyric Creator's Edit stage; Alt+Up/Down works from the line handle too.
- Duplicate a line directly below itself, including word/fragment timings and vocal flags.
- Drag-copy word or fragment timings in Time, with visual grab/drop feedback. The source timing is unchanged.
- Apply a timing offset to the entire song immediately, including existing timings, background vocals, preview, drafts, and exported TTML.
- Red, editable background-attachment warnings and repair controls. Work remains savable as a draft; correct invalid attachments before exporting TTML.
- Removed the Edit word gap input and the extra paint layer that caused ghosting behind animated words and intro/interlude dots.
- Auto-time continues to propose editable on-device timings from your own audio. Review its suggestions: singing accuracy varies, and overlapping voices or unusual phrasing can require manual correction.

## Install or update

Use the guided installer or the extension download at https://jackscurrie.com/icy-lyrics. Existing older public versions check the website at Spotify startup and load the checksum-verified update. An old local test file already labelled **1.3.1** must be replaced manually, since equal versions are not automatically replaced.

The JavaScript file in this release is the desktop extension only. Android, Android TV, and iOS releases are unchanged. The public version is **1.3.1**; the independent Spicy API compatibility handshake remains **6.3.12**.

## Compatibility

Use a Spotify version supported by Spicetify. As of October 9, 2026, Spicetify 2.45.3 declares Spotify support through **1.3.3 on Windows/macOS** and **1.2.96 on Linux**: https://github.com/spicetify/cli/releases/tag/v2.45.3.

Spotify **1.3.4 is not a verified clean-install target**. Local Windows tests used separate Spicetify startup repairs and a Starry Night compatibility shim. Those machine-specific modifications are not shipped in this extension. Icy Lyrics does not promise to fix native Home/Liked Songs issues or incompatible third-party themes/snippets.

## Validation and known limits

The clean public release source passed 450 tests across 59 files, TypeScript, lint, and the production build. This includes 10 new tests for editable background-attachment warnings and repair controls. The full local workspace also passed 498 tests across 67 files; the public source excludes tests for separate machine-specific repairs and an unused alignment experiment. Lint reports 27 existing warnings and no errors.

Whole-line dragging, duplication, timing transfer, global offset, and sampled word/dot rendering were tested in Spotify before the final warning update. The warning and repair controls received automated checks; no new native visual check was performed for them.

TTML export supports one attached background row per lead. Duplicated background rows are retained and can be repaired in Edit, rather than silently discarded. Draft saving remains available while the project contains validation warnings.

No sustained frame-rate guarantee or new across-language transcription benchmark is claimed. Keep exported backups of important drafts/local TTML before clearing Spotify profile data.
