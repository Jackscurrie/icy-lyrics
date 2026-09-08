# Icy Lyrics

PUBLIC GOOGLE PLAY STORE RELEASE COMING SOON!! Currently in closed testing
 
Icy Lyrics is a fork of the popular Spicetify lyrics extension "Spicy Lyrics" by Spikerko with multiple fullscreen modes, a lyric creator, and more

Icy Lyrics 1.1.0 is the current public desktop release, following the original 1.0.0 public release. The desktop extension auto-updates on startup from jackscurrie.com and falls back to the installed build whenever the website is unavailable or verification fails.

## 1.1.0 Highlights

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

The public Icy Lyrics version and the Spicy API compatibility version are deliberately separate. Icy Lyrics identifies this desktop release as `1.1.0`, while requests to the Spicy service continue to send `SpicyLyrics-Version: 6.3.12`, `client.version: 6.3.12`, and `X-mode: 2`. The Icy release number must not replace that upstream compatibility value.

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
