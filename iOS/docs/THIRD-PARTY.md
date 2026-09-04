# Port dependency attribution

Icy Lyrics retains the repository's AGPL-3.0-or-later license, Spicy Lyrics attribution, and existing third-party notices. The visual resource bundle carries readable license/notice files under `IcyAssets/legal`.

| Port component | Source / license |
| --- | --- |
| Kotlin, coroutines, serialization, atomicfu | JetBrains; Apache-2.0 |
| Compose Multiplatform, Skiko | JetBrains; Apache-2.0 |
| AndroidX Compose, Room, SQLite wrapper, lifecycle, saved state, annotations, collections | Android Open Source Project; Apache-2.0 |
| Skia renderer | Google / Skia authors; BSD-3-Clause and bundled notices |
| XMLUtil 0.91.3 | [pdvrieze/xmlutil](https://github.com/pdvrieze/xmlutil); Apache-2.0 |
| SnakeYAML Engine KMP 4.0.1 | [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp); Apache-2.0 |
| Ktor 3.3.3 | JetBrains; Apache-2.0 |
| Okio 3.16.4 | Square; Apache-2.0 |
| SQLite | Public domain core; wrapper attribution remains Apache-2.0 |
| Unicode 13 category data | Unicode, Inc.; included Unicode data license |
| Roboto and selected Noto fonts | Exact Android API 36 files; source, copyright, licenses and hashes recorded in `shared/ui/assets/font` |
| Material icon vectors | AndroidX Material icons1.7.8; Apache-2.0; extraction provenance in `shared/ui/assets/ICONS.md` |
| Kawarp 1.2.0 adaptation | Better Lyrics; MIT; retained existing notice |
| Spotify iOS SDK 5.0.1 | Spotify Developer Terms; separate [distribution review](DISTRIBUTION.md) remains open |
| MPMessagePack included by Spotify | Copyright 2014 Gabriel Handford; MIT text in `shared/ui/assets/legal/spotify-mpmessagepack-license.txt` |

The complete resolved dependency versions are the three module `gradle.lockfile` files. Before public binary distribution, include relevant upstream binary notices (including Skia/Skiko's packaged notices), corresponding source/build instructions, and the documented SDK/source-license decision. This inventory does not relicense third-party components.
