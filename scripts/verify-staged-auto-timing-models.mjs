import { createHash, createPublicKey, verify } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { relative, resolve, sep } from "node:path";
import { pipeline as streamPipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";

const siteDir = process.env.ICY_SITE_DIR;
if (!siteDir) throw new Error("Set ICY_SITE_DIR to the local Icy Lyrics website checkout.");
const publicDir = resolve(siteDir, "public");
const manifestPath = resolve(publicDir, "downloads", "auto-timing", "manifest.json");
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
const publicKeyBase64 = "6g6ErCPwqR2YqkHQ1iNmGz5bENhjxY9TIekaiUNeBGI=";

function canonicalJson(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  return `{${Object.keys(value)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
    .join(",")}}`;
}

function localPath(url) {
  const parsed = new URL(url);
  if (parsed.origin !== "https://jackscurrie.com" || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`Unapproved staged URL: ${url}`);
  }
  if (!parsed.pathname.startsWith("/downloads/auto-timing/")) {
    throw new Error(`Staged URL is outside the Auto-time directory: ${url}`);
  }
  const path = resolve(publicDir, `.${decodeURIComponent(parsed.pathname)}`);
  const relativePath = relative(resolve(publicDir, "downloads", "auto-timing"), path);
  if (!relativePath || relativePath === ".." || relativePath.startsWith(`..${sep}`)) {
    throw new Error(`Staged URL escapes the Auto-time directory: ${url}`);
  }
  return path;
}

async function addFileToHash(path, expectedSize, expectedHash, logicalHash) {
  let storedPath = path;
  let compressed = false;
  try {
    const fileStat = await stat(path);
    if (!fileStat.isFile() || fileStat.size !== expectedSize) throw new Error(`Wrong size or file type: ${path}`);
  } catch (error) {
    // Production may store precompressed sidecars while serving the original
    // signed URLs with Content-Encoding: gzip. Never hide a corrupt raw file
    // or filesystem access failure by falling back to its compressed copy.
    if (error.code !== "ENOENT") throw error;
    storedPath = `${path}.gz`;
    const fileStat = await stat(storedPath);
    if (!fileStat.isFile() || fileStat.size <= 0) throw new Error(`Invalid compressed asset: ${storedPath}`);
    compressed = true;
  }
  const hash = createHash("sha256");
  let decodedSize = 0;
  const stages = [createReadStream(storedPath)];
  if (compressed) stages.push(createGunzip());
  // pipeline propagates errors and destroys every stream, including a source
  // read failure, a damaged gzip trailer, and an oversized decoded stream.
  await streamPipeline(...stages, async (source) => {
    for await (const chunk of source) {
      decodedSize += chunk.byteLength;
      if (decodedSize > expectedSize) throw new Error(`Decoded asset exceeds its signed size: ${storedPath}`);
      hash.update(chunk);
      logicalHash?.update(chunk);
    }
  });
  if (decodedSize !== expectedSize) {
    throw new Error(`Wrong decoded size: ${storedPath}`);
  }
  if (hash.digest("hex") !== expectedHash) throw new Error(`Wrong checksum: ${path}`);
}

const { signature, ...payload } = manifest;
if (signature?.algorithm !== "Ed25519" || signature?.keyId !== "icy-auto-timing-1") {
  throw new Error("The manifest signature metadata is invalid.");
}
const rawPublicKey = Buffer.from(publicKeyBase64, "base64");
const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), rawPublicKey]);
const publicKey = createPublicKey({ key: spki, format: "der", type: "spki" });
if (!verify(null, Buffer.from(canonicalJson(payload)), publicKey, Buffer.from(signature.value, "base64"))) {
  throw new Error("The staged manifest signature is invalid.");
}

const files = [...manifest.runtime.files, ...manifest.modules.flatMap((module) => module.files)];

// Cloudflare combines every matching rule's headers, rather than letting a
// more specific rule replace them. Repeated ACAO becomes "*, *" and breaks
// browser downloads; no-store plus immutable defeats asset caching.
// https://developers.cloudflare.com/workers/static-assets/headers/
const headerRules = [];
for (const line of (await readFile(resolve(publicDir, "_headers"), "utf8")).split(/\r?\n/u)) {
  if (!line.trim() || line.trimStart().startsWith("#")) continue;
  if (line.startsWith("/")) {
    const pattern = line.trim().split("/").map((part) => {
      if (part === "*") return ".*";
      if (/^:[A-Za-z]\w*$/u.test(part)) return "[^/]+";
      return part.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
    }).join("/");
    headerRules.push({ matcher: new RegExp(`^${pattern}$`, "u"), headers: [] });
  } else {
    const match = line.match(/^\s+([^:]+):\s*(.+)$/u);
    if (!match || !headerRules.length) throw new Error(`Unsupported staging header rule: ${line}`);
    headerRules.at(-1).headers.push([match[1].trim().toLowerCase(), match[2].trim()]);
  }
}
function verifyHeaders(path, immutable) {
  const headers = new Map();
  for (const rule of headerRules) {
    if (!rule.matcher.test(path)) continue;
    for (const [name, value] of rule.headers) {
      headers.set(name, headers.has(name) ? `${headers.get(name)}, ${value}` : value);
    }
  }
  if (headers.get("access-control-allow-origin") !== "*") {
    throw new Error(`Missing or duplicate CORS origin header: ${path}`);
  }
  const cache = headers.get("cache-control") ?? "";
  if (immutable ? !cache.includes("immutable") || /no-store|no-cache/u.test(cache) : !cache.includes("no-store")) {
    throw new Error(`Conflicting or missing download cache policy: ${path}`);
  }
}
verifyHeaders("/downloads/auto-timing/manifest.json", false);
verifyHeaders("/downloads/icy-lyrics-update.json", false);
verifyHeaders("/downloads/icy-lyrics.js", false);
for (const file of files) {
  for (const part of file.parts ?? [file]) verifyHeaders(new URL(part.url).pathname, true);
}
if (manifest.remotePathTemplate !== "downloads/auto-timing/models/{model}/{revision}/") {
  throw new Error("Models must use immutable revisioned URLs.");
}
if (manifest.modules.length !== 2 || new Set(manifest.modules.map((module) => module.id)).size !== 2) {
  throw new Error("The manifest must contain both timing modules exactly once.");
}
for (const module of manifest.modules) {
  if (!/^[a-f\d]{40}$/u.test(module.revision) || !["fast", "accurate"].includes(module.id)) {
    throw new Error("A timing module's identity or pinned revision is invalid.");
  }
  const base = new URL(
    manifest.remotePathTemplate.replace("{model}", module.modelId).replace("{revision}", module.revision),
    manifest.remoteHost
  );
  if (module.files.reduce((sum, file) => sum + file.size, 0) !== module.size) {
    throw new Error(`Wrong module size: ${module.id}`);
  }
  for (const file of module.files) {
    if (new URL(file.path, base).href !== file.url) throw new Error(`Wrong model URL: ${file.url}`);
  }
}
const urls = new Set();
let verifiedBytes = 0;
for (const file of files) {
  localPath(file.url);
  if (urls.has(file.url)) throw new Error(`Duplicate logical file: ${file.url}`);
  urls.add(file.url);
  if (file.parts?.length) {
    const logicalHash = createHash("sha256");
    let logicalSize = 0;
    for (const part of file.parts) {
      await addFileToHash(localPath(part.url), part.size, part.sha256, logicalHash);
      logicalSize += part.size;
    }
    if (logicalSize !== file.size || logicalHash.digest("hex") !== file.sha256) {
      throw new Error(`Wrong reconstructed checksum: ${file.path}`);
    }
  } else {
    await addFileToHash(localPath(file.url), file.size, file.sha256);
  }
  verifiedBytes += file.size;
}

console.log(
  `Verified ${files.length} logical Auto-time files (${(verifiedBytes / 1024 / 1024).toFixed(1)} MiB), all chunks, the Ed25519 manifest signature, and download CORS/cache rules.`
);
