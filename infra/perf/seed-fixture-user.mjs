#!/usr/bin/env node
// Seeds a fixture user with ~1 year of realistic data for the 24h-offline
// full-sync benchmark. Writes to the Firestore emulator by default so it never
// touches prod. Emits the change-journal entries too (slice 5) when --journal
// is passed, so the journal-backed sync reader has data to page through.
//
// Usage:
//   FIRESTORE_EMULATOR_HOST=localhost:8080 \
//     node infra/perf/seed-fixture-user.mjs --uid=perf-fixture [--days=365] [--journal]
//
// Safety: refuses to run without FIRESTORE_EMULATOR_HOST set unless
// --i-know-this-is-prod is passed, so a stray run can't seed production.

import { Firestore } from "@google-cloud/firestore";

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? true];
  }),
);
const UID = args.uid || "perf-fixture";
const DAYS = parseInt(args.days || "365", 10);
const WITH_JOURNAL = !!args.journal;

if (!process.env.FIRESTORE_EMULATOR_HOST && !args["i-know-this-is-prod"]) {
  console.error(
    "Refusing to run: FIRESTORE_EMULATOR_HOST is not set. Seed the emulator, " +
      "or pass --i-know-this-is-prod if you really mean a real database.",
  );
  process.exit(2);
}

const db = new Firestore({
  projectId: process.env.GCLOUD_PROJECT || "health-fitness-160",
});
const userRef = db.collection("users").doc(UID);

// Deterministic pseudo-random so runs are reproducible (no Math.random).
let s = 1234567;
const rnd = () => ((s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const round = (n, d = 1) => Math.round(n * 10 ** d) / 10 ** d;

const start = new Date("2025-09-16T12:00:00Z");
const iso = (d) => d.toISOString();
const dayKey = (d) => d.toISOString().slice(0, 10);

let writes = 0;
let batch = db.batch();
const journalCol = userRef.collection("changeJournal");
let seq = 0;
async function put(ref, data, collection, id) {
  const ts = new Date(start.getTime() - (DAYS - writes) * 60000);
  batch.set(ref, { ...data, updatedAt: ts, syncStatus: "ACTIVE" });
  if (WITH_JOURNAL) {
    batch.set(journalCol.doc(String(seq).padStart(10, "0")), {
      seq: seq++,
      collection,
      docId: id,
      tombstone: false,
      updatedAt: ts,
    });
  }
  if (++writes % 400 === 0) {
    await batch.commit();
    batch = db.batch();
  }
}

for (let i = 0; i < DAYS; i++) {
  const d = new Date(start.getTime() - i * 86400000);
  const dk = dayKey(d);

  // daily metric (weight/sleep) — the heavy time-series
  await put(
    userRef.collection("dailyMetrics").doc(dk),
    { date: dk, weightKg: round(82 + Math.sin(i / 20) * 2 + rnd()), sleepMinutes: 380 + Math.floor(rnd() * 90) },
    "dailyMetrics",
    dk,
  );
  // nutrition day + 4 entries
  await put(userRef.collection("nutritionDailyLogs").doc(dk), { date: dk }, "nutritionDailyLogs", dk);
  for (let m = 0; m < 4; m++) {
    const id = `${dk}_e${m}`;
    await put(
      userRef.collection("nutritionDays").doc(dk).collection("entries").doc(id),
      { meal: ["breakfast", "lunch", "dinner", "snack"][m], kcal: 300 + Math.floor(rnd() * 500) },
      "nutritionDays/entries",
      `${dk}/${id}`,
    );
  }
  // 3 workouts a week
  if (i % 2 === 0) {
    const id = `${dk}_push`;
    await put(
      userRef.collection("workouts").doc(id),
      { date: dk, durationSeconds: 2400 + Math.floor(rnd() * 1800) },
      "workouts",
      id,
    );
  }
}
await batch.commit();
console.log(`seeded uid=${UID} days=${DAYS} → ${writes} docs${WITH_JOURNAL ? " (+journal)" : ""}`);
