# Experimental Skiko package notices

This experimental package modifies Skiko0.144.6 at commit `9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0` by adding a public, managed iOS FreeType Typeface factory and correcting selection of the explicitly configured Xcode SDK. The original CoreText default and full Skia modules remain enabled. The experimental artifact version is `0.144.6-icy-freetype.1`; it must never replace upstream0.144.6 bytes under that version.

Skiko and the additive factory are Apache-2.0. Skia is BSD-3-Clause. This software uses FreeType under the FreeType License option. Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved. Preserve the complete FreeType root `LICENSE.TXT` and `docs/FTL.TXT` included with these notices.

The complete notices from all locked source trees accompany the experiment, including HarfBuzz, ICU, libpng, zlib, libjpeg-turbo, libwebp, Expat, DNG SDK, Piex and Wuffs. The GN source/dependency record identifies the compiled closure separately from this broader notice collection. Original font bytes and their Apache/OFL notices are preserved. No font conversion or replacement is performed.

Keep this notice folder, `sources.lock.json`, patch provenance and artifact hashes with any transfer of the experimental local Maven repository. This local build experiment is not an external Maven release or an approved application distribution. App dependency selection and the app's existing licensing/distribution gates remain unchanged.
