# IMPL-PERF-01 — Performance Hardening

> Status: **planned** · Created 2026-09-13 · Source: audit run
> [`docs/audit/2026-09-13/`](../audit/2026-09-13/INDEX.md), findings PERF-001…009,
> COST-001. Measured prod baseline (Cloud Monitoring, 7d): backend p50 1.34s /
> p95 3.15s / startup p95 15.7s; web p50 228ms / startup 1.7s.

Five workstreams, independently shippable. A and B are the two structural
cliffs (both audit-critical); C–E are highs/mediums that ride behind them.
Constraint binding on all workstreams: **ADR-0021** — per-user access stays
path-scoped (`users/{uid}/…`), no `collectionGroup` queries. Where a fix sketch
in the audit offered a collectionGroup variant, this plan takes the
fence-respecting alternative.

---

## Workstream A — Sync delta read amplification (PERF-001, critical)

**Problem.** `FirestoreSyncChangeReader.readChanges` calls
`leafCollections(...)` per subcollection per page; `leafCollections`
(`FirestoreSyncChangeReader.java:308-329`) enumerates **every parent doc the
user owns** via unbounded `listDocuments()`, then `scan(...)` issues one
sequential query per leaf collection (`:287-296`). `nutritionDays` contributes
one parent per calendar day forever; the 14-day first-sync window filters
*after* the read (`:176-179`). Per-sync cost grows linearly with account age.

**Design decisions.**
1. **Cursor-bounded parent enumeration** (not collectionGroup — ADR-0021).
   `nutritionDays` doc IDs are ISO dates and an entry's `updatedAt` only moves
   forward, so a delta sync with cursor T only needs day-docs whose entries
   could have changed after T. Maintain a per-user high-water map or, simpler,
   bound enumeration to `date >= (T - slack)` where slack covers
   backdated-meal edits (the app allows editing past days — pick slack = the
   UI's max backdate range, then add a low-frequency full-scan fallback: every
   Nth sync, or when cursor is null, do the full enumeration).
2. **Push the first-sync window into the query**: replace post-read
   `windowAllows` filtering for heavy collections with a
   `whereGreaterThanOrEqualTo("updatedAt", windowFloor)` clause (single-field
   index; verify `firestore.indexes.json` — audit confirmed current coverage
   complete, this adds no composite).
3. Same treatment for `goalChatThreads/messages` (two-level frontier) — bound
   by thread `updatedAt` ≥ cursor before descending into messages.

**Implementation steps.**
1. Add a Firestore RPC counter (test-scoped interceptor or gRPC client logs)
   and a seeded fixture: dev-database user with 730 `nutritionDays` docs +
   entries. Record baseline RPCs and wall time for
   `SyncService.page(userId, null, 500)` and a no-change delta page.
2. Implement the date-floor bound in `leafCollections` for `nutritionDays`
   (cursor → floor derivation in `SyncService`); keep full enumeration when
   cursor is null *and* window is active (first sync already payload-capped).
3. Move `windowAllows` into the query for `nutritionDays/entries`, `workouts`,
   and other window-eligible collections; keep the in-app check as a belt.
4. Apply the thread-`updatedAt` bound to `goalChatThreads/messages`.
5. Regression: run the existing core-data `SyncEnginePullTest` /
   `ConvergenceTest` suites against the new backend behavior via the emulator
   tests (`TestPersistenceConfig` fakes won't exercise this — use the 13
   emulator tests as the harness; add one asserting a backdated-edit beyond
   slack still syncs via the fallback scan).

**Verification.** Seeded 730-day fixture: no-change delta page must drop from
~730+ RPCs to < 30; wall time < 500ms. Sync protocol unchanged (no client
release needed). Watch prod backend p95 for a step down.

**Effort:** 3–5 days. **Risk:** medium (sync correctness — the fallback scan
is the safety valve). **Rollback trigger:** any client-visible missing-entry
report or emulator-suite failure → revert commit; protocol unchanged so
revert is clean.

---

## Workstream B — Cold start & compute config (PERF-002 critical + COST-001 high, joint decision)

**Problem.** No `min-instances` on either service; backend starts in 15.7s
(p95, measured) as plain `java -jar` (no CDS/AOT, `backend/Dockerfile:28`).
First visit of the day ≈ 1.7s web cold + 15.7s backend cold + 10-call
dashboard fan-out ≈ 18–20s. Meanwhile `--no-cpu-throttling` +
sync keepalive bills 0.643 avg instances ≈ **$81/mo of mostly-idle compute**
(COST-001). The two fixes interact: pick the config once.

**Decision matrix (operator picks one — recommendation: Option 2).**

| Option | Config | First-visit latency | ~Monthly cost delta | Notes |
|---|---|---|---|---|
| 0 do-nothing | as-is | 18–20s daily | $0 (keeps paying ~$81 idle) | worst of both |
| 1 cheap | drop `--no-cpu-throttling`, keep scale-to-zero, add CDS | ~6–8s daily (CDS-only startup ~4-5s) | **−$75/mo** | cold start still exists, much shorter |
| 2 **recommended** | drop `--no-cpu-throttling`, add `--min-instances=1` (throttled idle), add CDS | warm always (~1.3s p50) | ≈ −$40 to −$55/mo net (throttled idle instance is billed per-request CPU + idle memory only) | best latency AND still cheaper than today |
| 3 latency-max | keep `--no-cpu-throttling` + `--min-instances=1` | warm always | ≈ +$0 vs today (keeps full idle CPU billing) | only if background CPU between requests is truly needed |

Pre-check for options 1–2 (the Chesterton fence from the audit): grep backend
for post-response async work still relying on always-on CPU
(`runAsync`/fire-and-forget outside the Cloud Tasks rail — the Withings
webhook's virtual-thread import is one; list and either move to Cloud Tasks or
accept throttled-CPU slowdown for them). The audit judged the rationale
"half-obsolete"; verify the remaining half before flipping.

**Implementation steps.**
1. Inventory post-response async sites (grep `runAsync|Thread.startVirtualThread|@Async`)
   → classify: rail-migrated / harmless-if-throttled / needs-migration.
2. Spring Boot CDS in `backend/Dockerfile`: layered-jar extraction + training
   run (`java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar …`)
   then runtime `-XX:SharedArchiveFile=app.jsa`. Target startup < 5s.
3. Apply chosen flags in `backend/cloudbuild.yaml` (and optionally
   `--min-instances=1` on web — cheap at 512Mi).
4. Optional (pairs with D9 work): keep a Cloud Monitoring eye on
   `container/startup_latencies` and billable-instance time for a week.

**Verification.** `startup_latencies` p95 < 5s after CDS; billable instance
time drops per the chosen option; one end-to-end cold-morning dashboard load
timed before/after.

**Effort:** 0.5d flags + 1–2d CDS + 0.5d async inventory.
**Rollback trigger:** webhook/async regressions after throttling → restore
`--no-cpu-throttling` (single flag, instant); CDS startup crash → drop the
`SharedArchiveFile` flag.

---

## Workstream C — Nutrition capture pipeline (PERF-004, high)

**Problem.** `/api/nutrition/capture/meal` and `/capture/label` hold a request
thread through a synchronous Gemini call (180s timeout) with the raw upload
(≤30MB) on-heap; concurrency 16 × 1.3GiB heap has a documented prior OOM.

**Implementation steps.**
1. Server-side downscale before Gemini/GCS: max edge 1536px, JPEG q≈85, in the
   upload path (streaming decode; never buffer the original longer than the
   resize). Gemini gains nothing from 30MP originals. (Independent, ship first.)
2. Move both endpoints onto the existing Cloud Tasks durable rail
   (same 202 + job-id + settle-poll/FCM shape as `describe-meal-async` — the
   rail, idempotency, and android op-rail already exist; this is the last
   sync-path holdout). Client change: android capture flow already handles the
   async shape for describe-meal — mirror it.
3. Measurement first (per audit prompt): a week of `generateContent` P50/P95 +
   multipart size histogram from request logs to size the win — cheap since
   Workstream B's monitoring is in place.

**Verification.** Heap headroom under 8 concurrent synthetic 20MB captures
(local); no OOM restarts in prod logs; capture UX unchanged on device.
**Effort:** 2–3 days. **Rollback:** endpoints keep their sync variant behind a
config flag for one release.

---

## Workstream D — All-users batch jobs (PERF-003, high; trigger-gated)

**Problem.** Four jobs iterate `findAllUserIds()` serially with per-user
external HTTP inside fixed 600–900s task timeouts; kills mid-loop at ~200–450
connected users, no checkpoint, alphabetically-late users starve.
`findAllUserIds` itself is a full-collection enumeration
(`UserRepository.java:264-274`).

**Trigger to build: >25 connected users** (before that, do nothing — cost is
zero today). Design when triggered:
1. Store integration flags queryable (`googleHealth.connected == true`) so
   jobs enumerate only relevant users (`whereEqualTo`, path-scoped — no
   full-user scan).
2. Bounded-parallel execution: virtual-thread executor, concurrency ~8,
   existing per-user try/catch preserved.
3. Checkpoint doc per job (`jobs/{name}.lastProcessedUserId`) so a timeout
   retry resumes instead of restarting; alternatively fan out one Cloud Tasks
   task per user (choose after measuring per-user cost — audit prompt has the
   measurement: read execution durations from Cloud Logging, divide timeout).

**Effort:** 2–3 days when triggered. **Verification:** kill a job mid-run in
dev; rerun resumes past the checkpoint.

---

## Workstream E — Query & payload hygiene (PERF-005/-006/-007/-008, mediums)

Batchable one-day-each items; do opportunistically after A/B.

1. **PERF-005** — window `/api/me/blood` (default 2y) and
   `/api/me/body-composition` (default 90d) with `from`/`to` params; push v1
   `updatedSince`/date filters into the Firestore query
   (`whereGreaterThanOrEqualTo` + same-field `orderBy`, single-field index).
   Update web dashboard loaders to pass windows.
2. **PERF-008** — add `.limit()` caps to the unbounded queries enumerated in
   the audit (`FirestoreStepRepository.findAllStepsForUser`,
   `FirestoreGoalChatRepository.java:108`, `LocationRepository.java:115-129`,
   `FirestoreOAuthGrantStore.java:65,76`, `FirestoreFcmTokenRepository.java:60`).
   Keep the goals→phases→steps walk path-scoped (ADR-0021); reduce RPCs by
   fetching phases+steps per goal concurrently (virtual threads) rather than
   restructuring documents. Full restructure only if Workstream D's
   measurements show the tree walk dominating.
3. **PERF-006** — only after A+B land: either a single `/api/me/dashboard`
   aggregate endpoint (one hop, backend composes the ~8 reads) or short
   `revalidate` on stable cards. It's a multiplier fix, not a root cause.
4. **PERF-007** — lower `userById` TTL 5min→60s (one line in
   `CacheConfig.java`). Do **not** add Redis. Defer entirely while
   `instance_count` max stays ≤2.

**PERF-009** (day-rollup transaction): no change — the full-recompute design is
the correct robustness choice under LWW. Add the log-based metric on Firestore
transaction-retry warnings (rides on the D9 observability work) and only act if
drink-session burst logging fires it.

---

## Sequencing & verification summary

```
Week 1:  A (sync reader) ──────────┐
         B decision + flags + CDS ─┤→ re-measure prod p50/p95/startup + billing
Week 2:  C resize (C async rail after) 
Later:   E items opportunistically; D at >25 connected users; PERF-007 at >2 instances
```

Success criteria against the measured baseline: backend startup p95 15.7s →
<5s; no-change delta sync page RPCs ~730 → <30 on the seeded fixture; idle
compute spend −$40+/mo; zero capture OOMs. Re-run the audit's baseline
commands (`docs/audit/2026-09-13/baseline.json` has each) after landing A+B.
