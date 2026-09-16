# Phase 2 — Decision log

Every non-obvious decision made during autonomous execution, for later review.
Format: **DEC-NN** — decision — rationale — reversibility.

---

## DEC-01 — All slices land on `performance-refactor-fable`, one commit per slice, not 13 separate branches
The plan specifies `refactor/<slice-name>` branches. I'm executing inside a
single pre-created worktree on `performance-refactor-fable`, and the slices are
sequentially dependent (6 needs 5's receipt order; 11 needs 4's data layer; 12
deletes what earlier slices obsolete). Thirteen branches with cross-branch
dependencies inside one worktree would mean constant rebasing and would make the
cumulative result un-reviewable as one branch — which is how this worktree is
set up to be reviewed. **Decision:** one single-concern commit per slice on this
branch, commit subject `refactor(<slice>): …`. **Reversible:** yes — commits are
per-slice, so any slice can be reverted or cherry-picked onto its own branch.

## DEC-02 — Autonomous mode: proceed on best judgment, log, never block
Per the user's instruction ("do not stop to ask questions"), every ambiguity is
resolved with the most conservative choice that preserves behavior and satisfies
the offline contract, and recorded here rather than surfaced as a question.

## DEC-03 — Perf harness: committed runnable probes + documented procedures for the live-stack legs
Slice 0 asks for backend latency, Android cold start, web bundle, web LCP, and a
24h-offline sync timer. The first three are committed as self-contained,
verified scripts (`infra/perf/{backend-latency.mjs,android-coldstart.sh,web-bundle.mjs}`).
Web LCP and the device-side sync timer need a live authenticated stack + a
physical/emulated device with a real backend, which can't be reduced to one
hermetic committed command in this environment. **Decision:** ship the fixture
**seeder** (`seed-fixture-user.mjs`, emulator-guarded) and document the LCP +
sync-timing procedures in `perf-harness.md` rather than fake a one-command run.
**Reversible:** yes — the documented legs can be scripted later when run against
real infra.

## DEC-04 — Android Macrobenchmark module deferred in favor of the `am start -W` probe
A full Macrobenchmark module measures authenticated cold start + DB-query timing
more faithfully, but it's a new Gradle module (build-time cost + R8/baseline-
profile coupling) added mid-refactor. The `am start -W` probe tracks the
slice-8/slice-9 deltas adequately (it already showed release 428 ms vs debug
1190 ms). **Decision:** defer the Macrobenchmark module; revisit only if the
800 ms target proves marginal on real hardware. **Reversible:** yes — additive
module, can be introduced without touching app code.

## DEC-06 — Backoff jitter is full ±50%, injected random for testability
The plan said "±50% jitter". Implemented as a pure companion
`jitteredBackoffMillis(attempts, rand)` mapping `rand∈[0,1)` onto factor
[0.5, 1.5]× the existing deterministic ladder, clamped to the 6h ceiling. The
ladder function `backoffMillis` is kept intact (tests + readability). The repo
takes an injected `random: () -> Double` (default `Random.Default.nextDouble()`)
so drains are deterministic under test. **Reversible:** yes.

## DEC-07 — Parked-row aging surfaced via process-scoped dedupe, not a new column
Emitting a diagnostics nudge when a parked (terminal-4xx) row ages past 24h
needs to fire once, not every drain. Two options: a persisted `agingSurfaced`
column (a Room schema bump — which slice 2 is specifically de-risking, so adding
one here is self-defeating) or an in-memory surfaced-id set. **Decision:**
in-memory set, matching `SyncDiagnostics`'s own process-scoped design — the
outbox row stays the durable truth; the nudge is a diagnostic aid, and
re-emitting once after a process restart is acceptable. The aging check runs in
`drain()` (not `drainLocked`) so a queue of only-parked rows (never "due") still
gets checked. **Reversible:** yes.

## DEC-08 — Destructive fallback narrowed to pre-outbox schemas, not removed outright
Schemas 1.json/2.json exist but have NO migrations — a plain
`.fallbackToDestructiveMigration()` was the only path those versions had. Removing
it wholesale would crash a real v1/v2 device on upgrade. But those versions
predate the offline outbox/drafts entirely, so a wipe there loses only refetchable
mirror data. **Decision:** `fallbackToDestructiveMigrationFrom(1, 2)` +
`fallbackToDestructiveMigrationOnDowngrade()`. A forward bump to v8+ without a
migration now THROWS (fail loud) instead of silently wiping outbox/drafts (DL-3);
v1/v2 upgrades and dev downgrades still wipe safely. `DESTRUCTIVE_FALLBACK_FROM`
is frozen and guarded by a test that forbids adding any v3+ entry. **Reversible:** yes.

## DEC-09 — "Reversible migration + round-trip" satisfied by forward-fixture + fail-loud, not hand-written down-migrations
The offline contract asks for "a reversible migration with a test that migrates
real fixture data forward and back." Room migrations are one-way; authoring
down-migrations that nothing calls would be dead speculative code (violates the
"no speculative generality" standard). **Interpretation adopted:** (1) every
version bump has an explicit forward `Migration` proven to preserve real fixture
rows (instrumented `HfDatabaseMigrationTest`, verified green on the emulator:
outbox row survives 3→7, draft survives 4→7); (2) "reverse" for the mirror
tables is refetch-from-server (a downgrade wipe is safe by design); (3) the
device-only tables are protected by making a missing forward migration throw.
The fast JVM `HfDatabaseMigrationCoverageTest` gates the chain in CI. If true
bidirectional migrations are wanted, revisit — logged for review. **Reversible:** yes.

## DEC-10 — Fixed the core-data instrumented suite's boot-receiver crash (in-scope test infra)
Running any core-data instrumented test on the emulator crashed the whole run
before test 0: the main manifest's Hilt `ReminderBootReceiver` (@AndroidEntryPoint)
merges into the test APK, which runs a plain `Application`, so an OS
protected-broadcast delivery throws "Hilt BroadcastReceiver must be attached to
an @HiltAndroidApp Application." Needed to run the new migration test at all, and
it was silently blocking the existing sync instrumented tests too. **Decision:**
strip the three reminder receivers from the androidTest manifest via
`tools:node="remove"` (fully-qualified names, since a relative name resolves
against the test app id). Minimal, standard, test-APK-only. **Reversible:** yes.

## DEC-11 — Write contract enforced by a live-endpoint registry test, not per-method annotations
Classifying replay-safety needs a mechanism that fails on any *new* unclassified
mutating endpoint. Options: annotate all ~132 handlers (very invasive) or a
checked-in registry the test cross-checks against the live surface. **Decision:**
registry (`backend/src/test/resources/write-contract.txt`, `CATEGORY|METHOD path`
lines) + `WriteContractTest` that introspects `RequestMappingHandlerMapping`,
fails on any live endpoint missing from the registry, any `NEEDS_FIX`, and any
stale entry. Keys are the exact Spring patterns (captured by running the test),
so no format drift. **Reversible:** yes.

## DEC-12 — Fixed all 6 NEEDS_FIX creates via the existing idempotentCreate; deferred 2 history-append dups
The audit found 6 server-minted creates with no replay guard (capture-meal,
relog, goal-chat commit, workout-program create, workout-chat commit, equipment
submit). All 6 routed through `SyncWriteContext.idempotentCreate` (the same
pattern the 9 already-guarded creates use), so a replay returns the original.
The 2 soft issues (MedicationController.update/changeDose append UUID-keyed
history rows → replay duplicates the *history log*, though the med doc converges)
are logged as BUG-02 rather than fixed inline: the fix needs a deterministic
history-row id, a larger change to the history model, and the primary resource
is already safe. **Reversible:** yes.

## DEC-13 — Baseline's "2 field-injection violations" were a false positive
The Phase 0 sweep flagged `GoogleHealthSignatureVerifier` and
`WithingsWebhookController` as field-injection. The audit confirmed both
`@Autowired`s are on **constructors** (to disambiguate a secondary/test
constructor), not fields. There is nothing to fix; slice 8's DI-cleanup item is
dropped. **Reversible:** n/a (no change).

## DEC-14 — Slice 4 split into 4a (outbox core, shipped) and 4b (write-path cutover, deferred)
The plan pre-authorized this split. The web write path is Next server-actions
invoked from client components; safely cutting the live nutrition/medication/
workout writes over to the outbox (replicating the server-side body mapping on
the client, adding optimistic UI + router.refresh on drain, without regressing
the SSR UX) is a substantial, separately-reviewable change. **4a (this commit):**
the durable infrastructure that closes DL-1 — a single typed IndexedDB layer
(`web/lib/offline/idb.ts`), the mutation outbox with client-UUID/seq/timestamp +
jittered backoff + park-on-terminal + survives-reload (`outbox.ts`), an
allowlisted authenticated replay proxy (`app/api/outbox/replay`), the mutation
client + drain triggers (online/visibility/interval), and the live drainer
mounted in the global Providers. 10 unit tests (fake-indexeddb) prove the
no-loss/no-duplicate/backoff/park/reload behaviour. **4b (next):** convert the
three highest-traffic writes to `submitMutation` + optimistic UI, add the pending
badge to the chrome, and a Playwright offline→online→sync-once e2e. **Reversible:** yes.

## DEC-15 — Replay proxy is allowlisted, never a generic path forwarder
The browser has no bearer token, so the outbox drains through a server route
that attaches the session bearer. A generic "forward any path" proxy would let
any page hit any backend endpoint (incl. admin) under the user's identity.
**Decision:** `REPLAY_ENDPOINTS` pins the exact (method, path-pattern) pairs the
proxy may forward — the user-data writes proven replay-safe by slice 3's write
contract — and both the client `enqueue` and the server route reject anything
else. Keep it in sync with `write-contract.md`. **Reversible:** yes.

## DEC-16 — fake-indexeddb added as a devDependency
Unit-testing the IndexedDB data layer in jsdom needs an IndexedDB implementation
(jsdom has none). `fake-indexeddb` is the standard in-memory one; no existing dep
provides it and a browser-only Playwright test would be far slower and can't
assert the internal backoff/park state. Dev-only. **Reversible:** yes.

## DEC-17 — Slice 5: parallelize the sync reader instead of a change-journal rewrite
The plan proposed a per-user change journal to make delta sync O(changes). On
inspecting the code I found (a) the naive N+1 the baseline flagged is **already
fixed** on main — `SyncEnumerationBounds` cursor-bounds the nutritionDays/
goalChatThreads enumeration; and (b) a journal has no single write choke point
with doc-id granularity (`SyncChangeNotifier` carries only collection names), so
it would require instrumenting every write site + a dual-reader transition — a
**wholesale rewrite of the core sync path**, which the guardrails forbid doing
autonomously without human review.
**Decision:** deliver the same goal (faster sync) via a safe, provable,
incremental change: the reader issued ~20 independent per-collection Firestore
reads **sequentially**; since the merged result is re-sorted by CANONICAL_ORDER
and truncated, append order is irrelevant, so I issue all scans concurrently and
collect. Output is identical (proven green by the emulator SyncContractIntegration
suite); wall-clock drops from the sum of ~20 network round-trips to ~the slowest
one. This is the dominant cost of the slowest hot endpoint. The full change-journal
is logged as a **future human-reviewed option** if this doesn't get p95 under
target in prod. **Reversible:** yes (one method's execution strategy).

## DEC-18 — Slice 6: document the per-entity rule; defer per-field merge for mutable entities
Investigating the conflict path showed the deterministic, server-authoritative
LWW rule is already implemented and well-tested: `ConflictResolver` keys on the
server clock (equal-timestamp → server wins) with 7 unit tests covering every
case. And the append-only exception already holds **by construction** — every
high-churn entity (nutrition entries, blood/body readings, adherence, logged
sets) is a separate document with a client-minted/deterministic id, enforced by
slice 3's `WriteContractTest`, so two devices never write the same append-only
doc. The genuinely missing deliverable was the explicit **per-entity contract
document** the mandate asks for → `docs/reference/conflict-resolution.md`.
**Deferred:** true per-field merge for the small set of *mutable* entities
(goals, meds, profile), which today resolve by document-level LWW. Implementing
it means field-diffing in the sync engine or reworking every mutable PATCH to
partial-merge writes — a core-sync-path change needing human review, and the
residual risk is only "two devices edit different fields of the same goal at
once → one field edit lost," never data at rest. The workout-session set-merge
is the highest-value next step and is documented as such. **Reversible:** n/a
(doc + decision; no behaviour change).

## DEC-19 — Slice 7: parallelize recent-activity reads; don't swap the workout source
The audit suggested replacing the workouts() programs×calendar N+1 with a single
read of the flat `Workout` collection. But that flat doc lacks the day label,
logged-set count, and duration the feed displays (they live on `ScheduledWorkout`),
so swapping the source would change the feed's copy — out of bounds
("never change copy"). Instead, killed the *latency* without changing output:
`recent()`'s five independent sources now run concurrently (was sequential), and
`workouts()` fans its per-program calendar reads out concurrently (was the N+1
in series). Same result (re-sorted upstream), each source keeps its `safe`
isolation. The food() per-day reads and the per-user-subcollection model stay
(collectionGroup is forbidden by ADR-0021; 4 day-reads is inherent). Verified by
the existing 5 RecentActivityServiceTest cases. **Reversible:** yes.

## DEC-20 — Slice 8: cold-start & auth-refresh premises already satisfied on main; no safe change to force
Investigated each slice-8 item and found the work already done or not safely
improvable:
- **CDS** (the real cold-start lever): already fully shipped in the Dockerfile
  (PERF-002) — exploded-jar training run with `-Dspring.context.exit=onRefresh`,
  `-XX:ArchiveClassesAtExit`, self-healing `-XX:+AutoCreateSharedArchive` at
  runtime. Nothing to add.
- **Lazy AI clients:** `GeminiConfig` already builds ONE conditionally-created,
  lightweight `Client` (a builder call, no work); the client classes just hold a
  reference. `@Lazy` would shave nothing measurable and can't be verified without
  a deploy, so not worth the startup-validation risk.
- **Field injection:** false positive (DEC-13).
- **auth/refresh (1.27 s p50):** SHA-256 (fast, not a KDF, correctly). The user
  read is already `@Cacheable(userById)`. The remaining cost is the token
  rotation — `tryMarkRotated` (compare-and-set) then `save(successor)` — which is
  **order-dependent by design** (the CAS is the theft-detection guarantee), so it
  can't be batched or parallelized. The latency is inherent Firestore write cost.
**Decision:** no code change; forcing lazy-init or reordering auth writes would be
a risky change against an already-correct design (guardrail: stop rather than push
through). Slice delivered as a verified finding. **Reversible:** n/a.

## DEC-21 — Slice 10: shipped the collection contract fixture; dropped the OpenAPI→TS codegen
The plan had two parts. **Dropped:** generating web `lib/types/` from the backend
OpenAPI snapshot — the only OpenAPI spec is the `/v1` third-party platform API
(`tesseta-platform-v1.yaml`), NOT the internal `/api/me` surface the web app
consumes, so there's no source to generate the synced-entity types from without
first authoring OpenAPI for the entire internal API (a large change out of scope).
**Shipped:** the higher-value half — the backend↔Android collection contract that
closes the exact bug class that dropped cross-device nutrition entries. A shared
fixture (`docs/reference/sync-emitted-collections.txt`) is pinned on the backend
side to `FirestoreSyncChangeReader.emittedCollectionNames()` and on the Android
side to `CollectionRegistry.tableFor(...)`. A backend collection change fails the
backend test until the fixture is updated, which then forces the Android test to
prove the registry routes it. Proven to catch drift (removing a line reddens the
backend test). **Reversible:** yes.

## DEC-22 — Slice 11: read-cache infra shipped; the other two items were already done on main
Of slice 11's three planned items, two were already realized on main:
`loadHiddenBiometrics` is already wrapped in React `cache()` (the "double fetch"
was a Phase-0 read-sweep false positive), and the heavy client deps (@dnd-kit,
react-markdown) are already route-split — the shared first-load JS is 127 KB,
well under the 200 KB target, so they're not in the shared chunk. (Dynamic-
importing react-markdown with `ssr:false` was considered and rejected: it risks a
visible unstyled-then-styled flash in chat, i.e. a visual change.) The remaining
substantive item, the read-through cache, is shipped as **infrastructure** (like
the slice-4a outbox core): `lib/offline/read-cache.ts` (readThrough +
stale-while-revalidate over the typed IndexedDB layer, DB bumped to v2 with an
additive store) with 7 unit tests. Wiring the SSR pages / client components to
hydrate from it is the cutover step, grouped with slice 4b. **Reversible:** yes.

## DEC-05 — integrationTest zero-test guard is CI-only
The new guard throws if `integrationTest` runs 0 tests while
`firestore.emulator.required=true` (the CI condition). Locally, where the
firebase CLI may be absent, the suite is allowed to skip so a dev without the
emulator isn't blocked. **Reversible:** trivially — one `if` in build.gradle.kts.
