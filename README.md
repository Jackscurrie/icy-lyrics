# Icy Lyrics

PUBLIC GOOGLE PLAY STORE RELEASE COMING SOON!! Currently in closed testing
 
Icy Lyrics is a fork of the popular Spicetify lyrics extension "Spicy Lyrics" by Spikerko with multiple fullscreen modes, a lyric creator, and more

Icy Lyrics 1.3.1 is the desktop release. The desktop extension auto-updates on startup from jackscurrie.com and falls back to the installed build whenever the website is unavailable or verification fails. Optional Auto-time modules are downloaded separately through Icy Lyrics settings.

## 1.3.1 highlights

- Lyrics and fullscreen entry use Spotify's stable content markers instead of retired outer classes, with bounded, cancellable opening requests.
- Missing optional artwork or tooltips no longer prevent route setup. Native controls are retained except those explicitly replaced by Icy.
- Move complete lines in Edit, duplicate lines with their timings, and drag-copy word or fragment timings in Time with grab/drop feedback.
- Song-wide timing offset updates existing timings immediately, including preview and export. Auto-time application preserves the same offset without shifting retained manual timings twice.
- Invalid background attachments stay editable and are marked in red, with controls to select a different lead or convert the row into a lead. Drafts can still be saved while corrections are in progress.
- Removed the word gap input and duplicate text painting behind animated words and intro/interlude dots.

### Compatibility and release notes

Use a Spotify version supported by your installed Spicetify release. At publication, [Spicetify 2.45.3](https://github.com/spicetify/cli/releases/tag/v2.45.3) declares support through Spotify **1.3.3 on Windows/macOS** and **1.2.96 on Linux**. Spotify 1.3.4 is outside that range. Native 1.3.4 tests used separate local repairs; they do not establish clean-install support. Icy Lyrics does not patch Spotify internals, repair third-party themes, or disable your Marketplace snippets.

See [Desktop 1.3.1 release notes](docs/releases/desktop-1.3.1.md). The updater accepts only higher version numbers: replace an older local test build already labelled 1.3.1 manually. Saved TTML and drafts are retained by normal extension updates; export important work before clearing or reinstalling Spotify.

## 1.3.0 Highlights

- Lyric Creator can now propose word and fragment timings from a user-selected local audio file. The transcription runs entirely on-device, remains editable, and is reviewed before it changes the draft.
- Optional Fast and Accurate timing modules can be downloaded, updated, verified, and removed independently from Icy Lyrics settings. Fast remains the first-run default and the last-used choice is remembered.
- Automatic timing covers lead, background, and second-speaker lanes, preserves existing timings by default, offers an explicit replace-all mode, and provides confidence review plus a one-step undo.

- Approved TTML from the public Icy Lyrics Database, with automatic fallback to Spotify, Apple Music, and community lyrics through the current Spicy Lyrics API protocol.
- Packed and raw-TTML Spicy API responses remain supported.
- Saved TTML files in the `icylyrics` IndexedDB, keyed by the complete Spotify URI and protected from ordinary cache clearing.
- Searchable settings, Lyric Creator, Lyrics Manager, compact/expanded Now Playing View card, virtualized lyrics, playback offset, volume controls, and the current renderer fixes.
- Four bounded cinema/fullscreen views: album art only, album art with titles, mixed, and lyrics only.
- Optional lyrics Reveal Mode and fullscreen-only animated-background blur control.

Saved TTML normally survives Spotify restarts. Clearing Spotify's profile data or uninstalling the client can still remove browser-managed IndexedDB data.

## Lyrics lookup, caching, and API compatibility

For a standard Spotify track, Icy Lyrics uses this source priority:

1. A saved local TTML record for the complete Spotify URI, when saved local lyrics are enabled.
2. An approved exact-URI match from the Icy Lyrics Database at `https://jackscurrie.com/api/ttml`.
3. The Spicy Lyrics API as the fallback for Spotify, Apple Music, and community sources.

An Icy Lyrics Database miss, rate limit, timeout, invalid response, or unsupported TTML never blocks the fallback. Full `spotify:local:` URIs remain eligible for saved local TTML but are not sent to the online Icy database.

Lyric Creator's Auto source follows the same order, and its source dropdown can request the Icy Lyrics Database directly when a creator wants to inspect that source alone.

Before making another network request, the extension reuses lyric results cached under the exact complete Spotify URI. Remote results are retained in the expiring cache for up to seven days, concurrent automatic requests for one URI are coalesced, and recent in-session resolutions and Icy database outcomes use bounded 128-entry caches. This reduces repeat downloads when Spotify rebuilds a view or returns to a recently played song, while exact-URI keys prevent different tracks—especially local tracks—from sharing a cache entry. Explicit refreshes and expired entries can be resolved again.

The public Icy Lyrics version and the Spicy API compatibility version are deliberately separate. Icy Lyrics identifies this desktop release as `1.3.1`, while requests to the Spicy service continue to send `SpicyLyrics-Version: 6.3.12`, `client.version: 6.3.12`, and `X-mode: 2`. The Icy release number must not replace that upstream compatibility value.

## Optional Auto-time modules

Auto-time never sends the selected audio, transcript, or draft lyrics to a transcription service. A local Web Worker runs a quantized Whisper model with WebGPU when available and falls back to WASM. Only the proposed timings selected by the user are applied to the editable Lyric Creator project.

The production URL for the signed module manifest is `https://jackscurrie.com/downloads/auto-timing/manifest.json`. Model and runtime files are checksum-verified before Icy Lyrics marks a module installed. Generate a production staging copy with `npm run stage:auto-timing-models` after setting `ICY_SITE_DIR` to the Icy website checkout. Publishing that staging copy is a separate step; production module downloads require it. For local runtime QA, use `npm run stage:auto-timing-models:dev` followed by `npm run test:auto-timing-browser:build`; the development manifest and harness are excluded from and removed by production staging. The signing private key is intentionally outside this repository.

For meaningful browser QA, also set `ICY_TEST_AUDIO` to a local spoken WAV containing at least 15 words and shorter than 9 seconds. The runtime harness repeats it across a long silence to check both chunk boundaries and repeated passages, then checks digital silence separately. Use `?device=webgpu&model=fast` and `?device=wasm&model=accurate` on `/downloads/auto-timing/runtime-test/`. `node scripts/build-auto-timing-dialog-harness.mjs` adds `/downloads/auto-timing/runtime-test/ui/`, which exercises the actual dialog with the fixture text documented in its source. These are local-only test tools, not production routes. Speech-fixture tests verify the runtime and workflow; they do not establish singing accuracy across songs or languages.

Downloads use immutable model-revision URLs and a per-install file inventory. Interrupted updates retain the previous working installation, and removing a module works offline while preserving runtime files still used by another installed module. Transcription uses overlapping audio windows merged by time rather than matching repeated lyric text. Fill missing preserves manual endpoints; all proposed timings remain reviewable and editable.

## Android repositories

The supported public Android app lives in [`android-v2`](android-v2). Its `play` flavor is the self-contained version intended for public source builds and Play Store releases. The earlier `android` prototype is retained only as a local reference and is excluded from this repository.

Personal-only Android functionality lives in a separate private repository as the optional `:local-private-feature` module. A local `personal` build can locate that sibling checkout with the `icyLyrics.privateFeaturePath` Gradle property or the `ICY_LYRICS_PRIVATE_FEATURE_PATH` environment variable. Private source, signing material, and machine-specific paths must never be copied into this public tree; the public `play` build does not depend on them.

## Local Build

```powershell
& 'C:\Program Files\nodejs\npm.cmd' run build
Copy-Item -LiteralPath .\dist\icy-lyrics.js -Destination "$env:APPDATA\spicetify\Extensions\icy-lyrics.js" -Force
& "$env:LOCALAPPDATA\spicetify\spicetify.exe" apply
```

Run `npm test` for the API, TTML persistence/migration, retry, request-generation, and fullscreen helper tests.

## Easy Install

Visit jackscurrie.com/icy-lyrics for a all-in-one install script for Windows, Linux, and macOS

## Manual Install

Build the extension, copy `dist/icy-lyrics.js` into your Spicetify `Extensions` directory, then enable `icy-lyrics.js` in Spicetify.

## License, source, and attribution

Icy Lyrics is a modified, independently distributed fork of [Spicy Lyrics](https://github.com/spikerko/spicy-lyrics), created by Spikerko. The original Spicy Lyrics work is Copyright (C) 2026 Spikerko. The Icy Lyrics modifications made in 2026 are Copyright (C) 2026 Jackscurrie.

This distribution is licensed under the GNU Affero General Public License, version 3 or, at your option, any later version. The complete terms and warranty disclaimer are in [`LICENSE`](LICENSE), and the preserved upstream acknowledgement is in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

When a built copy is distributed, its release materials must identify where recipients can obtain the complete Corresponding Source in one of the ways permitted by section 6 of the license.

Icy Lyrics preserves compatibility with the Spicy Lyrics API where needed for lyric lookup. It is an independent project and is not affiliated with, endorsed by, sponsored by, or an official release of Spicy Lyrics or Spikerko.
