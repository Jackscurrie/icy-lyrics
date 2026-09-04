# Opt-in mixed/lyrics motion captures

`DeterministicIosMotionCaptureTest` exercises the original production
`IcyLyricsApp` in one retained composition per media side. It adds no default
capture runtime, does not change the original 20 scenarios and does not claim
Android parity. The new sources compile as native main/test KLIBs; simulator
execution and matching preserved-Android motion captures are still pending.
This is offscreen Skia raster evidence, separate from UIKit/Metal acceptance.

## Run on macOS

With the normal iOS toolchain and an available ARM64 iPhone simulator, run from
the repository root:

```sh
./iOS/gradlew -p iOS :shared:ui:iosSimulatorArm64Test \
  -Picy.captureMotion=true \
  --tests 'com.icy.lyrics.ui.parity.DeterministicIosMotionCaptureTest' \
  --console=plain
```

To select an existing simulator, also pass `-Picy.iosSimulator=SIMULATOR_UUID`.
Use `--rerun` when intentionally replacing this suite's previous evidence for
the same inputs. Gradle supplies the same verified-font asset/output environment
as the original deterministic tests. On PowerShell, quote property arguments,
for example `'-Picy.captureMotion=true'`.

Without `icy.captureMotion=true`, the task filter excludes **only this new test
class**; its source still compiles. The opt-in command selects only this class,
not the original still captures. Its XML, HTML and binary test results go to
`iOS/build/reports/motion-ios-tests/iosSimulatorArm64Test/{xml,html,binary}`,
preserving the normal simulator verification reports.

PNG/JSON evidence is written separately under:

```text
iOS/build/reports/deterministic-ios-captures/
└── mixed-lyrics-motion-v1/android36-420dpi-landscape-v1/
    ├── left/    # manifest.json and 22 PNG/JSON frame pairs
    └── right/   # manifest.json and 22 PNG/JSON frame pairs
```

## Exact sequence for the Android counterpart

Each test uses the measured 2400×1080 landscape profile, density 2.625, font
scale 1 and safe insets `[0,63,0,63]`. Start from the existing
`landscape-mixed` fixture, changing only its mixed-media side. Retain the same
snapshot, artwork and lyric-document objects throughout that test: paused at
26,500 ms, speed zero, static blurred background, reduced motion false.
Kawarp, playback progression and lyric seeking are separate acceptance work.

1. Disable automatic clock advancement. Advance one 16 ms frame before
   `setContent`, so monotonic time starts above zero; advance 2,000 ms to settle
   the initial mixed view. `fixedFrameTimeNanos` is **null**, and the platform
   monotonic clock reads the same test clock. Production springs remain active.
2. For every transition, capture `before`, then perform a semantic click on the
   existing **Next fullscreen mode** or **Previous fullscreen mode** control.
   The click must not advance the clock. No touch press is synthesized, so
   pressed-edge feedback is not part of this sequence.
3. Advance exactly two 16 ms frames: one applies target state, the next supplies
   an animation timestamp. Record this primed epoch. Capture offsets
   **0, 128, 224, 448 and 2,000 ms** from it. These frame-aligned samples cover
   the initial sampled frame, the 120 ms fade boundary, approximately the
   220 ms midpoint, after the 440 ms bounds transition and a later settled
   sample. They are not a claim that all animation subcomponents start together.
4. Execute this plan without `key`, relaunch, `setContent` replacement or data
   reset between transitions:

| Sequence ID | Action | Samples after priming |
|---|---|---|
| `mixed-to-lyrics` | MIXED → LYRICS | 0, 128, 224, 448, 2,000 ms |
| `lyrics-to-mixed` | LYRICS → MIXED | 0, 128, 224, 448, 2,000 ms |
| `interrupted-mixed-to-lyrics` | MIXED → LYRICS | 0, 128, 224 ms |
| `reverse-to-mixed` | Reverse to MIXED immediately after that 224 ms sample | 0, 128, 224, 448, 2,000 ms |

Every sequence also has its own `before` frame, yielding 22 frames per side.
LEFT and RIGHT are separate tests, each with one retained composition.
`IcyMotionFixtureScreen.kt` holds the shared renderer and explicit plan. Keep
the original `IcyParityFixtureScreen` body unchanged: baseline preparation
extracts it. The Android lane must copy the new helper as a test adapter beside
the preserved original production UI and retain its source-integrity checks.

## Reading the evidence

Each frame records its sequence/index, planned target and actual composed mode,
action clock, primed clock, requested sample offset, capture clock, mode
composition clock and observed `withFrameNanos` timestamp. The composed mode is
the target state; an outgoing layer may still be visible during animation.
Geometry, track/position/speed, PNG hash/length and fixture mount/disposal counts
are also recorded. The suite checks that capture does not advance the clock,
only mode changes, data identities persist, controls apply the expected mode,
geometry remains exact and the fixture mounts once without disposal.

The manifest starts incomplete and is rewritten after every successful frame.
It becomes complete only after both directions and the interrupted reversal
finish for that side. Left and right manifests are independent. A complete
manifest means the sequence was captured, **not** that the images match Android.

Pair the full same-sequence Android and iOS evidence by actual clocks, geometry,
state and source revision. Keep strict pixel comparisons and inspect mid-frame
continuity; do not compare a motion frame with an independently initialized
still or waive differences. The root mount counter does not inspect internal
lyric-engine identity; preserved scene behavior still needs image/continuity
review. Track remaining coverage in [VISUAL-ACCEPTANCE.md](../docs/VISUAL-ACCEPTANCE.md).
