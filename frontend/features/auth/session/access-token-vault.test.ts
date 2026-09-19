import { describe, expect, it } from "vitest";
import { createAccessTokenVault } from "./access-token-vault";

describe("access token vault", () => {
  it("keeps and replaces the token only in runtime memory", () => {
    const vault = createAccessTokenVault();

    expect(vault.read()).toBeNull();

    vault.replace("first-access-token");
    expect(vault.read()).toBe("first-access-token");

    vault.replace("replacement-access-token");
    expect(vault.read()).toBe("replacement-access-token");

    vault.clear();
    expect(vault.read()).toBeNull();
  });

  it("does not use an empty string as a token or implicit clear operation", () => {
    const vault = createAccessTokenVault();

    expect(() => vault.replace("")).toThrow(TypeError);
    expect(vault.read()).toBeNull();
  });
});
