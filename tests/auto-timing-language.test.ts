import { describe, expect, it } from "vitest";
import { autoTimingLanguage, normalizeAutoTimingLanguage } from "../src/components/ReactComponents/LyricCreator/autoTiming/language.ts";

describe("Auto-time language selection", () => {
  it("accepts Creator metadata names, ISO codes and region tags", () => {
    for (const value of ["English", "eng", "en-GB", "en_US", "<|en|>"]) expect(normalizeAutoTimingLanguage(value)).toBe("en");
    expect(normalizeAutoTimingLanguage("jpn")).toBe("ja");
    expect(normalizeAutoTimingLanguage("Burmese")).toBe("my");
    expect(normalizeAutoTimingLanguage("not-a-language")).toBeUndefined();
  });

  it("does not silently decode Japanese or Korean lyrics in English", () => {
    expect(autoTimingLanguage("", "君の名前を呼んでいる 星空の下でいつまでも待っている")).toBe("ja");
    expect(autoTimingLanguage("", "우리 함께 노래하며 별빛 아래 걸어가요")).toBe("ko");
    expect(autoTimingLanguage("", "在漫长的夜晚我们一起仰望天空寻找自己的梦想")).toBe("zh");
  });

  it("keeps explicit metadata authoritative and avoids guessing short Latin refrains", () => {
    expect(autoTimingLanguage("English", "君の名前を呼んでいる")).toBe("en");
    expect(autoTimingLanguage("", "la la la la")).toBeUndefined();
    expect(autoTimingLanguage("", "go slowly")).toBeUndefined();
  });
});
