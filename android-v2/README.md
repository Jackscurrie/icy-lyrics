# Icy Lyrics Android v2

A clean Android 13+ rewrite of Icy Lyrics. It lives beside the original `android/` prototype, which remains untouched. The public Google Play flavor retains the existing application id: `com.icy.lyrics`.

## What is implemented

- Spotify-only now-playing discovery and transport control through Android's `MediaSession` notification-listener access. Playback does not depend on Spotify Web API polling.
- Portrait player with artwork, title/artist, transport controls, seek bar, source badge, and animated lyrics.
- Four bounded landscape modes in desktop order: artwork, artwork with titles, mixed, and lyrics. Tap the left/right screen edges to move between them; the mixed/lyrics transition retains and moves one live lyric scene.
- Desktop lyric behavior for static, line-synced, and syllable-synced lyrics: timing gradients, analytic word/letter springs, held-word letter emphasis, Reveal, interludes, background vocals, duet lanes, RTL text, transliterations, focus transitions, and tap-to-seek.
- Desktop-style Kawarp artwork background using an Android runtime shader. The background can be animated, held as a static blurred image with no scheduled frames, or turned off for plain black.
- Durable per-track TTML import and a local library for viewing/removing saved lyrics. Full `spotify:local:` URIs are preserved as keys.
- Global lyric timing from -5000 ms through +5000 ms in 10 ms increments, plus an overriding remembered value for each active Bluetooth output device.
- Privacy-safe diagnostics with provider attempts, selected source/sync type, errors, copy/share/clear actions, a 200-event limit, and seven-day retention.

## Lyric lookup order

Strict priority (the default) uses:

1. Saved local TTML (`ldb`)
2. The desktop-compatible Spicy Lyrics automatic query (upstream provenance remains visible as `spl`, `aml`, or `spt`)
3. LRCLIB
4. Apple Music-backed result (`aml`)
5. Spotify-backed result (`spt`)

The optional **Prefer better sync** policy keeps local TTML absolute, then chooses the highest timing resolution returned by the remote sources. A queued Spicy request does not prevent a lower-priority source from being shown while it prepares. In strict mode, queued Spicy jobs are rechecked on the desktop cadence (2s, 3s, 4.5s, 6.75s, then every 10s indefinitely) until they resolve or the track changes. As on desktop, the queue cadence ignores `Retry-After`; a visible lower-priority result remains on screen during those checks.

Spicy Lyrics is an unofficial/experimental protocol and is disabled by default. Android's primary Spicy route uses the normal desktop extension's exact `POST https://api.spicylyrics.org/query` contract: the `SpicyLyrics-Version`, `X-mode: 2`, and `SpicyLyrics-WebAuth` headers; the `queries` plus `client.version` body; result slot `0`; no Creator-only source variable; and the same packed-response shape. A valid automatic result wins in the Spicy route before LRCLIB while retaining its actual upstream source label for diagnostics. The later Apple and Spotify fallback slots use explicit source requests and validate their returned marker. LRCLIB and local TTML work without Spotify authorization.

Spotify's Web API supplies playback/catalog data, not lyric text. The developer app is therefore used for the experimental PKCE token capability and to resolve a notification that lacks a Spotify ID: the app first verifies the signed-in account's currently-playing item, then permits an exact, duration-compatible, unambiguous catalog match. The resolved URI is remembered locally and unlocks the Spicy/Spotify/Apple lyric queries; it is not treated as a direct Spotify lyrics endpoint.

## Local setup

Create `local.properties` (it is ignored by the repository) with the Android SDK path and, optionally, the public client id from your Spotify developer app:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
spotifyClientId=your_public_client_id
```

For Spotify connection:

1. In the Spotify developer dashboard, register `http://127.0.0.1/callback` as a redirect URI. Leave the port out of the registered URI; the app adds a short-lived dynamically assigned port to each authorization request, as Spotify permits for loopback IP literals.
2. Build with the public client id above. Do not put a client secret in this app.
3. In Icy Lyrics settings, connect Spotify, then separately approve the Spicy Lyrics token-sharing dialog.

Authorization requests only `user-read-currently-playing` and uses Code + PKCE, a CSRF state value, a loopback-only callback, refresh tokens, and Android Keystore-backed AES-GCM storage. There is no exported OAuth activity or custom URI scheme. See Spotify's [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [redirect URI](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri), [refresh token](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens), and [currently playing](https://developer.spotify.com/documentation/web-api/reference/get-the-users-currently-playing-track) documentation.

## Build

The Android app has two distribution flavors:

- `play` is the complete public/Play Store app. It always uses `com.icy.lyrics` and cannot depend on the private feature.
- `personal` is a separately installable build with application id `com.icy.lyrics.personal`. If a private feature repository is configured, that Android library is added only to this flavor.

From this directory, validate and build the public app with:

```powershell
.\gradlew.bat testPlayDebugUnitTest lintPlayDebug verifyPlayDistributionBoundary assemblePlayDebug
```

The debug APK is written to `app/build/outputs/apk/play/debug/app-play-debug.apk`. Build the Play Store bundle only with:

```powershell
.\gradlew.bat verifyPlayDistributionBoundary bundlePlayRelease
```

The release bundle is written to `app/build/outputs/bundle/playRelease/app-play-release.aab`. Do not use a personal task for Play Store uploads.

### Separate private feature repository

Keep private Android code in a separate private repository cloned beside (not inside) this public repository. Its root must be an Android library module with a `build.gradle.kts` or `build.gradle` file. Gradle includes it locally as `:local-private-feature`. Do not add it as a Git submodule, copy it anywhere inside the public repository, or commit its path to this repository.

Point Gradle at the external checkout using either the `icyLyrics.privateFeaturePath` Gradle property or `ICY_LYRICS_PRIVATE_FEATURE_PATH` environment variable. For example:

```powershell
$env:ICY_LYRICS_PRIVATE_FEATURE_PATH = "C:\path\outside\icy-lyrics\icy-lyrics-private-android"
.\gradlew.bat testPersonalDebugUnitTest assemblePersonalDebug
```

Or store the machine-specific absolute path in your user-level Gradle file at `%USERPROFILE%\.gradle\gradle.properties` (never the project's tracked `gradle.properties`):

```properties
icyLyrics.privateFeaturePath=C:/path/outside/icy-lyrics/icy-lyrics-private-android
```

When neither setting is present, Gradle does not load the private repository and both `play` and `personal` still build; the personal build simply has no private feature. If a configured path is missing, is not a Gradle module, or resolves inside the public repository, configuration fails before anything is built.

## First run

1. Grant Icy Lyrics notification-listener access on the Android system page it opens.
2. Start playback in the Spotify Android app.
3. Optionally grant nearby-device access to identify the active Bluetooth output for per-device timing.
4. To save TTML, play its matching song, choose **Import TTML**, and select the file. Imports always persist across normal app/device restarts.

Installing v2 over the old prototype deliberately performs a fresh start: the legacy `lyrics_store` and `spotify_auth` preferences are cleared and never migrated. Only ordinary app settings participate in Android backup; TTML, cached network lyrics, diagnostics, and credentials do not.

## Project layout

- `app/` — Android UI, MediaSession tracking, fullscreen/player behavior, and app integration.
- `core/lyrics/` — normalized models, parsers, provider orchestration, playback clock, and desktop animation/focus math.
- `core/platform/` — Room/DataStore persistence, providers, PKCE, Bluetooth timing, and diagnostics.

## License, source, and attribution

Icy Lyrics for Android is developed by Jackscurrie. The wider Icy Lyrics project includes modified portions of [Spicy Lyrics](https://github.com/spikerko/spicy-lyrics) by Spikerko. The original Spicy Lyrics work is Copyright (C) 2026 Spikerko; Icy Lyrics modifications made in 2026 are Copyright (C) 2026 Jackscurrie.

This distribution is licensed under the GNU Affero General Public License, version 3 or, at your option, any later version. The complete license and warranty disclaimer are in [`LICENSE`](LICENSE). Preserved Spicy Lyrics attribution and the Kawarp MIT notice are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

The public source archive matching this release is linked from [Icy Lyrics legal and credits](https://jackscurrie.com/icy-lyrics/legal). Icy Lyrics is an independent project and is not affiliated with, endorsed by, or sponsored by Spicy Lyrics, Spikerko, Spotify, Apple, LRCLIB, or their respective owners or operators.
