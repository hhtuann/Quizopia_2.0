import { defineConfig } from "@playwright/test";
import { resolve as resolvePath } from "node:path";

/**
 * W4 real-browser acceptance: the disposable real runtime (Gateway, Identity, Assessment,
 * Classroom, RabbitMQ, Redis, PostgreSQL) is started by the owning topology harness, which
 * also starts this frontend dev server and exports W4_REAL_* endpoint variables. Nothing
 * here starts or owns backend processes.
 */
function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value || !/^https?:\/\/127\.0\.0\.1:\d+$/.test(value)) {
    throw new Error(`${name} must point at the disposable loopback runtime`);
  }
  return value;
}

const frontendUrl = process.env.W4_REAL_FRONTEND_URL ?? "http://127.0.0.1:3100";

export default defineConfig({
  testDir: "./tests/w4-real-e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 30_000 },
  outputDir: resolvePath("../target/w4-acceptance-topology/playwright-results"),
  preserveOutput: "failures-only",
  use: { baseURL: frontendUrl },
  reporter: [["line"]],
  projects: [{ name: "chromium-w4-real", use: { browserName: "chromium" } }],
});

export const w4Real = {
  gatewayUrl: () => requireEnv("W4_REAL_GATEWAY_URL"),
  frontendUrl,
};
