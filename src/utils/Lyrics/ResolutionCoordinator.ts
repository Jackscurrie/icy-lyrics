export type ResolutionIntent = "ensure" | "refresh" | "queue-retry";

export type LyricsResolutionResult = readonly [object | string, number] | null;

const TRANSIENT_LYRICS_RESULTS = new Set([
  "offline",
  "status-not-200",
  "unknown-error",
]);

/** Keep durable results while allowing normal calls to recover after transient failures. */
export function shouldSettleLyricsResolution(value: LyricsResolutionResult): boolean {
  if (value === null) return false;
  const descriptor = value[0];
  return typeof descriptor !== "string" || !TRANSIENT_LYRICS_RESULTS.has(descriptor);
}

/**
 * Runtime-only exact-URI coordinator. It coalesces concurrent work and retains
 * the most recent result so lifecycle/UI rebuilds cannot accidentally perform
 * another network lookup for the same song.
 */
export class ResolutionCoordinator<T> {
  private readonly inFlight = new Map<
    string,
    { intent: ResolutionIntent; promise: Promise<T> }
  >();
  private readonly settled = new Map<string, T>();

  constructor(
    private readonly shouldSettle: (value: T) => boolean,
    private readonly maximumEntries = 128
  ) {}

  getSettled(uri: string): T | undefined {
    const value = this.settled.get(uri);
    if (value === undefined) return undefined;
    // Refresh insertion order so frequently revisited songs remain cached.
    this.settled.delete(uri);
    this.settled.set(uri, value);
    return value;
  }

  forget(uri: string): void {
    this.settled.delete(uri);
  }

  invalidateInFlight(): void {
    // Generation/URI guards make the old promises harmless. Clearing their
    // registrations ensures returning to a URI starts a fresh current request.
    this.inFlight.clear();
  }

  run(uri: string, intent: ResolutionIntent, work: () => Promise<T>): Promise<T> {
    const existing = this.inFlight.get(uri);
    if (
      existing &&
      intent !== "refresh" &&
      // A scheduled queue retry must supersede an ensure that is only
      // replaying the settled 503. Ensures join any live work, while a queue
      // tick joins an explicit refresh or another queue retry.
      (intent === "ensure" || existing.intent !== "ensure")
    ) {
      return existing.promise;
    }
    let request!: Promise<T>;
    request = work()
      .then((value) => {
        if (this.shouldSettle(value)) this.remember(uri, value);
        return value;
      })
      .finally(() => {
        if (this.inFlight.get(uri)?.promise === request) this.inFlight.delete(uri);
      });
    this.inFlight.set(uri, { intent, promise: request });
    return request;
  }

  private remember(uri: string, value: T): void {
    this.settled.delete(uri);
    this.settled.set(uri, value);
    while (this.settled.size > this.maximumEntries) {
      const oldestKey = this.settled.keys().next().value as string | undefined;
      if (oldestKey === undefined) break;
      this.settled.delete(oldestKey);
    }
  }
}
