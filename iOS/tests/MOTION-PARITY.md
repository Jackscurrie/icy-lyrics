# Opt-in mixed/lyrics motion captures

`DeterministicIosMotionCaptureTest` exercises the original production
`IcyLyricsApp` in one retained composition per media side. It adds no default
capture runtime, does not change the original 20 scenarios and does not claim
Android parity. The new sources compile as native main/test KLIBs. All **44
preserved-Android reference frames are captured**, and a second actual run
produced the same 44 PNG files byte for byte. The first iOS motion run captured
the initial frame on each side, then correctly failed because the touch-based
click helper advanced the clock by 16 ms. The direct semantic-action correction
awaits a simulator rerun; complete iOS motion capture/comparison remains pending.
This is offscreen Skia raster evidence,
separate from UIKit/Metal acceptance.

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

The existing GitHub workflow also exposes `additional_ios_parity`, false by
default. An explicit manual run with it enabled runs extended UIKit and motion
captures independently on the retained simulator, then attempts the strict
motion comparison. Capture and comparison exit codes remain separate in
`additional-parity/run-summary.json`; failures cannot turn into visual approval.
The `icy-ios-additional-parity` artifact holds these logs, manifests, images and
comparison reports separately from normal verification and delivery.

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
   the initial mixed view. `fixedFrameTimeNanos` is **null**. The iOS platform
   adapter's monotonic clock reads the same test clock. The preserved Android
   implementation retains its original `SystemClock`; its paused snapshot and
   zero speed keep playback position fixed. Both lanes control the Compose
   clock while production springs remain active.
2. For every transition, capture `before`, then perform a semantic click on the
   existing **Next fullscreen mode** or **Previous fullscreen mode** control.
   The click must not advance the clock. No touch press is synthesized, so
   pressed-edge feedback is not part of this sequence.
   Both lanes explicitly invoke `performSemanticsAction(SemanticsActions.OnClick)`;
   the iOS `performClick` convenience method injects a touch and cannot be used
   for this controlled-clock contract.
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
Geometry, track/position/speed/paused state, PNG hash/length and fixture
mount/disposal counts are also recorded. The suite checks that capture does not advance the clock,
only mode changes, data identities persist, controls apply the expected mode,
geometry remains exact and the fixture mounts once without disposal.

The manifest starts incomplete and is rewritten after every successful frame.
It becomes complete only after both directions and the interrupted reversal
finish for that side. Left and right manifests are independent. A complete
manifest means the sequence was captured, **not** that the images match Android.

The common source identity records SHA-256 hashes for the preserved Android
source manifest, shared motion helper and shared fixture data. Only source-text
CRLF/LF differences are normalized, using the declared `utf8-lf` encoding.
The seven bundled font binaries are hashed without normalization; Android also
records their actual `/system/fonts` hashes, and iOS records loaded asset
hashes. Android application provenance retains verification of all 57 original
production files. This shared fixture identity does not claim that the two
platform implementations have identical source; the archive and CI reports
retain their separate application/build provenance.

Pair the full same-sequence Android and iOS evidence by actual clocks, geometry,
state and source identity. Keep strict pixel comparisons and inspect mid-frame
continuity; do not compare a motion frame with an independently initialized
still or waive differences. The root mount counter does not inspect internal
lyric-engine identity; preserved scene behavior still needs image/continuity
review. Track remaining coverage in [VISUAL-ACCEPTANCE.md](../docs/VISUAL-ACCEPTANCE.md).

## Preserved Android reference and strict comparison

The [reference archive](evidence/android-motion-v1-reference.zip) is 23,256,905
bytes, SHA-256
`e5e9022384c8cbaf2ea7708fa66d37851716ee1fd6a1def948ac843b81398bf3`.
It contains `baseline/{left,right}` PNG/JSON evidence and provenance, including
the first-run manifests and verification that all 44 repeated PNGs match.
Its metadata is [android-motion-v1-reference.json](evidence/android-motion-v1-reference.json).

Both actual Android tests settled at 2,016 ms. Their action clocks were
2,016 / 4,048 / 6,080 / 6,336 ms, with two-frame primed clocks
2,048 / 4,080 / 6,112 / 6,368 ms and a final capture at 8,368 ms.
The comparator requires the iOS observations to match; a consistent 16 ms
shift is rejected rather than adjusted away.

From the repository root, unpack the checksum-pinned reference into a **new**
ignored directory:

```sh
python -c "import sys; from pathlib import Path; sys.path.insert(0, 'iOS/tests'); from compare_motion_parity import unpack_reference_archive; print(unpack_reference_archive(Path('iOS/tests/evidence/android-motion-v1-reference.zip'), Path('iOS/build/reference/android-motion-v1')))"
```

Then compare against complete downloaded or locally generated iOS evidence:

```sh
python iOS/tests/compare_motion_parity.py \
  iOS/build/reference/android-motion-v1/baseline \
  iOS/build/reports/deterministic-ios-captures/mixed-lyrics-motion-v1/android36-420dpi-landscape-v1 \
  --output iOS/build/reports/motion-comparison-reviewed
```

On PowerShell, put this command on one line instead of using shell backslashes.
Choose a new output directory for each comparison; existing inputs and reports
are never replaced. Both sides must have all 22 ordered frames, matching source,
font, profile, state, mount counts and actual clocks, plus valid PNG hashes and
standalone frame records. Any invalid side rejects the pair before pixel
comparison. Valid pairs produce full RGBA and maximum-channel difference PNGs
for all 44 frames, exact changed-pixel counts, `comparison.json` and `REPORT.md`.
There is no resizing, masking, tolerance or clock normalization. Exit 0 means
all 44 frames match exactly; exit 1 reports differences or invalid evidence;
exit 2 reports invalid command/output setup. Even an exact result covers only
this retained-composition motion sequence and does not approve the whole app.
