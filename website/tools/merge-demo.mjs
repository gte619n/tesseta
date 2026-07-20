// Merge per-domain demo JSON into demo-data.json (specialist files win shared keys),
// and normalize nutrition macro subtotals/totals so no negative/inconsistent numbers show.
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const dir = dirname(fileURLToPath(import.meta.url));
const demoDir = join(dir, "demo");

// Order matters: later overrides earlier. misc first, specialists after.
const order = ["misc", "workouts", "blood", "bodycomp", "meds", "nutrition", "goals"];
const files = readdirSync(demoDir).filter((f) => f.endsWith(".json"));
const merged = {};
for (const name of order) {
  const f = `${name}.json`;
  if (!files.includes(f)) continue;
  const obj = JSON.parse(readFileSync(join(demoDir, f), "utf8"));
  Object.assign(merged, obj);
}

// --- Normalize nutrition day macros from entries ---
const MK = ["caloriesKcal", "proteinGrams", "carbsGrams", "fatGrams", "fiberGrams", "sugarGrams"];
const round = (n) => Math.round(n * 10) / 10;
for (const key of Object.keys(merged)) {
  const v = merged[key];
  if (v && v.meals && Array.isArray(v.meals)) {
    const dayTot = Object.fromEntries(MK.map((k) => [k, 0]));
    for (const meal of v.meals) {
      const sub = Object.fromEntries(MK.map((k) => [k, 0]));
      for (const e of meal.entries ?? []) {
        for (const k of MK) sub[k] += Number(e.macros?.[k] ?? 0) * (e.quantity ?? 1);
      }
      for (const k of MK) { sub[k] = round(sub[k]); dayTot[k] += sub[k]; }
      meal.subtotal = sub;
    }
    for (const k of MK) dayTot[k] = round(dayTot[k]);
    v.totals = dayTot;
  }
}

// --- Scrub external image URLs (next/image rejects unconfigured hosts) ---
// The app renders a clean placeholder when image fields are null, which reads
// fine for screenshots. Null any http(s) string so no host config is needed.
function scrub(node) {
  if (Array.isArray(node)) return node.map(scrub);
  if (node && typeof node === "object") {
    for (const k of Object.keys(node)) {
      if (typeof node[k] === "string" && /^https?:\/\//.test(node[k])) node[k] = null;
      else node[k] = scrub(node[k]);
    }
  }
  return node;
}
scrub(merged);

writeFileSync(join(dir, "demo-data.json"), JSON.stringify(merged, null, 2));
console.log("merged keys:", Object.keys(merged).length);
console.log(Object.keys(merged).sort().join("\n"));
