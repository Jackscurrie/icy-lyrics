/** Avoid no-op mutations in animation loops. Even forced classList.toggle()
 * can notify MutationObservers when the requested class is already present.
 * Read the live value (not a stale cache) so renderer/seek cleanup stays safe. */
export function setFrameClass(element: HTMLElement, name: string, enabled: boolean): void {
  if (element.classList.contains(name) !== enabled) element.classList.toggle(name, enabled);
}

export function setFrameStyle(element: HTMLElement, name: string, value: string | null): void {
  if (element.style.getPropertyValue(name) === (value ?? "")) return;
  if (value === null) element.style.removeProperty(name);
  else element.style.setProperty(name, value);
}
