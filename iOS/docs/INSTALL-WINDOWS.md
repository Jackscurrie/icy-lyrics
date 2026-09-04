# Install and refresh Icy Lyrics from Windows

Use these instructions only with the generated device IPA. A simulator `.app` is not installable on an iPhone. The IPA needs signing on your computer; it is not an App Store download.

## Before installation

- iPhone running iOS 16 or later, Windows computer, USB cable, free Apple ID, and Spotify installed on the phone.
- Download Sideloadly from its [official website](https://sideloadly.io/). Follow its current Windows prerequisites, including the web versions of iTunes/iCloud linked there.
- Use the supplied `SHA256SUMS.txt`. In PowerShell, run `Get-FileHash -Algorithm SHA256 .\IcyLyrics-unsigned.ipa` and compare the hash exactly.
- A build without a configured Spotify client ID works for offline fixtures only. The maintainer must register the client/redirect first. Never enter an Apple password or Spotify client secret into repository files or GitHub Actions.

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
