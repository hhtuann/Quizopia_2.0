import { describe, expect, it } from "vitest";
import { realExecutorEnvironment } from "./real-executor-e2e/runner-environment.mjs";

describe("real executor runner environment", () => {
  it("preserves the default browser Origin and loopback binding", () => {
    expect(realExecutorEnvironment({})).toEqual({
      host: "127.0.0.1",
      port: 3000,
      baseURL: "http://localhost:3000",
      resolverRule: "MAP localhost:3000 127.0.0.1",
    });
  });

  it("isolates the harness on IPv6 without remapping backend ports", () => {
    expect(realExecutorEnvironment({ LD05_REAL_EXECUTOR_HOST: "::1" })).toEqual(
      {
        host: "::1",
        port: 3000,
        baseURL: "http://localhost:3000",
        resolverRule: "MAP localhost:3000 [::1]",
      },
    );
  });

  it("uses the same explicit port for server and browser", () => {
    expect(
      realExecutorEnvironment({ LD05_REAL_EXECUTOR_PORT: "3100" }),
    ).toMatchObject({
      port: 3100,
      baseURL: "http://localhost:3100",
      resolverRule: "MAP localhost:3100 127.0.0.1",
    });
  });

  it.each(["", "0", "65536", "3000.5", "3000junk", "-1", " 3000"])(
    "rejects invalid port %j without falling back",
    (port) => {
      expect(() =>
        realExecutorEnvironment({ LD05_REAL_EXECUTOR_PORT: port }),
      ).toThrow("LD05_REAL_EXECUTOR_PORT");
    },
  );

  it.each(["", "localhost", "0.0.0.0", "::", "example.test"])(
    "rejects non-loopback or ambiguous host %j",
    (host) => {
      expect(() =>
        realExecutorEnvironment({ LD05_REAL_EXECUTOR_HOST: host }),
      ).toThrow("LD05_REAL_EXECUTOR_HOST");
    },
  );
});
