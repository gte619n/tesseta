#!/usr/bin/env node
// Web bundle-size probe. After `pnpm build` in web/, reports the gzipped size
// of the shared first-load JS (root main chunks) and flags the largest client
// chunks. Records the before/after for any web bundle slice (target: initial
// JS <= 200 KB gzipped).
//
// Usage:  (cd web && pnpm build) && node infra/perf/web-bundle.mjs web/.next

import { readFileSync, readdirSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const NEXT = process.argv[2] || "web/.next";

const manifest = JSON.parse(readFileSync(join(NEXT, "build-manifest.json"), "utf8"));
const gz = (f) => gzipSync(readFileSync(join(NEXT, f))).length;

const rootMain = manifest.rootMainFiles || [];
const rootTotal = rootMain.reduce((a, f) => a + gz(f), 0);
console.log(`# shared first-load JS (rootMainFiles): ${rootMain.length} files, ${Math.round(rootTotal / 1024)} KB gzipped`);
console.log(`  target: <= 200 KB gzipped initial\n`);

// Largest client chunks by gzipped size.
const chunkDir = join(NEXT, "static", "chunks");
const chunks = readdirSync(chunkDir)
  .filter((f) => f.endsWith(".js"))
  .map((f) => ({ f, gz: gzipSync(readFileSync(join(chunkDir, f))).length }))
  .sort((a, b) => b.gz - a.gz)
  .slice(0, 12);
console.log("# largest client chunks (gzipped):");
for (const c of chunks) console.log(`  ${Math.round(c.gz / 1024)} KB\t${c.f}`);
