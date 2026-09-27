import { build } from "esbuild";
import { copyFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const siteDir = process.env.ICY_SITE_DIR;
if (!siteDir) throw new Error("Set ICY_SITE_DIR to the local Icy Lyrics website checkout.");
const outputDir = resolve(siteDir, "public", "downloads", "auto-timing", "runtime-test");
await mkdir(outputDir, { recursive: true });
if (process.env.ICY_TEST_AUDIO) {
  await copyFile(process.env.ICY_TEST_AUDIO, resolve(outputDir, "fixture.wav"));
}
await build({
  entryPoints: [resolve("scripts", "auto-timing-browser-harness.ts")],
  outfile: resolve(outputDir, "harness.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  define: { __ILdev__m: "true" },
});
await writeFile(
  resolve(outputDir, "index.html"),
  `<!doctype html><html><head><meta charset="utf-8"><title>Icy Auto-time Runtime Test</title><style>body{font:16px system-ui;color:#f4f6fb;background:#101117;padding:32px}main{max-width:900px;margin:auto}progress{width:100%;height:18px}pre{padding:18px;white-space:pre-wrap;background:#191b24;border-radius:12px}body[data-status=pass] pre{border:1px solid #45d69d}body[data-status=fail] pre{border:1px solid #ff6370}</style></head><body><main><h1>Icy Lyrics Auto-time runtime test</h1><progress id="progress" max="1"></progress><pre id="output"></pre></main><script src="./harness.js"></script></body></html>`,
  "utf8"
);
console.log(`Built browser runtime harness in ${outputDir}`);
