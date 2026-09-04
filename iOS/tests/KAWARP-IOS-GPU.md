# Optional UIKit/Metal Kawarp uniform-phase probe

This is an implemented diagnostic awaiting native execution. It does not establish whole-app appearance or animated-background parity. The ordinary 20 fixtures and 29 UIKit captures are unchanged.

The paired original Android reference is `evidence/android36-kawarp-gpu-phases.zip`, SHA-256 `4e9435349de15a8c39092ae30496440d520f5b3748181ffa2602cb7c13875a7c`. Its actual 256Ã—256 artwork PNG is copied without alteration to `fixtures/kawarp/input-artwork.png`; `contract.json` retains source, decoded-pixel, processed-texture, shader and float32 uniform identities.

The iOS probe calls the same `preprocessArtwork`, `ArtworkTexture` and `drawKawarpFrame` used by the production iOS background. The helper extraction preserves the original draw/bind/cleanup body. It uses real `ComposeUIViewController` Canvas rendering; the Swift wrapper requires a visible `CAMetalLayer` with a device and matching drawable dimensions. Neither the earlier raster sampling test nor an offscreen ImageBitmap capture can satisfy this acknowledgement.

There are eight cases: physical 256Ã—512 and 512Ã—256 viewports at shader phases 0, 1, 3 and 12 seconds, blend 1, intensity 1, saturation 1.5 and dithering 0.008. Both children use the same actual processed 128Ã—128 texture, default nearest sampling, clamp and identity matrix. No outer black gradient is drawn: this isolates the production Kawarp shader, matching the Android GPU probe.

The device stays portrait for both canvas shapes. UIKit divides the requested integral physical size by the actual window display scale, places the child on an integral physical origin, then checks real Compose canvas size and density. It never overrides `LocalDensity`. XCTest requests an unchanged full-screen PNG through the host's public `simctl io screenshot` command, reads its dimensions through ImageIO and rejects any dimension/orientation discrepancy. The original full framebuffer is retained; the collector extracts every pixel of the measured child rectangle and proves the crop is an exact indexed subset. XCTest's own screenshots remain separate diagnostics because its transport can round odd widths. Metadata includes the actual native geometry, Metal device/layer configuration, all seven uniform float bits, configuration hashes, decoded input and processed pixels. Original PNG/hash evidence is retained even when dimensions fail validation.

On the existing standard Apple Silicon macOS 26 / Xcode 26.4.1 host, with an already booted iPhone simulator:

```bash
export PATH="$PWD/iOS/build/python-verification/bin:$PATH"
ICY_KAWARP_GPU_PROBE=true bash iOS/scripts/capture_kawarp_ios.sh SIMULATOR_UUID
```

The existing verification Python environment supplies pinned Pillow. The script builds/stages this source's simulator framework, checks its architecture, and explicitly runs only `IcyLyricsKawarpGpu`. It uses the separate debug bundle `com.icy.lyrics.ios.kawarpprobe`, an empty Spotify client ID and no live playback/authentication. The fixture input enters the app only when both `CONFIGURATION=Debug` and `ICY_KAWARP_PROBE=YES`; ordinary Debug and Release remove that optional resource folder. The route is excluded from Swift Release code.

The existing workflow's `kawarp_gpu_probe` manual input defaults to false. Enabling it retains the main build's simulator, runs this diagnostic independently after ordinary validation evidence uploads, uploads `icy-ios-kawarp-gpu`, then deletes the retained simulator. Results go only to a unique `iOS/build/reports/kawarp-ios/uniform-phases-v1.*` directory. The script exports attachments and the test summary even after XCTest failure. The collector copies only seven named diagnostic files per case from the matching UUID directory in the separate app container; it never exports credentials, ordinary imports or the rest of the container. Source fingerprints must remain unchanged. This probe creates no IPA or default simulator-verification marker and never runs on an automatic push.

`evidence/comparison.json` reports exact preprocessing and GPU comparisons separately. Every RGBA channel of the entire measured child is compared with no resize, mask, tolerance, recoloring or threshold; PNG color metadata is retained. Differences are evidence, so a complete successful capture may have `matchesEveryGpuPixel=false`; this does not turn an incomplete capture into success or grant visual acceptance. `appearanceParityVerified` remains false even if all eight frames match: first-load fade, replacement/interruption, pause/resume, foreground gaps, elapsed clock behavior, cadence, reduced motion and full-app animation remain outside this experiment.

Local checks: iOS Kotlin metadata compilation and nine collector/contract tests plus nine project-generation tests passed on Windows. Swift compilation, CAMetalLayer acknowledgement and actual native GPU screenshots have not run for this new suite yet.
