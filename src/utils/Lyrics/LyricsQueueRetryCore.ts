export type QueueFetchResult = [object | string, number] | null;

export const QUEUE_RETRY_BASE_DELAY_MS = 2000;
export const QUEUE_RETRY_MAX_DELAY_MS = 10000;
export const QUEUE_RETRY_BACKOFF_FACTOR = 1.5;

export function computeQueueRetryDelay(attempt: number): number {
  const safeAttempt = Math.max(0, Math.floor(attempt));
  const scaled = QUEUE_RETRY_BASE_DELAY_MS * QUEUE_RETRY_BACKOFF_FACTOR ** safeAttempt;
  return Math.min(QUEUE_RETRY_MAX_DELAY_MS, Math.round(scaled));
}

export function isQueuedLyricsResult(result: QueueFetchResult): boolean {
  return result === null || (result[0] === "lyrics-queued" && result[1] === 503);
}

type TimerHandle = ReturnType<typeof setTimeout>;

export interface LyricsQueueRetryDependencies {
  getCurrentUri: () => string | null | undefined;
  fetch: (uri: string) => Promise<QueueFetchResult>;
  apply: (result: QueueFetchResult) => Promise<void>;
  showQueue: () => void;
  setTimer: (callback: () => void, delay: number) => TimerHandle;
  clearTimer: (handle: TimerHandle) => void;
  logError?: (error: unknown) => void;
}

export class LyricsQueueRetryController {
  private activeUri: string | null = null;
  private attempt = 0;
  private timer: TimerHandle | null = null;
  private inTick = false;

  constructor(private readonly dependencies: LyricsQueueRetryDependencies) {}

  IsRetryingFor(uri: string | null | undefined): boolean {
    return uri != null && this.activeUri === uri;
  }

  HandleQueued(uri: string): void {
    this.dependencies.showQueue();
    if (this.activeUri === uri) {
      if (this.timer === null && !this.inTick) this.scheduleNext();
      return;
    }
    this.clearPendingTimer();
    this.activeUri = uri;
    this.attempt = 0;
    if (!this.inTick) this.scheduleNext();
  }

  NotifyResolved(uri: string | null | undefined): void {
    if (uri != null && this.activeUri === uri) this.cancel();
  }

  OnSongChange(newUri: string | null | undefined): void {
    if (this.activeUri !== null && this.activeUri !== newUri) this.cancel();
  }

  Dispose(): void {
    this.cancel();
  }

  private cancel(): void {
    this.clearPendingTimer();
    this.activeUri = null;
    this.attempt = 0;
  }

  private clearPendingTimer(): void {
    if (this.timer === null) return;
    this.dependencies.clearTimer(this.timer);
    this.timer = null;
  }

  private scheduleNext(): void {
    this.clearPendingTimer();
    const uri = this.activeUri;
    if (!uri) return;
    const delay = computeQueueRetryDelay(this.attempt);
    this.attempt += 1;
    this.timer = this.dependencies.setTimer(() => void this.tick(uri), delay);
  }

  private async tick(uri: string): Promise<void> {
    this.timer = null;
    if (this.activeUri !== uri || this.dependencies.getCurrentUri() !== uri) {
      if (this.activeUri === uri) this.cancel();
      return;
    }

    this.inTick = true;
    let result: QueueFetchResult = null;
    let failed = false;
    try {
      result = await this.dependencies.fetch(uri);
      if (this.activeUri === uri) await this.dependencies.apply(result);
    } catch (error) {
      failed = true;
      this.dependencies.logError?.(error);
    } finally {
      this.inTick = false;
    }

    if (this.activeUri === uri) {
      if (failed || isQueuedLyricsResult(result)) this.scheduleNext();
      else this.cancel();
      return;
    }
    if (this.activeUri !== null && this.timer === null) this.scheduleNext();
  }
}
