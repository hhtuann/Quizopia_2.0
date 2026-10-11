export interface AccessTokenSnapshot {
  readonly accessToken: string | null;
  readonly sessionGeneration: number;
}

export interface AccessTokenVault {
  getOperationGeneration(): number;
  invalidateOperations(): number;
  subscribeOperations(listener: () => void): () => void;
  clear(): void;
  read(): string | null;
  readSnapshot(): AccessTokenSnapshot;
  replace(accessToken: string): void;
  startSession(accessToken: string): void;
}

export function createAccessTokenVault(): AccessTokenVault {
  let accessToken: string | null = null;
  let sessionGeneration = 0;
  // Explicit intent/cancellation fences asynchronous auth work. Token generation
  // separately fences protected-request replay; routine bootstrap completion or
  // token rotation must not cancel a login waiting for that same bootstrap.
  let operationGeneration = 0;
  const operationListeners = new Set<() => void>();

  function requireToken(nextAccessToken: string) {
    if (nextAccessToken.length === 0) {
      throw new TypeError("Access token must not be empty.");
    }
  }

  return Object.freeze({
    getOperationGeneration() {
      return operationGeneration;
    },
    invalidateOperations() {
      operationGeneration += 1;
      operationListeners.forEach((listener) => listener());
      return operationGeneration;
    },
    subscribeOperations(listener: () => void) {
      operationListeners.add(listener);
      return () => operationListeners.delete(listener);
    },
    clear() {
      accessToken = null;
      sessionGeneration += 1;
    },
    read() {
      return accessToken;
    },
    readSnapshot() {
      return Object.freeze({ accessToken, sessionGeneration });
    },
    replace(nextAccessToken: string) {
      requireToken(nextAccessToken);
      accessToken = nextAccessToken;
    },
    startSession(nextAccessToken: string) {
      requireToken(nextAccessToken);
      sessionGeneration += 1;
      accessToken = nextAccessToken;
    },
  });
}
