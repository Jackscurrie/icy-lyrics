import { francAll } from "franc-all";
import langs from "langs";

// Supported by the already-shipped multilingual Whisper Tiny/Base modules.
const supportedCodes = new Set(
  "en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su".split(" ")
);
const aliases: Record<string, string> = {
  jv: "jw", nb: "no", fil: "tl", cmn: "zh", yue: "zh", burmese: "my",
  flemish: "nl", moldovan: "ro", moldavian: "ro", castilian: "es",
};
const allLanguages = langs.all();
const supportedIso3 = allLanguages
  .filter((language: Record<string, string>) => supportedCodes.has(aliases[language["1"]] ?? language["1"]))
  .map((language: Record<string, string>) => language["3"]);

export function normalizeAutoTimingLanguage(value: string | undefined): string | undefined {
  if (!value?.trim()) return undefined;
  const input = value.trim().toLowerCase().replace(/^<\|(.+)\|>$/u, "$1");
  const primary = input.replaceAll("_", "-").split("-")[0];
  if (supportedCodes.has(primary)) return primary;
  if (aliases[input] ?? aliases[primary]) return aliases[input] ?? aliases[primary];
  const language = allLanguages.find((entry: Record<string, string>) =>
    [entry["3"], entry["2B"], entry["2T"], entry.name, entry.local]
      .some((name) => name?.toLowerCase() === input)
  );
  const code = language?.["1"];
  const normalized = aliases[code] ?? code;
  return supportedCodes.has(normalized) ? normalized : undefined;
}

/** Transformers.js 4.3 does not detect Whisper language: omitted language
 * silently becomes English. Infer only when written lyrics give clear evidence;
 * an explicit supported metadata language always wins. */
export function autoTimingLanguage(metadata: string | undefined, text: string): string | undefined {
  const explicit = normalizeAutoTimingLanguage(metadata);
  if (explicit) return explicit;
  const letters = text.match(/\p{L}/gu)?.length ?? 0;
  if (letters < 4) return undefined;
  const count = (script: RegExp) => text.match(script)?.length ?? 0;
  if (count(/[\p{Script=Hiragana}\p{Script=Katakana}]/gu) >= Math.max(2, letters * 0.1)) return "ja";
  if (count(/\p{Script=Hangul}/gu) > letters * 0.5) return "ko";
  if (count(/\p{Script=Han}/gu) > letters * 0.8) return "zh";
  // Short choruses and repeated syllables cannot identify closely related
  // languages reliably. Do not turn a low-evidence guess into a decoder lock.
  if (letters < 80 || new Set(text.toLowerCase().match(/\p{L}+/gu) ?? []).size < 12) return undefined;
  const candidates = francAll(text, { only: supportedIso3, minLength: 80 });
  if (candidates.length < 2 || candidates[0][0] === "und" || candidates[0][1] - candidates[1][1] < 0.08) return undefined;
  return normalizeAutoTimingLanguage(candidates[0][0]);
}
