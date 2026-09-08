import { describe, expect, it, vi } from "vitest";
import {
  ResolutionCoordinator,
  shouldSettleLyricsResolution,
  type LyricsResolutionResult,
} from "../src/utils/Lyrics/ResolutionCoordinator.ts";

describe("exact-URI lyric resolution coordinator", () => {
  it("coalesces concurrent automatic calls for one song", async () => {
    let finish!: (value: string) => void;
    const work = vi.fn(
      () => new Promise<string>((resolve) => {
        finish = resolve;
      })
    );
    const coordinator = new ResolutionCoordinator<string>(() => true);

    const first = coordinator.run("spotify:track:a", "ensure", work);
    const second = coordinator.run("spotify:track:a", "ensure", work);
    expect(second).toBe(first);
    expect(work).toHaveBeenCalledOnce();

    finish("lyrics");
    await expect(first).resolves.toBe("lyrics");
    expect(coordinator.getSettled("spotify:track:a")).toBe("lyrics");
  });

  it("keeps URI results independent and lets explicit refresh replace one", async () => {
    const coordinator = new ResolutionCoordinator<string>(() => true);
    await coordinator.run("spotify:track:a", "ensure", async () => "old-a");
    await coordinator.run("spotify:track:b", "ensure", async () => "lyrics-b");
    await coordinator.run("spotify:track:a", "refresh", async () => "new-a");

    expect(coordinator.getSettled("spotify:track:a")).toBe("new-a");
    expect(coordinator.getSettled("spotify:track:b")).toBe("lyrics-b");
  });

  it("does not let an obsolete promise clear its forced replacement", async () => {
    let finishOld!: (value: string) => void;
    let finishNew!: (value: string) => void;
    const coordinator = new ResolutionCoordinator<string>(() => true);
    const oldRequest = coordinator.run(
      "spotify:track:a",
      "ensure",
      () => new Promise((resolve) => { finishOld = resolve; })
    );
    const newRequest = coordinator.run(
      "spotify:track:a",
      "refresh",
      () => new Promise((resolve) => { finishNew = resolve; })
    );

    finishOld("old");
    await oldRequest;
    const joinedReplacement = coordinator.run("spotify:track:a", "ensure", async () => "wrong");
    expect(joinedReplacement).toBe(newRequest);

    finishNew("new");
    await expect(newRequest).resolves.toBe("new");
  });

  it("lets a scheduled queue retry supersede an ensure replay without duplicating queue ticks", async () => {
    let finishEnsure!: (value: string) => void;
    let finishRetry!: (value: string) => void;
    const coordinator = new ResolutionCoordinator<string>(() => true);
    const ensure = coordinator.run(
      "spotify:track:a",
      "ensure",
      () => new Promise((resolve) => { finishEnsure = resolve; })
    );
    const retry = coordinator.run(
      "spotify:track:a",
      "queue-retry",
      () => new Promise((resolve) => { finishRetry = resolve; })
    );
    const joinedRetry = coordinator.run(
      "spotify:track:a",
      "queue-retry",
      async () => "duplicate"
    );

    expect(retry).not.toBe(ensure);
    expect(joinedRetry).toBe(retry);
    finishEnsure("queued");
    await ensure;
    finishRetry("lyrics");
    await expect(retry).resolves.toBe("lyrics");
  });

  it("lets an explicit refresh finish when a scheduled queue timer fires", async () => {
    let finishRefresh!: (value: string) => void;
    const retryWork = vi.fn(async () => "unexpected-retry");
    const coordinator = new ResolutionCoordinator<string>(() => true);
    const refresh = coordinator.run(
      "spotify:track:a",
      "refresh",
      () => new Promise((resolve) => { finishRefresh = resolve; })
    );
    const queuedTimer = coordinator.run("spotify:track:a", "queue-retry", retryWork);

    expect(queuedTimer).toBe(refresh);
    expect(retryWork).not.toHaveBeenCalled();
    finishRefresh("fresh-lyrics");
    await expect(queuedTimer).resolves.toBe("fresh-lyrics");
  });

  it("bounds remembered songs and can forget a manual target", async () => {
    const coordinator = new ResolutionCoordinator<string>(() => true, 2);
    await coordinator.run("a", "ensure", async () => "A");
    await coordinator.run("b", "ensure", async () => "B");
    await coordinator.run("c", "ensure", async () => "C");
    expect(coordinator.getSettled("a")).toBeUndefined();
    expect(coordinator.getSettled("b")).toBe("B");
    coordinator.forget("b");
    expect(coordinator.getSettled("b")).toBeUndefined();
  });

  it("retries automatically after a transient result while retaining 404 and queued policy", async () => {
    const uri = "spotify:track:a";
    const recovered: LyricsResolutionResult = [{ Type: "Line" }, 200];
    const work = vi
      .fn<() => Promise<LyricsResolutionResult>>()
      .mockResolvedValueOnce(["offline", 400])
      .mockResolvedValueOnce(recovered);
    const coordinator = new ResolutionCoordinator<LyricsResolutionResult>(
      shouldSettleLyricsResolution
    );

    await expect(coordinator.run(uri, "ensure", work)).resolves.toEqual(["offline", 400]);
    expect(coordinator.getSettled(uri)).toBeUndefined();

    await expect(coordinator.run(uri, "ensure", work)).resolves.toBe(recovered);
    expect(work).toHaveBeenCalledTimes(2);
    expect(coordinator.getSettled(uri)).toBe(recovered);
    expect(shouldSettleLyricsResolution(["unknown-error", 0])).toBe(false);
    expect(shouldSettleLyricsResolution(["status-not-200", 500])).toBe(false);
    expect(shouldSettleLyricsResolution(["lyrics-not-found", 404])).toBe(true);
    expect(shouldSettleLyricsResolution(["lyrics-queued", 503])).toBe(true);
  });
});
