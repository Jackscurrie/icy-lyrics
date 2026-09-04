# Android-identical visual acceptance

**The iPhone app must look identical to Android V2.** A successful build,
simulator test suite or passing subset of screenshots does not satisfy this
requirement. Only Apple-owned screens, physical safe areas and necessary
platform/connection wording may differ. Record each exception explicitly.

This is the coverage checklist. [VALIDATION.md](VALIDATION.md) records build
results and evidence links; this document does not repeat its run history.

## Existing evidence and its limits

| Evidence | Current result | What it establishes |
|---|---|---|
| Original versus extracted Android | **20/20 exact**, zero changed RGBA pixels across 51,840,000 pixels | Extraction preserved those original scenes at their measured Android profiles. It does not establish iPhone parity. |
| Extended original Android references | **11/11 captured**; all 57 original production hashes preserved | Additional expanded/settings/dialog references. Matching iOS capture/comparison remains pending. See [EXTENDED-PARITY.md](../tests/EXTENDED-PARITY.md) for raw full-display scope and scroll limits. |
| iOS offscreen raster at Android profiles | **20/20 captured and compared; all 20 differ** | Complete raster evidence for investigation, not appearance acceptance. Updated font/paint adapters and all 108 native Kotlin tests passed, but these stills remain unequal. |
| Production UIKit/Metal | **Pending**; 29 captures planned | The same 20 fixtures with both landscape rotations and two large-text variants. These are additional viewports, not additional app states. |
| Physical iPhone | **Pending** | Production appearance, gestures, safe areas, orientation changes and performance remain unverified. |

The original 20 cover initial onboarding/empty player, portrait/long-title/error,
static/disabled/reduced-motion backgrounds, initial settings/library/legal/
diagnostics views, all four settled landscape modes, both mixed-media sides,
static multilingual lyrics and one syllable sample. All use one paused track
position. Settings and legal content below the first viewport is not captured.

`portrait`, `background-static` and `reduced-motion` currently render identical
images within each platform: reduced motion selects the static fallback.
**There is no actual Kawarp frame in the original 20.** Scenario resets and
settled screenshots do not test uninterrupted transitions.

## Coverage matrix

Every unchecked row remains required. “Fixture implemented” means only that
the scenario can be exercised; mark complete only after matched Android/iOS
evidence has been compared and reviewed. Link the report when checking a row.

| Done | App-owned surface/state | Required coverage beyond the current evidence |
|---|---|---|
| [ ] | Original 20 scenes | Complete strict iOS comparison and investigate every difference; production UIKit comparison at measured matching profiles |
| [ ] | Expanded portrait | Expand/collapse/back, scrollable full-height lyrics, track-change reset; preserve ordinary portrait lyric presentation |
| [ ] | Settings: Fullscreen | Entire section, selected style/side chips, Reveal and awake switches; background-off hides style choices |
| [ ] | Settings: lyric sources and account | Source strategy, Spicy/token/LRCLIB switches; connected, disconnected, connecting/cancel, client-ID-unconfigured states |
| [ ] | Settings: timing/Bluetooth | Positive/negative/reset and slider-preview values; permission needed, no device, device/global fallback, override and remembering disabled |
| [ ] | Settings: remaining sections | Troubleshooting/debug, Privacy and About controls; scrolled content and wrapping |
| [ ] | Token-sharing consent dialog | Title/body/buttons, backdrop, dismissal and confirmation; normal and large text |
| [ ] | Legal page and both document dialogs | Lower cards/links, AGPL and third-party notices, opening/closing, first and scrolled document viewports |
| [ ] | Timeline scrub popup | Held drag, middle/edge positions, preview thumb/labels, release-to-seek; normal and large text |
| [ ] | Playback and artwork states | Play/Pause icons, timeline phases, no artwork placeholder/fallback, absent/zero/long duration, source badges |
| [ ] | Lyrics status messages | Ready, Loading, Idle, Empty, Failed and no-renderable-lines state; long messages |
| [ ] | Library | Empty/populated/long list, long and multilingual titles/artists, dates, scrolling, deletion/replacement updates |
| [ ] | Diagnostics and snackbars | Empty/error/long provider reports, scrolled Copy/Share/Clear controls; import/connection success/error messages and dismissal |
| [ ] | Landscape controls | All four modes in both rotations; mixed media left/right; artwork-only and artwork-titles controls shown/hidden |
| [ ] | Timed lyric content | Active lead/background/opposite-aligned vocals, multiline/long tokens, transliteration, timed RTL and grapheme/emoji boundaries |
| [ ] | Reveal and interlude/outro | Before/within/after line and syllable timing; instrumental gaps, intro/outro and track-end states |
| [ ] | Compact layouts | Narrow/short portrait, timeline-only compact branch, compact mixed, expanded lyrics and long titles |
| [ ] | Large text | Player, expanded lyrics, settings, dialogs, library and diagnostics at matched effective text scaling; no clipping or inaccessible controls |
| [ ] | Reduced motion | Static background fallback, timeline/lyric behavior, mode changes and auto-follow; verify changed behavior at sampled frames |
| [ ] | Actual iOS wording | Approved platform/connection wording fits production views; canonical fixture wording overrides alone cannot verify this |

For source locations and implementation details, the main surfaces are in
`shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyLyricsApp.kt`; lyric
rendering/scrolling is in `LyricsCanvas.kt`, and platform artwork rendering is
in the Android/iOS `ArtworkBackground` adapters.

## Motion and interaction matrix

Capture these in the **same live composition**, using a controlled monotonic,
playback and Compose clock. Record actions and sample times, including a
mid-transition frame and settled result. Do not replace motion evidence with
independently initialized stills.

| Done | Sequence | Required evidence |
|---|---|---|
| [ ] | Kawarp motion | Explicit animated mode with reduced motion off, preprocessing ready, several nonzero shader phases |
| [ ] | Artwork changes | Initial 500 ms fade, subsequent 1,000 ms fade, interrupted replacement and pause/resume; preserve preprocessing/colors/blur/sampling |
| [ ] | MIXED ↔ LYRICS | Both directions and media sides, 440 ms bounds movement and 120/180 ms presentation fades; retained lyric position/scene, interrupted reversal |
| [ ] | Other landscape mode changes | Scale/fade, edge controls and reduced-motion alternative; both rotations |
| [ ] | Lyric progression | Line/syllable/letter boundaries, spring/glow frames, background vocals, reveal and interlude transitions |
| [ ] | Seeking and playback clock | Forward/backward seek, pause/resume, timing changes and track/document replacement without stale display |
| [ ] | Manual lyric scrolling | Drag/release, lyric tap-to-seek, 2,000 ms auto-follow wait and 440 ms recenter; reduced-motion snap |
| [ ] | Transport/overlay interaction | Wavy timeline phases, scrub preview/release, artwork controls 120/160 ms fade and three-second hide cycle |

The fixed-frame Kawarp path forces a completed artwork blend. It can verify
settled shader phases, but cannot establish real artwork crossfades.

## Extended fixture evidence

The separate opt-in `extended-v1` Android/UIKit suites share these 11 case IDs.
**All 11 original Android references are captured; iOS capture/comparison is
pending.** They preserve the original 20/default 29 sequences. The
[reference archive](../tests/evidence/android-extended-v1-reference.zip) contains
2,248,624 bytes, SHA-256
`942e7641952cdf42b69008d0bee7c4e501e1672335c13e6e92abdb3ddd94ee22`.
These full-display references include Android system bars; modal frames include
the real dialog and dimmed backdrop. Compare observed scroll/anchor positions,
not merely requested item indices. No matrix row closes on Android-only evidence.

| Area | Case IDs |
|---|---|
| Portrait | `portrait-expanded` |
| Settings | `settings-fullscreen`, `settings-sources`, `settings-troubleshooting`, `settings-privacy` |
| Consent | `token-consent` |
| Legal | `legal-lower`, `legal-agpl`, `legal-agpl-scrolled`, `legal-third-party`, `legal-third-party-scrolled` |

These first additions cover only part of the remaining matrix. Account-state
variants, actual Kawarp, scrub previews and motion sequences still need their
own evidence.

## Evidence rules and completion

- Keep the original 20 IDs, data, order, 2,000 ms clock steps and reference
  hashes unchanged. Add opt-in extended fixtures and separate manifests/output
  directories; capture the preserved original Android implementation first.
- Use the same production controls/layouts on both platforms. Additional
  fixtures remain **pending matched iOS capture/comparison** unless their
  evidence explicitly establishes both sides.
  A fixture count is not an acceptance percentage.
- Pair evidence by source commit, scenario/actions, dimensions, density,
  effective text scaling, safe-area contract, locale, fonts/assets and clocks.
  Keep original images, geometry metadata, hashes and exact difference reports.
- Dialogs/popups require a verified capture surface containing the dialog,
  backdrop and overlay. A single Compose-root capture can omit or become
  ambiguous with another window. Confirm coverage before comparing.
- Compare app-owned pixels strictly and review differences. Do not resize
  references, raise tolerances or add masks to hide visible changes. An
  explicitly permitted safe-area/system exception must not conceal app UI.
- Offscreen raster results cannot approve UIKit/Metal by themselves. Complete
  matched production-renderer comparisons and the matrix above before claiming
  Android-identical appearance; then complete physical iPhone validation.

Until physical testing passes, any qualifying deliverable remains labeled
**“simulator-verified IPA; physical iPhone validation pending.”** That label
requires the simulator/build gates to pass and does not itself assert visual
acceptance. Keep **visual acceptance pending** explicit while rows remain open.
