#!/usr/bin/env node
/**
 * Guard: every App Router page must declare a title so navigating to it always
 * updates document.title. Fails (exit 1) if any `app/**​/page.tsx` is missing
 * both `metadata` and `generateMetadata`.
 *
 * Use the `pageMetadata` / `absoluteTitle` / `entityMetadata` helpers in
 * `lib/page-metadata.ts`. Run via `pnpm check:titles` (and in CI).
 */
import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = join(fileURLToPath(import.meta.url), "..", "..");
const appDir = join(webRoot, "app");

/** @returns {string[]} absolute paths to every page.tsx under app/ */
function findPages(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...findPages(full));
    else if (entry.name === "page.tsx") out.push(full);
  }
  return out;
}

const DECLARES_TITLE =
  /export\s+(const\s+metadata|(async\s+)?function\s+generateMetadata|const\s+generateMetadata)\b/;

const offenders = findPages(appDir).filter(
  (file) => !DECLARES_TITLE.test(readFileSync(file, "utf8")),
);

if (offenders.length > 0) {
  console.error(
    "✗ These pages are missing a title (export `metadata` or `generateMetadata`):\n",
  );
  for (const file of offenders) console.error(`  - ${relative(webRoot, file)}`);
  console.error(
    "\nAdd one using the helpers in lib/page-metadata.ts, e.g.:\n" +
      '  export const metadata = pageMetadata("Goals");\n',
  );
  process.exit(1);
}

console.log("✓ All pages declare a title.");
