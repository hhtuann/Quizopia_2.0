import { defineConfig } from "@playwright/test";
const origin = process.env.W4F_DEMO_FRONTEND;
if (!origin || !/^http:\/\/127\.0\.0\.1:\d+$/.test(origin))
  throw new Error("Private demo requires the owned loopback harness");
export default defineConfig({
  testDir: "./tests/w4f-demo",
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 30_000 },
  reporter: "line",
  use: { baseURL: origin, browserName: "chromium", trace: "off" },
});
