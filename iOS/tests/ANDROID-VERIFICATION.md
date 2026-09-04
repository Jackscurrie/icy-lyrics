# Android UI extraction verification

The **20 captured scenarios are pixel-identical** before and after extraction: 0 changed RGBA pixels out of 51,840,000 compared pixels. All 13 portrait and seven landscape scenarios were freshly captured on September 4, 2026, with the final fixture path. This uses exact lossless images, without masks, resampling or tolerances. All frozen original production-source hashes match the pre-extraction manifest.

[Full capture evidence](evidence/android-complete-parity.zip) contains 40 original/extracted PNGs, per-image viewport/inset metadata, instrumentation logs, APK hashes, source provenance, and the strict comparison result. [Machine-readable report](evidence/android-complete-comparison.json). [Capture provenance](evidence/android-complete-provenance.json). [Multilingual landscape example](evidence/android-landscape-multilingual.png).

Verified scenarios: onboarding; empty player; portrait player; long title and artist; lyric failure; static background; disabled background; reduced motion; settings; populated local library; empty local library; legal; diagnostics; all four landscape modes; reversed mixed layout; multilingual lyrics; syllable and background-vocal lyrics.

Device: Android API 36 `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`, Android Emulator 36.5.11.0. Portrait used emulator5584 at 1080x2400px; landscape used emulator5582 at 2400x1080px. Both used 420dpi, font scale 1, safe-drawing insets 0/63/0/63px (24dp top/bottom), NVIDIA GeForce RTX 5060 Ti host GLES, and disabled Vulkan. Each before/after pair used the same emulator and frozen paused playback at 26.5 seconds. Both orientations used identical APK hashes for each tree; those hashes are recorded in the archive. Both owned emulators were stopped after capture.

The earlier landscape blocker was a native QEMU crash while using deprecated `swiftshader_indirect`; Windows recorded access violations (`0xc0000005`). Switching the dedicated AVDs to `-gpu host -feature -Vulkan` allowed valid captures. An attempted host-renderer rotation stayed alive but failed the harness's portrait-dimension assertion, so separate AVDs with the required natural geometries were used. This does not verify rotation transitions.

The first syllable comparison differed by 3,063 pixels because the extracted fixture bypassed the spring animator and drew target values directly. Removing that fixture-only bypass restored the original animation path. A standalone corrected capture still differed from the seventh-in-sequence baseline by 1,057 pixels; replaying the same complete fixture sequence under the same controlled Compose clock produced exact equality. The archive retains both earlier nonzero comparisons under `investigation/`. No thresholds or original production sources were changed.

This proves the listed Android snapshots survive the source extraction unchanged. It does not prove iOS pixel parity. Same-size iOS images, supported-script font shaping, animated shader/spring trajectories, rotation, interactions, accessibility, and uncaptured states remain separate gates. See [PARITY.md](PARITY.md).

Frozen checkout commit: `50af07bb5a58649f8760e96bc66a2ef4838c1e51` plus captured pre-existing working-copy icon edits. Complete archive: 13,265,834 bytes, SHA-256 `b12f2e9e119de948e22017524a9b49fcc0cc144bdaaff26bc00bec79df45f8bf`.

The [earlier 13-scenario portrait archive](evidence/android-portrait-parity.zip), [its report](evidence/android-portrait-comparison.json), and [example image](evidence/android-portrait.png) remain unchanged as historical evidence. Its SHA-256 remains `3dc024cc9004f84a4def159ed204d7dc56038999ba572890d5307f18c710fd6a`.
