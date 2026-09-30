import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  fullyParallel: false,
  reporter: "list",
  testDir: "./tests/real-executor-e2e",
  timeout: 180_000,
  use: {
    baseURL: "http://localhost:3000",
    launchOptions: {
      args: ["--host-resolver-rules=MAP localhost 127.0.0.1"],
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
