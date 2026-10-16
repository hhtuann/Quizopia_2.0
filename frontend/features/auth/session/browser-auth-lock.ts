const REFRESH_LOCK_NAME = "quizopia-auth-refresh";

export async function withBrowserAuthLock<T>(
  operation: () => Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  if (typeof navigator === "undefined" || navigator.locks === undefined) {
    return operation();
  }

  return signal === undefined
    ? navigator.locks.request(REFRESH_LOCK_NAME, operation)
    : navigator.locks.request(REFRESH_LOCK_NAME, { signal }, operation);
}
