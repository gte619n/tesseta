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

// E2E-11 (reachable-surface portion): the global app shell must not overflow
// horizontally at phone width. The authenticated Overview itself is verified by
// construction (grid-cols-1 default, lg:grid-cols-2) + RTL component tests
// (decision IL-9); this guards the shared shell the Overview renders inside.
test("app shell has no horizontal overflow at phone width", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1); // sub-pixel tolerance
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
