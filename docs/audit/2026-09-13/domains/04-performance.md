# D4 — Performance & Scalability Audit (PERF)

Date: 2026-09-13 · Auditor: D4 · Repo: tesseta monorepo (worktree `sess_bef801a7`)
Prod telemetry: Cloud Monitoring read-only REST (ADC, `x-goog-user-project: health-fitness-160`) — **succeeded**; numbers below are real 7-day prod values, not estimates.

## BLUF

The system is healthy for one user but has two structural cliffs. (1) The `/api/me/sync` delta reader re-enumerates **every parent document the user has ever created** (notably one `nutritionDays/{date}` doc per day, forever) on **every sync page**, issuing one Firestore query per day-doc — per-sync cost grows linearly with account age, and the 14-day first-sync window is applied *after* the Firestore read, so it caps payload, not reads. (2) Measured prod: backend request p50 **1.34s** / p95 **3.15s**, backend container-startup p95 **15.7s**, on ~6.2k requests/week — with `min-instances` absent from both deploys and no CDS/AOT, the first morning visit pays web cold (~1.7s) + backend JVM cold (~15.7s) + a 10-call dashboard fan-out. Secondary: four `findAllUserIds()` batch jobs iterate all users **serially** inside fixed 600–900s Cloud Run Job task timeouts (breaks at low hundreds of users); the two nutrition-capture endpoints block a request thread on Gemini with a 180s timeout while holding up-to-30MB unresized images on the heap. Firestore composite-index coverage was verified against `infra/firestore/firestore.indexes.json` and is **complete** for every shipped `where`+`orderBy` combination found — a genuine non-finding. Android sync hygiene (single Room transaction per page, all-suspend DAOs, 14-day gated first sync, tiny Wear payloads) is good.

Measured baseline (7 days ending 2026-09-13, Cloud Monitoring `run.googleapis.com/*`):

| Service | p50 | p95 | startup p95 | requests/7d |
|---|---|---|---|---|
| health-fitness-backend | 1,342 ms | 3,153 ms | 15,749 ms | 6,158 |
| health-fitness-web | 228 ms | 850 ms | 1,692 ms | 3,437 |

---

## Findings

### PERF-001 — Sync delta read fan-out grows unboundedly with account age (per-page parent enumeration) — **Critical** · [Certain] mechanism / [Likely] magnitude

**Evidence:**
- `backend/src/main/java/com/gte619n/healthfitness/persistence/sync/FirestoreSyncChangeReader.java:174-203` — every call to `readChanges` loops all 15 `TOP_LEVEL` collections and all 7 `SUBCOLLECTIONS`, and for each subcollection calls `leafCollections(...)`.
- `FirestoreSyncChangeReader.java:308-329` — `leafCollections` enumerates parent docs with `parent.collection(parentCollection).listDocuments()` — "listDocuments() returns an Iterable of child doc refs directly (it is itself the blocking enumeration)" — with **no cursor bound and no limit**; the frontier is every parent doc the user owns.
- `FirestoreSyncChangeReader.java:144` — `new Subcollection(List.of("nutritionDays"), "entries", "nutritionDays/entries")`: the parent set is one doc per calendar day with any food logged. `goals/phases/steps` (line 142) is a two-level frontier (goals × phases).
- `FirestoreSyncChangeReader.java:287-296` — `scan(...)` then issues **one query per leaf collection** (`q.limit(limit + 1).get()`), i.e. one Firestore RPC per nutrition day-doc per page, executed sequentially via `await(...)`.
- Window is post-read: `FirestoreSyncChangeReader.java:176-179` fetches docs first, then `if (!windowAllows(name, doc, window)) continue;` — the 14-day first-sync bound (`SyncService.java:62-66`, `windowAllows` at 371-379) filters **after** the `limit+1` docs are already read from Firestore.
- Read amplification: `SyncService.java:76` requests `limit + 1` (default 500 → 501, `SyncService.java:33`) and the reader fetches up to 501 docs from *each* scanned collection, then truncates the merged list to 500 (`FirestoreSyncChangeReader.java:218-222`) — worst-case docs-read ÷ docs-emitted ≈ number of scanned leaf collections.

**Impact:** For a 2-year-old account, `nutritionDays` alone contributes ~730 parent refs → ~730 sequential leaf queries **per sync page, on every delta sync**, even when the cursor makes every query return zero rows. At ~5–20 ms/RPC that is ~4–15 s per page [Likely, arithmetic]; measured backend p95 of 3.15s at day ~N is consistent with the early part of this curve. Firestore billing also scales with enumeration + per-query minimum reads. Every Android foreground sync and FCM-triggered wakeup pays this.

**Do-nothing cost (R6):** sync latency and Firestore read spend grow ~linearly with days-of-use per user, multiplied by user count and sync frequency; at some account age Android sync exceeds its HTTP timeout and devices stop converging.

**Effort:** 3–5 days. **Autonomy:** high (pure backend refactor, protocol unchanged). 
**Fix sketch / prompt:** *"In FirestoreSyncChangeReader, replace per-parent `listDocuments()` enumeration for `nutritionDays/entries` (and `goalChatThreads/messages`) with a single per-user collectionGroup query filtered by a stored `userId` field on the leaf docs (add the field + composite index `(userId ASC, updatedAt ASC)` COLLECTION_GROUP in infra/firestore/firestore.indexes.json), or bound the parent enumeration by cursor (`nutritionDays` doc IDs are dates: only enumerate day-docs with date ≥ cursor-derived floor, since an entry's `updatedAt` can only move forward). Also push the recent-window date bound into the Firestore query for heavy collections instead of the in-app `windowAllows` filter. FIRST MEASUREMENT: seed a test user with 730 nutritionDays docs in a dev database, time `SyncService.page(userId, cursor, 500)` and count Firestore RPCs (wrap Firestore with a counting interceptor or enable gRPC client logs)."*

---

### PERF-002 — Double cold start: no min-instances, 15.7s measured JVM startup, first-visit dashboard chain — **Critical** · [Certain] (measured)

**Evidence:**
- `backend/cloudbuild.yaml` deploy step (lines ~40–75) and `web/cloudbuild.yaml` deploy step: `grep -n "min-instances"` over both files returns **nothing** — both services scale to zero. Backend sets `--memory=2Gi --cpu=2 --no-cpu-throttling --concurrency=16`; web sets no resource flags (Cloud Run defaults).
- `backend/Dockerfile:28` — `ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65.0 -jar /app/app.jar"]` — plain `java -jar`; no Spring AOT, no CDS archive (`Dockerfile` has no training-run/`-XX:SharedArchiveFile`/`spring.aot` step).
- Measured (Cloud Monitoring, 7d): backend `container/startup_latencies` p95 = **15,748 ms**; web = 1,692 ms. Request p50/p95: backend 1,342/3,153 ms; web 228/850 ms. Traffic: 6,158 backend requests over 7 days (~37/hr average) — far below what keeps a scale-to-zero instance warm, so cold starts recur daily.
- `web/app/page.tsx:30` — `export const dynamic = "force-dynamic";` — the dashboard is re-rendered server-side on every visit, and each render fans out ~10 backend HTTP calls (see PERF-006), so the first visit serializes: web cold start → auth → 10 calls into a backend that is itself cold.

**Impact:** First morning visit ≈ 1.7s (web cold) + 15.7s (backend cold, first proxied call) + warm-path render ≈ **~18–20s to a populated dashboard** [Likely — chain arithmetic from measured components; not measured end-to-end]. Same cliff hits the Android first sync of the day and FCM-triggered background syncs (which can time out against a cold backend — cross-ref the fcm-push memory).

**Do-nothing cost (R6):** every new user's first impression is a ~20s blank dashboard; Android sync retries against cold starts waste battery/requests; worsens as Gemini/feature weight grows the JAR.

**Effort:** 0.5 day (flags) + 1–2 days (CDS/AOT). **Autonomy:** high, but the min-instances flag has a real dollar cost the operator must approve.
**Fix sketch / prompt:** *"(a) Add `--min-instances=1` to the backend deploy in backend/cloudbuild.yaml (note: combined with the existing `--no-cpu-throttling`, an idle min-instance bills CPU continuously — check the pricing calculator; consider dropping `--no-cpu-throttling` now that image generation runs on Cloud Tasks, or scope always-on CPU carefully). (b) Enable Spring Boot CDS: extract the layered jar and do a training run in the Dockerfile (`java -XX:ArchiveClassesAtExit`), or adopt `spring.aot`/CRaC — target startup <5s. (c) Optionally `--min-instances=1` on web too (cheap, default 512Mi). FIRST MEASUREMENT: already measured — startup p95 15.7s; re-measure `container/startup_latencies` after CDS to confirm the win."*

---

### PERF-003 — Four batch jobs iterate all users serially inside fixed 600–900s task timeouts — **High** · [Certain] pattern / [Likely] break-point

**Evidence:**
- `backend/src/main/java/com/gte619n/healthfitness/persistence/user/UserRepository.java:264-274` — `findAllUserIds()` walks `firestore.collection(COLLECTION).listDocuments()` (full collection enumeration).
- Serial callers, each `for (String userId : users.findAllUserIds())` with no parallelism:
  - `core/goals/eval/StepEvaluationService.java:122-144` (`reevaluateAllSustained`) — per user calls `steps.findAllSustained(userId)` which triggers the goals→phases→steps triple-nested walk (PERF-008), plus metric reads per step.
  - `api/googlehealth/GoogleHealthRefreshService.java:54-82` — per connected user: 1 Firestore read + `bodyCompositionBackfill.runBackfill(userId, windowDays)` + `dailyMetricBackfill.runBackfill(...)` + external Google API HTTP calls.
  - `api/googlehealth/GoogleHealthHealthCheckService.java:37-61` — per user: 1 read + `tokens.accessTokenFor(userId)` (external HTTP token exchange).
  - `api/withings/WithingsRefreshService.java:42-63` — per connected user: 1 read + `backfill.runBackfill(...)` + Withings HTTP calls.
- Fixed budgets: `infra/scripts/deploy-goals-sustained-job.sh:51` — `--task-timeout=600`; `infra/scripts/deploy-gh-refresh-job.sh:56` — `--task-timeout=900`.

**Impact:** gh-refresh does OAuth refresh + two windowed API pulls + Firestore upserts per user — plausibly 2–5s/user serial [Likely]. At ~180–450 connected users the 900s task timeout kills the job mid-loop; users after the cutoff silently never refresh (the loop has no checkpoint/resume — it restarts from user #1 on retry). The Withings refresh-token *rotation* makes a mid-flight kill worse (burned rotation, cross-ref withings memory). Sustained-goal re-eval hits the same wall at 600s.

**Do-nothing cost (R6):** invisible today (1 user); at growth, integration data goes stale for an alphabetically-late cohort with no error surfaced to them.

**Effort:** 2–3 days. **Autonomy:** high.
**Fix sketch / prompt:** *"Convert the four findAllUserIds() loops to either (a) bounded-parallel execution (virtual-thread executor, concurrency ~8, per-user try/catch already exists) plus a checkpoint (persist last-processed userId so a retry resumes), or (b) fan out one Cloud Tasks task per user. Only enumerate users with the relevant integration connected (store a `googleHealth.connected` flag queryable via whereEqualTo instead of findById-per-user). FIRST MEASUREMENT: read the Cloud Run Job execution durations for gh-refresh/goals-sustained-reeval from Cloud Logging (ADC REST) to get today's per-user cost; divide task-timeout by it for the real cliff N."*

---

### PERF-004 — Nutrition capture blocks request threads on Gemini (180s timeout) with unresized ≤30MB images on-heap — **High** · [Certain] code path / [Likely] limits math

**Evidence:**
- `backend/src/main/java/com/gte619n/healthfitness/integrations/config/GeminiConfig.java:43` — `@Value("${app.gemini.timeout-ms:180000}") int timeoutMs`.
- `api/nutrition/NutritionCaptureController` — `meal()` (line ~48-50) does `byte[] bytes = readBytes(photo)` then `capture.analyzeMeal(userId, bytes, …)` which blocks on `client.models.generateContent(...)` (`MealPhotoExtractor.java:161`); same for `analyzeLabel` (`NutritionLabelExtractor.java:92`). The HTTP response is held for the full Gemini round-trip.
- `backend/src/main/resources/application.yml:8-14` — `max-file-size: 30MB / max-request-size: 30MB`; no resize anywhere: `MealPhotoExtractor.java:153-154` sends `Part.fromBytes(imageBytes, contentType)` (full original), `MealPhotoStorage.store():56` writes the original to GCS.
- Capacity context: `backend/cloudbuild.yaml` `--concurrency=16`, `--memory=2Gi`; `backend/Dockerfile:22-27` caps heap at 65% ≈ 1.3GiB and the comment itself records the prior OOM-kill history ("the default 512Mi OOM-kills the instance mid-analysis").

**Impact:** One capture holds a thread + several×image-size heap (original bytes + base64 copy for Gemini + response buffers) for up to 180s. ~10 concurrent 10–30MB captures on one instance approaches the 1.3GiB heap [Likely, arithmetic] — the failure mode already happened once at 512Mi. Also pure UX: user watches a spinner for the full Gemini latency; the Android op-rail (memory: nutrition-durable-op-rail) makes it survivable but not fast. The async describe rail (`describe-meal(-async)`) exists — these two endpoints are the stragglers.

**Do-nothing cost (R6):** capture latency = Gemini latency (seconds to minutes at tail); a burst of captures (several users at dinner time) risks instance OOM → orphaned ANALYZING entries again.

**Effort:** 2–3 days. **Autonomy:** high.
**Fix sketch / prompt:** *"Move /api/nutrition/capture/meal and /capture/label onto the existing Cloud Tasks durable-job rail (store photo to GCS, return 202 + job id, deliver result via the existing settle-poll/FCM path — same shape as describe-meal-async). Independently, downscale server-side before Gemini and GCS (e.g. max edge 1536px JPEG ~85 — Gemini gains nothing from 30MP originals). FIRST MEASUREMENT: log P50/P95 of the generateContent call and the multipart size histogram for a week (or extract from existing request logs) to size the win before refactoring."*

---

### PERF-005 — Full-history payload endpoints: `/api/me/blood` and body-composition serve unwindowed history — **Medium** · [Certain]

**Evidence:**
- `api/blood/BloodController:50-54` — `readings.findByUser(userId)` returns **all** blood readings; no pagination or date window on the first-party endpoint. Consumed by the dashboard on every render: `web/lib/blood-panel.ts:71` — `apiJson<BloodReadingApi[]>("/api/me/blood")` — plus `/api/me/blood/reports` (line 72).
- `api/v1/V1LabsController:89` — v1 also does `blood.findByUser(userId)` then filters in memory before paginating (`V1Page.paginate`, line 95-98): pagination caps the response, **not the Firestore read**.
- Body composition: `web/lib/body-composition-dashboard.ts:37` — `apiJson<Reading[]>("/api/me/body-composition")`; v1 `V1LabsController:168` — `bodyComposition.findByUser(userId)` unwindowed. (Dashboard `BiometricsService` correctly uses a 60-day window — `BiometricsService:36-37`; daily-metrics v1 defaults to 90 days — `V1LabsController:149`.)

**Impact:** Payload and Firestore read cost grow with lab/scale history; a daily-weigh-in user accumulates ~365 body-comp docs/metric/year, all serialized on every `force-dynamic` dashboard render. Not acute today; a slow-burn tax on every page view.

**Do-nothing cost (R6):** dashboard TTFB and backend read spend creep upward forever; v1 API reads whole collections to serve a 50-item page.

**Effort:** 1–2 days. **Autonomy:** high.
**Prompt:** *"Add `from`/`to` (default e.g. 2y for blood, 90d for body-comp) to /api/me/blood and /api/me/body-composition, and push the v1 updatedSince/date filters into the Firestore query (whereGreaterThanOrEqualTo + orderBy on the same field — single-field index, no new composite needed). FIRST MEASUREMENT: log response byte sizes for these endpoints (or check Cloud Run response_size metric filtered by path via log-based metric) against the owner account's real history."*

---

### PERF-006 — Dashboard fan-out: ~10 backend hops per force-dynamic render — **Medium** · [Certain] count / [Likely] latency attribution

**Evidence:** `web/app/page.tsx:30` (`force-dynamic`) renders sections that call: `/api/me/body-composition` (coalesced once via `const loadBodyCompositionCached = cache(loadBodyComposition)` — page.tsx:101), `/api/me` (`web/lib/biometrics-api.ts:25-27`, `cache()`-wrapped), daily-metrics (`web/lib/dashboard-vitals.ts:1` → `fetchDailyMetrics`), nutrition day (`web/lib/nutrition-dashboard.ts:21-26`), workout summary ×2 (`web/lib/workout-dashboard.ts:17-21` — `getWorkoutHistorySummary()` + `listPrograms()`), blood ×2 (`web/lib/blood-panel.ts:71-72`), doses (`page.tsx:183`), recent feed (`web/lib/recent-feed.ts:47`) ≈ **10 backend calls/render**. Mitigations already present and correct: per-section `<Suspense>` so cards stream independently (page.tsx:44-83), React `cache()` dedupe of session (`web/lib/api.ts:20`) and shared loaders.

**Impact:** Warm-path measured web p50 228 ms is fine; the fan-out matters mainly because (a) each call is a Cloud Run→Cloud Run hop that multiplies backend request count (10× page views — visible in the 6.1k vs 3.4k weekly request split) and (b) against a cold backend the *first* call eats the 15.7s startup and the other nine queue behind it (PERF-002).

**Do-nothing cost (R6):** acceptable; grows linearly with dashboard cards. Backend request billing ~10× page views.

**Effort:** 1 day. **Autonomy:** high.
**Prompt:** *"Optional consolidation: add a single `/api/me/dashboard` aggregate endpoint (backend composes the 8 reads server-side, one hop), or set a short `revalidate` on stable sections instead of force-dynamic. Do this only after PERF-001/002 — it is a multiplier fix, not a root cause. FIRST MEASUREMENT: Cloud Trace or timing logs per section loader on one cold + one warm render."*

---

### PERF-007 — Per-instance Caffeine caches: cross-instance staleness after writes; memory fine at 1k users — **Medium** · [Certain] design / [Likely] harm

**Evidence:** `backend/src/main/java/com/gte619n/healthfitness/config/CacheConfig.java:52-65` — `userById` 5 min/5,000 max, `userHealthSnapshot` 60 s/5,000, `exerciseDigest` 60 s/5,000, `drugById` 5 min/2,000, `drugCatalog` 5 min/16; "Mutations evict explicitly via @CacheEvict" (CacheConfig.java:37) — but eviction is in-process only; with `--concurrency=16` (backend/cloudbuild.yaml) any burst >16 concurrent requests spawns a second instance whose caches don't see the eviction.

**Per-cache harm assessment:** `userById` — worst case: profile/settings/integration-state edits (e.g. hiddenBiometrics, googleHealth connect) served stale up to 5 min on the other instance; auth-adjacent fields (admin allowlist is env-based, OK). `userHealthSnapshot`/`exerciseDigest` — 60s TTL, advisory chat context: harmless. Drug catalog — near-static: harmless. No correctness-critical path (sync reads Firestore directly). **Memory:** 1k users ≈ 1k entries/cache; user docs and snapshots are KB-scale → single-digit MB total against a 1.3GiB heap [Likely, arithmetic] — not a concern; the 5,000 maxSize caps it regardless.

**Do-nothing cost (R6):** occasional "my settings change didn't take" reports once traffic regularly exceeds one instance; zero cost until then.

**Effort:** 0.5 day. **Autonomy:** high.
**Prompt:** *"Lower `userById` TTL to 60s (matching the others), or key invalidation off Firestore updatedAt (read-through with a compare). Do NOT add Redis for this. FIRST MEASUREMENT: Cloud Monitoring `container/instance_count` — if max concurrent instances is still 1–2, defer."*

---

### PERF-008 — Goals triple-nested N+1 (goals→phases→steps) with no limits, also multiplied by the daily job — **Medium** · [Certain]

**Evidence:** `persistence/goals/FirestoreStepRepository.java:109-132` — `findAllStepsForUser`: query all `goals` (no limit, line 113), then per goal all `phases` (line 117), then per phase all `steps` (line 122) — 1 + G + Σphases sequential queries. Same shape in `findByGoal` (lines 64-84). Called per-user by `StepEvaluationService.reevaluateAllSustained` (`StepEvaluationService.java:129`) — this nests inside the PERF-003 all-users loop. Related unbounded reads: `FirestoreGoalChatRepository.java:108` (`messages(...).get()` — all messages before tombstoning), `LocationRepository.java:115-129` (collectionGroup `whereArrayContains("equipmentIds", …)` no limit), `FirestoreOAuthGrantStore.java:65,76`, `FirestoreFcmTokenRepository.java:60`.

**Impact:** Bounded by per-user goal counts (small: ~10s of queries/user), so latency is minor today; the cost is multiplicative under the batch job (users × goals × phases sequential RPCs) and each unbounded query is a latent footgun if a collection grows (chat threads especially).

**Do-nothing cost (R6):** contributes to the PERF-003 timeout cliff; chat-thread delete cost grows with thread length.

**Effort:** 1–2 days. **Autonomy:** high.
**Prompt:** *"Add `.limit(...)` caps to the unbounded queries listed; for findAllStepsForUser consider a single per-user collectionGroup steps query (field overrides for steps already exist in infra/firestore/firestore.indexes.json — add `userId` to step docs to keep user-scoping without cross-user scan, mirroring the PERF-001 fix). MEASUREMENT: count queries via Firestore client metrics for one reevaluateAllSustained run."*

---

### PERF-009 — Day-rollup transaction re-reads all of a day's entries on every entry write — **Low** · [Certain]

**Evidence:** `persistence/nutrition/FirestoreNutritionDailyLogRepository.java:78-95` — `recomputeFromEntries` runs a transaction that does `txn.get(entriesRef)` (**all** entries for the day) + rollup read + rollup write to `users/{u}/nutritionDailyLogs/{yyyy-MM-dd}`, on every food-entry mutation. Also `FirestoreGoalChatRepository.appendMessage:75-83` writes the thread doc's `updatedAt` on every message.

**Impact:** The hotspot doc is per-user-per-day, so Firestore's ~1 write/sec/doc guidance is only threatened by a single user logging very rapidly (drink-mode taps, bulk meal edits, or the leftovers flow re-writing many ingredients) — transaction contention/retries, not cross-user. Read cost is O(entries-per-day) per write: fine (≤ ~50 docs). The full-recompute design is actually the *correct* robustness choice given the LWW sync model — flagged for awareness, not change.

**Do-nothing cost (R6):** negligible until a burst-write feature (drink sessions) meets a slow transaction; symptom would be ABORTED/retried transactions in logs.

**Effort:** 0 (monitor). **Autonomy:** n/a.
**Prompt/measurement:** *"Add a log-based metric on Firestore transaction retry warnings; only act if it fires during drink-session use."*

---

### PERF-010 — Compose recomposition hygiene on the two biggest screens — **Low** · [Likely] (static heuristics only)

**Evidence (spot-checks):**
- `android/feature-workouts/.../session/WorkoutSessionScreen.kt:630-636` — per-page inline lambdas `onAdjust = { adjustment -> onAdjust(step.key, adjustment) }`, `onToggleSet`, `onEditSet`, `onLogTimed`, `onLogSet` all recreate per recomposition capturing `step.key`.
- `WorkoutSessionScreen.kt:646` — `onAutoStartConsumed = { if (autoStartStep == page) autoStartStep = null }` captures mutable parent state.
- `WorkoutSessionScreen.kt:654-655` — pager scroll lambdas capturing `scope`/`pagerState` recreate per SessionBody recomposition.
- `android/feature-nutrition/.../NutritionTodayComponents.kt:205` — `entries.forEach { entry -> … EntryRow(entry = entry, …) }` — non-lazy, un-keyed iteration; any list change recomposes every row.

**Impact:** Unverified without recomposition counts; the workout screen already had one state-gate bug fixed (rest-timer memory), suggesting recomposition churn is real but tolerable on modern hardware.

**Do-nothing cost (R6):** minor jank risk on low-end devices; no functional risk.
**Effort:** 1 day. **Autonomy:** high.
**Prompt:** *"FIRST MEASUREMENT: enable Layout Inspector recomposition counts (or `Modifier.recomposeHighlighter()`) on WorkoutSessionScreen while ticking the rest timer — count ExercisePage recompositions per second. If >~2/s, hoist the per-step lambdas with `remember(step.key)` and key the nutrition entry rows."*

---

### Verified non-findings (report as green)

- **Firestore composite indexes: complete.** [Certain] Every shipped `where`+`orderBy` combination found in persistence/ maps to an entry in `infra/firestore/firestore.indexes.json`: `bloodReadings(marker,sampleDate DESC)` ↔ `BloodReadingRepository.findLatestByMarker:61-77`; `bodyComposition(metric,sampleTime DESC)` ↔ `BodyCompositionRepository.findLatest:93-106`; `scheduled(status,date ASC)` + `(status,date DESC)` ↔ `deletePlannedFrom`/`latestDateByStatus`; `progressionObservations(exerciseId,completedAt)`; field overrides for `steps.metric.metricKey`, `steps.kind`, `medications.drugId`, `locations.equipmentIds` (CONTAINS). Pure single-field order/range queries (`dailyMetrics.date`, sync's `updatedAt` scans, chat `createdAt`/`updatedAt`) need only auto indexes, as the file's own `_comment` (fieldOverrides block) correctly argues. Residual process risk: deploy is a manual script (`infra/scripts/deploy-firestore-indexes.sh`) — a forgotten run on a fresh DB is a runtime 500, not a perf issue (belongs to D-ops).
- **Sync paging is bounded and resumable.** `SyncService.java:33-36` (default 500, max 1000), limit+1 hasMore trick, cursor tiebreak no-dup (`SyncService.java:74-96`); Android applies each page in one Room transaction (`android/core-data/.../sync/SyncEngine.kt:126-135`) and persists the cursor per page.
- **Android first sync is gated and windowed.** `android/app/.../sync/FirstSyncGate.kt:126-131` (`RECENT_WINDOW_DAYS = 14`), splash gate only on genuine first run (`MainActivity.kt:301-328`).
- **Room main-safety:** all DAOs suspend/Flow, no `allowMainThreadQueries` (core-data DAOs, e.g. `SyncStateDao.kt`).
- **Wear payloads are trivial:** auth-token-only Data Layer messages (`PhoneTokenPublisher.kt:30` ~1KB; `RefreshRequestPublisher.kt:21` 0 bytes).
- **Generated food images** get `cache-control: public, max-age=31536000, immutable` (`FoodImageStorage:66`) and exercise media has a thumbnail Cloud Function (`infra/scripts/deploy-thumbnail-fn.sh`, `functions/exercise-thumbnails/`). Gap: **meal photos** are stored full-size with no cache-control and no thumbnail path (`MealPhotoStorage.store():54-56`, public URL at :110) — folded into PERF-004's resize fix. No CDN anywhere [Certain — no CDN config in infra/terraform]; acceptable at this scale, note GCS egress pricing if mobile image traffic grows.

---

## Scaling-cliffs table

| System | Breaks at ≈ N | Symptom | Basis |
|---|---|---|---|
| `/api/me/sync` parent enumeration (PERF-001) | account age ~1–2 yr (≈400–730 `nutritionDays` docs), any user count | sync p95 → 5–15 s/page, then client HTTP timeouts; Firestore read bill grows per sync | [Certain] mechanism, [Likely] arithmetic |
| Cold-start chain (PERF-002) | now (measured) | ~18–20 s first dashboard of the day; Android background sync timeouts | [Certain] components measured; chain [Likely] |
| `gh-refresh` serial job vs `--task-timeout=900` (PERF-003) | ~180–450 connected users @2–5 s/user | job killed mid-loop; alphabetically-late users never refreshed; Withings rotation burns | [Likely] arithmetic |
| `goals-sustained-reeval` vs `--task-timeout=600` (PERF-003/008) | ~500–2,000 users depending on goals/user | same kill-mid-loop; goal statuses stale | [Likely] |
| Nutrition capture concurrency (PERF-004) | ~8–12 concurrent 10–30 MB captures per instance | heap exhaustion → per-task OOM (best case) or instance churn; 180 s thread occupancy | [Likely] arithmetic vs 1.3 GiB heap; prior incident documented in Dockerfile comment |
| Caffeine per-instance staleness (PERF-007) | sustained >16 concurrent requests (2nd instance) | settings edits invisible up to 5 min on other instance | [Certain] design, [Likely] harm |
| `nutritionDailyLogs/{date}` rollup doc (PERF-009) | ~1 write/s by a single user on one day (burst logging) | Firestore transaction retries/latency on food logging | [Likely] |
| Cloud Tasks nutrition queue | not a near cliff — rate/concurrency are explicitly configured (`bootstrap-nutrition-jobs.sh:37-48`, values env-driven) | backlog delay if MAX_CONCURRENT set low vs capture volume | [Certain] configurable / values not in repo defaults |
| Full-history endpoints (PERF-005) | years of data (hundreds of docs/serialization) | dashboard TTFB creep; v1 reads whole collection per page | [Certain] pattern |

---

## HYPOTHESES (unverifiable here — measurement plans)

1. **H1 — Sync page latency at 2-year account age is >5 s.** Measure: seed 730 `nutritionDays` day-docs (+1 entry each) for a test uid in the dev database; time `GET /api/me/sync?limit=500` cold and with a fresh cursor; count RPCs via gRPC client logging. (Backs PERF-001 magnitude.)
2. **H2 — End-to-end first-morning dashboard is ~18–20 s.** Measure: synthetic check (Cloud Scheduler + curl with `-w %{time_total}`) at 06:00 after idle night against `app.tesseta.com`, capture web+backend `container/startup_latencies` correlation for the same minute.
3. **H3 — gh-refresh per-user wall time is 2–5 s.** Measure: Cloud Logging (ADC REST) query on the gh-refresh job's execution logs: total duration ÷ connected users; today N=1 so instrument a per-user timing log line first.
4. **H4 — Capture endpoint heap high-water ~3–4× image size per request.** Measure: enable `-XX:NativeMemoryTracking=summary`/heap histogram or a simple `Runtime.totalMemory` log around `analyzeMeal` with a 20 MB test photo.
5. **H5 — Warm backend p50 excluding cold starts is well under 1.34 s** (i.e., the measured p50 is startup-polluted). Measure: re-query `request_latencies` grouped by `response_code_class` with alignment 1h and correlate spikes with `startup_latencies` points; or filter out the first request per instance via log-based metric on `instanceId`.
6. **H6 — Gemini p95 for meal analysis dominates capture UX (>8 s).** Measure: add a timer metric around `generateContent` in MealPhotoExtractor; one week of data.

---

```json
[
  {"id":"PERF-001","title":"Sync delta reader enumerates all parent docs (nutritionDays et al.) per page; cost grows with account age; 14-day window filters post-read","severity":"critical","confidence":"certain-mechanism/likely-magnitude","files":["backend/src/main/java/com/gte619n/healthfitness/persistence/sync/FirestoreSyncChangeReader.java:174-203","backend/src/main/java/com/gte619n/healthfitness/persistence/sync/FirestoreSyncChangeReader.java:308-329","backend/src/main/java/com/gte619n/healthfitness/core/sync/SyncService.java:62-96"],"effort_days":4,"autonomy":"high","null_option_cost":"per-sync latency and Firestore reads grow linearly with days-of-use x users x sync frequency; eventual client sync timeouts"},
  {"id":"PERF-002","title":"Double cold start: no min-instances, measured 15.7s JVM startup p95, no CDS/AOT; backend p50 1.34s/p95 3.15s","severity":"critical","confidence":"certain-measured","files":["backend/cloudbuild.yaml","web/cloudbuild.yaml","backend/Dockerfile:28","web/app/page.tsx:30"],"effort_days":2,"autonomy":"high-with-cost-approval","null_option_cost":"~20s first dashboard daily per user; Android sync timeout retries"},
  {"id":"PERF-003","title":"findAllUserIds batch jobs iterate all users serially inside fixed 600-900s task timeouts, no checkpoint","severity":"high","confidence":"certain-pattern/likely-breakpoint","files":["backend/src/main/java/com/gte619n/healthfitness/persistence/user/UserRepository.java:264-274","backend/src/main/java/com/gte619n/healthfitness/api/googlehealth/GoogleHealthRefreshService.java:54-82","backend/src/main/java/com/gte619n/healthfitness/core/goals/eval/StepEvaluationService.java:122-144","infra/scripts/deploy-gh-refresh-job.sh:56","infra/scripts/deploy-goals-sustained-job.sh:51"],"effort_days":3,"autonomy":"high","null_option_cost":"silent stale integrations for late-in-list users at ~200-450 users"},
  {"id":"PERF-004","title":"Capture endpoints block on Gemini (180s timeout) holding <=30MB unresized images on heap; concurrency 16 vs 1.3GiB heap","severity":"high","confidence":"certain-path/likely-limits","files":["backend/src/main/java/com/gte619n/healthfitness/integrations/config/GeminiConfig.java:43","backend/src/main/resources/application.yml:8-14","backend/cloudbuild.yaml"],"effort_days":3,"autonomy":"high","null_option_cost":"capture UX = Gemini tail latency; dinner-hour burst risks OOM recurrence (documented prior incident)"},
  {"id":"PERF-005","title":"/api/me/blood and body-composition serve full unwindowed history; v1 paginates after full read","severity":"medium","confidence":"certain","files":["backend BloodController:50-54","backend V1LabsController:89,168","web/lib/blood-panel.ts:71-72"],"effort_days":2,"autonomy":"high","null_option_cost":"dashboard TTFB and read spend creep with data age"},
  {"id":"PERF-006","title":"Dashboard renders ~10 backend hops per force-dynamic visit (coalescing via React cache() partially mitigates)","severity":"medium","confidence":"certain-count","files":["web/app/page.tsx:30-187","web/lib/api.ts:20"],"effort_days":1,"autonomy":"high","null_option_cost":"10x backend request billing per page view; queuing behind cold starts"},
  {"id":"PERF-007","title":"Per-instance Caffeine caches: cross-instance staleness (userById 5min) once >1 instance; memory footprint fine at 1k users","severity":"medium","confidence":"certain-design/likely-harm","files":["backend/src/main/java/com/gte619n/healthfitness/config/CacheConfig.java:52-65"],"effort_days":0.5,"autonomy":"high","null_option_cost":"sporadic stale-settings reports at multi-instance traffic"},
  {"id":"PERF-008","title":"Goals->phases->steps triple-nested N+1 without limits, multiplied by daily all-users job; assorted unbounded queries","severity":"medium","confidence":"certain","files":["backend/src/main/java/com/gte619n/healthfitness/persistence/goals/FirestoreStepRepository.java:109-132","backend/src/main/java/com/gte619n/healthfitness/persistence/goals/FirestoreGoalChatRepository.java:108","backend/src/main/java/com/gte619n/healthfitness/persistence/location/LocationRepository.java:115-129"],"effort_days":1.5,"autonomy":"high","null_option_cost":"contributes to PERF-003 cliff; latent unbounded reads"},
  {"id":"PERF-009","title":"nutritionDailyLogs day-rollup transaction re-reads all day entries per entry write (per-user-per-day hotspot doc)","severity":"low","confidence":"certain","files":["backend/src/main/java/com/gte619n/healthfitness/persistence/nutrition/FirestoreNutritionDailyLogRepository.java:78-95"],"effort_days":0,"autonomy":"monitor-only","null_option_cost":"transaction retries only under burst logging (drink sessions)"},
  {"id":"PERF-010","title":"Compose recomposition hygiene: per-page inline lambdas in WorkoutSessionScreen; un-keyed non-lazy entry list in NutritionTodayComponents","severity":"low","confidence":"likely-static-heuristics","files":["android/feature-workouts/src/main/java/com/gte619n/healthfitness/feature/workouts/session/WorkoutSessionScreen.kt:630-655","android/feature-nutrition/src/main/java/com/gte619n/healthfitness/feature/nutrition/NutritionTodayComponents.kt:205"],"effort_days":1,"autonomy":"high","null_option_cost":"minor jank risk on low-end devices"}
]
```
