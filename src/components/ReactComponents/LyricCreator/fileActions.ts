export interface CreatorDownloadEnvironment {
  document: Pick<Document, "body" | "createElement">;
  createObjectURL: (blob: Blob) => string;
  revokeObjectURL: (url: string) => void;
  defer: (callback: () => void) => void;
}

// MIME associations in Windows Chromium can turn XML into an XSLT-only
// picker filter. Extension-only values keep real `.ttml` files selectable.
export const CREATOR_TTML_FILE_ACCEPT = ".ttml,.xml";

function defaultDownloadEnvironment(): CreatorDownloadEnvironment {
  return {
    document,
    createObjectURL: (blob) => URL.createObjectURL(blob),
    revokeObjectURL: (url) => URL.revokeObjectURL(url),
    // Revoking in the same task can cancel downloads in Spotify's Chromium
    // shell. Wait until the synthetic click has been consumed.
    defer: (callback) => window.setTimeout(callback, 0),
  };
}

export function sanitizeCreatorFilename(value: string): string {
  return (
    value
      .replace(/[^a-zA-Z0-9_\- .]/gu, "_")
      .trim()
      .slice(0, 100) || "lyrics"
  );
}

export function downloadCreatorTTML(
  rawTTML: string,
  projectName: string,
  environment: CreatorDownloadEnvironment = defaultDownloadEnvironment()
): string {
  const filename = `${sanitizeCreatorFilename(projectName)}.ttml`;
  const blob = new Blob([rawTTML], { type: "application/ttml+xml;charset=utf-8" });
  const url = environment.createObjectURL(blob);
  const anchor = environment.document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.hidden = true;
  environment.document.body.appendChild(anchor);
  try {
    anchor.click();
  } finally {
    anchor.remove();
    environment.defer(() => environment.revokeObjectURL(url));
  }
  return filename;
}

export function openCreatorFilePicker(input: HTMLInputElement | null): boolean {
  if (!input) return false;
  // Selecting the same file twice must still produce a change event.
  input.value = "";
  input.click();
  return true;
}

export async function readCreatorTextFile(file: File): Promise<string> {
  if (typeof file.text === "function") return file.text();
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener("load", () => resolve(String(reader.result ?? "")), { once: true });
    reader.addEventListener("error", () => reject(reader.error ?? new Error("File read failed.")), {
      once: true,
    });
    reader.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), {
      once: true,
    });
    reader.readAsText(file);
  });
}
