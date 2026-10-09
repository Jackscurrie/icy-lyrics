interface RoutePageRequestOptions<T> {
  route: string;
  navigate: () => void;
  getPathname: () => string | undefined;
  getReadyPage: () => T | null;
  onError: (error: unknown) => void;
  timeoutMs?: number;
  pollMs?: number;
}

/** One bounded opening request, cancelled by a newer click or navigation away. */
export function createRoutePageRequest<T>(options: RoutePageRequestOptions<T>) {
  type Pending = {
    deadline: number;
    timer?: ReturnType<typeof setTimeout>;
    onReady: (page: T) => void | Promise<void>;
  };
  let pending: Pending | undefined;

  const cancel = () => {
    if (pending?.timer !== undefined) clearTimeout(pending.timer);
    pending = undefined;
  };

  const fail = (request: Pending, error: unknown) => {
    if (pending !== request) return;
    cancel();
    options.onError(error);
  };

  const check = (request: Pending) => {
    if (pending !== request) return;
    try {
      if (options.getPathname() !== options.route) {
        cancel();
        return;
      }
      const page = options.getReadyPage();
      if (page !== null) {
        // Detach before invoking callbacks: opening fullscreen can synchronously
        // cause another navigation/request, which must retain its own timer.
        cancel();
        try {
          void Promise.resolve(request.onReady(page)).catch(options.onError);
        } catch (error) {
          options.onError(error);
        }
        return;
      }
      if (Date.now() >= request.deadline) {
        fail(request, new Error("The Icy Lyrics page did not become ready in time."));
        return;
      }
      request.timer = setTimeout(() => check(request), options.pollMs ?? 50);
    } catch (error) {
      fail(request, error);
    }
  };

  return {
    request(onReady: (page: T) => void | Promise<void> = () => {}) {
      cancel();
      const request: Pending = {
        deadline: Date.now() + (options.timeoutMs ?? 5_000),
        onReady,
      };
      pending = request;
      try {
        options.navigate();
        check(request);
      } catch (error) {
        fail(request, error);
      }
    },
    cancel,
    onRouteChange(pathname: string | undefined) {
      if (pathname !== options.route) cancel();
    },
  };
}
