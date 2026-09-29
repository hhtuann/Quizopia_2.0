import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  fullyParallel: false,
  reporter: "list",
  testDir: "./tests/real-e2e",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  webServer: {
    command: "pnpm dev",
    env: { NEXT_PUBLIC_API_URL: "http://localhost:8080" },
    reuseExistingServer: true,
    url: "http://localhost:3000",
  },
  projects: [{ name: "chromium-real", use: { ...devices["Desktop Chrome"] } }],
});
