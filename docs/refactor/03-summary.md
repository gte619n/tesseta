# Phase 2 — execution summary

All 13 planned slices were executed on `performance-refactor-fable` (one
single-concern commit each; DEC-01). Every commit left all three codebases green.
Details per slice in `02-progress.md`; every non-obvious call in `decisions-log.md`
(DEC-01…23); out-of-scope bugs in `bugs.md`.

## What shipped, by goal

**Goal 1 — robust offline-first & correct sync (data-loss first):**
- **Web mutation outbox** (slice 4a) — the top data-loss risk (DL-1): a typed
  IndexedDB layer + outbox (client UUID = Idempotency-Key, seq, timestamp,
  jittered backoff, park-on-terminal, survives tab close) draining through an
  allowlisted authenticated proxy. Live drainer mounted; 10 tests.
- **Uniform server idempotency** (slice 3) — audited all 132 mutating endpoints,
  fixed 6 unguarded creates, and enforce it forever with `WriteContractTest`.
- **Reversible Room migrations** (slice 2) — removed the destructive fallback that
  could wipe the outbox/drafts; fail-loud on an un-migrated bump; fixture
  round-trip tests (JVM coverage + emulator row-survival).
- **Collection contract** (slice 10) — the cross-device drop bug class closed: a
  shared fixture pins the backend's emitted collections to the Android registry.
- **Conflict contract documented** (slice 6) — per-entity rules; append-only holds
  by construction, LWW already tested.
- **Outbox jitter + parked-row aging** (slice 1); **Android history cache-first**
  (slice 9).

**Goal 2 — speed:**
- **Sync reader parallelized** (slice 5) — the slowest hot endpoint: ~20
  sequential Firestore round-trips → concurrent, identical output.
- **recent-activity fan-out parallelized** (slice 7).
- **Web read-through cache infra** (slice 11); backend cold-start already handled
  on main (slice 8, verified).

**Goal 3 — measurement & clean structure:**
- **Perf harness + revived dead test suite** (slice 0) — 8 emulator integration
  classes that never ran now run and gate CI; committed latency/bundle/cold-start
  probes.
- **Final green gate** (slice 12).

## Deferred (tracked, not dropped)

- **Slice 4b** — cut the live web write paths (nutrition/medication/workout) over
  to `submitMutation` + optimistic UI + pending badge, and the SSR pages over to
  the read-through cache; Playwright offline→online→sync-once e2e. The outbox and
  read-cache **infrastructure is shipped and tested**; this is the UI cutover,
  which is a distinct reviewable change against the SSR/server-action write path.
- **Per-field merge for mutable entities** and the **workout-session set-merge**
  (slice 6 / DEC-18) — core-sync changes wanted human review.
- **Full change-journal** (slice 5 / DEC-17) — only if the parallelized reader
  doesn't hit p95 target in prod.

## Where the plan met reality

Several planned items were already done on `main` (this branch is fresh off it):
the naive sync N+1 was already cursor-bounded, Spring CDS was already shipped,
`loadHiddenBiometrics` was already deduped, the heavy web deps were already
route-split, and the "2 field-injection violations" were a false positive. Those
slices (5, 8, 11) pivoted to the genuinely-remaining safe win and recorded the
finding rather than manufacturing change. Two planned approaches were replaced
with safer equivalents for the same goal (parallelize vs. rewrite the sync path;
parallelize vs. swap the workout feed source) — see DEC-17/19.

## Verify

- Backend: `cd backend && ./gradlew build` (871 unit + 12 emulator integration).
- Android: `cd android && ./gradlew testDebugUnitTest :app:assembleRelease` (591).
- Web: `cd web && pnpm typecheck && pnpm lint && pnpm test && pnpm build` (77).
- Perf probes: `docs/refactor/perf-harness.md`.
