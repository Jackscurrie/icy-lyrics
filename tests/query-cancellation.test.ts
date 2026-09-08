import { afterEach, describe, expect, it, vi } from "vitest";
import { Query } from "../src/utils/API/Query.ts";

afterEach(() => vi.unstubAllGlobals());

describe("Spicy API transport cancellation", () => {
  it("passes the lyric-resolution AbortSignal to fetch", async () => {
    const controller = new AbortController();
    const fetchImpl = vi.fn(async (_url: string, init?: RequestInit) => {
      expect(init?.signal).toBe(controller.signal);
      return Response.json({ queries: [] });
    });
    vi.stubGlobal("fetch", fetchImpl);

    await Query(
      [{ operation: "lyrics", variables: { id: "0123456789ABCDEFGHIJKL" } }],
      {},
      { signal: controller.signal }
    );

    expect(fetchImpl).toHaveBeenCalledOnce();
  });
});
