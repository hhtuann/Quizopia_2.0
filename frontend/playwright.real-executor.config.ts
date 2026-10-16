import { defineConfig, devices } from "@playwright/test";
import { realExecutorEnvironment } from "./tests/real-executor-e2e/runner-environment.mjs";

const environment = realExecutorEnvironment(process.env);

export default defineConfig({
  fullyParallel: false,
  reporter: "list",
  testDir: "./tests/real-executor-e2e",
  timeout: 180_000,
  use: {
    baseURL: environment.baseURL,
    launchOptions: {
      args: [`--host-resolver-rules=${environment.resolverRule}`],
    },
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium-real-executor",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
