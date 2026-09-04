# Shared lyrics domain

`src/commonMain/kotlin` is the canonical source for the Android V2 and iPhone lyrics domain. Package names and public Kotlin APIs remain `com.icy.lyrics.core.lyrics`. Android compiles this directory directly; the iOS project consumes this Kotlin Multiplatform module.

The module contains the original normalized models, LRC/JSON/TTML/Lyricsfile parsers, provider orchestration, playback clock, scene generation, fullscreen math, and springs. Platform networking, storage, playback capture, and rendering remain outside this module.

Portability changes:

- TTML uses XMLUtil's generic XML reader and a bounded, ordered tree. The existing interpretation preserves namespace attributes, mixed text/CDATA, word spacing, backgrounds, romanization, source labels, and timing. The original 2,000,000-character, 50,000-element, and 64-level limits apply, with tree limits also checked during reading. DTDs, declared/external entities, unknown entities, invalid characters, malformed namespaces, and duplicate attributes are rejected.
- Lyricsfile uses SnakeYAML Engine KMP with the original Core schema and strict duplicate-key, document-count, tag/anchor/alias, and field/type checks. Large integers are checked through their exact decimal spelling instead of a JVM `BigInteger` cast.
- PlaybackClock uses an atomicfu lock around the same three methods previously marked `@Synchronized`.
- Surrogate-aware Unicode helpers preserve emphasis units and invisible/music-placeholder classification. Supplementary category ranges use Unicode 13.0, the Java 17 reference baseline. Parser regexes explicitly name ASCII digits and whitespace so JVM and Native regex engines interpret them alike.

Dependencies are pinned in this module's Gradle file: XMLUtil 0.91.3, SnakeYAML Engine KMP 4.0.1, atomicfu 0.27.0, coroutines 1.10.2, and serialization 1.9.0. These dependencies are compatible with Android's Kotlin 2.2.21 compiler. No platform parser adapter or `expect`/`actual` declarations are required.

`commonTest` contains all 73 original Android domain tests converted to `kotlin.test`, their embedded fixtures, and seven additional parser/Unicode portability cases. Android's original JUnit tests also remain in `android-v2/core/lyrics/src/test` to verify the direct-source integration.

From `iOS`, run `./gradlew :shared:lyrics:compileCommonMainKotlinMetadata :shared:lyrics:verificationTest`. On macOS also run `./gradlew :shared:lyrics:iosSimulatorArm64Test` to execute the same contract using Kotlin/Native. A successful JVM/metadata run alone does not establish native execution or visual parity.

The Unicode range source is [UnicodeData 13.0](https://www.unicode.org/Public/13.0.0/ucd/UnicodeData.txt), under the [Unicode data license](https://www.unicode.org/license.txt). XMLUtil, SnakeYAML Engine KMP, and atomicfu use Apache 2.0 licenses. The original Icy Lyrics licensing and notices continue to apply.
