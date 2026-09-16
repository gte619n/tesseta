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

