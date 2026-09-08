# Extended Android reference captures

`extended-v1` is an explicit, separate instrumentation suite. It does not add to
or change the original 13 portrait/7 landscape fixture lists, capture clocks,
sequence, PNGs, JSONs or archives.

The current Android 1.1 checkout produced all **11 captures** in
`evidence/android-extended-v1-reference.zip`. Instrumentation passed in 23.27 s.
The archive is 2,418,943 bytes with SHA-256
`bf9bc37b63d63ac1c90936464ef46c16c606752d9f97ed6036db049a4261d200`;
the adjacent JSON records the same size and hash. Every capture has its own PNG
hash, actions, clock, measured Compose density/font scale/insets, semantic
bounds, activity window bounds and accessible native window bounds. The archive
also records both APK hashes and a 102-file manifest for the Android production
sources used by this shared build.

| Case | Existing fixture and interaction |
|---|---|
| `portrait-expanded` | portrait; click **Expand lyrics** |
| `settings-fullscreen` | settings; scroll LazyColumn to item 2 |
| `settings-sources` | settings; scroll to item 3 |
| `settings-troubleshooting` | settings; scroll to item 4 |
| `settings-privacy` | settings; scroll to item 5 |
| `token-consent` | settings item 3; click the Apple Music switch |
| `legal-lower` | legal; scroll to item 3 |
| `legal-agpl` | legal item 2; **Read the full license offline** |
| `legal-agpl-scrolled` | open AGPL, then scroll the actual dialog by 360 dp |
| `legal-third-party` | legal item 3; **Read third-party notices offline** |
| `legal-third-party-scrolled` | open notices, then scroll the actual dialog by 360 dp |

Each case creates a fresh existing fixture. The Compose clock advances 2,000 ms
after the initial composition and after each action. Dialog dismissal is verified
after capture. Callback stubs remain those of the existing offline fixtures; the
suite does not authorize a real account, enable a provider or open external links.

The Apple Music row has no separate semantics node. Its source-card switches
share a card parent, so an ancestor-only selector is ambiguous. The harness uses
the observed label/description vertical bounds to locate the unique switch in
that row, then clicks its semantic node. Those bounds are included in the action
record.

## Capture scope and limits

These are raw **1080×2400 full-display UiAutomation PNGs**, density 2.625, font
scale 1, native safe drawing insets `[0,63,0,63]`. Unlike the original Compose-root
captures, they include the real Android status/navigation bars. No cropping,
masking, resizing or color conversion was applied. Modal dialogs have two Compose
roots; screenshots visibly include the dialog and dimmed activity backdrop. Both
legal scroll observations were exactly 0 → 945 px. Accessible window listings may
omit the obscured activity; its measured bounds and the two Compose roots are
recorded independently.

Scrolled settings/legal pages can clamp at the end of the list. The requested
item index is not a promise that its heading reaches the viewport top; inspect
the recorded semantic bounds and scroll values. Native UIKit swipe actions also
need observed position/anchor agreement before strict cross-platform comparison.

**iOS comparison is pending.** These references add surface coverage, not a claim
of identical rendering or complete interaction/animation coverage. System bars
must remain explicitly distinguished from app content in any later comparison.

## Explicit local run

From the repository root, with an owned Android emulator running:

```powershell
./android-v2/gradlew.bat -p android-v2 :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest
python iOS/tests/capture_android_extended.py extracted --serial emulator-5580
python iOS/tests/package_extended_reference.py iOS/tests/results/android/extended-v1/extracted/<run-id>
```

Use `--case token-consent` for one case. The script requires an owned emulator,
creates a unique output under `results/android/extended-v1/<tree>/<runId>`, verifies
hashes/completion and preserves partial failure output. Packaging requires all 11
cases, writes a deterministic checksum-pinned archive, and records current source
and APK hashes. The instrumentation class is
`com.icy.lyrics.parity.IcyExtendedParityScreenshotTest`; it skips unless the
explicit `extendedRunId` argument is supplied.
