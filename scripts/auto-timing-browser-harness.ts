import { AutoTimingModelRepository } from "../src/components/ReactComponents/LyricCreator/autoTiming/modelRepository.ts";
import { transcribeAutoTimingAudio } from "../src/components/ReactComponents/LyricCreator/autoTiming/workerClient.ts";
import { decodeAutoTimingAudio } from "../src/components/ReactComponents/LyricCreator/autoTiming/audio.ts";

const output = document.querySelector<HTMLPreElement>("#output")!;
const requestedDevice =
  new URLSearchParams(location.search).get("device") === "webgpu" ? "webgpu" : "wasm";
const requestedModel =
  new URLSearchParams(location.search).get("model") === "accurate" ? "accurate" : "fast";
function report(message: string) {
  output.textContent += `${message}\n`;
  document.body.dataset.status = message.startsWith("PASS")
    ? "pass"
    : message.startsWith("FAIL")
      ? "fail"
      : "running";
}

async function run() {
  try {
    report("Loading signed local manifest…");
    const repository = new AutoTimingModelRepository({
      manifestUrl: "http://localhost:3000/downloads/auto-timing/manifest.local.json",
    });
    const progress = document.querySelector<HTMLProgressElement>("#progress")!;
    await repository.install(requestedModel, (value) => {
      progress.value = value.progress;
      output.textContent = `Installing ${requestedModel} module: ${Math.round(value.progress * 100)}% · ${value.file}\n`;
    });
    report(`${requestedModel} module installed and verified.`);
    const { manifest, definition } = await repository.getInstalledDefinition(requestedModel);
    report("Initializing the cached worker runtime…");
    const response = await fetch("./fixture.wav");
    if (!response.ok)
      throw new Error("Build this harness with ICY_TEST_AUDIO pointing to a spoken WAV fixture.");
    const audio = await decodeAutoTimingAudio(
      new File([await response.blob()], "fixture.wav", { type: "audio/wav" })
    );
    // Repeat real spoken content across Whisper's 29-second chunk boundary.
    const pcm = new Float32Array(Math.max(40 * 16_000, audio.pcm.length + 31 * 16_000));
    pcm.set(audio.pcm, 16_000);
    pcm.set(audio.pcm, 31 * 16_000);
    const result = await transcribeAutoTimingAudio({
      pcm,
      language: "en",
      manifest,
      definition,
      devicePreference: requestedDevice,
      onProgress: (value) =>
        report(`${value.phase} ${Math.round(value.progress * 100)}%: ${value.message}`),
    });
    report(JSON.stringify(result.words));
    const earlyWords = result.words.filter((word) => word.startTimeMs < 15_000);
    const lateWords = result.words.filter((word) => word.startTimeMs > 30_000);
    if (earlyWords.length < 15 || lateWords.length < 15) {
      throw new Error(
        "Repeated phrases were lost across the 29-second chunk boundary."
      );
    }
    if (
      result.words.some(
        (word) =>
          word.startTimeMs < 0 ||
          word.endTimeMs <= word.startTimeMs ||
          word.endTimeMs > pcm.length / 16
      )
    ) {
      throw new Error("The worker returned invalid word times.");
    }
    const silence = await transcribeAutoTimingAudio({
      pcm: new Float32Array(16_000),
      manifest,
      definition,
      devicePreference: requestedDevice,
    });
    if (silence.words.length) throw new Error("Digital silence produced hallucinated words.");
    report(
      `PASS · ${result.device.toUpperCase()} recognized ${result.words.length} words across chunk boundaries; silence produced no words.`
    );
  } catch (error) {
    console.error(error);
    report(`FAIL · ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`);
  }
}

void run();
