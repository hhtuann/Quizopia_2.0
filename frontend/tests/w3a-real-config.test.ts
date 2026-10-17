import { createServer } from "node:net";
import { describe, expect, it } from "vitest";
import {
  assertFrontendPortFree,
  resolveW3aRealFrontendEnvironment,
} from "./w3a-real-e2e/w3a-real-config";

describe("W3A Real Frontend Configuration", () => {
  it("resolves default loopback port 3000 and strictly enforces reuseExistingServer: false", () => {
    const config = resolveW3aRealFrontendEnvironment({});
    expect(config).toEqual({
      host: "localhost",
      port: 3000,
      baseURL: "http://localhost:3000",
      gatewayURL: "http://localhost:8080",
      webServerCommand:
        "pnpm exec next dev --webpack --hostname localhost --port 3000",
      reuseExistingServer: false,
    });
  });

  it("dynamically aligns webServer command port with custom W3A_REAL_FRONTEND_URL", () => {
    const config = resolveW3aRealFrontendEnvironment({
      W3A_REAL_FRONTEND_URL: "http://localhost:38291",
      W3A_REAL_GATEWAY_URL: "http://localhost:55695",
    });
    expect(config.port).toBe(38291);
    expect(config.host).toBe("localhost");
    expect(config.baseURL).toBe("http://localhost:38291");
    expect(config.gatewayURL).toBe("http://localhost:55695");
    expect(config.webServerCommand).toBe(
      "pnpm exec next dev --webpack --hostname localhost --port 38291",
    );
    expect(config.reuseExistingServer).toBe(false);
  });

  it("resolves gatewayURL from W3A_REAL_STATE_DIRECTORY state.json if W3A_REAL_GATEWAY_URL not set", () => {
    const fs = require("node:fs");
    const os = require("node:os");
    const path = require("node:path");
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "w3a-config-test-"));
    try {
      fs.writeFileSync(
        path.join(tempDir, "state.json"),
        JSON.stringify({ endpoints: { gateway: "http://127.0.0.1:54321" } }),
      );
      const config = resolveW3aRealFrontendEnvironment({
        W3A_REAL_STATE_DIRECTORY: tempDir,
      });
      expect(config.gatewayURL).toBe("http://localhost:54321");
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("supports 127.0.0.1 loopback binding", () => {
    const config = resolveW3aRealFrontendEnvironment({
      W3A_REAL_FRONTEND_URL: "http://127.0.0.1:4000",
    });
    expect(config.host).toBe("127.0.0.1");
    expect(config.port).toBe(4000);
    expect(config.webServerCommand).toBe(
      "pnpm exec next dev --webpack --hostname 127.0.0.1 --port 4000",
    );
  });

  it("rejects non-loopback hosts to prevent unintended network binding", () => {
    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "http://0.0.0.0:3000",
      }),
    ).toThrow("W3A_REAL_FRONTEND_URL host must be a loopback address");

    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "http://192.168.1.50:3000",
      }),
    ).toThrow("W3A_REAL_FRONTEND_URL host must be a loopback address");

    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "http://example.com:3000",
      }),
    ).toThrow("W3A_REAL_FRONTEND_URL host must be a loopback address");
  });

  it("rejects invalid or out-of-range ports", () => {
    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "http://localhost:0",
      }),
    ).toThrow("invalid port");

    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "http://localhost:70000",
      }),
    ).toThrow(/invalid.*port|Invalid W3A_REAL_FRONTEND_URL/i);

    expect(() =>
      resolveW3aRealFrontendEnvironment({
        W3A_REAL_FRONTEND_URL: "not-a-valid-url",
      }),
    ).toThrow("Invalid W3A_REAL_FRONTEND_URL");
  });

  it("assertFrontendPortFree passes on an unoccupied loopback port", async () => {
    // Probe an available ephemeral port
    const probe = createServer();
    await new Promise<void>((resolve) =>
      probe.listen(0, "127.0.0.1", () => resolve()),
    );
    const port = (probe.address() as { port: number }).port;
    await new Promise<void>((resolve) => probe.close(() => resolve()));

    await expect(
      assertFrontendPortFree(port, "127.0.0.1"),
    ).resolves.toBeUndefined();
  });

  it("assertFrontendPortFree fails closed when port is occupied by another process", async () => {
    const existingServer = createServer();
    await new Promise<void>((resolve) =>
      existingServer.listen(0, "127.0.0.1", () => resolve()),
    );
    const occupiedPort = (existingServer.address() as { port: number }).port;

    try {
      await expect(
        assertFrontendPortFree(occupiedPort, "127.0.0.1"),
      ).rejects.toThrow(
        /Port \d+ on 127\.0\.0\.1 is already in use by another process.*failing closed.*Do not kill another listener/i,
      );
    } finally {
      await new Promise<void>((resolve) =>
        existingServer.close(() => resolve()),
      );
    }
  });
});
