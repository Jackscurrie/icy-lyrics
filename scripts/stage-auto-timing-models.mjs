import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import {
  access,
  mkdir,
  readFile,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { dirname, join, relative, resolve, sep } from "node:path";
import { pipeline as streamPipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const siteDir = process.env.ICY_SITE_DIR;
if (!siteDir) {
  throw new Error("Set ICY_SITE_DIR to the local Icy Lyrics website checkout.");
}
const signingKeyPath =
  process.env.ICY_MODEL_SIGNING_PRIVATE_KEY ??
  join(process.env.LOCALAPPDATA ?? "", "IcyLyrics", "keys", "auto-timing-model-signing-private.pem");
await access(signingKeyPath);

const outputRoot = resolve(siteDir, "public", "downloads", "auto-timing");
const developmentStage = process.argv.includes("--dev");
const tempRoot = resolve(root, ".cache", "auto-timing-models");
const chunkBytes = 20 * 1024 * 1024;
const publicRoot = "https://jackscurrie.com/downloads/auto-timing/";
const privateKey = createPrivateKey(await readFile(signingKeyPath));
const publicKey = createPublicKey(privateKey).export({ type: "spki", format: "der" }).subarray(-32).toString("base64");
if (privateKey.asymmetricKeyType !== "ed25519" || publicKey !== "6g6ErCPwqR2YqkHQ1iNmGz5bENhjxY9TIekaiUNeBGI=") {
  throw new Error("The model signing key does not match the extension's pinned public key.");
}
const sourceFiles = [
  "config.json",
  "generation_config.json",
  "preprocessor_config.json",
  "tokenizer.json",
  "tokenizer_config.json",
  "added_tokens.json",
  "special_tokens_map.json",
  "vocab.json",
  "merges.txt",
  "normalizer.json",
  "onnx/encoder_model_q4.onnx",
  "onnx/decoder_model_merged_q4.onnx",
];

const modules = [
  {
    id: "fast",
    displayName: "Fast",
    description: "Smaller download and quicker processing for a first timing pass.",
    modelId: "icy/fast",
    sourceId: "onnx-community/whisper-tiny_timestamped",
    revision: "517244293732ee2d58139af5814231b7e6830a0d",
  },
  {
    id: "accurate",
    displayName: "Accurate",
    description: "Larger multilingual model for a more detailed timing pass. Singing still needs manual review.",
    modelId: "icy/accurate",
    sourceId: "onnx-community/whisper-base_timestamped",
    revision: "608c49e61301901684bc36cac8f74b95ff6b5a8e",
  },
];

function canonicalJson(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  return `{${Object.keys(value)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
    .join(",")}}`;
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

async function sourceMatches(path, metadata) {
  try {
    const fileStat = await stat(path);
    if (fileStat.size !== metadata.size) return false;
    if (metadata.lfs) return await sha256File(path) === metadata.lfs.sha256;
    // Small Hugging Face files use Git blob SHA-1 rather than LFS SHA-256.
    const hash = createHash("sha1").update(`blob ${fileStat.size}\0`);
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest("hex") === metadata.blobId;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
}

async function download(url, destination, metadata) {
  if (await sourceMatches(destination, metadata)) return;
  await mkdir(dirname(destination), { recursive: true });
  const response = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(300_000) });
  if (!response.ok || !response.body) throw new Error(`Download failed (${response.status}): ${url}`);
  const partial = `${destination}.download-${process.pid}`;
  try {
    await streamPipeline(response.body, createWriteStream(partial));
    if (!await sourceMatches(partial, metadata)) throw new Error(`The pinned upstream file failed integrity verification: ${url}`);
    await rename(partial, destination);
  } finally {
    await rm(partial, { force: true });
  }
}

async function stageImmutable(sourcePath, destination, range) {
  await mkdir(dirname(destination), { recursive: true });
  const partial = `${destination}.stage-${process.pid}`;
  try {
    await streamPipeline(createReadStream(sourcePath, range), createWriteStream(partial));
    const checksum = await sha256File(partial);
    try {
      const existingHash = await sha256File(destination);
      if (existingHash !== checksum) throw new Error(`Immutable asset changed at ${destination}; use a new model/runtime version.`);
      return checksum;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    await rename(partial, destination);
    return checksum;
  } finally {
    await rm(partial, { force: true });
  }
}

async function writeAtomic(path, content) {
  const partial = `${path}.stage-${process.pid}`;
  try {
    await writeFile(partial, content, "utf8");
    await rename(partial, path);
  } finally {
    await rm(partial, { force: true });
  }
}

async function removeOutputDirectory(path) {
  const resolved = resolve(path);
  const within = relative(outputRoot, resolved);
  if (!within || within === ".." || within.startsWith(`..${sep}`) || resolve(outputRoot, within) !== resolved) {
    throw new Error(`Refusing to remove a directory outside the staged output: ${path}`);
  }
  await rm(resolved, { recursive: true, force: true });
}

function webPath(path) {
  return relative(resolve(siteDir, "public"), path).split(sep).join("/");
}

async function stageFile(sourcePath, destination) {
  await mkdir(dirname(destination), { recursive: true });
  const fileStat = await stat(sourcePath);
  const sha256 = await sha256File(sourcePath);
  const descriptor = {
    path: relative(dirname(destination), destination).split(sep).join("/"),
    url: `${publicRoot}${webPath(destination).replace(/^downloads\/auto-timing\//u, "")}`,
    size: fileStat.size,
    sha256,
  };
  if (fileStat.size <= chunkBytes) {
    await stageImmutable(sourcePath, destination);
    return descriptor;
  }

  const parts = [];
  let index = 0;
  let offset = 0;
  while (offset < fileStat.size) {
    const end = Math.min(fileStat.size, offset + chunkBytes);
    const partPath = `${destination}.part${String(index).padStart(3, "0")}`;
    const partHash = await stageImmutable(sourcePath, partPath, { start: offset, end: end - 1 });
    parts.push({
      url: `${publicRoot}${webPath(partPath).replace(/^downloads\/auto-timing\//u, "")}`,
      size: end - offset,
      sha256: partHash,
    });
    offset = end;
    index += 1;
  }
  descriptor.parts = parts;
  return descriptor;
}

await mkdir(outputRoot, { recursive: true });
await mkdir(tempRoot, { recursive: true });

const ortPackage = JSON.parse(
  await readFile(resolve(root, "node_modules", "onnxruntime-web", "package.json"), "utf8")
);
const runtimeVersion = ortPackage.version;
const runtimeSource = resolve(root, "node_modules", "onnxruntime-web", "dist");
const runtimeFiles = [];
for (const name of [
  "ort-wasm-simd-threaded.wasm",
  "ort-wasm-simd-threaded.mjs",
  "ort-wasm-simd-threaded.asyncify.wasm",
  "ort-wasm-simd-threaded.asyncify.mjs",
]) {
  runtimeFiles.push(
    await stageFile(
      resolve(runtimeSource, name),
      resolve(outputRoot, "runtime", runtimeVersion, name)
    )
  );
  runtimeFiles.at(-1).path = name;
}

const stagedModules = [];
for (const module of modules) {
  const sourceMetadataResponse = await fetch(
    `https://huggingface.co/api/models/${module.sourceId}/revision/${module.revision}?blobs=true`,
    { signal: AbortSignal.timeout(30_000) }
  );
  if (!sourceMetadataResponse.ok) throw new Error(`Could not verify upstream revision for ${module.displayName}.`);
  const sourceMetadata = await sourceMetadataResponse.json();
  if (sourceMetadata.sha !== module.revision || !Array.isArray(sourceMetadata.siblings)) {
    throw new Error(`Upstream did not return the pinned ${module.displayName} revision.`);
  }
  const files = [];
  for (const name of sourceFiles) {
    const sourcePath = resolve(tempRoot, module.id, module.revision, name);
    const sourceUrl = `https://huggingface.co/${module.sourceId}/resolve/${module.revision}/${name}`;
    console.log(`[${module.displayName}] ${name}`);
    const metadata = sourceMetadata.siblings.find((file) => file.rfilename === name);
    if (!metadata || !Number.isSafeInteger(metadata.size) || !(metadata.lfs?.sha256 || metadata.blobId)) {
      throw new Error(`Missing upstream integrity metadata for ${module.displayName}: ${name}`);
    }
    await download(sourceUrl, sourcePath, metadata);
    const destination = resolve(outputRoot, "models", module.modelId, module.revision, name);
    const descriptor = await stageFile(sourcePath, destination);
    descriptor.path = name;
    files.push(descriptor);
  }
  stagedModules.push({
    id: module.id,
    displayName: module.displayName,
    description: module.description,
    version: `${module.revision.slice(0, 12)}-q4`,
    modelId: module.modelId,
    revision: module.revision,
    dtype: "q4",
    size: files.reduce((sum, file) => sum + file.size, 0),
    files,
  });
}

const payload = {
  schemaVersion: 1,
  release: "1.3.0",
  generatedAt: new Date().toISOString(),
  remoteHost: "https://jackscurrie.com/",
  remotePathTemplate: "downloads/auto-timing/models/{model}/{revision}/",
  runtime: {
    version: runtimeVersion,
    wasmBaseUrl: `${publicRoot}runtime/${runtimeVersion}/`,
    files: runtimeFiles,
  },
  modules: stagedModules,
};
function signedManifest(value) {
  const signature = sign(null, Buffer.from(canonicalJson(value)), privateKey).toString("base64");
  return {
    ...value,
    signature: { algorithm: "Ed25519", keyId: "icy-auto-timing-1", value: signature },
  };
}
const manifest = signedManifest(payload);
await writeAtomic(resolve(outputRoot, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
if (developmentStage) {
  const localOrigin = (process.env.ICY_MODEL_DEV_ORIGIN ?? "http://localhost:3000/").replace(/\/?$/u, "/");
  const localPayload = JSON.parse(
    JSON.stringify(payload).replaceAll("https://jackscurrie.com/", localOrigin)
  );
  await writeAtomic(
    resolve(outputRoot, "manifest.local.json"),
    `${JSON.stringify(signedManifest(localPayload), null, 2)}\n`
  );
} else {
  await rm(resolve(outputRoot, "manifest.local.json"), { force: true });
  await removeOutputDirectory(resolve(outputRoot, "runtime-test"));
}
await writeFile(
  resolve(outputRoot, "README.txt"),
  [
    "Icy Lyrics 1.3.0 optional on-device Auto-time modules.",
    "Generated from pinned ONNX Community Whisper revisions.",
    "Large ONNX and WASM files are split into deployment-safe chunks and reassembled only in browser cache.",
    "Audio is processed locally and is never uploaded.",
    "",
  ].join("\n"),
  "utf8"
);
console.log(`Staged signed model manifest in ${outputRoot}`);
