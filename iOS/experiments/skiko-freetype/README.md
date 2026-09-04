# Optional full Skiko package experiment

This builds a separate local Maven package, `org.jetbrains.skiko:skiko:0.144.6-icy-freetype.1`, with a public managed iOS factory for the original bundled fonts. The application still uses upstream Skiko 0.144.6. This experiment does not change the app's dependencies, fonts, UI, workflow, signing, or IPA verification markers.

The preceding standalone native probe rendered the original COLRv1 and CBDT fonts through FreeType on the Mac. That proves the font backend can render those bytes. It does not establish that a rebuilt Skiko package compiles, links, works with Compose, or matches Android pixels. Those are separate checks.

## Reviewed changes and fixed inputs

`sources.lock.json` fixes Skiko commit `9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0` and Skia commit `22f58c9fd43d55bde818821c04b48fda5d7ec939`, the exact 11 external source dependencies, and Mac GN/Ninja tools. Gitiles archives use verified Git-tree identity plus a canonical per-file SHA256 tree; compressed gzip bytes are only an observation because Gitiles can recompress an unchanged tree. Codeload/tool archives use exact archive bytes. Source extraction rejects traversal, unexpected links and unverified source content.

The five recorded source changes are:

1. Add `makeTypefaceFromDataFreeType(Data, ttcIndex = 0): Typeface?` to Skiko's iOS Kotlin source set.
2. Add its private native bridge in the same package. It copies the exact font bytes into an owned SkData allocation, so a caller can close Data made with either owned or borrowed memory. The managed result owns its normal Typeface lifetime; no pointer API is exposed to application code.
3. Make upstream's Xcode resolver honor the explicitly selected `DEVELOPER_DIR`/`skiko.ci.xcodehome`, even if a different `/Applications/Xcode.app` exists.
4. Pin the producer Gradle 8.14.3 distribution with its official SHA256.
5. Restrict Skia's existing arm64 branch to generic arm64. Upstream also emits arm64e, making FAT archives; this package targets Kotlin's generic arm64 device and simulator artifacts. No rendering code is changed by this architecture restriction.

The normal CoreText default is preserved byte-for-byte. Full Skia modules remain enabled, including Metal, SkParagraph/HarfBuzz/ICU, SVG, Skottie, PDF and codecs. Release flags enable the embedded FreeType data manager alongside CoreText. The obsolete upstream `skia_use_sfntly` no-op is omitted so GN's `--fail-on-unused-args` remains effective.

The producer uses Kotlin 2.2.20, Gradle 8.14.3 and JDK 17. The independent consumer uses Kotlin 2.4.10 and the SHA-pinned Gradle 9.4.1 wrapper used by the app. Both native targets require arm64 macOS with Xcode 26.4.1. Library deployment flags are 12.0; every actual object must declare the correct device/simulator platform and a minimum no higher than 16.0. The explicit GN `ios_min_target` also applies to ICU's generated assembly. This avoids the stock simulator package's observed ICU data object minimum 18.5; the rebuilt object still has to pass binary inspection.

The native source and tools are locked before use. Upstream Gradle plugin/transitive Maven inputs are fixed by the pinned producer source's declared versions and resolved through its existing HTTPS repositories; their complete downloaded byte inventory is recorded after the first actual build. That inventory is **not** a pre-reviewed checksum lock for every transitive Maven artifact. Do not describe the initial package as a fully hermetic/reproducible binary build. Keep its exact artifact hashes and resolved-input inventories; review and freeze those before adopting a production dependency override. The Gradle wrapper distribution itself is verified before execution.

The consumer wrapper's `.properties` input is explicitly normalized from CRLF to LF before checksum comparison and copying. This text-only rule handles Git's Windows checkout conversion, including the current mixed-line-ending working copy. Bare CR is rejected. Executable scripts retain their repository-declared LF/CRLF form, and the wrapper JAR is hashed as unmodified binary bytes. No repository wrapper file is rewritten.

## Local checks on Windows

All generated/downloaded content stays under ignored `iOS/build`. Use a fresh work directory for each recipe version. Prepared source and consumer fingerprints reject later changes.

```powershell
python -m unittest discover -s iOS/experiments/skiko-freetype -p 'test_*.py' -v
python iOS/experiments/skiko-freetype/build_package.py --stage prepare --work-dir iOS/build/skiko-freetype/reviewed
python iOS/experiments/skiko-freetype/build_package.py --stage graph --work-dir iOS/build/skiko-freetype/reviewed --graph-gn iOS/build/freetype-probe-downloads/gn.exe
```

The optional `--graph-gn` must be GN 2175 (`b2afae122eeb`). Its actual executable hash and host are recorded. The local audit used an independently obtained Windows build of this same GN revision; it is only a source-graph check with a deliberately nonexistent Apple SDK path. Mac native builds use only the archive-locked Mac GN and Ninja.

The measured graph has 84 targets and 3,873 declared source/header/action inputs for each platform. All 11 external dependencies are locked. The graph explicitly checks retained FreeType/CoreText/paragraph/SVG/Skottie targets, generic arm64 flags and the ICU assembler minimum. This is not a C++ compile or an Apple-SDK check.

## Proposed manual CI jobs

These jobs are a proposal for the existing workflow after review. No workflow or dispatch is supplied by this experiment. Use standard public `macos-26` runners, Xcode 26.4.1 and JDK 17. No paid Apple program, owner-transfer key, Spotify credential, signing identity or private repository is needed. Keep the experiment independent of normal app/IPA gates. The driver passes only basic OS/toolchain variables to subprocesses and publishes only to the generated local BuildRepo.

Run two independent matrix jobs (`target=ios` and `target=iosSim`), each with a fresh source preparation:

```bash
export DEVELOPER_DIR=/Applications/Xcode_26.4.1.app/Contents/Developer
python3 iOS/experiments/skiko-freetype/build_package.py --stage prepare --work-dir iOS/build/skiko-freetype/native
python3 iOS/experiments/skiko-freetype/build_package.py --stage skia --target "$TARGET" --jobs 4 --work-dir iOS/build/skiko-freetype/native
```

Transfer only `products/skia-*.zip` and its adjacent JSON SHA/size record into a dependent third Mac job. Verify the downloaded ZIP hash against the producing job's record before import. That job prepares the same sources and imports both validated native artifacts:

```bash
python3 iOS/experiments/skiko-freetype/build_package.py --stage prepare --work-dir iOS/build/skiko-freetype/package
python3 iOS/experiments/skiko-freetype/build_package.py --stage package --work-dir iOS/build/skiko-freetype/package \
  --import-skia /absolute/download/skia-ios-arm64-0.144.6-icy-freetype.1.zip \
  --import-skia /absolute/download/skia-iosSim-arm64-0.144.6-icy-freetype.1.zip
```

Import checks exact source/patch/lock fingerprints, member allowlists, all library hashes, source-graph hashes and each actual Mach-O member. No downloaded source tree is trusted from another job. The producer runs only the three local BuildRepo publication tasks for root metadata, iosArm64 and iosSimulatorArm64. The package validator requires the two `cinterop-uikit` KLIBs, all 21 included archives per target, matching original native input hashes and actual defined FreeType/CoreText/factory symbols.

After that package succeeds, boot an available iPhone simulator using the existing CI simulator selection, then pass its actual UUID:

```bash
python3 iOS/experiments/skiko-freetype/build_package.py --stage consumer --work-dir iOS/build/skiko-freetype/package --simulator "$SIMULATOR_ID"
```

The consumer's repository filter forces `org.jetbrains.skiko` to this local package. Four native tests cover invalid/closed data and collection indices; returned-face lifetime/variation cloning with CoreText still available; five real COLRv1/CBDT glyph renders; and two flag sequences shaped through the full SkParagraph/HarfBuzz package. The driver requires all four tests to execute without failures/skips, seven actual colored PNGs and matching raw RGBA exports. This is an iOS simulator **raster** consumer test. It does not prove Metal, Compose text metrics, UIKit behavior, physical-iPhone execution, Android pixel parity or complete animation behavior.

A single Mac can run `--stage all --target both` and then `--stage consumer`, but the two matrix jobs reduce first-run serial latency. No build duration has been measured for this full package yet. Budget up to 90 minutes per native job and 90 minutes for first-time package/consumer setup, then revise from actual logs; four compiler workers limit memory pressure. Expect several GB of source, compiler, Gradle and object caches. Do not bypass a failure to meet that estimate.

## Evidence and handoff

Retain `runs/*.json`, `prepared.json`, `patches.json`, `sources.lock.json`, `verified-inputs.json`, `source-files.json`, `toolchain.json`, `reports/**` and `products/**`. Stage failures retain their log and run record; no stage emits an app/IPA verification marker. Package archives contain local Maven artifacts, notices, hashes and source/toolchain provenance. They deliberately do not include source downloads, Gradle caches, credentials or signing material. Every transfer must preserve the adjacent SHA256/size JSON record and the source notices.

`native_archive.py` checks every arm64 Mach-O object, platform/minimum version and archive index structure. `xcrun nm` separately proves required definitions; an index string alone is not treated as full symbol evidence. Fat archives, bitcode, arm64e, unknown load-command layouts and ambiguous legacy simulator platforms are rejected. The only permitted empty archive is explicitly named `libwebp_sse41.a`.

After a successful managed package run, the next measurement should compare real HarfBuzz/paragraph clusters and positions, shaping size and drawing size at 73/73.5/84 physical pixels, and native iPhone densities. Raw glyph sums do not establish paragraph parity. No global font-size rounding, font substitution or production dependency fork is approved by this experiment.

For later direct TTC integration, preserve the selected collection index when cloning variations. The current app repacks its chosen CJK face into a standalone SFNT and correctly clones index0 there; directly loading the original TTC through this new factory must not silently reuse that standalone-only index assumption.
