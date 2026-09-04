# iPhone binary distribution review

Reviewed September 4, 2026 against Spotify iOS SDK 5.0.1, Spotify Developer
Terms version 10 (effective May 15, 2025), the current linked Developer Policy,
and this checkout. **The public binary gate remains closed.** This is an
evidence-based engineering review, not a claim that external permissions or
legal compatibility have been established.

## What the SDK terms actually permit

The SDK tag points to Spotify's Developer Terms; MPMessagePack's MIT license
does not license the Spotify framework itself. Section III.1 permits SDK
binary distribution inside a compliant application for personal use on
approved devices. There is no blanket requirement in that clause for a
separate Spotify approval merely to package an unsigned IPA. The terms still
apply to private testing; publishing an artifact in a public repository is not
an owner-only delivery. [SDK 5.0.1 terms notice](https://github.com/spotify/ios-sdk/tree/v5.0.1#terms-of-use),
[Developer Terms, sections II-IV](https://developer.spotify.com/terms).

An SDK-equipped application with playback controls meets Spotify's definition
of a Streaming SDA even when Spotify's own app plays the audio. Developer
Terms V.8, V.11 and V.12 require account disconnection/deletion, specific
end-user terms and acceptance, and a privacy policy before installation or
signup. A license file alone does not perform those user-facing actions.
[Developer Terms](https://developer.spotify.com/terms).

AGPL section 2 permits making and running private copies without conveying
them. An owner-controlled personal build is therefore a different question
from publicly distributing the combined program. This review does not find
an AGPL requirement to obtain permission merely to compile a private copy.
It also does not treat a downloadable public GitHub artifact as a private
copy or clear the separate Spotify use-case conditions.

## External decisions still needed

1. **AGPL and the combined SDK binary.** This project preserves Spikerko's
   AGPL-covered work and currently links it into a program that directly calls
   Spotify's proprietary framework. The checked-in AGPL sections 1, 5, 6 and
   10 require corresponding source and whole-work licensing without additional
   restrictions. Spotify's binary-only grant and restrictions are not an
   established compatible license. The framework is separately embedded, but
   packaging alone does not establish independent-work status. No linking
   exception or permission from the relevant upstream copyright holders is
   present. Obtain an applicable exception/compatible permission or a supported
   legal determination of the combination before distribution; an owner
   acknowledgment or repository variable cannot grant somebody else's rights.
   [Project license](../../LICENSE),
   [GNU explanation of incompatible libraries and copyright-holder exceptions](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs).
2. **Spotify use-case restrictions.** Policy III.5 prohibits integration with
   another service's streams/content; III.6 prohibits synchronizing recordings
   with visual media. Timed lyrics fetched from other providers and the visual
   presentation warrant a specific applicability decision. The text does not
   explicitly name lyrics, so this review does not assert that every lyrics
   display is prohibited. No documented Spotify interpretation/permission for
   this exact implementation was found. Policy II.4 also requires Spotify
   attribution and track links, and II.5 requires relevant art/metadata during
   streaming. These must be reconciled with the identical-interface rule;
   this review changes no player layout. [Developer Policy](https://developer.spotify.com/policy),
   [iOS SDK policy notes](https://developer.spotify.com/documentation/ios/getting-started).
3. **Developer account configuration.** Register the iOS application, actual
   installed bundle ID and `com.icy.lyrics.ios://spotify-callback`, then provide
   its public client ID. A client secret is unnecessary. This is required for
   real connection testing, not for offline fixture compilation. Web API
   development mode currently requires a Premium app owner and allows five
   allowlisted users. Wider Web API access needs Spotify's quota process; do
   not infer that downloading a public IPA grants API access or that these
   Web API limits describe all App Remote behavior. [iOS registration guide](https://developer.spotify.com/documentation/ios/getting-started),
   [Web API quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes).

## Concrete engineering items

| Item | Finding and next action |
| --- | --- |
| Renderer notices | Resolved in source: `shared/ui/assets/legal/native` now contains 25 exact upstream files plus provenance, covering pinned Skiko/Skia and the represented native dependencies. Each fetched file was checked against its upstream Git blob or the release archive SHA-256. The existing resource-copy and packaging checks include this directory. Final IPA inclusion remains build-verified. |
| Font notices | Already present. All seven font binaries match recorded hashes. Noto copyright/OFL notices, including Adobe's reserved name, and Roboto copyright/Apache text accompany the unmodified fonts. No additional font permission is needed for this bundling. [OFL mobile bundling FAQ 1.20](https://openfontlicense.org/ofl-faq/), [Apache redistribution requirements](https://www.apache.org/licenses/LICENSE-2.0). |
| Spotify bundled dependency | MPMessagePack's full MIT notice already accompanies the app assets; its bytes match the SDK notice. Preserve it and Spotify's own framework notices. |
| Corresponding source | Resolved in packaging source: `build-report.json` now includes exact-commit browse/archive/build/install links and the delivery includes `SOURCE.md`. Packaging rejects changed or untracked port/Android/workflow sources, including unexpected generated schemas. Immutable-reference and real-Git dirty-source tests pass. Final delivered-file verification remains part of packaging. This does not resolve the proprietary SDK combination. |
| Privacy and end-user terms | Reviewable iOS drafts are now in [PRIVACY.md](PRIVACY.md) and [END-USER-TERMS-DRAFT.md](END-USER-TERMS-DRAFT.md), referenced by the installation guide. They are not published policies or an implemented acceptance flow. The existing website privacy URL still explicitly applies only to Android and describes Android Keystore, notification access and Android backup. Review, publish and expose the finalized iPhone notice before installation; review agreement acceptance before changing the UI. |
| Imported-file retention | Resolved in source: Library deletion removes an unreferenced managed UUID copy; parse/save failures remove the unreferenced new copy, and replacement removes the old copy only after a successful save. All saved rows protect referenced files, including rows whose lyric JSON cannot currently be decoded. Cleanup refuses external files, nested files and symlink redirection. It does not scan historical orphaned files or guarantee cleanup after a process crash or unavailable storage. Eight native regression tests are added; execution on macOS and physical-device verification remain pending. |
| Spotify attribution/links | The canonical artwork displays have no track-link action, and the only identified Spotify text is connection/provider wording. Check all playback modes against the policy's attribution/artwork requirements. Any visual changes require review against the golden rule. |

## Existing disconnect control: source fix and remaining deletion

The original native callback cleared only lyrics credentials and exposed the
button only for lyrics authorization. The native integration now uses that
same **Disconnect Spotify** control whenever either credential purpose exists.
Stored authorization also makes Settings reachable before the first playback
snapshot. The existing Connect action authorizes playback; explicitly enabling
the experimental provider with token-sharing consent can request its separate
lyrics authorization. Startup does not launch that authorization flow.
It cancels pending authorization/reconnection, provider and import work,
disconnects App Remote, clears current playback/artwork/lyrics, and deletes both
credential purposes. A Keychain deletion error prevents token use in the current
process and leaves the control available for retry. Physical verification is
pending; this review adds no new screen or navigation.

Stored provider cache, catalog aliases and diagnostic rows still remain,
alongside intentionally durable user TTML and settings. Consequently the
working control alone does not yet satisfy the complete data-deletion review.
Classify and remove Spotify-derived personal data while preserving the user's
own imported text; a blanket database wipe would violate the durable-import
requirement. The privacy draft states this present limitation explicitly.

## What clears the gate

Finish the engineering items, record the specific rights/use-case decision
and applicable external permission where required, and retain reproducible
source, notices and validation evidence. Then the owner can deliberately
authorize public binary distribution. Do not equate successful compilation,
free Apple signing, or a manually set variable with licensing clearance.
Physical iPhone testing and cross-platform visual acceptance remain separate
release requirements.
