import { build } from "esbuild";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const siteDir = process.env.ICY_SITE_DIR;
if (!siteDir) throw new Error("Set ICY_SITE_DIR to the local Icy Lyrics website checkout.");
const outputDir = resolve(siteDir, "public", "downloads", "auto-timing", "runtime-test", "ui");
await mkdir(outputDir, { recursive: true });
await build({
  entryPoints: [resolve("scripts", "auto-timing-dialog-harness.tsx")],
  outfile: resolve(outputDir, "harness.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  define: { __ILdev__m: "true" },
});
await writeFile(resolve(outputDir, "index.html"), `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Icy Auto-time Dialog Test</title><link rel="stylesheet" href="./harness.css"></head><body><div id="root"></div><script src="./harness.js"></script></body></html>`, "utf8");
console.log(`Built actual Auto-time dialog harness in ${outputDir}`);
