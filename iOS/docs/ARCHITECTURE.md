# iPhone port architecture

## Canonical presentation

`shared/ui/src/commonMain` contains the existing Compose screen tree, lyric canvas, analytic animation/timeline, all four landscape modes, transitions, expanded portrait, settings/library/diagnostics/legal screens, dialogs, and copy. New native integrations sit below `IcyUiPlatform` and `MobileBackend`/`PlaybackGateway`. Android compiles the common and Android source folders directly, preserving AGP and the Android Kotlin compiler. The separate iOS Gradle build compiles common and iOS source sets to a static `IcyShared.framework`.

Roboto and selected Android API 36 Noto fallback files are copied unchanged with provenance hashes. Used Material icons are extracted vectors from the original Android icon artifact. The app icon derives from the current Android launcher vectors, including the user's uncommitted icon changes. iOS reads these files from the `IcyAssets` application resource folder. Fonts, shaping, scroll physics, blur, and Skia rendering still need cross-platform pixel comparisons; shared Compose alone is insufficient.

Kawarp's shader source, preprocessing, sampled artwork, uniforms, motion inputs, and color calculations remain shared. Android uses its RuntimeShader adapter. iOS uses Skia RuntimeEffect. Both accept deterministic frame clocks for fixtures. Physical screen cutouts/insets, OS-owned dialogs and pickers, and necessary connection/platform wording are the allowed visual differences.

## Playback and authorization

The common playback contract exposes track identity, full raw URI, image, duration, position, speed, monotonic capture time, transport capabilities, connection state, and transport methods. Android maps its existing MediaSession state to that model. `NativeHost` uses Spotify App Remote 5.0.1, subscribes to player state, fetches artwork with identity/generation guards, and requests fresh state after connection and foreground return. A separate, serial Spotify Web API monitor supplies current-song metadata and progress when App Remote cannot establish its local channel. Recent App Remote samples take priority, while lifecycle, account and sample generations reject late Web responses. A paused/disconnected snapshot freezes interpolation.

Spotify remains the audio player. Backgrounding Icy Lyrics disconnects App Remote; returning reconnects without automatically forcing another app switch. A connection action may send the user to Spotify. If the SDK cannot wake/connect to Spotify, the shared UI shows necessary connection wording asking the user to start playback and return. No iOS audio/background entitlement is claimed, and no attempt is made to discover arbitrary other apps' now-playing sessions.

`SpotifyAuthorization` uses browser PKCE (S256), an exact custom-scheme callback, a one-use state/verifier, a ten-minute authorization lifetime, strict response/scopes validation, redirect rejection, cancellation/generation checks, and single-flight refresh. Keychain slots are separate for combined `app-remote-control`/`user-read-currently-playing` playback and narrowly scoped `user-read-currently-playing` experimental lyrics authorization. Playback tokens never enter a lyrics provider. Obsolete playback grants are removed once when the required scope set changes, so the user can grant the replacement instead of remaining in an unusable authorized state. The existing explicit experimental-provider consent and defaults remain shared. There is no client secret or token-exchange server. The owner must configure the Spotify developer app and permitted accounts.

## Providers, durable storage and import races

`shared/lyrics` contains shared TTML/LRC/payload parsers, models, tokenization, timing, transitions and provider selection. XML uses a bounded common parser; YAML uses SnakeYAML Engine KMP with the original rejection rules. Existing Android parser tests run against these exact common files.

`shared/platform` retains local→Spicy→LRCLIB→Apple→Spotify ordering, strict/better-sync policies, exact local imports, cache keys, retries, diagnostics/redaction, alias resolution and provider wire formats. Android keeps OkHttp, Room opening/migrations, DataStore and Bluetooth monitoring. iOS uses Ktor/Darwin HTTP, Room/BundledSQLite in Application Support, NSUserDefaults settings, and native Unicode/date adapters.

Full `spotify:local:` URIs remain exact durable keys. iOS copies Files imports into Application Support and stores parsed/raw lyrics durably. Selection captures the original track; completion can save that original import, but only reloads the display if the currently playing track still matches. Request generations also prevent cancelled or uncooperative providers from publishing stale success/failure. Imports are bounded before parsing, external resource/entity loading is prohibited, and malformed content surfaces an error.

Global timing stays active. Shared device profiles exist, but iOS does not assume that its own AVAudioSession route identifies Spotify's output, which could be another Connect device. Automatic Bluetooth offsets remain inactive until a trustworthy output identity is available. Android route identification and saved profiles remain unchanged.

UIKit adapters provide document selection, sharing, idle-timer control, app lifecycle and callback handling. Compose supplies iPhone safe-area/orientation information; the iOS UI platform provides clipboard, clocks, dates and reduced-motion state. The legal/copy screen remains shared.

## Packaging boundaries

`app/IcyLyrics.xcodeproj` is checked in and reproducibly generated. Simulator and device Kotlin frameworks are staged separately. Xcode builds an unsigned iPhone-only iOS 16+ archive; `package_ipa.py` checks thin arm64 device Mach-O metadata, dylib closure, resources, plist metadata, CRC and SHA-256 before creating `Payload/IcyLyrics.app`. Its simulator verification marker includes the source fingerprint to reject packaging after source changes.

New credentials, provisioning profiles, keystores, binaries, generated tool downloads and local patches are ignored under `iOS`. Public CI never imports the external personal Android module. Public binary distribution is subject to the separately documented SDK/source-license review.

References: [Compose platform differences](https://kotlinlang.org/docs/multiplatform/compose-platform-specifics.html), [Spotify app lifecycle](https://developer.spotify.com/documentation/ios/concepts/application-lifecycle), [Spotify SDK 5.0.1](https://github.com/spotify/ios-sdk/releases/tag/v5.0.1), [Spotify currently playing](https://developer.spotify.com/documentation/web-api/reference/get-the-users-currently-playing-track), [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).
