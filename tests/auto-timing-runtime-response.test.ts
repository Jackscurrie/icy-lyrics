import { describe, expect, it } from "vitest";
import { autoTimingAssetContentType, autoTimingWasmResponse } from "../src/components/ReactComponents/LyricCreator/autoTiming/runtimeResponse.ts";

const emptyWasm = Uint8Array.from([0, 97, 115, 109, 1, 0, 0, 0]);

describe("Auto-time cached runtime MIME handling", () => {
  it("assigns the streaming WASM MIME type when installing either runtime variant", () => {
    expect(autoTimingAssetContentType("ort-wasm-simd-threaded.wasm")).toBe("application/wasm");
    expect(autoTimingAssetContentType("ort-wasm-simd-threaded.asyncify.wasm")).toBe("application/wasm");
    expect(autoTimingAssetContentType("config.json")).toBe("application/json");
    expect(autoTimingAssetContentType("ort-wasm.mjs")).toBe("text/javascript");
    expect(autoTimingAssetContentType("onnx/model.onnx")).toBe("application/octet-stream");
  });

  it("repairs an old installed response while preserving every signed byte and header", async () => {
    const old = new Response(emptyWasm, {
      headers: { "Content-Type": "application/octet-stream", "Content-Length": "8", "X-Icy-Verified": "yes" },
    });
    const repaired = autoTimingWasmResponse(old);
    expect(repaired.headers.get("Content-Type")).toBe("application/wasm");
    expect(repaired.headers.get("Content-Length")).toBe("8");
    expect(repaired.headers.get("X-Icy-Verified")).toBe("yes");
    expect(old.headers.get("Content-Type")).toBe("application/octet-stream");
    expect(new Uint8Array(await repaired.arrayBuffer())).toEqual(emptyWasm);
  });

  it("allows actual streaming instantiation without an ArrayBuffer fallback", async () => {
    const repaired = autoTimingWasmResponse(new Response(emptyWasm));
    const result = await WebAssembly.instantiateStreaming(repaired);
    expect(result.module).toBeInstanceOf(WebAssembly.Module);
    expect(result.instance).toBeInstanceOf(WebAssembly.Instance);
  });

  it("leaves a correctly typed installed response untouched", () => {
    const correct = new Response(emptyWasm, { headers: { "Content-Type": "application/wasm" } });
    expect(autoTimingWasmResponse(correct)).toBe(correct);
  });
});
