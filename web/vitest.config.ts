import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Unit/component tests for the web app. jsdom gives DOM APIs for Testing
// Library; Playwright (see playwright.config.ts) owns full-browser E2E.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["**/*.{test,spec}.{ts,tsx}"],
    // e2e/ is Playwright's; never let Vitest pick those specs up.
    exclude: ["node_modules/**", ".next/**", "e2e/**"],
    coverage: {
      provider: "v8",
      reporter: ["text", "json", "html"],
      reportsDirectory: "./coverage",
      // Cover the testable pure logic; UI wiring is exercised by Playwright.
      include: ["lib/**", "components/**", "app/**"],
    },
  },
  resolve: {
    // Mirror tsconfig "@/*" -> "./*".
    alias: { "@": fileURLToPath(new URL("./", import.meta.url)) },
  },
});
