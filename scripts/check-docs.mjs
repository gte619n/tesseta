#!/usr/bin/env node
// docs-lint: guards against known documentation regressions — stale claims that
// were reconciled against the code. Fails (exit 1) if any forbidden phrase
// reappears, or a required doc goes missing. Run from the repo root:
//   node scripts/check-docs.mjs
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.cwd();

// Phrases that must NOT appear in tracked docs (they were false and fixed).
const FORBIDDEN = [
  { pattern: /Room (is an unused|as a dead|is a dead)/i, why: 'Room is the offline-first mirror (ADR-0007), not unused' },
  { pattern: /workout logging is deferred/i, why: 'active workout logging shipped (ADR-0012)' },
  { pattern: /the \*one\* documented exception/i, why: 'there are two Gemini Pro exceptions (ADR-0005, ADR-0013)' },
  { pattern: /Do not use any other Gemini model/i, why: 'policy is now "no new model without an ADR"' },
];

// Docs that must exist.
const REQUIRED = [
  "docs/requirements/README.md",
  "docs/requirements/privacy-and-compliance.md",
];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name.startsWith(".")) continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (/\.(md|mdx)$/.test(name)) out.push(p);
  }
  return out;
}

let failures = 0;

for (const rel of REQUIRED) {
  if (!existsSync(join(ROOT, rel))) {
    console.error(`✗ missing required doc: ${rel}`);
    failures++;
  }
}

const docFiles = [
  ...walk(join(ROOT, "docs")),
  join(ROOT, "README.md"),
  join(ROOT, "CLAUDE.md"),
  join(ROOT, "android", "CLAUDE.md"),
  join(ROOT, "web", "CLAUDE.md"),
  join(ROOT, "backend", "CLAUDE.md"),
].filter((p) => existsSync(p));

for (const file of docFiles) {
  const text = readFileSync(file, "utf8");
  for (const { pattern, why } of FORBIDDEN) {
    if (pattern.test(text)) {
      console.error(`✗ ${file.replace(ROOT + "/", "")}: forbidden phrase ${pattern} — ${why}`);
      failures++;
    }
  }
}

if (failures > 0) {
  console.error(`\ndocs-lint failed with ${failures} issue(s).`);
  process.exit(1);
}
console.log(`docs-lint passed (${docFiles.length} files, ${REQUIRED.length} required docs present).`);
