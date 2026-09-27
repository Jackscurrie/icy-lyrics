import { describe, expect, it, vi } from "vitest";
import { createCreatorDraftArtworkResolver, normalizeCreatorArtworkUrl } from "../src/components/ReactComponents/LyricCreator/draftArtwork.ts";

const uri = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";

describe("Creator draft artwork recovery", () => {
  it("normalizes Spotify images into durable URLs and rejects ephemeral or malformed sources", () => {
    expect(normalizeCreatorArtworkUrl("spotify:image:0123abcdef")).toBe("https://i.scdn.co/image/0123abcdef");
    expect(normalizeCreatorArtworkUrl("https://images.test/cover.jpg")).toBe("https://images.test/cover.jpg");
    expect(normalizeCreatorArtworkUrl("blob:expired-previous-session")).toBe("");
    expect(normalizeCreatorArtworkUrl("undefined")).toBe("");
  });

  it("deduplicates track recovery, persists the result, and avoids fetching it again", async () => {
    let finish: (track: { coverUrl: string }) => void = () => undefined;
    const resolveTrack = vi.fn(() => new Promise<{ coverUrl: string }>((resolve) => { finish = resolve; }));
    const persist = vi.fn(async () => undefined);
    const recover = createCreatorDraftArtworkResolver({ resolveTrack, persist });
    const first = recover(uri);
    const second = recover(uri);
    expect(first).toBe(second);
    finish({ coverUrl: "spotify:image:0123abcdef" });
    expect(await first).toBe("https://i.scdn.co/image/0123abcdef");
    expect(persist).toHaveBeenCalledWith(uri, "https://i.scdn.co/image/0123abcdef");
    expect(await recover(uri)).toBe("https://i.scdn.co/image/0123abcdef");
    expect(resolveTrack).toHaveBeenCalledTimes(1);
  });

  it("does not retry a broken URL indefinitely and recovers after a temporary lookup failure", async () => {
    const resolveTrack = vi.fn()
      .mockRejectedValueOnce(new Error("Spotify unavailable"))
      .mockResolvedValueOnce({ coverUrl: "https://images.test/broken.jpg" })
      .mockResolvedValueOnce({ coverUrl: "https://images.test/new.jpg" });
    const persist = vi.fn(async () => undefined);
    const recover = createCreatorDraftArtworkResolver({ resolveTrack, persist });
    await expect(recover(uri)).rejects.toThrow("Spotify unavailable");
    expect(await recover(uri, "https://images.test/broken.jpg")).toBe("");
    expect(persist).not.toHaveBeenCalled();
    expect(await recover(uri)).toBe("https://images.test/new.jpg");
  });
});
