import { SpotifyPlayer } from "../components/Global/SpotifyPlayer.ts";
import PageView from "../components/Pages/PageView.ts";
import { toast } from "sonner";
import fetchLyrics, { LyricsStore } from "./Lyrics/fetchLyrics.ts";
import ApplyLyrics from "./Lyrics/Global/Applyer.ts";
import { $currentLyricsData } from "./stores.ts";

export const RemoveCurrentLyrics_AllCaches = async (ui: boolean = false) => {
  const currentSongId = SpotifyPlayer.GetId();
  const currentSongUri = SpotifyPlayer.GetUri();
  if (!currentSongUri) {
    if (ui) toast.error("The current song URI could not be retrieved");
    return;
  }
  try {
    await LyricsStore.RemoveItem(currentSongUri ?? "");
    // Remove the pre-6.3 ID-keyed entry too, but never touch durable local TTML.
    if (currentSongId) await LyricsStore.RemoveItem(currentSongId);
    $currentLyricsData.set("");
    if (ui) toast.success("Remote lyrics caches for the current song were cleared");
    if (PageView.IsOpened) {
      const uri = SpotifyPlayer.GetUri();
      if (uri && uri !== undefined) {
        fetchLyrics(uri).then(ApplyLyrics);
      }
    }
  } catch (error) {
    if (ui) toast.error("The current song could not be removed from remote caches");
    console.error("IcyLyrics:", error);
  }
};

export const RemoveLyricsCache = async (ui: boolean = false) => {
  try {
    await LyricsStore.Destroy();
    if (ui) toast.success("The remote lyrics cache was cleared");
    if (PageView.IsOpened) {
      const uri = SpotifyPlayer.GetUri();
      if (uri && uri !== undefined) {
        fetchLyrics(uri).then(ApplyLyrics);
      }
    }
  } catch (error) {
    if (ui) toast.error("The remote lyrics cache could not be cleared");
    console.error("IcyLyrics:", error);
  }
};


export const RemoveCurrentLyrics_StateCache = (ui: boolean = false) => {
  try {
    $currentLyricsData.set("");
    if (ui) toast.success("The current song was removed from internal lyrics state");
    if (PageView.IsOpened) {
      const uri = SpotifyPlayer.GetUri();
      if (uri && uri !== undefined) {
        fetchLyrics(uri).then(ApplyLyrics);
      }
    }
  } catch (error) {
    if (ui) toast.error("The current song could not be removed from internal lyrics state");
    console.error("IcyLyrics:", error);
  }
};
