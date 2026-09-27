export interface CreatorTask {
  signal: AbortSignal;
  isCurrent: () => boolean;
  throwIfCancelled: () => void;
}

/** Keeps late async completions from changing a different audio/project session. */
export class CreatorTaskScope {
  private controller: AbortController | null = null;

  begin(): CreatorTask {
    this.cancel();
    const controller = new AbortController();
    this.controller = controller;
    const isCurrent = () => this.controller === controller && !controller.signal.aborted;
    return {
      signal: controller.signal,
      isCurrent,
      throwIfCancelled: () => {
        if (!isCurrent()) throw new DOMException("The operation was cancelled.", "AbortError");
      },
    };
  }

  cancel(): void {
    this.controller?.abort();
    this.controller = null;
  }
}
