import { DOMParser } from "@xmldom/xmldom";
import { beforeAll, describe, expect, it, vi } from "vitest";

const query = vi.fn(async () => ({
  get: (operationId: string) =>
    operationId === "0"
      ? {
          httpStatus: 200,
          format: "json" as const,
          data: {
            Result: {
              Type: "Static",
              Lines: [{ Text: "Parsed by the compatibility service" }],
            },
          },
        }
      : undefined,
}));

vi.mock("../src/utils/API/Query.ts", () => ({ Query: query }));

const UNSUPPORTED_LOCAL_DIALECT = `<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml">
  <body><div><metadata-only /></div></body>
</tt>`;

beforeAll(() => {
  Object.defineProperty(globalThis, "DOMParser", {
    value: DOMParser,
    configurable: true,
  });
});

describe("raw TTML compatibility fallback", () => {
  it("uses the API parser only when the local parser rejects a TTML dialect", async () => {
    const { decodeLyricsPayload } = await import("../src/utils/Lyrics/payload.ts");
    const lyrics = await decodeLyricsPayload(UNSUPPORTED_LOCAL_DIALECT);

    expect(query).toHaveBeenCalledWith([
      { operation: "parseTTML", variables: { ttml: UNSUPPORTED_LOCAL_DIALECT } },
    ]);
    expect(lyrics).toMatchObject({
      Type: "Static",
      source: "spl",
      Lines: [{ Text: "Parsed by the compatibility service" }],
    });
  });
});
