# Original Android Kawarp GPU phase evidence

`evidence/android36-kawarp-gpu-phases.zip` contains the frozen Android renderer's actual 256×256 fixture artwork, a diagnostic 128×128 resize, the original private preprocessing result, and eight hardware Canvas/PixelCopy shader images. The adjacent JSON records the archive hash and key input identities. The instrumentation passed in 9.843 seconds on the owned API36 host-GPU emulator; the baseline APK/test APK build passed in 7 seconds. All 57 original production-source hashes matched before and after.

This is a selected-uniform GPU diagnostic. It does not exercise the original live frame coroutine, first-load fade, subsequent crossfade, interrupted artwork replacement, pause/resume, foreground timing, reduced-motion transitions, or the app's layout springs. No iOS appearance or full-animation parity is claimed.

## Captured contract

- Input: `IcyParityFixtures.artwork()`, exported once from Android and preserved as PNG plus decoded RGBA and little-endian ARGB/BGRA bytes. It is 256×256, opaque, sRGB, Android `ARGB_8888`; Android's alpha-capable and premultiplied flags are both true.
- Processing: test reflection invokes the actual private `ArtworkBackgroundKt.preprocessArtwork(Bitmap)` in the frozen original app. The separate resized intermediate uses `Bitmap.createScaledBitmap(input,128,128,true)` for diagnosis; it does not replace or intercept the production call.
- Shader/constants: reflection reads the original `KAWARP_SHADER`, blur size, intensity, saturation and dithering. The exact runtime shader text is exported as `original-kawarp.agsl` with SHA-256.
- Viewports: exact physical 256×512 and 512×256 pixels. Both small canvases fit the existing portrait emulator, so no display, density, font-scale or rotation settings were changed.
- Frame IDs: `<width>x<height>-phase-<time>`, with selected shader times 0, 1, 3 and 12 seconds. These are shader phase parameters, not elapsed playback timestamps.
- Uniform order: width, height, time, blend, intensity, saturation, dithering. Values are `[width,height,time,1,1,1.5,0.008]`; every actual float32 bit pattern and little-endian byte is recorded. Dithering's float32 bits are `3c03126f`.
- Both shader children use the same original processed texture, default filter mode0, CLAMP/CLAMP and identity matrix. Original outer paint has filtering enabled and dithering disabled; actual Android paint flags were1283.
- Every draw acknowledged a hardware-accelerated Canvas. PixelCopy captures the exact tagged Compose node; recorded bounds in the Compose root are `[0,0,width,height]`. There is no manual image crop, resize or mask.

All eleven exported PNGs (three texture stages and eight GPU images) decode to exactly the separately exported RGBA and ARGB-LE bytes. All pixels are opaque. The eight GPU output hashes are distinct. `pixel-validation.json` records this byte validation separately from instrumentation metadata.

## Input identities for the iOS GPU consumer

| Artifact | SHA-256 |
| --- | --- |
| Input PNG | `637ea5fc5b72e14361d2e801b64451bd333df8a52ec676c8872463f2b0ed4c18` |
| Input decoded RGBA | `9afe42cd3ba4e0e1e7cd3b7c576794a5a924fa8bcaa53ffb377cfbf682f209d2` |
| Input decoded ARGB-LE/BGRA | `dccb39b6102ede6f8d0e24f1047fb5357484cae613b1878b5efd2edee2947896` |
| Processed128 RGBA | `72c5d9307a973d0d26c891f07f899705a0d26f493753863d5cc5257c6433eef1` |
| Original runtime shader text | `755f24a2ef9d9f873877f44f9a7cda0f5945a7be5ffe6351cc79dcca7b382d3b` |

The native consumer should decode the exact captured PNG and check its pixels before invoking its existing preprocessing. It should export its actual processed texture before comparing the eight GPU images. A processing mismatch must remain visible independently from final-image differences. Native Skia raster output cannot substitute for the separate UIKit/Metal capture.

## Reproduction

Use the existing frozen checkout, never a regenerated copy of the port. The preparation script adds only the probe to its test source set and verifies the original production hashes:

```powershell
python iOS/tests/prepare_android_kawarp.py
# Build the frozen baseline's own Gradle root:
iOS/build/android-baseline/gradlew.bat -p iOS/build/android-baseline :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest --offline --console=plain
python iOS/tests/capture_android_kawarp.py --serial emulator-5580
```

The capture runner uses unique directories under `iOS/tests/results/android/kawarp-gpu-phases`, records installed APKs, source/probe hashes, device/GLES information, and verifies all 57 production hashes again after capture. Original20, extended11 and motion44 reference archives remain unchanged.
