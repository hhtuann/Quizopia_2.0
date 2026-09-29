import { describe, expect, it } from "vitest";
import { createAccessTokenVault } from "./access-token-vault";

describe("access token vault", () => {
  it("keeps and replaces the token only in runtime memory", () => {
    const vault = createAccessTokenVault();

    expect(vault.read()).toBeNull();
    expect(vault.readSnapshot()).toEqual({
      accessToken: null,
      sessionGeneration: 0,
    });

    vault.startSession("first-access-token");
    expect(vault.read()).toBe("first-access-token");
    expect(vault.readSnapshot().sessionGeneration).toBe(1);

    vault.replace("replacement-access-token");
    expect(vault.read()).toBe("replacement-access-token");
    expect(vault.readSnapshot().sessionGeneration).toBe(1);

    vault.clear();
    expect(vault.read()).toBeNull();
    expect(vault.readSnapshot().sessionGeneration).toBe(2);
  });

  it("does not use an empty string as a token or implicit clear operation", () => {
    const vault = createAccessTokenVault();

    expect(() => vault.replace("")).toThrow(TypeError);
    expect(() => vault.startSession("")).toThrow(TypeError);
    expect(vault.read()).toBeNull();
  });
});
