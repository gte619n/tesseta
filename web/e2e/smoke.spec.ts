import { expect, test } from "@playwright/test";

// Phase-0 sample: proves the Playwright harness boots the Next.js app and can
// drive a real browser. Unauthenticated, the app should serve a page (the
// sign-in surface) rather than error. Richer auth/a11y specs land in Phase 5.
test("serves the app shell unauthenticated", async ({ page }) => {
  const response = await page.goto("/");
  expect(response?.status() ?? 0).toBeLessThan(400);
  // A document with a non-empty <title> renders (sign-in or a redirect target).
  await expect(page).toHaveTitle(/.+/);
});

test("sends security headers on every response", async ({ page }) => {
  const response = await page.goto("/");
  const headers = response?.headers() ?? {};
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  expect(headers["strict-transport-security"]).toContain("max-age=");
  expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
});
