import Global from "../../components/Global/Global.ts";
import { SpotifyPlayer } from "../../components/Global/SpotifyPlayer.ts";
import Logger from "../Logger.ts";
import { isCreatorPreviewActive } from "../../components/ReactComponents/LyricCreator/previewOwnership.ts";
import ApplyLyrics from "./Global/Applyer.ts";
import fetchLyrics, { ShowQueueLoader } from "./fetchLyrics.ts";
import { LyricsQueueRetryController } from "./LyricsQueueRetryCore.ts";

export * from "./LyricsQueueRetryCore.ts";

const queueLogger = new Logger("Lyrics Queue Retry");

export const LyricsQueueRetry = new LyricsQueueRetryController({
  getCurrentUri: () => SpotifyPlayer.GetUri(),
  fetch: (uri) => (isCreatorPreviewActive() ? Promise.resolve(null) : fetchLyrics(uri)),
  apply: (result) => (isCreatorPreviewActive() ? Promise.resolve() : ApplyLyrics(result)),
  showQueue: () => ShowQueueLoader(),
  setTimer: (callback, delay) => setTimeout(callback, delay),
  clearTimer: (handle) => clearTimeout(handle),
  logError: (error) => queueLogger.error("Retry tick failed", error),
});

Global.Event.listen("playback:songchange", (event: any) => {
  const newUri: string | undefined = event?.data?.item?.uri ?? SpotifyPlayer.GetUri();
  LyricsQueueRetry.OnSongChange(newUri);
});
