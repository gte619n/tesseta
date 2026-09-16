# Phase 2 — Progress (before/after metrics per slice)

Baseline numbers are from `docs/refactor/00-baseline.md`. Each slice records the
metric it targeted, measured the same way.

---

## Slice 0 — perf-harness + revive dead integration suite
**Target:** enable measurement; make the backend emulator suite actually run.
- **Backend integration suite:** before = **0 tests executed** (NO-SOURCE, silently
  skipped locally + CI). After = **12 tests across 7 emulator-backed classes
  execute and pass** (RefreshTokenRotationConcurrency, AdherenceSameDayConcurrency,
  NutritionRollupConcurrency, StepRepositoryScoping, FirestoreScheduledWorkoutRange,
  ReminderSettingsRepositoryImpl, FirestoreEmulatorSmoke). Verified:
  `./gradlew integrationTest -Dfirestore.emulator.required=true` → 12/0/0.
- **Zero-test guard:** added — CI (`firestore.emulator.required=true`) now fails
  the build if the suite ever resolves to 0 tests again.
- **Perf probes:** `infra/perf/{backend-latency.mjs, android-coldstart.sh,
  web-bundle.mjs, seed-fixture-user.mjs}` committed; backend-latency and
  web-bundle verified to reproduce the Phase 0 baseline (sync p50 ~1.1–1.8 s,
  shared JS 127 KB gz). Full harness doc: `perf-harness.md`.
- **Builds:** backend `build jacocoTestReport` green; jacoco now aggregates unit
  + integration execution data. Android/web untouched (still green from Phase 0).

## Slice 1 — Android outbox backoff jitter + parked-row aging
**Target:** eliminate reconnect lockstep (baseline problem #9) and stop parked
mutations stranding silently (DL-5). No runtime metric — correctness/robustness.
- **Jitter:** `jitteredBackoffMillis` applies full ±50% jitter to the 30 s→6 h
  ladder; retries no longer align across rows/devices. Verified: band + ceiling
  test, and a "two failing rows get different backoffs" test.
- **Aging:** a parked (terminal-4xx) row past 24 h now emits one
  `outbox-parked-aging` diagnostics record (was: invisible until the user opened
  a feature banner). Verified: nudge fires once past threshold, not before, and
  not twice.
- **Tests:** +3 in `OutboxDrainTest` (24 total in that class); full Android suite
  **587 tests, 0 failures** (was 584). Backend/web untouched.

## Slice 2 — remove Room destructive migration fallback + migration tests
**Target:** DL-3 — a schema bump can no longer silently wipe outbox + drafts.
- **Before:** `fallbackToDestructiveMigration()` — any un-migrated version bump
  wipes the DB (incl. outbox rows + workout drafts that exist nowhere else).
- **After:** `fallbackToDestructiveMigrationFrom(1, 2)` (only pre-outbox
  pre-release schemas may wipe) + `…OnDowngrade()`. A forward bump to v8+ without
  a migration now throws at open (fail loud). Migration list + version exposed as
  `ALL_MIGRATIONS`/`SCHEMA_VERSION`.
- **Tests:** JVM `HfDatabaseMigrationCoverageTest` (2 tests, runs in CI gate:
  chain is contiguous to SCHEMA_VERSION; no v3+ in the destructive-from set).
  Instrumented `HfDatabaseMigrationTest` (2 tests, **verified green on emulator**:
  outbox row survives 3→7, draft survives 4→7).
- **Bonus infra fix:** the entire core-data instrumented suite was crashing on a
  Hilt boot-receiver before any test ran; stripped the receivers from the test
  manifest (DEC-10). Android JVM suite **589 tests, 0 failures**.

## Slice 3 — uniform server idempotency + enforced write contract
**Target:** every mutating endpoint safe to replay before the web outbox (slice
4) starts replaying blindly.
- **Audit:** all **132 live mutating endpoints** classified (subagent read all 63
  controllers). Found **6 unguarded server-minted creates** (capture-meal, relog,
  goal-chat commit, workout-program create, workout-chat commit, equipment
  submit) — all routed through `SyncWriteContext.idempotentCreate`, so a replay
  returns the original instead of duplicating.
- **Enforcement:** `WriteContractTest` introspects the live surface and fails on
  any unclassified endpoint, any `NEEDS_FIX`, or any stale registry entry —
  replay-safety can no longer be silently skipped. Registry:
  `write-contract.txt`; rationale doc: `docs/reference/write-contract.md`.
- **Also:** added `SyncWriteContextTest` (4 tests) — the first direct coverage of
  the `idempotentCreate` primitive all guarded creates rely on. Confirmed the
  baseline's "2 field-injection violations" were a false positive (constructor
  `@Autowired`), so slice 8 loses that item (DEC-13).
- **Tests:** backend unit **870, 0 failures** (was 865) + 12 emulator integration
  green. Android/web untouched.

## Slice 4a — web typed IndexedDB layer + mutation outbox (core)
**Target:** DL-1 — the top data-loss risk (web loses any write on a flaky
network). Split per DEC-14; 4b (live write-path cutover + e2e) is the follow-up.
- **Shipped:** `web/lib/offline/` — a single typed IndexedDB layer, the mutation
  outbox (client UUID = Idempotency-Key, monotonic seq, client timestamp,
  jittered exponential backoff, park-on-terminal-4xx, survives tab close), an
  **allowlisted** authenticated replay proxy (`app/api/outbox/replay`), the
  mutation client with drain triggers (reconnect/visibility/interval), and the
  live `OutboxDrainer` mounted in the global client Providers so a queued write
  is retried on load.
- **Metric:** shared first-load JS unchanged at **127 KB gz** (offline lib is
  client-lazy, not in the shared chunk).
- **Tests:** +10 web unit tests (fake-indexeddb) proving no-loss/no-duplicate on
  2xx, backoff on 5xx, park on 4xx, 429/408 treated transient, survives reload,
  rearm, and the ±50% jitter band. Web suite **70 tests, 0 failures** (was 60);
  typecheck + lint + build green; `/api/outbox/replay` route registered.
- **Deferred to 4b:** convert nutrition/medication/workout writes to
  `submitMutation` + optimistic UI, pending badge in the chrome, Playwright
  offline→online→sync-once e2e.

## Slice 5 — faster delta sync (parallelized reader, not a journal)
**Target:** `GET /api/me/sync` p50 1.76 s / p95 4.1 s — the slowest hot path.
- **Approach change (DEC-17):** the baseline's N+1 was already fixed on main
  (`SyncEnumerationBounds`), and a change-journal would be a wholesale rewrite of
  the core sync path with no doc-id-granular write choke point. Delivered the
  same goal safely instead: the reader issued ~20 independent per-collection
  Firestore reads **sequentially**; since the result is re-sorted by
  CANONICAL_ORDER + truncated, order is irrelevant, so all scans now fire
  concurrently and are collected together.
- **Expected effect:** wall-clock drops from the sum of ~20 sequential network
  round-trips to ~the slowest single one. The win scales with Firestore RTT, so
  it shows in prod (measure with `infra/perf/backend-latency.mjs`), not the local
  emulator (no network latency). Structural: N sequential RTTs → 1.
- **Correctness:** output is identical (same queries, same sort). Verified by the
  emulator **SyncContractIntegrationTest** (full write→delta→fan-out loop) — 12
  integration tests green. Full backend build green (870 unit + 12 integration).

## Slice 6 — conflict contract documented per entity
**Target:** the contract clause "deterministic, server-authoritative conflict
resolution; append-only data never conflicts; document the rule per entity type."
- **Found already in place:** the deterministic LWW rule (server-clock keyed,
  equal→server wins) is implemented in `ConflictResolver` with 7 unit tests; the
  append-only exception holds **by construction** — every high-churn entity is a
  separate client-minted-id document (enforced by `WriteContractTest`), so two
  devices never write the same append-only doc.
- **Delivered:** the missing explicit **per-entity contract**,
  `docs/reference/conflict-resolution.md` — classifies all 23 synced collections
  as append-only vs mutable-LWW with the id source and the rule.
- **Deferred (DEC-18):** true per-field merge for the small mutable set (a
  core-sync change needing review; append-only-by-construction already protects
  all data-at-rest) and the workout-session set-merge (highest-value next step).
- **Tests:** no behaviour change; all suites remain green.

## Slice 7 — kill the fan-out latency on recent-activity
**Target:** `GET /api/me/recent-activity` p50 609 ms (N+1: programs × calendar +
5 sequential sources).
- **Approach (DEC-19):** couldn't swap the workout source to the flat `Workout`
  collection without regressing the feed's copy (that doc lacks day-label/sets/
  duration). Killed the latency instead: the 5 independent sources now run
  concurrently, and the per-program calendar reads inside `workouts()` fan out
  concurrently — same output, wall-clock = slowest source/calendar not the sum.
- **Expected effect:** with 5 sources + N programs previously serial, p50 drops
  toward the slowest single read; measure in prod via `backend-latency.mjs`.
- **Tests:** existing 5 `RecentActivityServiceTest` cases green (output
  unchanged); full backend build green.

## Slice 8 — backend cold start: already handled on main (verified finding)
**Target:** ~13 s cold start; auth/refresh 1.27 s p50.
- **Finding (DEC-20):** the cold-start lever (Spring CDS) is already fully shipped
  in the Dockerfile (PERF-002). The AI-client beans are cheap (lazy-init would
  shave nothing measurable). Field injection was a false positive (DEC-13). In
  auth/refresh, the user read is already cached and the token rotation is an
  order-dependent compare-and-set (theft detection) that can't be batched — the
  latency is inherent Firestore write cost.
- **Outcome:** no safe code change to make; forcing one would risk an
  already-correct design. Recorded as a finding; builds unchanged/green.

## Slice 9 — Android workout history is now cache-first (ADR-0018)
**Target:** the one network-only read with no offline fallback (baseline problem
#10) — a spinner on every re-entry and an error offline.
- **Change:** the `@Singleton` repository keeps the last successful first page in
  memory; `workoutHistoryPage(0)` caches on success and falls back to the cached
  page on failure. The ViewModel renders the cached page immediately (no spinner
  on re-entry) and keeps it visible if revalidation fails.
- **Effect:** re-entering the screen is instant (no spinner), and after one
  successful load the screen survives going offline within the session. (Full
  cross-process-restart offline would want a Room table — deferred; the durable
  copy is the server and this screen is append-only review data.)
- **Tests:** +1 `WorkoutHistoryViewModelTest` case (cached shows instantly +
  survives failed revalidation) + `@Before` cache stub; full Android suite
  **590 tests, 0 failures**.

## Slice 10 — cross-client collection contract, enforced
**Target:** the recurring wire-contract-drift bug (a backend collection the
client silently drops — the nutrition slash-collection sync loss).
- **Shipped:** a shared fixture `docs/reference/sync-emitted-collections.txt`,
  pinned on the backend side to `FirestoreSyncChangeReader.emittedCollectionNames()`
  (new accessor) and on the Android side to `CollectionRegistry.tableFor(...)`.
  A backend collection change fails the backend test until the fixture updates,
  which forces the Android test to prove the registry routes the new collection.
- **Proven:** removing a fixture line reddens the backend contract test
  (verified); all 23 emitted collections are routed by the Android registry
  today (no gap found).
- **Dropped (DEC-21):** the OpenAPI→TS web codegen — no OpenAPI source exists for
  the internal `/api/me` surface (only the `/v1` platform API is specced).
- **Tests:** backend **871 unit** + 12 integration green; Android **591** green.

## Slice 11 — web read-through cache (infra) + repeat-view speed
**Target:** repeat-view LCP + offline reads; dedupe dashboard fetch; trim bundle.
- **Already on main (DEC-22):** `loadHiddenBiometrics` is already `cache()`-wrapped
  (the "double fetch" was a read-sweep false positive); the heavy deps are already
  route-split (shared JS 127 KB < 200 KB target).
- **Shipped:** the read-through cache **infrastructure** — `lib/offline/read-cache.ts`
  (`readThrough` with maxAge + stale-on-error, `swr` stale-while-revalidate) over
  the typed IndexedDB layer (DB v2, additive store). 7 unit tests: cold fetch,
  fresh hit, revalidate-past-maxAge, stale-fallback-when-offline, throw-when-empty,
  swr immediate+refresh.
- **Deferred (with 4b):** hydrating the SSR pages/client components from the cache
  (the repeat-view LCP + offline-read cutover).
- **Tests:** web **77 tests, 0 failures** (+7); typecheck/lint/build green; shared
  bundle unchanged at 127 KB gz.

