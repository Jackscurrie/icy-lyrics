# Icy Lyrics for iPhone privacy notice — draft

**Review required. Prepared September 4, 2026; no effective date assigned.**
This describes the implemented iPhone port for review. It is not a published
website policy, an accepted agreement, or evidence of Spotify approval. The
current in-app privacy link opens an Android-only policy. Before wider
distribution, publish the reviewed iPhone notice and make it available before
installation. Review the implementation limits below again before adoption.

## Who operates the app

Icy Lyrics is an independently maintained lyrics application by Jackscurrie,
not a Spotify product. Contact: [jack@jackscurrie.com](mailto:jack@jackscurrie.com).
This notice covers the iPhone application's handling of information. Spotify,
lyrics providers, Apple, Sideloadly, your selected file provider and destinations
you share to operate their own services under their own terms and policies.

## Information used and where it goes

| Feature | Information and purpose | Recipient |
| --- | --- | --- |
| Spotify connection and playback | Spotify authorization, current track URI, title, artists, album, duration, artwork, position, pause state and available controls allow the app to display and synchronize lyrics. Play, pause, skip and seek actions control Spotify's installed app. Audio playback stays in Spotify; Icy Lyrics does not record or upload audio. | Spotify's authorization service and installed Spotify app through App Remote. |
| Optional catalog matching | If a track lacks a usable catalog identity, an authorized lookup can read the currently playing track and search by title/artist. A confident match is remembered locally. A complete `spotify:local:` identity stays the local import key. | `api.spotify.com`, using the separate lyrics authorization. |
| LRCLIB lyrics | When enabled, lyric lookup sends title, artist, available album and, for exact matching, duration. LRCLIB is enabled by default. No Spotify token is included. | `lrclib.net/api/`. |
| Experimental Spicy Lyrics | Disabled by default. Requires both enabling the provider and separate token-sharing consent. Requests send the Spotify catalog track ID, requested source, compatibility version and the short-lived **lyrics** access token. A token is a credential, not anonymous data. Playback credentials and refresh tokens are not sent to this provider. | `api.spicylyrics.org/query`. Apple-backed and Spotify-backed fallback requests also go through this service; the app does not directly authorize Apple Music. |
| Imported TTML | The selected file's text, parsed lyrics, matching song metadata, complete track key and import timestamps are saved locally. A private copy avoids dependence on continuing access to the original Files provider. | Application storage. The app does not upload imported TTML contents to lyric providers. Selecting a cloud file may cause your Files provider to download it. |
| Diagnostics | Local provider/playback outcomes, timestamps, HTTP status, redacted messages and hashed track keys help troubleshoot failures. | Local storage, unless you explicitly copy or share diagnostics. |

For any Internet request, the receiving service and its infrastructure can
observe connection information such as your IP address and request headers.
The implementation has no maintainer-operated token server, telemetry endpoint,
advertising SDK or authored automatic crash-report uploader. This does not
describe every internal action of Spotify's closed-source SDK or Apple's
system services; their own privacy notices apply. See
[Spotify's privacy policy](https://www.spotify.com/legal/privacy-policy/).

An exact saved TTML match is checked before remote lyric lookup when local
lyrics are enabled. Without one, enabled providers may receive song metadata,
including metadata for a local song. Disabling a provider stops using that
provider; it does not retroactively erase requests already received by it.

## Authorization and passwords

Sign-in uses the iOS system authentication browser and Spotify's authorization
page with PKCE. Icy Lyrics does not ask for or retain your Spotify password.
Playback requests `app-remote-control`; separate lyrics authorization requests
`user-read-currently-playing`. The two credential purposes remain separate.

Access tokens, available refresh tokens, expiry and scopes are stored in
device-only, non-synchronizing Apple Keychain records accessible while the
device is unlocked. They are not stored in the settings file or lyrics
database. Keychain items can outlast app removal; do not assume uninstalling
alone removes every authorization record. This port does not implement a
custom cloud account or cloud synchronization service.

Apple credentials used to sideload or refresh the app are entered in
Sideloadly/Apple's signing flow, not in Icy Lyrics. The app does not receive
those credentials from its own interface.

## Storage, retention and backups

- Settings and choices, including provider consent and timing offsets, are
  saved in iOS UserDefaults. Imported lyrics, cached responses, catalog
  aliases, timing records and diagnostics use an app-private SQLite database.
- Saved TTML text and metadata remain until removed or the app's storage is
  deleted. Imported UUID file copies are stored in Application Support with
  iOS file protection after first unlock. Removing a Library entry also removes
  its app-owned copy when no saved entry still references that file. A failed
  import removes the new, unreferenced copy; replacing lyrics removes the old
  copy only after the new document is saved successfully. Cleanup is restricted
  to regular UUID-named files directly inside the app's Imports directory and
  requires a successful check of saved references. It does not scan for older
  orphaned files or promise cleanup after a process crash or storage failure.
  The original file in your chosen Files provider is never deleted by this
  action. All eight native cleanup regressions passed in macOS simulator run
  33855937655. Physical-device verification remains pending.
- Positive provider cache entries normally expire after three days; negative
  lookups after one hour. Initialization removes expired entries and trims the
  cache to 250 records. During an active session, an expired result
  may still be used when a provider fails. Expiry is not a secure-erasure
  guarantee. Catalog aliases have no automatic age limit in this revision.
- Diagnostics retain at most 200 events, with a seven-day retention window
  pruned at initialization and on new writes. **Clear** removes stored events;
  later operations can create new ones. Debug logging adds informational
  provider traces; warnings and failures can be recorded while it is off.
  Secret redaction reduces exposure
  but is not a promise that arbitrary error text cannot contain personal data.
  Inspect a report before sharing it.
- The port does not exclude its settings, database or import copies from iOS
  device backups. Apple/device backup settings can therefore affect retention
  and restoration. Normal app updates are intended to retain these files;
  actual sideload refresh and upgrade persistence still require iPhone tests.

## Permissions, clipboard and browser storage

The app accesses only files you select through the system picker. It does not
request microphone, camera, contacts, location, photo-library, notification
reading or Bluetooth scanning permission. It uses Spotify playback state,
rather than Android notification access. Automatic Bluetooth-specific timing
is inactive when the real output cannot be identified reliably.

Reduced-motion and orientation settings affect presentation. The optional
keep-awake setting prevents screen sleep while the app is active. **Copy**
writes the selected diagnostic report to the system clipboard; the app does
not continuously read your clipboard. System clipboard sharing and a chosen
share destination can make that copied report available outside this app.

The native interface does not place advertising cookies or embed a tracking
web view. Spotify authentication opens a system web session which may reuse
browser login cookies; external links open websites with their own cookie
behavior. The app does not set an ephemeral-only authentication session.
Manage website data through the relevant browser/iOS settings and Spotify's
own controls. Rejecting or clearing login cookies may require signing in again.

## Choices and present deletion limits

Use Settings to disable LRCLIB or the experimental provider, withdraw
experimental token sharing, change local-lyrics use, or turn off keep-awake.
Use Library to remove saved lyric entries and Diagnostics to clear reports.

The iPhone **Disconnect Spotify** action now cancels authorization/reconnection
and provider work, closes App Remote, clears current playback/artwork/lyrics,
and removes both playback and lyrics Keychain credentials. It remains available
when either credential purpose exists. If Keychain deletion fails, the app
reports the error, blocks further token use in that process, and retains the
disconnect control for retry. Physical-device verification is pending.

**Current deletion limitation:** this action preserves saved TTML, settings,
provider cache, catalog aliases and diagnostic database rows. It does not yet
complete erasure of previously stored Spotify-derived information; this remains
a release blocker. To revoke authorization at Spotify itself, use the account
Apps page and remove the Icy Lyrics application's access. Spotify provides
[account-access removal instructions](https://support.spotify.com/us/article/spotify-on-other-apps/).
Service revocation is not local deletion. Removing the app deletes its normal
sandbox storage but can leave backups and Keychain records. Keep your original
TTML files separately before deleting anything.

Contact the maintainer about information you send directly, such as a support
email or shared report. The app has no mechanism for the maintainer to remotely
read or erase your phone's database. Contact Spotify or a lyrics provider
separately about data held by that service. This draft assigns no unverified
retention period to their server logs or to support correspondence.

## Required review before publication

Resolve retained Spotify-data deletion, verify managed-import cleanup and
backup behavior on a device, and reassess retained catalog aliases and provider
data against the distribution review. Confirm the maintainer's contact and
support-data handling, choose an effective date, and publish an iPhone-specific
URL before representing this draft as a live policy. The proposed end-user
terms designate Spotify as a beneficiary of that agreement and privacy policy;
that clause is pending review and acceptance, not activated by this file.

Implementation references: [authorization](../app/IcyLyrics/SpotifyAuthorization.swift),
[native host and import copying](../app/IcyLyrics/NativeHost.swift),
[iPhone services](../shared/platform/src/iosMain/kotlin/com/icy/lyrics/core/platform/ios/IosServices.kt),
[managed-import cleanup](../shared/platform/src/iosMain/kotlin/com/icy/lyrics/core/platform/ios/IosLocalTtmlImports.kt),
[storage and retention](../shared/platform/src/commonMain/kotlin/com/icy/lyrics/core/platform/storage/LyricsStorageRepositories.kt),
[diagnostics](../shared/platform/src/commonMain/kotlin/com/icy/lyrics/core/platform/diagnostics/Diagnostics.kt),
[distribution review](DISTRIBUTION-REVIEW.md).
