/** Optional decoration must never delay or abort core navigation startup. */
export function runOptionalStartupTask(
  task: () => unknown | Promise<unknown>,
  reportError: (error: unknown) => void
): void {
  try {
    void Promise.resolve(task()).catch(reportError);
  } catch (error) {
    reportError(error);
  }
}
