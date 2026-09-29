export interface AccessTokenSnapshot {
  readonly accessToken: string | null;
  readonly sessionGeneration: number;
}

export interface AccessTokenVault {
  clear(): void;
  read(): string | null;
  readSnapshot(): AccessTokenSnapshot;
  replace(accessToken: string): void;
  startSession(accessToken: string): void;
}

export function createAccessTokenVault(): AccessTokenVault {
  let accessToken: string | null = null;
  let sessionGeneration = 0;

  function requireToken(nextAccessToken: string) {
    if (nextAccessToken.length === 0) {
      throw new TypeError("Access token must not be empty.");
    }
  }

  return Object.freeze({
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
