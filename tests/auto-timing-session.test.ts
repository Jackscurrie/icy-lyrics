import { describe, expect, it } from "vitest";
import { CreatorTaskScope } from "../src/components/ReactComponents/LyricCreator/autoTiming/session.ts";

describe("Auto-time async session ownership", () => {
  it("ignores an old decoded audio file when a newer file finishes first", async () => {
    const scope = new CreatorTaskScope();
    const applied: string[] = [];
    let finishFirst!: () => void;
    const first = scope.begin();
    const firstDecode = new Promise<void>((resolve) => { finishFirst = resolve; });
    const firstCompletion = firstDecode.then(() => {
      if (first.isCurrent()) applied.push("old audio");
    });
    const second = scope.begin();
    await Promise.resolve();
    second.throwIfCancelled();
    applied.push("new audio");
    finishFirst();
    await firstCompletion;
    expect(first.signal.aborted).toBe(true);
    expect(applied).toEqual(["new audio"]);
  });

  it("cannot start a worker if its model download finishes after the dialog closes", async () => {
    const scope = new CreatorTaskScope();
    const task = scope.begin();
    let finishDownload!: () => void;
    let workerStarted = false;
    const operation = new Promise<void>((resolve) => { finishDownload = resolve; }).then(() => {
      task.throwIfCancelled();
      workerStarted = true;
    });
    scope.cancel();
    finishDownload();
    await expect(operation).rejects.toMatchObject({ name: "AbortError" });
    expect(workerStarted).toBe(false);
  });

  it("prevents late alignment after cancelling while the browser yields for a frame", async () => {
    const scope = new CreatorTaskScope();
    const task = scope.begin();
    const frame = Promise.resolve();
    scope.cancel();
    await frame;
    expect(() => task.throwIfCancelled()).toThrowError("The operation was cancelled.");
    const retry = scope.begin();
    expect(retry.isCurrent()).toBe(true);
    expect(task.isCurrent()).toBe(false);
  });
});
