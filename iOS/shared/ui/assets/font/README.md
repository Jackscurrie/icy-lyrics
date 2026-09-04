# Android font parity

The font binaries in this directory are unmodified copies from the API 36 baseline emulator. SHA-256 hashes, embedded notices and version identifiers are recorded in PROVENANCE.json and FALLBACK-PROVENANCE.json. The deprecated fonts.xml is retained only as provenance; it is never parsed at runtime.

Android retains its system default Roboto. iOS explicitly uses the bundled variable Roboto for every Material typography role and custom canvas measurement. Script spans select bundled Noto Naskh Arabic UI, Noto Sans Devanagari UI, Noto Sans CJK (collection indices 0 Japanese, 1 Korean, 2 simplified Chinese), and Noto Color Emoji/flags, so Apple system fallback does not silently change those glyphs.

Coverage is bounded: Latin, Greek, Cyrillic, Arabic, Devanagari, common CJK, and emoji are covered by these fixtures. This does not establish parity for every Android fallback script. Han regional forms currently use the Android English-locale simplified-Chinese fallback; traditional-Chinese locale preferences, complex emoji sequences, font synthesis, and platform shaping/antialiasing still need simulator/device snapshot validation. Native loading fails visibly when a packaged font is missing rather than substituting an unverified font.

Roboto and icon sources use Apache-2.0; the Noto fonts use SIL OFL 1.1. Full licenses and notices are bundled under ../legal.
