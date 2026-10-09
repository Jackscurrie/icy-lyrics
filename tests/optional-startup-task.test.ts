import { describe, expect, it, vi } from "vitest";
import { runOptionalStartupTask } from "../src/utils/OptionalStartupTask.ts";

describe("optional startup decoration", () => {
  it("does not block core initialization on pending artist artwork", () => {
    const report = vi.fn();
    const bindNavigation = vi.fn();
    runOptionalStartupTask(() => new Promise(() => {}), report);
    bindNavigation();
    expect(bindNavigation).toHaveBeenCalledTimes(1);
    expect(report).not.toHaveBeenCalled();
  });

  it("reports optional synchronous tooltip failure without aborting startup", () => {
    const error = new Error("tooltip unavailable");
    const report = vi.fn();
    expect(() => runOptionalStartupTask(() => { throw error; }, report)).not.toThrow();
    expect(report).toHaveBeenCalledExactlyOnceWith(error);
  });

  it("handles asynchronous artwork rejection rather than leaving an unhandled promise", async () => {
    const error = new Error("artist artwork unavailable");
    const report = vi.fn();
    runOptionalStartupTask(() => Promise.reject(error), report);
    await Promise.resolve();
    expect(report).toHaveBeenCalledExactlyOnceWith(error);
  });

  it("allows an optional tooltip to be absent", async () => {
    const report = vi.fn();
    runOptionalStartupTask(() => undefined, report);
    await Promise.resolve();
    expect(report).not.toHaveBeenCalled();
  });
});
