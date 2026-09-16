# Phase 1 — Refactor plan

Date: 2026-09-16. Input: `docs/refactor/00-baseline.md` (fresh Phase 0 off
`origin/main`). Slices are ordered; each is independently shippable, ends with
green builds/tests across all three codebases, and records its metric in
`docs/refactor/02-progress.md`. Branch naming: `refactor/<slice-name>`.

Sizes: **S** ≈ ≤ half a day, **M** ≈ a day, **L** ≈ 1–2 days. Every slice is
scoped to be reviewable in one sitting.

---

## Offline contract: compliance map and one deviation

The target contract from the mandate, against reality:

| Contract clause | Android | Web | Backend |
|---|---|---|---|
| Local store is UI source of truth; reads never block on network | Mostly true today; exceptions fixed in slice 9 | **Deviation — see below** | n/a |
| Mutations via local outbox: client UUID + per-device monotonic seq + client timestamp | Already true (`OutboxEntity`: `mutationId`, device-wide `seq`, `createdAt`) | Built in slice 4 | Idempotency made uniform in slice 3 |
| Idempotent server mutations; safe replays | Already sends `Idempotency-Key` | Slice 4 | 15 controllers covered today; slice 3 closes the rest |
| Background sync, exp. backoff **with jitter**, cursor-resumable, survives process death | True except jitter → slice 1 | Slice 4 | Cursor exists; slice 5 makes it fast |
| Delta pull off cursor, never full refetch | True | n/a (read-through cache, not mirror) | True (slow → slice 5) |
| Deterministic server-authoritative conflicts: per-field LWW by receipt order; append-only types never conflict; documented per entity | Client honors server outcome | n/a | **Not true today** (whole-doc LWW) → slice 6 |
| Reversible migrations + fixture round-trip test | **Not true** (destructive fallback, no tests) → slice 2 | n/a | Firestore is schemaless with lazy field defaults; slice 6 documents per-entity evolution rules instead |
| Auth expiry offline queues silently, re-auth on reconnect, outbox preserved | True (ADR-0019 chain + replay-time token) | Slice 4 (IndexedDB outbox survives session refresh) | n/a |

**The deviation (making the case, per the mandate):** the web client gets a
**mutation outbox + read-through cache**, not a full local mirror as UI source
of truth. Reasons: (1) the actual data-loss hole on web is writes, and an
outbox closes it completely; (2) web is an SSR app — first render happens on
the server, so "UI reads hit local only" would mean rebuilding the app around
client-side rendering, a wholesale rewrite the guardrails forbid; (3) PHI in
IndexedDB should be minimized — a bounded read-through cache holds less
long-lived plaintext than a full mirror; (4) Android already covers the
phone-in-a-basement use case; web's offline requirements are "never lose a
write, tolerate flaky networks, fast repeat loads". The contract's intent
(no lost mutations, no blocked UI, deterministic convergence) is met; the
letter ("local store is source of truth for UI") is met for writes and for
repeat/offline reads, not for first server render.

## Performance targets: two adjustments (with evidence)

- **Backend read p95 ≤ 150 ms** — adopted, with one exception:
  `GET /api/me/sync` target **p95 ≤ 300 ms** for a typical delta page. Even
  journal-backed, a 500-change page pays Firestore batch-read floor + payload
  serialization; 150 ms p95 is not credible. (Baseline: 4,126 ms p95.)
- **Web LCP ≤ 1.5 s throttled 4G** — adopted for **repeat views** (cache-warm).
  For first-visit, target **≤ 2.5 s**: every meaningful page is auth-gated
  per-request SSR, so first-visit LCP is bounded by TLS + Cloud Run + backend
  fan-out, which no client-side work removes. Baseline audit web p50 TTFB was
  228 ms server-side; the 4G RTT budget dominates.
- Android cold start ≤ 800 ms and initial JS ≤ 200 KB gz: adopted unchanged
  (release cold start measured ~428 ms on emulator; shared JS 127 KB gz).
- Full sync after 24 h offline ≤ 5 s on Wi-Fi: adopted; depends on slice 5.

Out of scope for this refactor (logged, not addressed): Wear offline support
(stays a read-only relay per ADR-0007), the XPLAT-001 timezone bug (tracked in
`docs/refactor/bugs.md` when Phase 2 opens; interacts with slice 6 review),
`IMPL-PWA-01` full PWA, iOS.

---

## Slices, in order

### Slice 0 — `refactor/perf-harness`: measurement + revive the dead test suite
**What:** (a) Fix `backend/build.gradle.kts` `integrationTest` task — wire
`testClassesDirs`/`classpath` to the test source set so the 8
`firestore-emulator` classes actually run; make CI fail if the suite resolves
to zero tests. (b) Build the perf harness the targets require: Android
Macrobenchmark module (cold start to first authenticated frame + heaviest-
screen DB query timing), an authenticated Lighthouse script for web (session
cookie injection, throttled-4G profile), a scripted backend latency probe
(replays the top-10 endpoint mix, reports p50/p95), and a seeded fixture user
(~1 year: 730 nutrition days, 3 workouts/week, daily metrics) with a
sync-timing script for the 24 h-offline scenario. (c) Document how to run in
`docs/refactor/perf-harness.md`; record fresh numbers as the canonical baseline.
**Why:** every later slice claims a metric; nothing can be proven without this.
And the integration suite is the safety net for slices 3, 5, 6.
**Goal:** enabler for 1–3. **Risk:** low (additive; one Gradle fix).
**Verify:** CI shows the 8 emulator classes executing; harness reproduces the
Phase 0 numbers within noise. **Size:** L.

### Slice 1 — `refactor/outbox-jitter`: Android backoff jitter + parked-row aging
**What:** add full jitter to `OutboxRepository.backoffMillis` (±50% random,
same 30 s→6 h ladder); emit a diagnostics record when a parked
(terminal-4xx) row ages past 24 h so it surfaces in the existing sync
diagnostics UX instead of hiding behind a banner.
**Why:** reconnect stampedes and silently stranded mutations (DL-5).
**Goal:** 1. **Risk:** low. **Verify:** unit tests: backoff distribution
bounds, ladder cap unchanged, parked rows excluded from jitter; aging record
emitted at threshold. **Size:** S.

### Slice 2 — `refactor/room-migrations`: remove the destructive fallback
**What:** replace `fallbackToDestructiveMigration()` with
`fallbackToDestructiveMigrationOnDowngrade()` (downgrade = dev-only case);
add a migration test harness: fixture DBs at schema v3…v7 containing real
outbox rows, drafts, and mirror rows; tests migrate each forward to current
and assert row-level survival; add a reverse-migration test for the current
version per the contract. All future schema bumps require a fixture test (CI
enforced by a schema-version guard test).
**Why:** DL-3 — one careless bump from wiping user data that exists nowhere else.
**Goal:** 1. **Risk:** low-medium (touches DB init; behavior preserved for
non-migration paths). **Verify:** the new migration tests; full Android suite
green; manual upgrade-in-place smoke on emulator. **Size:** M.

### Slice 3 — `refactor/idempotency-uniform`: uniform server idempotency + documented write contract
**What:** audit all 269 mappings; for every mutating endpoint either (a) wire
`SyncWriteContext`/`Idempotency-Key`, (b) prove set-semantics/deterministic-id
replay safety, or (c) fix it. Encode the result in a contract test that walks
the controller surface and fails on any unclassified mutating endpoint.
Document per-endpoint replay semantics in `docs/reference/` (extends
api-surface.md).
**Why:** the web outbox (slice 4) will replay blindly; today coverage is
per-endpoint folklore. Also fixes web double-submit duplicates.
**Goal:** 1. **Risk:** medium (touches many controllers, mechanically).
**Verify:** new contract test; emulator idempotency replay tests for newly
covered endpoints; backend suite + revived integration suite green.
**Size:** L.

### Slice 4 — `refactor/web-outbox`: web typed data layer + mutation outbox
**What:** single typed IndexedDB data layer (`web/lib/offline/`) per the code
standards; mutations move from bare server actions to a client mutation client
that (1) journals to the outbox (client UUID, per-device seq from a device
record, client timestamp), (2) applies optimistic UI, (3) replays through the
existing API route-handler proxy with `Idempotency-Key`, exponential backoff +
jitter, triggered on enqueue/`online`/visibility/interval, surviving tab close
(IndexedDB) and auth refresh. Failed-terminal mutations surface in a visible
queue UI. Convert the highest-traffic writes first — nutrition entries,
medication adherence, workout session log — leaving the long tail on server
actions behind the same typed layer for later conversion; add a "pending"
badge consistent with existing design (no visual redesign).
**Why:** DL-1, the top-ranked problem.
**Goal:** 1 (+2: writes become perceived-instant).
**Risk:** high (new client infrastructure; auth/session edge cases).
**Verify:** Playwright e2e: log entry offline → close tab → reopen online →
entry syncs exactly once (asserted against a fake backend route); unit tests on
the outbox reducer/replay; duplicate-suppression test via idempotency key;
existing web suite green. **Size:** L (the largest slice; if it stops fitting
one sitting, split into 4a data-layer/outbox core + 4b write-path conversions).

### Slice 5 — `refactor/sync-journal`: per-user change journal for delta sync
**What:** on every mutating write, append a compact entry to
`users/{uid}/changeJournal` (monotonic receipt order via server timestamp +
tiebreak, collection, id, tombstone flag) inside the same write batch; new
`SyncChangeReader` implementation reads the journal with one indexed range
query per page instead of re-enumerating 21 collection families; keep the
scan-based reader as fallback behind a config flag for one release; cursor
format versioned so existing device cursors migrate transparently (first pull
on an old cursor falls back to scan once, then adopts journal cursor). TTL on
journal entries (e.g. 90 days) with scan fallback for older-than-journal
cursors. No shipped Firestore data is altered.
**Why:** the slowest hot path (1.76 s p50 / 4.1 s p95) gating every device's
convergence and the 24 h-offline target; also establishes the **receipt-order
primitive slice 6 needs** for per-field LWW.
**Goal:** 2 (+1: faster convergence). **Risk:** high (core data path; dual
implementations during transition). **Verify:** `SyncContractIntegrationTest`
extended to run against both readers with identical output on the same write
history (emulator); no-skip/no-dup property test across cursor migration;
prod metric: sync p50 < 300 ms warm (target ≤ 300 ms p95 typical page);
24 h-offline fixture sync < 5 s via slice-0 harness. **Size:** L.

### Slice 6 — `refactor/conflict-contract`: append-only semantics + per-field LWW, documented per entity
**What:** write `docs/reference/conflict-resolution.md` classifying every
synced entity: **append-only** (logged sets, nutrition entries, blood/body
readings, adherence events, chat messages — client-minted ids; server treats
create-replay as no-op, never overwrites an existing id, deletes are explicit
tombstones) vs **mutable** (goals, medications, protocols, targets, profile,
program definitions — per-field LWW using journal receipt order: writes carry
the fields they touched; the server merges at field level instead of
document-replace). Change the session-completion PUT to merge logged sets by
set key rather than wholesale list replacement (two-device completion
preserves both devices' sets). Clients unchanged except dropping now-dead
"discard local" paths.
**Why:** DL-2 — the remaining silent-data-drop scenario; contract requirement.
**Goal:** 1. **Risk:** high (semantic change to write paths; needs slice 5's
receipt order and slice 0's revived concurrency tests). **Verify:** new
emulator concurrency tests: concurrent same-session completion → both sets
present; concurrent different-field goal edits → both fields survive;
same-field edits → deterministic receipt-order winner. Android/web suites
green (behavioral assertions on "updated elsewhere" UX updated). **Size:** L.

### Slice 7 — `refactor/feed-queries`: kill the fan-out reads on hot endpoints
**What:** `recent-activity`: one indexed range query on the already-fanned-out
`Workout` collection instead of programs × calendar; one date-range query for
food instead of 4 day-by-day reads. `imported-history` (1.9 s p50): same
treatment (indexed query + Firestore-side pagination instead of in-memory).
`medications/today` (381 ms p50): batch reads. Add any needed composite
indexes via `infra/firestore/firestore.indexes.json`.
**Why:** problems #8; these are the top dashboard paths on both clients.
**Goal:** 2. **Risk:** medium (read-path only; no write changes).
**Verify:** emulator tests asserting identical feed output on a seeded
fixture; prod/harness metric: recent-activity p95 ≤ 150 ms warm,
imported-history p50 < 400 ms. **Size:** M.

### Slice 8 — `refactor/backend-cold-start`: ship CDS + lazy AI clients
**What:** enable the Spring CDS training-run stage already scaffolded in the
Dockerfile; make Gemini clients (`GeminiGoalChatClient`,
`GeminiWorkoutProgramChatClient`, media/enrichment services) lazy or
provider-backed so cold start doesn't pay their construction; fix the 2 field-
injection violations while in those constructors (they're in this blast
radius). Measure `auth/refresh` (1.27 s p50) during this slice — if it's
token-chain walking, fix here; if bigger, log to bugs.md.
**Why:** ~13 s cold starts hit users 264×/week and inflate web TTFB.
**Goal:** 2. **Risk:** medium (deploy pipeline change; canary + Trivy gate
already in place catches image regressions). **Verify:** Cloud Run startup
probe time < 5 s on the deployed revision; cold-start request p50 from logs
halved or better; all suites green. **Size:** M.

### Slice 9 — `refactor/android-read-paths`: close the cache-first exceptions
**What:** workout history becomes cache-backed (page cache in Room keyed by
page, served-then-revalidated per ADR-0018) so the screen works offline and
stops blocking on the slow endpoint; same pattern for any other network-only
read found by a quick ADR-0018 conformance sweep (`catalog_cache` already
covers food search). Delete the per-screen bespoke retry code this obsoletes.
**Why:** last UI paths that block on network (goal 1 clause: reads hit local).
**Goal:** 1+2. **Risk:** low-medium. **Verify:** unit tests (cache seed +
revalidate + offline read); airplane-mode manual smoke: history renders
cached pages; Macrobenchmark screen-open timing recorded. **Size:** M.

### Slice 10 — `refactor/contract-fixtures`: stop hand-maintaining the wire contract three times
**What:** (a) generate web `lib/types/` for synced entities from the backend's
OpenAPI snapshot (build-time codegen, checked in, CI-diffed — replaces the
hand-written duplicates incrementally, starting with entities slices 4/6
touched); (b) a shared contract fixture: backend test emits its
sync-collection list + sample DTO payloads as a checked-in fixture; an Android
unit test asserts `CollectionRegistry` routes every emitted collection and
Moshi can decode every sample. Closes the bug class that produced the
nutrition slash-collection sync drop.
**Why:** the triplicated contract is the systemic DRY failure and a proven
source of silent prod bugs.
**Goal:** 3 (+1: prevents future sync drops). **Risk:** low (test/build-time
only). **Verify:** deliberately removing a registry alias fails the Android
test; deliberately renaming a DTO field fails the web CI diff. **Size:** M.

### Slice 11 — `refactor/web-read-cache`: read-through cache + repeat-view speed
**What:** extend the slice-4 data layer with a read-through cache for the
top read endpoints (dashboard sections, nutrition day, workout screens):
client components hydrate from cache instantly on navigation, revalidate in
background, and serve cached data offline with a staleness indicator; dedupe
the double `loadHiddenBiometrics` fetch; dynamic-import the heavy client deps
(`@dnd-kit`, `react-markdown`) so they leave the shared bundle.
**Why:** every nav currently pays full SSR + backend fan-out; this is the
LCP-repeat-view lever and gives web offline *reads*.
**Goal:** 2 (+1). **Risk:** medium (staleness UX; cache invalidation on
mutation via the slice-4 outbox hooks). **Verify:** Lighthouse harness:
repeat-view LCP ≤ 1.5 s on throttled 4G; first-visit ≤ 2.5 s; initial JS still
≤ 200 KB gz; Playwright offline-read e2e. **Size:** L.

### Slice 12 — `refactor/hygiene`: deletion pass + boundary cleanups
**What:** delete code obsoleted by earlier slices (bespoke retry paths, dead
"discard local" branches, superseded hand-written web types); decompose only
the god files earlier slices already had to modify (e.g. if
`WorkoutSessionScreen.kt`/`NutritionController.java` were touched, finish the
extraction); remove unused dependencies surfaced by the bundle work; wear
module gets the one unit test its token-relay logic deserves. No new
abstractions.
**Why:** goal 3 — leave the codebase navigable in an hour; prefer deletion.
**Goal:** 3. **Risk:** low. **Verify:** all suites green; net-negative LOC
diff stated in the progress doc. **Size:** M.

---

## Ordering rationale & dependencies

Safety net first (0–2): nothing risky ships without the integration suite,
harness, and migration protection. Contract ground before new writers (3
before 4). The journal (5) precedes conflicts (6) because per-field LWW keys
off receipt order the journal introduces. Pure-read perf (7–9) after the
write-path dust settles. Contract fixtures (10) land after 4/6 define the
final DTO shapes. Web read cache (11) builds on 4's data layer. Hygiene (12)
last so it deletes with full knowledge.

Independent pairs that could reorder if a slice stalls: 7/8/9 are mutually
independent; 1/2 can land in either order; 10 can move earlier if contract
drift bites during 4–6.

**Expected end state vs baseline** (all verified by the slice-0 harness):
sync p50 1.76 s → < 300 ms; recent-activity 609 → < 150 ms p95; cold starts
13 s → < 5 s; zero-mutation-loss on all clients; deterministic documented
conflict rules with append-only data conflict-free; repeat-view web LCP
≤ 1.5 s; migrations reversible and fixture-tested; the wire contract enforced
by CI instead of convention.
