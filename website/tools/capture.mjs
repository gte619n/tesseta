// Dev-only: sign in via the UAT dev route, then screenshot each app route.
// Uses Playwright from web/node_modules. Boot mock-backend + web dev first.
import { mkdirSync } from "node:fs";
import { createRequire } from "node:module";
// Playwright is resolved from the web app's install (../../web).
const require = createRequire(new URL("../../web/package.json", import.meta.url));
const { chromium } = require("@playwright/test");

const BASE = process.env.WEB_BASE ?? "http://localhost:3001";
const OUT = process.env.OUT ?? "/tmp/shots";
mkdirSync(OUT, { recursive: true });

// Routes to capture: [slug, path, fullPage?]
const ROUTES = (process.env.ROUTES ?? "").trim()
  ? JSON.parse(process.env.ROUTES)
  : [
      ["dashboard", "/", false],
      ["blood", "/me/blood", true],
      ["body-composition", "/me/body-composition", true],
      ["meds", "/me/meds", true],
      ["nutrition", "/me/nutrition", true],
      ["workouts-programs", "/me/workouts/programs", true],
      ["goals", "/me/goals", true],
      ["profile", "/me/profile", true],
    ];

const b = await chromium.launch();
const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));

// --- Dev sign-in ---
await page.goto(`${BASE}/auth/dev`, { waitUntil: "networkidle" });
await page.fill('[data-testid="dev-userId"]', "demo-user");
await page.fill('[data-testid="dev-email"]', "demo@tesseta.com");
await page.fill('[data-testid="dev-name"]', "Alex Rivera");
await Promise.all([
  page.waitForURL((u) => !u.pathname.startsWith("/auth"), { timeout: 20000 }).catch(() => {}),
  page.click('[data-testid="dev-signin-submit"]'),
]);
console.log("signed in, at", page.url());

// Hide Next.js dev overlay/indicator so it never appears in marketing shots.
await ctx.addInitScript(() => {
  const css = "nextjs-portal,#__next-build-watcher,[data-nextjs-dialog],[data-nextjs-toast]{display:none !important}";
  const inject = () => {
    const s = document.createElement("style");
    s.textContent = css;
    document.documentElement.appendChild(s);
  };
  if (document.documentElement) inject();
  else document.addEventListener("DOMContentLoaded", inject);
});

for (const [slug, path, fullPage] of ROUTES) {
  try {
    await page.goto(`${BASE}${path}`, { waitUntil: "networkidle", timeout: 30000 });
    await page.waitForTimeout(800);
    await page.addStyleTag({ content: "nextjs-portal{display:none !important}" }).catch(() => {});
    await page.screenshot({ path: `${OUT}/${slug}.png`, fullPage: !!fullPage });
    console.log("OK  ", slug, "->", page.url());
  } catch (e) {
    console.log("FAIL", slug, String(e).split("\n")[0]);
  }
}

if (errors.length) console.log("PAGE ERRORS:", [...new Set(errors)].slice(0, 10));
await b.close();
