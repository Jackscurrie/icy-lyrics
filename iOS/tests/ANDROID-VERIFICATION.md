# Android UI extraction verification

The **13 captured portrait scenarios are pixel-identical** before and after extraction: 0 changed RGBA pixels out of 33,696,000 compared pixels. This uses exact lossless images, without masks, resampling or tolerances. All frozen original production-source hashes match the pre-extraction manifest.

[Full capture evidence](evidence/android-portrait-parity.zip) contains original and extracted PNGs, per-image viewport/inset metadata, instrumentation logs, device metadata and the strict comparison result. [Machine-readable report](evidence/android-portrait-comparison.json). [Example player image](evidence/android-portrait.png).

Verified scenarios: onboarding; empty player; portrait player; long title and artist; lyric failure; static background; disabled background; reduced motion; settings; populated local library; empty local library; legal; diagnostics.

Device: Android API 36 `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`, emulator5584, 1080x2400px at 420 dpi, font scale 1, safe-drawing insets 0/63/0/63px (24dp top/bottom). Original application version 1.0.0-alpha01. Both runs used the same emulator and frozen paused playback at 26.5 seconds.

**The complete 20-scenario gate is still incomplete.** Seven intended landscape/multilingual/syllable scenarios lack valid landscape captures. This Windows emulator repeatedly exited natively with rotation, dynamic display resizing, and a dedicated landscape AVD. Invalid/zero-size/portrait-shaped attempts were excluded from the evidence archive and moved out of the reference folders. The strict comparator returns failure for these missing cases; they have not been waived.

This proves the listed Android portrait views survive the source extraction unchanged. It does not prove iOS pixel parity. iOS simulator/device images, supported-script font shaping, all landscape views, animated shader/spring trajectories, and interaction/accessibility cases remain required gates. See [PARITY.md](PARITY.md) for reproduction and matching iPhone geometry requirements.

Frozen checkout commit: `50af07bb5a58649f8760e96bc66a2ef4838c1e51` plus captured pre-existing working-copy icon edits. Archive SHA-256: `3dc024cc9004f84a4def159ed204d7dc56038999ba572890d5307f18c710fd6a`.
