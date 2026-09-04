# Experiment dependency notices

This experiment downloads source into ignored build output; it does not vendor those dependencies into the app or add them to an IPA. Preserve the original source notices and the generated `notices/` directory with any experimental binary shared for review. This document is not a distribution approval.

- **Skia**: Google and contributors, BSD 3-Clause license, exact commit `22f58c9fd43d55bde818821c04b48fda5d7ec939`. Original `LICENSE` is copied into the result.
- **FreeType**: The FreeType Project, exact Chromium-pinned commit `1518bc83d26b434031bd12c706ac3c7dab3902fd`. FreeType offers the FreeType License and GPLv2; this experiment uses the **FreeType License (FTL)** option. Original `LICENSE.TXT` and `docs/FTL.TXT` are copied into the result. Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved.
- **libpng**: PNG Reference Library authors, exact commit `49363adcfaf098748d7a4c8c624ad8c45a8c3a86`. Its original `LICENSE` is copied into the result.
- **zlib**: Jean-loup Gailly, Mark Adler and Chromium contributors, exact commit `646b7f569718921d7d4b5b8e22572ff6c76f2596`. Its original `LICENSE` is copied into the result.
- **Build tools only**: GN commit `b2afae122eeb6ce09c52d63f67dc53fc517dbdc8` ([source/license](https://gn.googlesource.com/gn/+/b2afae122eeb6ce09c52d63f67dc53fc517dbdc8/LICENSE)); Ninja `2@1.12.1.chromium.4`, Apache-2.0 ([upstream](https://github.com/ninja-build/ninja/blob/v1.12.1/COPYING)). Their hash-pinned CIPD archives are retained in the download cache. They are not linked into the probe or the app.
- **Original Android font assets**: unchanged Roboto (Apache-2.0), Noto Color Emoji and Flags (SIL Open Font License 1.1). Existing repository provenance, font hashes and full font licenses are copied into the result; the fonts are neither converted nor renamed.

The existing application/source license remains applicable to this repository's own experimental code. The experiment does not change Spotify licensing, delivery gates, or the distribution review.
