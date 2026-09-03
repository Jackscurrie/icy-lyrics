export type CreatorWorkspaceMode = "edit" | "time" | "preview";

/**
 * Tracks mounted Creator workspaces. Preview deliberately clears the previous
 * edit/time identity so returning from it restores the selected line in view.
 */
export class CreatorWorkspaceScrollTracker {
  private previous: string | null = null;

  shouldScroll(step: number, mode: CreatorWorkspaceMode): boolean {
    if (step === 1 || mode === "preview") {
      this.previous = mode === "preview" ? "preview" : null;
      return false;
    }

    const workspace = `${step}:${mode}`;
    const changed = this.previous !== workspace;
    this.previous = workspace;
    return changed;
  }
}
