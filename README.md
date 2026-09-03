# Icy Lyrics

Icy Lyrics is a forked Spicetify lyrics extension with fullscreen lyrics, permanent local TTML storage, dynamic backgrounds, and word-by-word lyrics rendering.

Icy Lyrics 1.0.0 is the first public release. The desktop extension remains an independent Icy-branded fork while keeping Spicy Lyrics 6.3.12 API compatibility for lyric lookup. Its Icy-owned startup updater checks the release manifest on jackscurrie.com and falls back to the installed build whenever the website is unavailable or verification fails.

## 1.0.0 Highlights

- Spotify, Apple Music, and community lyrics through the current Spicy Lyrics API protocol, including packed and raw-TTML responses.
- Saved TTML files in the Icy-owned `icylyrics` IndexedDB, keyed by the complete Spotify URI and protected from ordinary cache clearing.
- Searchable settings, Lyrics Manager, compact/expanded Now Playing View card, virtualized lyrics, playback offset, volume controls, and the current renderer fixes.
- Four bounded cinema/fullscreen views: album art only, album art with titles, mixed, and lyrics only.
- Optional lyrics Reveal Mode, final-line outro, and fullscreen-only animated-background blur control.

Saved TTML normally survives Spotify restarts. Clearing Spotify's profile data or uninstalling the client can still remove browser-managed IndexedDB data.

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

## Manual Install

Build the extension, copy `dist/icy-lyrics.js` into your Spicetify `Extensions` directory, then enable `icy-lyrics.js` in Spicetify.

## License, source, and attribution

Icy Lyrics is a modified, independently distributed fork of [Spicy Lyrics](https://github.com/spikerko/spicy-lyrics), created by Spikerko. The original Spicy Lyrics work is Copyright (C) 2026 Spikerko. The Icy Lyrics modifications made in 2026 are Copyright (C) 2026 Jackscurrie.

This distribution is licensed under the GNU Affero General Public License, version 3 or, at your option, any later version. The complete terms and warranty disclaimer are in [`LICENSE`](LICENSE), and the preserved upstream acknowledgement is in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

When a built copy is distributed, its release materials must identify where recipients can obtain the complete Corresponding Source in one of the ways permitted by section 6 of the license.

Icy Lyrics preserves compatibility with the Spicy Lyrics API where needed for lyric lookup. It is an independent project and is not affiliated with, endorsed by, sponsored by, or an official release of Spicy Lyrics or Spikerko.
