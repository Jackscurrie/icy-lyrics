# Optional bundled-font FreeType experiment

This standalone macOS command-line program asks whether the **unchanged Android fonts** can produce their original COLRv1 and CBDT color glyphs through FreeType in the exact Skia revision used by Skiko 0.144.6. It retains a CoreText font manager for a side-by-side backend comparison. Nothing in this directory changes the app, UIKit, Compose, the default build, or CI.

**Status: source prototype; native compile and execution pending.** Local preparation and test results do not establish native support. Only a successful `validation.json` from a real Mac establishes this bounded glyph experiment. It never establishes iOS execution, text shaping, screenshot parity, or permission to distribute an IPA.

## Run on the existing public Mac runner

Use an arm64 macOS host with Xcode **26.4.1** selected, Python 3.10 or newer, network access, and approximately 2 GB of free workspace space (build output varies). No signing, Apple Developer membership, Homebrew, Rust, or paid service is required. The first download is 69,484,296 bytes compressed. GN and Ninja are hash-pinned downloads, so installed tool versions do not affect the experiment. Xcode's selected Apple clang and SDK versions are recorded. CPU compilation can take several minutes; there is no measured runtime estimate yet.

From the repository root:

```sh
bash iOS/experiments/freetype/run_macos.sh --jobs 4
```

Outputs are placed in a new directory under `iOS/build/freetype-probe/`. Each run has fresh extracted sources/build output, so an earlier successful probe cannot become a new result. Verified download archives are shared in a cache. The script refuses other Xcode versions and non-arm64/non-Mac execution. It does not start a simulator or remote job.

The existing `Public source checks` workflow now also has a **`font_backend_probe`** manual input, defaulting to **false**. Selecting it runs this experiment in a separate standard `macos-26` job, independent of app/package gates. Its `icy-font-backend-probe` artifact contains metrics, PNGs, source lock, status/validation and toolchain/build logs only. Downloaded archives, extracted sources, font files and native executables are excluded. This input has been wired but has not yet been dispatched or executed.

Windows can verify downloads, locked configuration, original font hashes, extraction and the overlay without attempting native execution:

```powershell
python iOS/experiments/freetype/run_probe.py --prepare-only
python -m unittest discover -s iOS/experiments/freetype -p "test_*.py" -v
```

## What is measured

Both backends load the exact same hash-verified Roboto, Noto Color Emoji and Noto Color Emoji Flags files. The program produces 34 sample records:

- Three COLRv1 glyphs: U+2744 snowflake, U+1F3B5 musical note, U+2764 heart.
- Two CBDT glyphs: Canada (glyph 65) and US (glyph 261), from the actual font's GSUB ligatures, at the original 109 ppem strike size. Their indices and sequences are in the lock file. This deliberately tests the renderer after glyph selection; it does **not** prove flag sequence shaping.
- Roboto header glyph advances, bounds and raster output for weights 400/700 at 73, 73.5 and 84 physical pixels, with linear metrics disabled/enabled. Width/italic axes stay 100/0, hinting is normal, antialiasing is enabled, subpixel placement is disabled, forced autohinting is disabled, and embedded bitmaps are enabled.

The raw header uses public C++ `textToGlyphs` with an explicit UTF-8 **byte length**. It applies no kerning, paragraph layout, fallback, bidi, or line-height policy. The known Android 73-vs-73.5 shaping/drawing split is therefore **not** reproduced or “fixed.” The unchanged Android instrumented metrics file is copied beside the result for context; raw glyph sums cannot be compared as if they were Compose paragraph advances.

The runner independently decodes the native PNGs and rejects blank required images, missing colored pixels in any FreeType emoji/flag sample, clipped glyphs, missing matrix entries and non-finite metrics. It records each PNG's hash, dimensions, nontransparent/chromatic pixel counts and ink bounds. CoreText color failures remain observed comparison results; CoreText Roboto must still render. There is no replacement font, font conversion, bitmap substitution for COLRv1, or image resizing.

## Reproducible source boundary

`sources.lock.json` pins the Skia archive and exact FreeType/libpng/zlib commits already selected by that Skia revision's `DEPS`, plus the GN/Ninja versions selected by its fetch scripts. Every downloaded archive has a verified SHA-256 and size. The lock also verifies the original Skia root build file and FreeType options/module headers. The only extracted-source overlay is this program's directory and one additive GN group in the extracted root build file. No font-manager or renderer implementation is patched.

`args.gn` enables bundled FreeType/custom-data loading while retaining CoreText. Skia's checked-in `freetype-android` configuration already enables `TT_CONFIG_OPTION_COLOR_LAYERS`, `TT_SUPPORT_COLRV1`, and PNG support. The probe disables HarfBuzz, ICU, Fontations/Rust, WOFF2, SVG, GPU backends, PDF and unrelated codecs to keep its dependency closure small. libpng and zlib remain enabled for the original bitmap font and PNG output. The extracted Skia Fontations Cargo symlink is materialized as an identical contained file for Windows preparation; Fontations is disabled and that file is not compiled.

The native run records the resolved GN dependency closure, architecture check, dylib list, compiler/SDK versions, source hashes, source/archive lock, executable hash, metrics and captures. `run.json` always records failure or preparation-only status; `validation.json` is written only after real execution and independent output checks succeed. No app verification marker or IPA is created.

## What would follow a successful probe

A Mac pass is evidence that this exact portable backend renders these original fonts. It is not evidence that the current prebuilt iOS Skiko archive contains FreeType (it does not). An iOS experiment would next need the same pinned sources built for `ios_arm64` and `ios_simulator_arm64`, followed by a controlled custom Skiko package that exposes a public supported font factory. Existing UIKit/CoreText operation must remain intact. Directly passing C++ pointers into the existing private Skiko ABI is not part of this experiment.

Even after iOS linking, Compose shaping, color sequences, line metrics, baselines, animation and actual native-density screenshots must be measured. The app's fonts and production rendering remain unchanged until those measurements justify a narrow adapter.

Pinned upstream sources: [Skiko version reference](https://github.com/JetBrains/skiko/blob/9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0/skiko/gradle.properties), [Skia DEPS](https://github.com/JetBrains/skia/blob/22f58c9fd43d55bde818821c04b48fda5d7ec939/DEPS), [public FreeType data manager](https://github.com/JetBrains/skia/blob/22f58c9fd43d55bde818821c04b48fda5d7ec939/include/ports/SkFontMgr_data.h), [FreeType build configuration](https://github.com/JetBrains/skia/blob/22f58c9fd43d55bde818821c04b48fda5d7ec939/third_party/freetype2/BUILD.gn).
