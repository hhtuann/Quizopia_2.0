import { defineConfig, devices } from "@playwright/test";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { resolveW3aRealFrontendEnvironment } from "./tests/w3a-real-e2e/w3a-real-config";

// The operator starts a revision-pinned, isolated frontend and backend fixture.
// Strict ownership: fail closed, never reuse/start an unrelated developer server.
const frontendEnv = resolveW3aRealFrontendEnvironment();

export default defineConfig({
  testDir: "./tests/w3a-real-e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: "./tests/w3a-real-e2e/sanitized-reporter.ts",
  outputDir: join(tmpdir(), `quizopia-w3a-real-browser-${process.pid}`),
  preserveOutput: "never",
  use: {
    baseURL: frontendEnv.baseURL,
    trace: "off",
    screenshot: "off",
    video: "off",
    actionTimeout: 15_000,
  },
  webServer: {
    command: frontendEnv.webServerCommand,
    url: frontendEnv.baseURL,
    env: {
      NEXT_PUBLIC_API_URL: frontendEnv.gatewayURL,
      NEXT_TELEMETRY_DISABLED: "1",
    },
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    { name: "chromium-w3a-real", use: { ...devices["Desktop Chrome"] } },
  ],
});
