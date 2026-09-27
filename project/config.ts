export const ProjectName = "icy-lyrics";
// Icy Lyrics has its own public release line. This is the version shown to
// users and compared by the website startup updater.
export const ProjectVersion = "1.3.0";

// Public read-only API for Icy's approved TTML database. Keep this separate
// from the Spicy Lyrics compatibility endpoint and version handshake below.
export const IcyLyricsDatabaseApiUrl = "https://jackscurrie.com/api/ttml";

// Production origin for optional Lyric Creator transcription modules.
export const AutoTimingModelManifestUrl =
  "https://jackscurrie.com/downloads/auto-timing/manifest.json";

// The Spicy Lyrics service performs compatibility checks against the upstream
// client version. Keep this independent from Icy Lyrics' public version so an
// Icy release number is never sent to the lyrics API.
export const SpicyLyricsApiVersion = "6.3.12";
