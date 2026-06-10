# IMPL-16 — Active workout logging: implementation decision log

Working log of decisions made and questions encountered while implementing
[ADR-0012](../decisions/ADR-0012-active-workout-logging.md). Each entry records
the question, the decision taken so work could proceed, and the rationale.
Items marked **OPEN** need a human call before or shortly after merge.

## Orchestrator decisions (made up front, before agent fan-out)

### D1 — Completion endpoint nests under the program
**Question:** ADR-0012 names `PUT /api/me/workouts/sessions/{scheduledId}`, but
`ScheduledWorkout` is keyed `(userId, programId, scheduledId)` and
`ScheduledWorkoutRepository` has no programId-free lookup; `scheduledId`
(`"{date}_{dayId}"`) is only unique within a program.
**Decision:** `PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}`,
beside the existing program/calendar routes. When ad-hoc sessions land
(deferred), they get their own container or a synthetic program id — the
endpoint shape doesn't block that.

### D2 — Request/response contract
**Decision:**

```
PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}
{
  "status": "COMPLETED" | "SKIPPED",
  "completedAt": "<ISO instant>",      // required for COMPLETED
  "durationSeconds": 3210,             // required for COMPLETED
  "logged": [
    { "blockId": "...", "orderIndex": 0,   // identifies the Prescription
      "sets": [ { "weightLbs": 135.0, "reps": 8, "rpe": 8.5,
                   "restSeconds": 90, "completedAt": "<ISO instant>" } ] }
  ]
}
→ 200 with the updated ScheduledWorkoutResponse
```

Prescriptions have no id, so logged sets key by `(blockId, orderIndex)` against
the session snapshot. Unknown keys are a 400 (nothing silently dropped, same
stance as the IMPL-15 validator). Repeat PUTs replace actuals and re-run the
fan-out (idempotent upsert per ADR-0012 Decision 5).

### D3 — `LoggedSet` grows three nullable fields
**Decision:** `LoggedSet(weightLbs, reps, rpe, restSeconds, completedAt)`
(ADR-0012 Decision 2). All new fields nullable; imported-history rows stay
valid; Firestore mapper and deep-response DTO carry them through.

### D4 — Status transitions are permissive
**Question:** which transitions does the upsert allow?
**Decision:** any of PLANNED/COMPLETED/SKIPPED → COMPLETED or SKIPPED.
COMPLETED requires `completedAt` + `durationSeconds`; SKIPPED clears actuals.
Rationale: ADR-0012 makes after-the-fact editing the correction lever, so the
server doesn't enforce a one-way lifecycle.

### D5 — Fan-out semantics
**Decision:** on COMPLETED the backend (in `core/`, one service):
- saves a `Workout` with `workoutId = "{programId}_{scheduledId}"` (unique per
  user, idempotent), `startTime = completedAt - durationSeconds`,
  `endTime = completedAt`, `activityType = "STRENGTH"`, `source = "logger"`,
  `locationId` from the session;
- recomputes that ISO week's (Monday-start) `WeeklyWorkoutAggregate` by
  scanning the user's COMPLETED sessions across programs in the week:
  `sessionCount`, `totalTonnage = Σ weightLbs×reps` where both present
  (weight-only imported sets contribute count, not tonnage — ADR-0012 D5);
- publishes `MetricChangedEvent` for the workout metrics (closing the TODO in
  `WorkoutRepository`) so goals re-evaluate.
SKIPPED writes no `Workout` and removes a previously fanned-out one if the
session is being un-completed.

### D6 — Web surface
**Decision:** per the interview, web gets a "log result / edit actuals" modal
(ModalBackdrop primitive, server-action-as-prop, toasts) reachable from the
program detail this-week strip and the history list. No live timer.

### D7 — Android v1 shape
**Decision:** per ADR-0012: Room-backed local draft (IMPL-AND-20 encrypted
store), `WorkoutSessionService` foreground service (`health` type) for
timer/notification, Compose logger in `feature-workouts` launched from
today's scheduled session, completion upload through the offline outbox,
stale-draft auto-finalize (24 h; zero-set drafts discarded) checked on app
open plus a periodic worker.

## Agent decisions (logged during implementation)

### A1 — Core error surface for the completion upsert
**Question:** how does `WorkoutSessionCompletionService` (core, no Spring Web)
signal not-found vs. invalid-request so the controller can map 404 vs 400?
**Decision:** missing session → `IllegalArgumentException` (same as
`WorkoutScheduleService.activate`); invalid request → a nested
`InvalidSessionLogException` carrying the flat human-readable issue list
(same shape as `WorkoutProgramValidator`), so the API can return issues inline.

### A2 — Repeat PUTs replace ALL actuals
**Decision:** the request's `logged` list fully replaces previous actuals:
prescriptions absent from the payload end up with `loggedSets = null`. This is
what makes the re-PUT a true upsert (D2) — partial-merge semantics would make
deleting a mistaken entry impossible.

### A3 — SKIPPED with logged sets is a validation error
**Decision:** a SKIPPED upsert carrying logged entries is rejected (not
silently dropped), consistent with the unknown-key stance in D2. SKIPPED also
clears `completedAt`/`durationSeconds` along with all `loggedSets` (D4).

### A4 — Aggregate week keyed by the session's scheduled date
**Decision:** the ISO week recomputed is the one containing
`ScheduledWorkout.date` (Monday `previousOrSame`), since the week scan is a
date-range query over `findByProgram`. Recompute is a full rescan across all
programs and writes `0 / 0.0` when the week empties (no aggregate delete API).

### A5 — Workout persistence goes live as `FirestoreWorkoutRepository`
**Decision:** replaced the `UnsupportedOperationException` scaffold with a
Firestore impl at `users/{userId}/workouts/{workoutId}` (Timestamps for
start/end, server-stamped created/updated). `delete` is the IMPL-AND-20
soft-delete tombstone (`syncStatus=ARCHIVED` + bumped `updatedAt`) and a later
re-complete revives the doc via the upsert. Because the bean is now
`@ConditionalOnProperty`-gated like the rest of persistence,
`TestPersistenceConfig` gains an `InMemoryWorkoutRepository`. The defensive
`UnsupportedOperationException → 0` catch in `FirestoreMetricResolver.countSince`
stays (a test pins the degradation behavior).

### A6 — Request DTO embeds the core actuals records
**Decision:** `LogSessionRequest(status, completedAt, durationSeconds, logged)`
where `logged` is the service's `LoggedPrescription` record (which carries core
`LoggedSet`s), so the wire shape matches D2 byte-for-byte with no mapping
layer. Precedent: `CreateProgramRequest` embeds core `ProgramPhase`/
`ProgramSchedule` the same way.

### A7 — Controller error mapping for the upsert
**Decision:** the endpoint lives on `WorkoutProgramController` beside the other
`/{programId}/...` session routes. Missing program (pre-checked) or missing
session (the core `IllegalArgumentException`) → 404; an
`InvalidSessionLogException` → `ResponseStatusException(BAD_REQUEST, issues
joined with "; ")`, which `GlobalExceptionHandler` renders as the conventional
`ErrorResponse{error, message, timestamp}` 400 body. (The global handler would
otherwise map the core `IllegalArgumentException` to 400 and the nested
exception to 500, so the controller catches both explicitly.)

### A8 — Response serialization of full actuals needed no DTO change
**Decision:** `PrescriptionResponse.loggedSets` already embeds the core
`LoggedSet` record, so `rpe`/`restSeconds`/`completedAt` serialize everywhere
loggedSets appear (upsert response, history, calendar, deep program) without
touching the assembler; the MockMvc tests pin the JSON shape. The upsert
announces `workoutPrograms/scheduled` via `SyncChangeNotifier` with a null
origin device, matching `activate` on the same controller.

### A9 — `ScheduledWorkoutResponse` gains `programId`
**Question:** the D6 history-list edit path needs to address the completion
upsert, but history rows span programs and `scheduledId` is only unique within
a program (D1) — the web gated the Edit affordance on a `programId` the backend
never serialized.
**Decision:** add `programId` to `ScheduledWorkoutResponse` (the assembler has
it on the core `ScheduledWorkout`), serialized on every scheduled-workout read
(history, calendar, activate, upsert response). Additive, so Android's Moshi
DTOs and program-scoped web readers are unaffected; the MockMvc tests pin it.

### A10 — Outbox: a terminal 4xx parks the mutation instead of retrying forever
**Question:** the completion upsert is the first outbox producer whose 400/404
can be manufactured by ordinary concurrent edits (web re-activation rewriting
PLANNED sessions mid-phone-session), and the Room draft is deleted on finish —
the previous drain loop would replay the doomed payload on backoff forever.
**Decision:** `RestOutboxReplayClient` now throws a typed
`OutboxReplayHttpException`; the drain loop parks deterministic client
rejections (4xx except 401/408/425/429) with `nextAttemptAt = Long.MAX_VALUE`
instead of backing off. The row and its wire payload are **kept** (no silent
deletion), it stays in the D11 FAILED count, and the manual
"changes failed — retry" lever re-arms parked rows (`OutboxDao.rearmFailed`)
before re-draining.
**OPEN:** parked completion uploads still have no end-user recovery beyond the
blind manual retry — restoring the draft into the logger (so the user can
re-log against the rewritten snapshot) vs. backend accept-and-flag of unknown
keys (would contradict D2) needs a product call.

### A11 — The logger screen requests POST_NOTIFICATIONS
**Decision:** `WorkoutSessionRoute` asks for the API 33+ notification grant
once when the logger opens (same idiom as the nutrition Capture screen's
CAMERA request), as the manifest comment promised. Best-effort: a denial is
non-blocking — the foreground service still runs, there's just no shade entry.

## Web decisions

### W1 — Modal save prop returns `Promise<void>`
The server actions discard the response; `revalidatePath` + `router.refresh()`
deliver updated data, matching the `ProgramEditModal` server-action-as-prop
idiom.

### W2 — Edit form sends a full replace
Every prescription is sent keyed by `(blockId, orderIndex)`; an emptied set
list clears that exercise's actuals. Full-replace is the only semantics that
lets a user delete sets from an edit form (pairs with A2), and all keys come
from the session snapshot so none can 400 as unknown.

### W3 — Prefill for a never-logged session
One row per prescribed set: reps from `repsMax ?? repsMin`, RPE from the
prescription's RPE intensity, rest from prescribed `restSeconds`, weight blank
(no target exists; a weight-null set is valid, e.g. bodyweight). "Add set"
duplicates the previous row.

### W4 — SKIPPED confirmation is conditional
Danger-toned `useConfirm` only when the session is COMPLETED or has logged
sets (that's when D4's "SKIPPED clears actuals" is destructive); skipping a
clean PLANNED session needs no confirm.

### W5 — Per-set `completedAt` is not web-editable
Phone-captured per-set timestamps are carried through row state untouched;
new/web-edited rows send null. The web is an after-the-fact editor (ADR-0012
D6), and editing must not destroy the timestamps that drive duration/rest
derivation.

### W6 — No new web tests
`web/` has no unit-test runner (no test script; the only web tests are the
manual Selenium UAT suite in `uat/`, which needs a running stack). Gates run:
typecheck, lint, build — all green.

## Android decisions

### N1 — Outbox carries a separate wire payload
The completion upload's wire body (D2) differs from the mirror payload the
calendar UI reads, so `MirrorRepositorySupport` gained an
`updateLocalWithWire(...)` overload: PENDING mirror row written with one JSON,
outbox UPDATE enqueued with the other. No direct-call fallback needed; the
write behaves exactly like existing offline writes (idempotency key, backoff,
drain kick). Outbox identity reuses the existing `workoutScheduled` mirror
table with composite id `"{programId}/{scheduledId}"`, and the endpoint
registry maps every op to the idempotent PUT.

### N2 — Draft is a dedicated non-mirror Room entity
`WorkoutSessionDraftEntity` keyed `(programId, scheduledId)` with
repository-encoded Moshi JSON columns (`sessionJson` snapshot, `loggedJson`
in the D2 wire sub-shape — no Room TypeConverters, matching the codebase's
opaque-JSON idiom). The draft never exists on the backend, so mirror sync
bookkeeping would be meaningless.

### N3 — DB v3→v4 is a real additive migration
`Migration(3,4)` registered (verified byte-identical to the exported
`schemas/4.json`), keeping `fallbackToDestructiveMigration()` only for unknown
versions. Mirror tables can be wiped and resynced; a draft is the only copy of
an in-progress session, so a destructive upgrade would silently destroy user
data (ADR-0012 Decision 1).

### N4 — Finish/skip/discard delete the draft immediately
Only `DraftStatus.ACTIVE` is ever persisted; on finish the outbox row becomes
the single owner of the upload. Keeping a COMPLETED draft alongside the outbox
mutation would create two competing sources of upload truth.

### N5 — Auto-finalize semantics
Stale = idle strictly >24h (`lastActivityAt < now − 24h`), swept by a 6-hourly
unconstrained `@HiltWorker` plus an immediate pass on app open
(`WorkoutSessionBootstrap`, wired like `FirstSyncGate`). `completedAt` =
max per-set `completedAt`, falling back to `lastActivityAt` when no set
carries a timestamp (all LoggedSet fields are nullable per D3);
`durationSeconds` clamped ≥ 0. Skip works with or without a draft (a bare
`{"status":"SKIPPED"}` needs no startedAt).

### N6 — Foreground service is stateless glue, started by observation
The service takes no extras: a `WorkoutSessionForegroundLauncher` observes
`observeDrafts()` and starts the service when a draft appears; the service
observes the newest active draft and stops itself when none remains.
START_STICKY + draft-driven launch give two independent rehydration paths
after process death, and the UI never references the `:app` module.

### N7 — API 34+ health-FGS gate via HIGH_SAMPLING_RATE_SENSORS
The `health` foreground-service type (bound by ADR-0012 Decision 6) requires
either that install-time permission or an ACTIVITY_RECOGNITION/BODY_SENSORS
runtime grant; the install-time permission satisfies the gate with zero
prompting for a manual logger that uses no sensors. **OPEN:** Play's
foreground-service declaration review may query the unused-sensor permission.

### N8 — Notification uses the platform chronometer
Count-up anchored at `startedAt` (workout) or count-down at rest `endsAt` —
no per-second re-posts. Rest-timer state is an in-memory `StateFlow` in
core-data (deliberately not persisted: a rest countdown that outlives the
process is stale garbage; but it can't live in the UI because the service —
and later Wear — read it too). "Current exercise" derives deterministically
from the draft: first prescription in `(blockId, orderIndex)` order with
fewer logged sets than prescribed. First notification channel in the app:
`workout_session`, IMPORTANCE_LOW, ongoing.

### N9 — Set check-off ergonomics and derived actuals
A checked set prefills weight/reps from the previous set (falling back to
prescription targets), stamps `completedAt = now`, and derives actual
`restSeconds` from the gap since the last set logged anywhere in the session
when 1s–30min (else null) — zero extra taps for rest data. Only the next
unlogged row can be checked (dense, ordered list) but any row can be
unchecked. Start affordances are suppressed while any draft is in flight
(single-session-in-flight assumption). One route serves Start and Resume
(`repository.start()` resumes an existing draft). **OPEN:** the 30-min cap
and cross-prescription rest basis are judgment calls — sanity-check before
this trains auto-progression.

## Open questions for review

1. **Aggregate scope vs archived programs** — the weekly recompute scans
   programs from `findByUser`, which excludes tombstoned programs; completed
   sessions under an archived program drop out of the aggregate on the next
   recompute. Intended?
2. **Review minors, logged not fixed** (the review fixer handled all four
   critical/major findings; these three minors remain):
   a. `FirestoreWorkoutRepository.delete()` on a never-completed session
      fabricates a phantom tombstone doc (cosmetic);
   b. no per-set numeric validation — negative weight/reps are accepted and
      would poison weekly tonnage;
   c. `DbWiper`'s schema-version wipe (`clearAllTables`) destroys in-flight
      session drafts (rare: only on schemaVersion bumps).
3. **Parked outbox uploads** (see A10) — no expiry, and the only recovery is
   the blind manual retry; restoring a parked completion into the logger is a
   product call.
4. **Web offers "Log result" on future-dated this-week rows** — D4's
   permissive transitions allow it; gate or de-emphasize?
5. **Sandbox gaps** — not verifiable here, need a device/emulator + deployed
   stack: Room 3→4 migration instrumented test, sync E2E with the new outbox
   parking, foreground-service behavior on a real device, a UAT flow for
   log-result, and a backend run with real Firestore. Android builds also
   need `WEB_OAUTH_CLIENT_ID` available (tests ran with a placeholder).
6. **`seed_preview.json`** regenerated by the seed-mapping test with the new
   wire shape (rpe/restSeconds/completedAt nulls) — committed alongside the
   backend change since it's deterministic test output.
7. **Malformed enum values in request JSON surface as 500** via the
   `GlobalExceptionHandler` catch-all (pre-existing, codebase-wide; noted
   while testing the new endpoint).
8. **Mirror convergence nuance** — after a successful completion replay, the
   phone's mirror row keeps the client-built snapshot until the next delta
   pull replaces it with the server's canonical row (normal IMPL-AND-20
   behavior, just worth knowing when debugging).
