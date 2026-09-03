import {
  $currentLyricsType,
  $fullscreenRevealMode,
  $simpleLyricsMode,
} from "../../../../utils/stores.ts";
import { LyricsObject, type LyricsType } from "../../lyrics.ts";
import { getLyricsAnimationPosition } from "../Shared.ts";
import {
  timedElementProgress,
  timedElementStatus,
  type TimedElementStatus,
} from "../../../FullscreenPresentation.ts";

// Extend the LyricsType to include "None"
type ExtendedLyricsType = LyricsType | "None";

// Define a type for the word/syllable status
type ElementStatus = TimedElementStatus;

// Define interfaces for the objects we're working with
interface _SyllableLead {
  HTMLElement: HTMLElement;
  StartTime: number;
  EndTime: number;
  Status?: ElementStatus;
  [key: string]: any;
}

function getElementStatus(currentTime: number, startTime: number, endTime: number): ElementStatus {
  return timedElementStatus(currentTime, startTime, endTime);
}

const revealStatusClasses = ["RevealNotSung", "RevealActive", "RevealSung"];

const clearRevealTiming = (element: HTMLElement | undefined) => {
  if (!element) return;
  element.classList.remove("RevealTimed", ...revealStatusClasses);
  element.style.removeProperty("--reveal-progress");
  element.style.removeProperty("--reveal-gradient-position");
};

function syncRevealTiming(
  element: HTMLElement | undefined,
  currentTime: number,
  startTime: number,
  endTime: number
) {
  if (!element || !Number.isFinite(startTime) || !Number.isFinite(endTime)) {
    clearRevealTiming(element);
    return;
  }

  const status = getElementStatus(currentTime, startTime, endTime);
  const statusClass = `Reveal${status}`;
  element.classList.add("RevealTimed");
  if (!element.classList.contains(statusClass)) {
    element.classList.remove(...revealStatusClasses);
    element.classList.add(statusClass);
  }

  const progress = timedElementProgress(currentTime, startTime, endTime);
  const progressValue = progress.toFixed(4);
  const gradientValue = `${(-20 + 120 * progress).toFixed(3)}%`;
  if (element.style.getPropertyValue("--reveal-progress") !== progressValue) {
    element.style.setProperty("--reveal-progress", progressValue);
  }
  if (element.style.getPropertyValue("--reveal-gradient-position") !== gradientValue) {
    element.style.setProperty("--reveal-gradient-position", gradientValue);
  }
}

function syncRevealClasses(lines: any[], currentTime: number): void {
  for (const line of lines) {
    const leads = line.Syllables?.Lead ?? [];
    if (leads.length === 0) {
      syncRevealTiming(line.HTMLElement, currentTime, line.StartTime, line.EndTime);
      continue;
    }

    // Apply the reveal mask to one word-level surface only. Masking both a
    // letter-group parent and its children compounds opacity/masks and creates
    // a layer per letter; the group uses the word's own sung timing instead.
    clearRevealTiming(line.HTMLElement);
    for (const word of leads) {
      syncRevealTiming(word.HTMLElement, currentTime, word.StartTime, word.EndTime);
      for (const letter of word.Letters ?? []) {
        clearRevealTiming(letter.HTMLElement);
      }
    }
  }
}

export function TimeSetter(PreCurrentPosition: number): void {
  // Keep Reveal Mode on the exact clock used by LyricsAnimator.Animate. Simple
  // mode intentionally delays its sung-word animation by 33.5ms.
  const CurrentPosition = getLyricsAnimationPosition(PreCurrentPosition, $simpleLyricsMode.get());
  const CurrentLyricsType = $currentLyricsType.get() as ExtendedLyricsType;

  if (!CurrentLyricsType || CurrentLyricsType === "None") return;

  // Type assertion to ensure we can index with CurrentLyricsType
  const lines = LyricsObject.Types[CurrentLyricsType as LyricsType].Lines;

  if (CurrentLyricsType === "Syllable") {
    for (let i = 0; i < lines.length; i++) {
      // Type assertion for the line
      const line = lines[i] as any;

      const lineTimes = {
        start: line.StartTime,
        end: line.EndTime,
        total: line.EndTime - line.StartTime,
      };

      if (getElementStatus(CurrentPosition, lineTimes.start, lineTimes.end) === "Active") {
        line.Status = "Active";

        // Check if Syllables exists
        if (!line.Syllables?.Lead) continue;

        const words = line.Syllables.Lead;
        for (let j = 0; j < words.length; j++) {
          const word = words[j];
          word.Status = getElementStatus(CurrentPosition, word.StartTime, word.EndTime);

          if (word?.LetterGroup) {
            for (let k = 0; k < word.Letters.length; k++) {
              const letter = word.Letters[k];
              letter.Status = getElementStatus(CurrentPosition, letter.StartTime, letter.EndTime);
            }
          }
        }
      } else if (lineTimes.start > CurrentPosition) {
        line.Status = "NotSung";

        // Check if Syllables exists
        if (!line.Syllables?.Lead) continue;

        const words = line.Syllables.Lead;
        for (let j = 0; j < words.length; j++) {
          const word = words[j];
          word.Status = "NotSung";

          if (word?.LetterGroup) {
            for (let k = 0; k < word.Letters.length; k++) {
              const letter = word.Letters[k];
              letter.Status = "NotSung";
            }
          }
        }
      } else if (lineTimes.end <= CurrentPosition) {
        line.Status = "Sung";

        // Check if Syllables exists
        if (!line.Syllables?.Lead) continue;

        const words = line.Syllables.Lead;
        for (let j = 0; j < words.length; j++) {
          const word = words[j];
          word.Status = "Sung";

          if (word?.LetterGroup) {
            for (let k = 0; k < word.Letters.length; k++) {
              const letter = word.Letters[k];
              letter.Status = "Sung";
            }
          }
        }
      }
    }
  } else if (CurrentLyricsType === "Line") {
    for (let i = 0; i < lines.length; i++) {
      // Type assertion for the line
      const line = lines[i] as any;

      const lineTimes = {
        start: line.StartTime,
        end: line.EndTime,
        total: line.EndTime - line.StartTime,
      };

      if (getElementStatus(CurrentPosition, lineTimes.start, lineTimes.end) === "Active") {
        line.Status = "Active";
        if (line.DotLine) {
          const leads = line.Syllables.Lead;
          for (let i = 0; i < leads.length; i++) {
            const dot = leads[i];
            dot.Status = getElementStatus(CurrentPosition, dot.StartTime, dot.EndTime);
          }
        }
      } else if (lineTimes.start > CurrentPosition) {
        line.Status = "NotSung";
        if (line.DotLine) {
          const leads = line.Syllables.Lead;
          for (let i = 0; i < leads.length; i++) {
            const dot = leads[i];
            dot.Status = "NotSung";
          }
        }
      } else if (lineTimes.end <= CurrentPosition) {
        line.Status = "Sung";
        if (line.DotLine) {
          const leads = line.Syllables.Lead;
          for (let i = 0; i < leads.length; i++) {
            const dot = leads[i];
            dot.Status = "Sung";
          }
        }
      }
    }
  }

  // Reveal Mode is CSS-driven, but the status source of truth lives on lyric
  // objects. Mirror it onto the actual animator elements every frame so a
  // backward seek immediately hides future words again.
  if (
    $fullscreenRevealMode.get() &&
    document.getElementById("IcyLyricsPage")?.classList.contains("FullscreenView--lyrics")
  ) {
    // Classify the complete timed lyric set while Reveal is live. The focus
    // virtualizer can then swap in any group without a one-frame visibility
    // flash, while ordinary route/NPV playback pays no extra DOM-write pass.
    syncRevealClasses(lines as any[], CurrentPosition);
  }
}
