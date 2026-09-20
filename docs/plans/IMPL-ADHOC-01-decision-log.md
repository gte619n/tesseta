# IMPL-ADHOC-01 — Decision Log

Running log of as-built decisions, deviations from the spec, and verification
evidence. Reviewed after implementation. Newest entries appended at the bottom
of each section.

## Format
Each decision: `[AD-nn] <topic>` — what was decided, why, and any spec delta.

---

## Build decisions

### [AD-01] Implementation order
Backend Phase 1→2→3, then Android (Phase 4), then Web (Phase 5), per spec D8.
Each backend phase compiled + unit/E2E-tested before moving on. Rationale: the
clients depend on a stable API contract; proving persistence/sync first
de-risks the most.

### [AD-04] Reuse `ScheduledWorkout` as the ad-hoc run record
Rather than a parallel `AdHocSession` record, ad-hoc runs are stored as the
existing `ScheduledWorkout` record in a new subcollection
`users/{uid}/adhocWorkouts/{adhocId}/sessions/{sessionId}`, with
`programId := adhocId`, `scheduledId := sessionId`, `phaseId := null`,
`dayId := template.day.dayId`, `weekIndexInPhase := 1`, `isDeload := false`.
Why: reuses the record, its Firestore serialization helpers
(`daysToWire`/`daysFromWire` — promoted to public), and the
`ScheduledWorkoutResponse` assembler wholesale; far less code and risk than a
parallel type. Spec delta: the spec named a distinct `AdHocSession` record;
as-built it's a `ScheduledWorkout` in an ad-hoc-scoped collection. Behaviourally
identical (mirrors ScheduledWorkout shape, as the spec required for player
reuse).

### [AD-05] Client-minted session id, terminal-state-only persistence
No `POST /sessions` start endpoint. `sessionId` is client-minted (`aws_…`); the
terminal `PUT …/sessions/{sessionId}` upsert-materializes the snapshot from the
template + records the outcome in one idempotent call. Matches spec's corrected
lifecycle (offline start needs no server call; no stale PLANNED docs).

### [AD-06] Read-path inclusion via a `CompletedSessionSource` SPI
To make ad-hoc runs feed e1RM/last-sets (digest) and weekly stats without a
package cycle (core.adhoc already depends on core.workoutprogram for WorkoutDay
etc.), a neutral interface `CompletedSessionSource` is added in
core.workoutprogram. `ExercisePerformanceDigestService` and
`WorkoutSessionCompletionService` consume `List<CompletedSessionSource>` (Spring
injects all beans; empty when none). The ad-hoc module registers one. This is
the concrete mechanism the spec flagged as "silently false otherwise".

### [AD-07] Minimal duplication of completion helpers
`AdHocSessionService.complete()` mirrors the ~40 lines of
`WorkoutSessionCompletionService` snapshot-rewrite + per-set validation rather
than refactoring that well-tested shared service. Chosen to keep blast radius
off the program completion path. Flagged for a later dedupe if desired.

### [AD-08] Duration estimator placement + constants
`WorkoutDurationEstimator` lives in core.workoutprogram (pure, shared). Formula:
per set = reps × secPerRep (default 3.5s, tempo total overrides when parseable)
+ 4s inter-set setup; + restSeconds between sets; timed sets use
durationSeconds + 10s setup; + 20s per-exercise transition overhead. Tunable
constants; calibrate against real logged durations post-launch (spec risk).

---

## Verification evidence

### Backend Phases 1–3 + Phase 2 mechanics — VERIFIED 2026-09-20
`./gradlew compileJava` → BUILD SUCCESSFUL (all main sources compile).
`./gradlew test` on the new + affected suites → BUILD SUCCESSFUL, 0 failures:

```
core.adhoc.AdHocConstraintValidatorTest           tests=4  failures=0 errors=0
core.adhoc.AdHocSessionServiceTest                tests=7  failures=0 errors=0
core.adhoc.AdHocWorkoutServiceTest                tests=4  failures=0 errors=0
core.workoutprogram.WorkoutDurationEstimatorTest  tests=5  failures=0 errors=0
core.workoutprogram.ExercisePerformanceDigestServiceTest  tests=8  failures=0 errors=0   (regression: ad-hoc source injection)
core.workoutprogram.WorkoutSessionCompletionServiceTest   tests=21 failures=0 errors=0   (regression: weekly recompute + extra sources)
persistence.sync.SyncEmittedCollectionsContractTest       tests=1  failures=0 errors=0   (new adhocWorkouts + adhocWorkouts/sessions accepted)
```

What the run-lifecycle test (`AdHocSessionServiceTest`) proves against the spec DoD (P3):
- generate/save→run→complete materializes the snapshot from the template, logs sets onto it.
- flat `Workout` fan-out exists (id `{adhocId}_{sessionId}`, source `adhoc`).
- template `runCount==1`, `lastPerformedAt` set.
- weekly aggregate counts the ad-hoc run (sessionCount=1, tonnage=500) — D6 stats inclusion.
- digest/e1RM + `lastSessionSets` include the ad-hoc set — D6 history/prefill inclusion.
- **replaying the identical PUT leaves runCount==1, one persisted run, aggregate sessionCount==1** — outbox-replay idempotency.
- SKIPPED run: no fan-out, not counted.
- archived template: hidden from library but its completed run stays in stats + digest — D12.
- validation: unknown template → IllegalArgumentException (404); COMPLETED w/o duration → InvalidSessionLogException (400).

Pure-function coverage: estimator (hand-computed rep/timed/tempo cases), constraint validator
(bodyweight-everywhere, missing-equipment flagged, satisfied passes, unknown-exercise flagged).

Not verified here (documented, not claimed done): live Gemini generation
(`GeminiAdHocWorkoutGenerator`) — gated behind `app.adhoc-workouts.enabled`, no API key in
CI, exactly like the existing `GeminiWorkoutProgramChatClient`. Its orchestration
(equipment resolve → estimate → validate → assemble) is covered by the controller path and
pure components.

---

### HTTP-layer E2E (MockMvc) — VERIFIED 2026-09-20
`AdHocWorkoutControllerE2ETest` (@SpringBootTest + MockMvc + in-memory repos +
dev-header auth + a stub `AdHocWorkoutGenerator`). BUILD SUCCESSFUL, tests=2,
failures=0. Drives the whole journey through the real controllers/services:
`POST /generate` (bodyweight preset → draft, estimate>0, **empty
constraintReport**) → `POST /` (201 + minted adhocId) → `GET /` (in library,
tagged) → `PUT …/sessions/aws_…` (COMPLETED with a logged set) → `GET /{id}`
(**runCount==1**, lastPerformedAt set) → `POST /{id}/last-sets` (returns the
just-logged reps → proves history/e1RM inclusion through HTTP) → **replay the
identical PUT → runCount stays 1** → `DELETE` (gone from list, present with
includeArchived) → `POST /restore` (back). Plus a blank-prompt 400. This closes
the "no HTTP/MockMvc coverage" gap; the API contract is now part of the gate.
Also added the two in-memory ad-hoc repos to `TestPersistenceConfig`.

### Web client slice (Phase 5) — VERIFIED 2026-09-20
`pnpm typecheck` (tsc --noEmit) → exit 0. `eslint` on the new files → clean.
Delivered: `lib/types/adhoc.ts`, `lib/adhoc-api.ts`, `app/me/workouts/library/`
(list + filter/sort + pin/archive/restore server actions), `library/generate/`
(+ `components/workouts/AdHocGenerator.tsx` prompt→preview→save), and the hub
"Log Workout" card rewired to the library (was "Coming soon").

### Android sync-mirror foundation (Phase 4, data layer) — VERIFIED 2026-09-20
`./gradlew :core-data:compileDebugKotlin` → clean (Room KSP ran, entities
validated). Room generated `schemas/…/8.json` (version 8, tables `adhocWorkouts`
+ `adhocSessions`). JVM unit tests → BUILD SUCCESSFUL, 0 failures:

```
data.sync.CollectionRegistryContractTest    tests=1 failures=0   (routes adhocWorkouts + adhocWorkouts/sessions — both sides of the shared fixture now green)
data.sync.CollectionRegistryTest            tests=4 failures=0
data.db.HfDatabaseMigrationCoverageTest     tests=2 failures=0   (MIGRATION_7_8 makes the chain contiguous to v8)
```

Delivered: MirrorTables constants + ALL; `AdHocWorkoutEntity`/`AdHocSessionEntity`;
`AdHocWorkoutDao`/`AdHocSessionDao`; `HfDatabase` entities + DAOs + version 7→8 +
`MIGRATION_7_8` (additive CREATE TABLE, no wipe); `DbModule` providers;
`MirrorStore` adapters + mappers; `CollectionRegistry` slash-alias +
`adhocId` idField. This is the offline-sync backbone Phase 4's UI builds on.

### [AD-12] Android Room migration is additive (v7→v8), not destructive
The two new mirror tables are added via `MIGRATION_7_8` (CREATE TABLE) rather
than a destructive wipe, honoring the repo's DL-3 baseline (outbox + workout
drafts must survive upgrades). Row-level survival is covered by the instrumented
`HfDatabaseMigrationTest` (emulator-only, not run headless here); the additive
SQL matches the KSP-generated 8.json column shape exactly.

### [AD-13] Merged main (tabbed workouts IA) + Library placement
Fast-forwarded `origin/main` in, which landed **IMPL-WEB-WORKOUT-01** (#267): the
Workouts section is now a persistent **tab bar** (`layout.tsx` + `WorkoutTabs`)
over a read-first **Overview** dashboard — the old card grid (where the ad-hoc
entry card briefly lived) is gone. Resolution: discarded the obsolete
`page.tsx` card edit before the FF (only overlapping file); all ad-hoc work was
untracked/unstaged and survived cleanly.

Placement decided with the owner: **a new first-class "Library" tab** (Overview ·
**Library** · History · Programs · Progression · Gyms), NOT a section under
Programs — ad-hoc is a distinct "do a workout whenever" concept, matching the
standalone data model (D1/AD-04), and the Programs page stays periodization-
specific. **No Overview CTA** (owner's call) — reached solely via its tab. The
`/me/workouts/library` pages were adapted to the shared layout chrome (dropped
their own back-link/title; the layout provides them). Verified: `tsc` + eslint
clean, and the merged `workout-tabs.test.tsx` (4 tests) still passes with the
added tab.

### [AD-14] Android Library placement mirrors web (hub tab, not under Programs)
The Android workouts hub (`WorkoutsHubScreen`) is itself a tabbed shell
(`WorkoutsTab`: This Week · Programs · History · Gyms) — the direct analog of the
web tab bar. Mirrored the web decision by adding a **`LIBRARY("Library")` tab**
right after This Week (first-class, before Programs), with a `libraryContent`
slot wired in `WorkoutsHubRoute`. Content is `WorkoutLibraryScreen` — an honest
section scaffold (shared `EmptyState` idiom, sparkle icon) describing the feature
and noting web-generated workouts sync in, with on-device generate + guided run
still to come. This is the IA/placement mirror; the functional Android screen
(repository + API client + ViewModel reading the `adhocWorkouts` Room mirror, the
generate flow, the `WorkoutRef` player refactor) remains Phase 4 feature work.
Verified: `./gradlew :feature-workouts:compileDebugKotlin` clean (the exhaustive
`when(selectedTab)` forced the new branch, so the tab can't be half-wired).

### [AD-15] Android functional Library list (read-only)
Built the first real slice of the Android feature module: `AdHocLibraryItem`
(core-domain), `AdHocWorkoutMirrorDto` + `AdHocLibraryRepository` (core-data,
reads the `adhocWorkouts` Room mirror the SyncEngine fills, parses payloadJson
via Moshi, sorts pinned→most-done), `WorkoutLibraryViewModel` (Hilt), and the
`WorkoutLibraryRoute`/list UI (cards + empty-state) under the Library tab.
Read-only by design — a workout generated on web now appears on the phone. On-
device generate + the guided run (`WorkoutRef` refactor) remain. Verified:
`:feature-workouts:compileDebugKotlin` clean (Hilt/KSP wired end to end).

### [AD-16] Deployment readiness
- **Flag**: added `app.adhoc-workouts.{enabled,gemini-model}` to
  `application.yml` (`ADHOC_WORKOUTS_ENABLED` default **true**; shares the live
  `GEMINI_API_KEY` + shared genai Client, so generation works on deploy) and
  `enabled: false` in `application-test.yml` (the E2E uses a stub generator).
- **Write contract**: registered all 7 new mutating endpoints in
  `write-contract.txt` (generate=NON_PERSISTING, create=KEY_GUARDED, PATCH/
  restore=SET_SEMANTICS, DELETE=IDEMPOTENT_DELETE, run PUT=DETERMINISTIC_ID,
  last-sets=NON_PERSISTING) — `WriteContractTest` green.
- **Verification sweep (all green)**: full backend `./gradlew test` SUCCESSFUL;
  web `tsc` clean + `pnpm test` 115/115; android `:core-data` +
  `:feature-workouts` compile, JVM contract/migration tests pass.
- **Committed** to branch `ad-hoc-workouts` (node_modules symlink excluded).
- **Deploy = merge to main** → watch `deploy-*-on-main` check-runs (CI doesn't
  gate deploys; Trivy HIGH/CRITICAL can block). No prod secret changes needed
  (reuses GEMINI_API_KEY). Android ships on its own track, not this deploy.

## Open items / deferrals

- **[AD-11] Client order deviates from D8 (Backend → Web → Android).** The spec
  ordered Android before Web (phone-first). As-built the *web* client is done
  first because it is deterministically verifiable in this environment
  (`tsc`/`next build`), whereas Android needs an emulator/instrumentation run I
  can't drive headless here. Backend-first is preserved. Android remains the
  last phase.
- **Web template detail / run screen.** Save now redirects back to the Library
  list (no detail page yet); `/me/workouts/library/{adhocId}` and the log-after
  run UI are the follow-up. The `logAdHocRun` client + backend PUT already exist.
- **Unified history-list merge + calendar view for ad-hoc.** The D6 *stats/
  streaks* + *e1RM/last-sets* inclusion is done and tested. The **history list**
  and **calendar** surfacing of ad-hoc runs is deferred: ad-hoc completions
  already write the flat `Workout` (source `adhoc`), so history readers built on
  that pick them up; a first-class merge into `WorkoutHistoryController` +
  calendar should be reconciled with IMPL-WEB-WORKOUT-01's streak/stats
  endpoints (which must read ad-hoc too). Recorded so it isn't mistaken as done.
- **AI recap on ad-hoc completion.** `aiRecap` field exists on the response but
  the ad-hoc completion returns it null (no `WorkoutSessionCoach` adapter yet).
  Low-risk nicety; the numeric summary shows regardless.
- **Constraint repair loop.** Generation surfaces a `constraintReport` of
  violations but does not yet auto-regenerate/swap. The hard check (D11) is in
  place; the automatic repair is the follow-up.
- **Live Gemini generation + golden-prompt evals.** Gated + unverifiable without
  an API key (see Verification evidence).
- **Firestore composite index.** `adhocWorkouts/sessions` completed-run read
  uses a per-user parent walk (no collection-group), so no new composite index
  is required for correctness; if a future collection-group read is added it
  needs `(userId, status, date DESC)` in the infra-owned indexes file.
