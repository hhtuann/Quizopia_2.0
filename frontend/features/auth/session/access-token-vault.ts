export interface AccessTokenVault {
  clear(): void;
  read(): string | null;
  replace(accessToken: string): void;
}

export function createAccessTokenVault(): AccessTokenVault {
  let accessToken: string | null = null;

  return Object.freeze({
    clear() {
      accessToken = null;
    },
    read() {
      return accessToken;
    },
    replace(nextAccessToken: string) {
      if (nextAccessToken.length === 0) {
        throw new TypeError("Access token must not be empty.");
      }

      accessToken = nextAccessToken;
    },
  });
}
