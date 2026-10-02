import { defineConfig, devices } from "@playwright/test";

/**
 * Browser tests against the running compose stack (`make up`).
 * They drive the real dashboard, which calls the real services.
 */
export default defineConfig({
  testDir: "tests/e2e",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    // 127.0.0.1, not localhost: another local dev server on ::1:3000 would otherwise win
    baseURL: process.env.DASHBOARD_URL ?? "http://127.0.0.1:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
