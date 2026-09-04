# Native renderer notices

These files are unmodified upstream notices for Skiko 0.144.6, its pinned Skia
build, and the native dependencies represented in the iOS release archive.
They remain under their respective licenses, rather than the application's
AGPL license.

- Skiko tag `v0.144.6`: commit `9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0`.
- That tag's `skiko/gradle.properties` pins Skia to `m144-22f58c9fd4`:
  commit `22f58c9fd43d55bde818821c04b48fda5d7ec939`.
- External-library revisions are from that Skia commit's `DEPS` file.
- The libpng and zlib notices are exact entries from the published iOS ARM64
  release archive, verified against its GitHub SHA-256 digest.
- `PROVENANCE.json` records each source URL, original Git blob identifier where
  applicable, archive identifier where applicable, and file SHA-256.

This software is based in part on the work of the Independent JPEG Group.

The notices cover Skiko/AOSP, Skia and its wrappers, ICU, HarfBuzz, libjpeg-turbo,
libwebp, Adobe DNG SDK, piex, Expat, Wuffs, libpng, and zlib. Some upstream
components may be removed by the final linker's dead-code elimination; keeping
their notices does not claim that every feature is enabled. This inventory
does not replace the separate Spotify SDK/application licensing review.

The app's existing resource-copy step includes this directory beneath
`IcyAssets/legal/native`. IPA validation compares every bundled asset with its
source file. The final device bundle still needs that build-time verification.
