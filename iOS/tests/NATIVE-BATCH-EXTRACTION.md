# Default UIKit capture batch extraction

`extract_native_batch.py` consumes the actual array written by `xcresulttool export attachments`. It requires the unchanged default catalog: 13 portrait cases, seven landscape cases in each orientation, and two large-text cases (29 pairs). Each PNG is paired with its `-geometry` JSON using `suggestedHumanReadableName`, its owning test, device, configuration, and test-record identity. Exported UUID filenames alone are not identities. Duplicate, incomplete, wrong-type, failure-associated, reused, escaped, and mismatched attachments are rejected. Screen recordings, automatic failure screenshots, and separate debug attachments are retained in the ignored-attachment inventory; they cannot substitute for a capture.

Run with the existing Pillow verification environment, supplying downloaded artifact metadata:

```powershell
iOS/build/python-verification/Scripts/python.exe iOS/tests/extract_native_batch.py `
  iOS/build/reports/RUN/build/reports/ios-captures/manifest.json `
  --artifact-metadata iOS/build/reports/RUN-artifact.json `
  --workflow-run ACTUAL_RUN_ID --source-revision ACTUAL_COMMIT `
  --output iOS/build/reports/RUN-native-profiles
```

`--artifact-metadata` accepts one original GitHub REST artifact object, including `id`, `name`, `size_in_bytes`, and `workflow_run.id/head_sha`. Its original path, bytes hash, metadata, and optional published ZIP digest are recorded. Alternatively, supply `--simulator-verification`; the tool automatically discovers the existing `simulator-verification.json` beside the `ios-captures` directory. It validates that marker's test summary and simulator, rather than accepting `result: passed` alone. A marker carries a commit but no workflow run ID. Optional CLI run/revision labels are consistency assertions against these files, never evidence on their own.

The tool does not authenticate a local sidecar, download an artifact, verify its ZIP digest, or prove archive membership of extracted files. Those limitations are explicit in every report. Preserve the original API response and independently verified downloaded archive alongside the extraction. Do not manufacture metadata from operator labels.

`simulator-stage-results.json` is discovered beside `ios-captures`, or supplied with `--stage-results`. Nonzero native/Swift/export exit codes identify a failed run; incomplete stages remain incomplete. A pass marker contradicting failed/incomplete stages is rejected. An artifact without a stage record or marker has an unknown test outcome, even if all 29 pairs exist.

Default extraction rejects missing pairs and known failed/incomplete runs. `--allow-partial` explicitly permits diagnostic extraction, reporting every missing/rejected case and the actual known run outcome. Zero pairs produces only a diagnostic report and exits nonzero. A geometry rejection also exits nonzero; a valid explicitly partial subset may exit zero, which means only that profiles exist for review. `completeDefault29`, run outcome, and visual parity are separate fields. No output is a visual acceptance or simulator verification marker.

Every pair delegates to `extract_native_profile.py`: actual PNG dimensions, density, font scale, integer content boundaries, safe insets, orientation, and draw readiness must agree. No iPhone measurements are invented, and density is read from each capture. It preserves original paths/hashes and color metadata (including ICC profile hash, PNG mode, gamma, and color chunks), emits an exact content crop, and optionally an exact safe-area interior with `--safe-area-interior`. No resize, mask, color conversion, or pixel comparison occurs. Font-conversion samples are retained when present; absent samples remain pending, and equal font scale never establishes text shaping parity.

Current UI test runners use `NativeCapture/NativeDisplayCapture.swift` with `scripts/capture_native_framebuffer.py` around `xcodebuild`. A fresh UUID request in the exact test runner's data container asks the host for a public `simctl io screenshot --type=png --mask=ignored` capture. The host preserves that original PNG and returns a SHA-256 acknowledgement bound to the exact request bytes. The twelfth Mac run produced 14 valid portrait pairs at 1179×2556. Three landscape requests correctly failed because this display's raw framebuffer stays in fixed portrait coordinates; older evidence lacks the measured corner correspondence needed to certify any transform.

New DEBUG instrumentation measures ordered window-to-fixed-display points through public UIKit coordinate APIs and requires identical measurements before and after capture. `scripts/framebuffer_coordinates.py` accepts only a full-frame signed-unit coordinate permutation, verifies every RGBA byte through its inverse, and preserves color interpretation. The host retains `framebuffer.png` unchanged; a separately named `window.png` exists only when a measured permutation is necessary. No direction is guessed from orientation enums, no interpolation occurs, and failed/inconsistent geometry remains a failure. Actual Mac execution of this measured mapping remains pending; see [the coordinate contract](../docs/FRAMEBUFFER-COORDINATES.md).

For a transformed window capture, XCTest also attaches `<case>-raw-framebuffer.png`. The batch extractor requires the same test record, device and configuration as the canonical PNG and geometry. Single-profile extraction requires `--raw-framebuffer ORIGINAL.png`; it independently verifies the recorded mapping, original/derived hashes, all pixels and color metadata. Both original files are retained in the extracted evidence. Identity mappings preserve the original PNG bytes exactly and need no second attachment. This proof certifies a coordinate representation only; appearance parity still requires the matching Android comparison. Legacy XCTest application-window pairs remain separately identified and must pass their original geometry rules.

The fresh output directory must be under `iOS/build`. `batch-report.json` groups validated profiles by measured geometry and records unexecuted argument arrays for the existing original Android capture runner. It also flags existing Android scenario evidence that a subsequent capture might overwrite. Review profiles, source provenance, original images, group identity, and those overwrite warnings before any separately authorized emulator work. This tool never operates the emulator or changes the frozen Android sources, fixture catalog, or reference archives.

Focused verification:

```powershell
iOS/build/python-verification/Scripts/python.exe -m unittest discover -s iOS/tests -p test_native_batch_extraction.py -v
```

The unit inputs are explicitly synthetic small images in the actual xcresult manifest shape; they are not native screenshot evidence.
