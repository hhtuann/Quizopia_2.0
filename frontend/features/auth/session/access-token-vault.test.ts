import { describe, expect, it } from "vitest";
import { createAccessTokenVault } from "./access-token-vault";

describe("access token vault", () => {
  it("keeps explicit operation cancellation separate from token replacement and replay fencing", () => {
    const vault = createAccessTokenVault();
    const listener = () => {
      notifications += 1;
    };
    let notifications = 0;
    const unsubscribe = vault.subscribeOperations(listener);
    expect(vault.invalidateOperations()).toBe(1);
    vault.startSession("access");
    vault.replace("renewed");
    expect(vault.getOperationGeneration()).toBe(1);
    expect(vault.readSnapshot().sessionGeneration).toBe(1);
    expect(vault.invalidateOperations()).toBe(2);
    expect(vault.read()).toBe("renewed");
    expect(notifications).toBe(2);
    unsubscribe();
    vault.invalidateOperations();
    expect(notifications).toBe(2);
  });
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
