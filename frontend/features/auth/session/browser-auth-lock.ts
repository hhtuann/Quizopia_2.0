const REFRESH_LOCK_NAME = "quizopia-auth-refresh";

export async function withBrowserAuthLock<T>(
  operation: () => Promise<T>,
): Promise<T> {
  if (typeof navigator === "undefined" || navigator.locks === undefined) {
    return operation();
  }

  return navigator.locks.request(REFRESH_LOCK_NAME, operation);
}
