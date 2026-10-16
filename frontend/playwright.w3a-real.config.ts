import { defineConfig, devices } from "@playwright/test";
import { tmpdir } from "node:os";
import { join } from "node:path";

// The operator starts a revision-pinned, isolated frontend and backend fixture.
// Never reuse/start an unrelated developer server from this acceptance suite.
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
    baseURL: "http://localhost:3000",
    trace: "off",
    screenshot: "off",
    video: "off",
    actionTimeout: 15_000,
  },
  projects: [
    { name: "chromium-w3a-real", use: { ...devices["Desktop Chrome"] } },
  ],
});
