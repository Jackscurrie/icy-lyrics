# Icy Lyrics Android v2

A clean Android rewrite of Icy Lyrics for Android 13+ phones and Android 11+ Google TV devices. It lives beside the original `android/` prototype, which remains untouched. Public Google Play phone and TV flavors retain the existing application id: `com.icy.lyrics`.

## What is implemented

- Spotify-only now-playing discovery and transport control through Android's `MediaSession` notification-listener access. Playback does not depend on Spotify Web API polling.
- Portrait player with artwork, title/artist, transport controls, seek bar, source badge, and animated lyrics.
- Four bounded landscape modes in desktop order: artwork, artwork with titles, mixed, and lyrics. Tap the left/right screen edges to move between them; the mixed/lyrics transition retains and moves one live lyric scene.
- Desktop lyric behavior for static, line-synced, and syllable-synced lyrics: timing gradients, analytic word/letter springs, held-word letter emphasis, Reveal, interludes, background vocals, duet lanes, RTL text, transliterations, focus transitions, and tap-to-seek.
- Desktop-style Kawarp artwork background using an Android runtime shader. The background can be animated, held as a static blurred image with no scheduled frames, or turned off for plain black.
- Durable per-track TTML import and a local library for viewing/removing saved lyrics. Full `spotify:local:` URIs are preserved as keys.
- A built-in mobile Lyric Creator in both Play and personal builds for editing, word timing with **Start**, **Commit**, and **End**, safe unfinished previews, local saves, recoverable drafts, and TTML export.
- Global lyric timing from -5000 ms through +5000 ms in 10 ms increments, plus an overriding remembered value for each active Bluetooth output device.
- Privacy-safe diagnostics with provider attempts, selected source/sync type, errors, copy/share/clear actions, a 200-event limit, and seven-day retention.

## Lyric lookup order

Strict priority (the default) uses:

1. Saved local TTML (`ldb`)
2. Icy Lyrics Database (`icy`) at `https://jackscurrie.com/api/ttml`
3. LRCLIB
4. Apple Music-backed result (`aml`)

Each source has its own settings toggle. The optional **Prefer better sync** policy keeps local TTML absolute, then checks enabled remote sources sequentially and chooses the highest timing resolution returned. It stops as soon as a source supplies syllable timing.

The Icy Lyrics Database adapter anonymously sends only the complete Spotify track URI in an exact-match POST. It parses the returned TTML locally so word timing is preserved. The app performs that request only when a different song is loaded or the user explicitly reloads lyrics. Concurrent app/personal-car surfaces share a serialized cache lookup, successful results remain in the on-device cache for 30 days, and misses remain cached for one hour. Rate limits and server errors fall through without automatic Icy retries. The dedicated TV build has one additional fallback: if Spotify TV omits its track ID, it sends the displayed song title to the public Icy catalog and accepts a URI only for one unambiguous exact title/artist match. Album, duration, and ISRC must also agree whenever both sides provide them. This fallback is disabled with the Icy source switch and is not enabled in the Play phone, personal, or iOS builds.

LRCLIB removes known Spotify quality badges from artist metadata, validates exact responses, tries album/artist/broad/title-only searches, handles reordered or featured artist credits with album/duration corroboration, prefers synchronized representations at equal match confidence, and versions away cache rows written by the older matching policy. Static hits are rechecked for newly available timing, and rejected identities are not negative-cached.

The automatic Spicy Lyrics database source and the direct Spotify lyric source are no longer part of Android resolution. Spotify account integration remains only because the optional Apple Music fallback is still transported through Spicy Lyrics and because it can resolve a notification that lacks a Spotify ID. Spotify's Web API itself supplies playback/catalog data, not lyric text. Local TTML, Icy Lyrics Database, and LRCLIB do not need Spotify authorization.

## Local setup

Create `local.properties` (it is ignored by the repository) with the Android SDK path and, optionally, the public client id from your Spotify developer app:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
spotifyClientId=your_public_client_id
```

For the optional Apple Music fallback:

1. In the Spotify developer dashboard, register `http://127.0.0.1/callback` as a redirect URI. Leave the port out of the registered URI; the app adds a short-lived dynamically assigned port to each authorization request, as Spotify permits for loopback IP literals.
2. Build with the public client id above. Do not put a client secret in this app.
3. Enable **Apple Music** in Icy Lyrics settings, approve the one-time token-sharing explanation, and connect Spotify.

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
- `app/src/main/java/com/icy/lyrics/creator/` — the shared mobile Lyric Creator used by both Play and personal builds.
- `core/lyrics/` — normalized models, parsers, provider orchestration, playback clock, and desktop animation/focus math.
- `core/platform/` — Room/DataStore persistence, providers, PKCE, Bluetooth timing, and diagnostics.

## License, source, and attribution

Icy Lyrics for Android is developed by Jackscurrie. The wider Icy Lyrics project includes modified portions of [Spicy Lyrics](https://github.com/spikerko/spicy-lyrics) by Spikerko. The original Spicy Lyrics work is Copyright (C) 2026 Spikerko; Icy Lyrics modifications made in 2026 are Copyright (C) 2026 Jackscurrie.

This distribution is licensed under the GNU Affero General Public License, version 3 or, at your option, any later version. The complete license and warranty disclaimer are in [`LICENSE`](LICENSE). Preserved Spicy Lyrics attribution and the Kawarp MIT notice are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

The public source archive matching this release is linked from [Icy Lyrics legal and credits](https://jackscurrie.com/icy-lyrics/legal). Icy Lyrics is an independent project and is not affiliated with, endorsed by, or sponsored by Spicy Lyrics, Spikerko, Spotify, Apple, LRCLIB, or their respective owners or operators.
