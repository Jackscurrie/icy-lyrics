# Install and refresh Icy Lyrics from Windows

Use these instructions only with the generated device IPA. A simulator `.app` is not installable on an iPhone. The IPA needs signing on your computer; it is not an App Store download.

For an owner-only encrypted GitHub artifact, first follow
`iOS/docs/OWNER-TRANSFER.md` in its exact source revision to recover the
verified IPA. The encrypted path preserves the public
plaintext distribution gate and requires the owner's original local key.

## Before installation

This delivery route requires no paid Apple Developer membership. Standard GitHub-hosted runners are free for this public repository, and Sideloadly supports free Apple ID signing with a seven-day refresh. Any existing coding subscription or usage billing is separate. Spotify's Web API Development Mode requires the developer app's owner to maintain Spotify Premium; reusing an existing eligible developer account does not add another Spotify subscription. These Web API rules do not mean every App Remote listener must be Premium. [GitHub billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [Sideloadly](https://sideloadly.io/), [Spotify quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes).

- iPhone running iOS 16 or later, Windows computer, USB cable, free Apple ID, and Spotify installed on the phone.
- Download Sideloadly from its [official website](https://sideloadly.io/). Follow its current Windows prerequisites, including the web versions of iTunes/iCloud linked there.
- Use the supplied `SHA256SUMS.txt`. In PowerShell, run `Get-FileHash -Algorithm SHA256 .\IcyLyrics-unsigned.ipa` and compare the hash exactly.
- Keep `build-report.json` and `SOURCE.md` with the IPA. They identify its exact Git commit and link to that revision's source archive and build instructions.
- A build without a configured Spotify client ID works for offline fixtures only. The maintainer must register the client/redirect first. Never enter an Apple password or Spotify client secret into repository files or GitHub Actions.

## Source and document review

Open the source link in `SOURCE.md`, or the `correspondingSource.browseUrl` in
`build-report.json`, to inspect the code associated with this delivery. Use
`correspondingSource.archiveUrl` to download the same revision as a ZIP, or clone
the repository and check out the complete commit recorded in the report. Do not
substitute the latest branch for a delivered revision when reproducing a build.
The archive includes the iPhone build scripts under `iOS/scripts/`; running them
requires the Apple Silicon Mac/Xcode/JDK toolchain documented in that revision's
`iOS/README.md`, or its public GitHub macOS workflow. Windows is the installation
host, not the Xcode build host.

Packaging requires the port, Android V2 and workflow sources to match the
reported commit, including generated Room schemas. If a build reports changed
or untracked source, review and commit it, then rerun verification. Ignored
build outputs and signing/configuration files are not source archive contents.

Review `iOS/docs/PRIVACY.md` and `iOS/docs/END-USER-TERMS-DRAFT.md` in that exact
source revision before testing. Both are drafts awaiting publication/adoption;
the current in-app website policy covers Android only. These documents do not
record acceptance or clear the separate public binary distribution requirements
in `iOS/docs/DISTRIBUTION-REVIEW.md`. This guide does not authorize distribution
of a build whose report says public binary release is not cleared.

## First installation

1. Connect and unlock the iPhone. Accept **Trust This Computer** when prompted.
2. Open Sideloadly, select the phone, choose `IcyLyrics-unsigned.ipa`, and enter your Apple ID in Sideloadly's signing interface. Complete Apple's authentication there.
3. Keep the chosen bundle identifier stable. Record the identifier Sideloadly actually installs; it must also agree with the Spotify developer app's iOS registration. Keep the embedded callback scheme `com.icy.lyrics.ios` unchanged.
4. Enable automatic refreshing if desired, then start sideloading. Allow Sideloadly to resign the application and embedded Spotify framework.
5. Follow iPhone prompts to trust your developer profile and, on supported versions, enable Developer Mode in **Settings → Privacy & Security → Developer Mode**. Restart/confirm if iOS requests it.
6. Open Icy Lyrics and connect Spotify. Approve the system sign-in, open Spotify and start a song, then return to Icy Lyrics. Reconnection may require this app switch again later.

## Seven-day refresh and updates

With a free Apple account, the signing validity is seven days. Sideloadly's automatic refresh needs its daemon running and the paired phone reachable by USB or configured Wi-Fi. Refresh before expiry, or sideload again with the same Apple ID and bundle ID. Free signing also limits the number of active sideloaded applications; see the current [Sideloadly FAQ](https://sideloadly.io/faq).

To update, select the new IPA and overwrite the installed application using the **same Apple ID and same bundle identifier**. Do not delete Icy Lyrics first: uninstalling can erase imported lyrics/settings. Keep original TTML files backed up. Keychain access may require reconnecting Spotify if signing identity changes. Physical refresh/update persistence remains part of the pending iPhone test checklist.

## Validation label and known limits

Only a passing macOS pipeline may label an output **“simulator-verified IPA; physical iPhone validation pending.”** That label does not claim that Spotify callbacks, actual sideload installation, refresh, local tracks, Bluetooth timing or phone performance have passed device testing. Cross-platform visual acceptance is recorded separately in the build report.

Spotify must be installed and available; developer-mode API account restrictions still apply. Automatic per-Bluetooth-device timing is inactive when Spotify's output cannot be identified reliably; global timing works. Experimental lyrics-provider credentials and consent are separate from playback authorization.

Sources: [Sideloadly download/prerequisites](https://sideloadly.io/), [Sideloadly refresh and update FAQ](https://sideloadly.io/faq), [Apple Developer Mode](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device).
