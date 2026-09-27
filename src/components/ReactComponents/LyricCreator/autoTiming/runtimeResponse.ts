export function autoTimingAssetContentType(path: string): string {
  if (path.endsWith(".wasm")) return "application/wasm";
  if (path.endsWith(".json")) return "application/json";
  if (path.endsWith(".mjs")) return "text/javascript";
  return "application/octet-stream";
}

/** Old installed runtimes were cached as application/octet-stream. Correct the
 * local response header without redownloading, copying, or altering the signed
 * bytes so WebAssembly.instantiateStreaming can use its normal fast path. */
export function autoTimingWasmResponse(response: Response): Response {
  if (response.headers.get("Content-Type") === "application/wasm") return response;
  const headers = new Headers(response.headers);
  headers.set("Content-Type", "application/wasm");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
