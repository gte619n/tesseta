import { defineConfig, devices } from "@playwright/test";

// Full-browser E2E + accessibility specs. The web server boots `next dev` with
// a dummy BACKEND_URL / auth secret so pages render without a live backend;
// specs that need backend data should route-mock in the test.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
  webServer: {
    command: "pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:9999",
      AUTH_SECRET: process.env.AUTH_SECRET ?? "playwright-test-secret-0123456789",
      AUTH_URL: "http://localhost:3000",
    },
  },
});
