# Android release policy

- There are two public PHONE release channels: GitHub (`github`) and Play Store (`play`).
- Every phone feature, fix, and version update applies to BOTH channels. Keep common code in shared source; do not maintain phone feature forks.
- The only feature difference is Android Auto: `:android-auto` and `app/src/github` belong only to GitHub. Never add car components, resources, or dependencies to Play or TV.
- Do not recreate the former personal/private build or depend on an external PERSONAL checkout.
- Change phone versionName/versionCode once in `phone-release.properties`. TV is a separate existing device flavor; do not silently release or renumber it with phone updates.
- Before shipping, run `:app:assemblePhoneReleasePair` and package with `scripts/package-phone-release.ps1`. Both channels must pass the common tests, channel tests, lint, and boundary/parity checks.
- Release APKs must be non-debuggable and signed with the durable release key. Never publish signing material, machine-local files, device data, or test-labelled APKs as final releases.
- GitHub package identity is `com.icy.lyrics.github`; Play stays `com.icy.lyrics`. Do not change either after publication. The old debug-signed `com.icy.lyrics.personal` installation is separate; do not uninstall it or erase its saved work.
- Public availability does not remove Android Auto's parked/driving restrictions. Keep the shared lyrics session/cache across phone, overlay, and car surfaces.
