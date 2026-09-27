import type { TimingAction } from "./timing.ts";

type ClosestTarget = {
  closest?: (selectors: string) => Element | null;
};

const EDITABLE_SELECTOR =
  "input, textarea, select, [contenteditable]:not([contenteditable='false']), [role='textbox']";
const TEXT_EDITING_SELECTOR =
  "textarea, input:not([type]), input[type='text'], input[type='number'], input[type='search'], input[type='email'], input[type='url'], input[type='password'], [contenteditable]:not([contenteditable='false']), [role='textbox']";

type CreatorShortcutEvent = Pick<KeyboardEvent, "code" | "repeat" | "isComposing" | "target"> &
  Partial<Pick<KeyboardEvent, "key" | "keyCode" | "which" | "altKey" | "ctrlKey" | "metaKey">>;

function isCreatorSpaceKey(event: CreatorShortcutEvent): boolean {
  const key = event.key?.toLowerCase();
  // Embedded/native keyboard paths can still use legacy Spacebar or only VK32.
  return event.code === "Space" || key === " " || key === "spacebar" || key === "space" ||
    ((!event.code || event.code === "Unidentified") && (event.keyCode === 32 || event.which === 32));
}

function shortcutIsEditableOrModified(event: CreatorShortcutEvent): boolean {
  return Boolean(event.isComposing || event.altKey || event.ctrlKey || event.metaKey ||
    isCreatorTextEditingTarget(event.target));
}

/**
 * Spotify installs document-level keyboard shortcuts. Creator editing controls
 * must be treated as an isolated application surface so those shortcuts never
 * navigate away while a user is typing.
 */
export function isCreatorEditableTarget(target: EventTarget | null): boolean {
  const candidate = target as ClosestTarget | null;
  return typeof candidate?.closest === "function" && candidate.closest(EDITABLE_SELECTOR) !== null;
}

/**
 * Text-entry controls are the only Creator surfaces that may keep printable
 * keys. Range controls, buttons, and the waveform can retain focus without
 * stealing the timing or transport shortcuts.
 */
export function isCreatorTextEditingTarget(target: EventTarget | null): boolean {
  const candidate = target as ClosestTarget | null;
  return (
    typeof candidate?.closest === "function" && candidate.closest(TEXT_EDITING_SELECTOR) !== null
  );
}

export function creatorTimingActionFromKeyboardEvent(
  event: CreatorShortcutEvent
): TimingAction | null {
  if (event.repeat || shortcutIsEditableOrModified(event)) return null;
  const key = event.key?.toLocaleLowerCase();
  if (event.code === "KeyF" || key === "f") return "start";
  if (event.code === "KeyG" || key === "g") return "end-and-next";
  if (event.code === "KeyH" || key === "h") return "end";
  return null;
}

export function isCreatorTimingAdvanceShortcut(event: CreatorShortcutEvent): boolean {
  return !event.repeat && !shortcutIsEditableOrModified(event) &&
    (isCreatorSpaceKey(event) || event.code === "KeyJ" || event.key?.toLowerCase() === "j");
}

export function isCreatorPlaybackShortcut(
  event: CreatorShortcutEvent
): boolean {
  return (
    isCreatorSpaceKey(event) &&
    !event.repeat &&
    !shortcutIsEditableOrModified(event)
  );
}

/**
 * Stop key events produced by inputs inside the Creator before they reach
 * Spotify's bubbling shortcut handlers. React's delegated input handlers still
 * run because stopPropagation does not cancel listeners on the Creator host.
 */
export function installCreatorKeyboardIsolation(host: HTMLElement): () => void {
  const isolateEditableEvent = (event: Event) => {
    if (isCreatorEditableTarget(event.target)) event.stopPropagation();
  };
  const eventTypes = ["keydown", "keypress", "keyup"] as const;
  for (const eventType of eventTypes) host.addEventListener(eventType, isolateEditableEvent);
  return () => {
    for (const eventType of eventTypes) host.removeEventListener(eventType, isolateEditableEvent);
  };
}
