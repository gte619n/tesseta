# ADR-0012: Active workout logging — device-local sessions, completion upsert to the backend

- Status: Accepted
- Date: 2026-06-10

## Context

The planning side of workouts is built: IMPL-14 gives a global Exercise
catalog, IMPL-15 lets the user design periodized programs (AI chat or manual)
and materializes dated `ScheduledWorkout`s, and the web shows a program
roadmap, a this-week strip, and a Workout History page. What does not exist is
**live capture of actuals** — tapping "start workout" at the gym, checking off
sets, recording the weight really lifted, and finishing the session. The
parity roadmap calls this Phase 7 and asks for this ADR before scoping;
`WorkoutSessionService` is a commented-out manifest stub.

Prior work constrains the shape more than a greenfield reading suggests:

- **The performed-session model already exists.** ADR-0008 (history import)
  added `LoggedSet(weightLbs, reps)` and `Prescription.loggedSets`, and writes
  each performed session as a `COMPLETED` `ScheduledWorkout` with
  `completedAt` / `durationSeconds` and a `session` snapshot. The Workout
  History view already reads this shape. Live logging should produce the same
  records, not a parallel model.
- **The goal metrics are dormant, not missing.** `FirestoreMetricResolver`
  wires `workouts.count` (via `Workout`) and `workouts.weeklyVolume` (via
  `WeeklyWorkoutAggregate`), but nothing produces those records today, so the
  metrics resolve unavailable/empty. Logging is the missing producer.
- **Offline infrastructure exists on the phone.** ADR-0007 (offline-first
  sync) gives Android an encrypted Room store, a sync engine, and a queued
  local-edit path. Gyms are exactly the poor-connectivity environment that
  work was built for.
- **ADR-0001 boundary.** Clients talk to the backend, never Firestore.
- Health Connect and Health Services dependencies are declared in the Android
  version catalog but unused.

The central architectural question: **where does the in-progress session
live?** Three options were considered:

1. **Server-resident live session.** A `POST .../start` creates a server
   session; every set logs as it happens; the server owns elapsed state.
   Real-time multi-device for free, but every tap needs connectivity — wrong
   for a basement gym — and it forces a write-heavy chatty API plus a
   server-side notion of "abandoned" sessions.
2. **Device-local session, completion-only upload.** The in-progress session
   is phone-local state; the backend learns about a session only when it
   finishes (or is skipped), via one idempotent upsert that flows through the
   existing offline outbox. No live server state, no chattiness, fully
   functional with zero connectivity.
3. **Hybrid checkpointing.** Local-first, but periodically PATCH partial
   progress so other devices can observe a session mid-flight. Extra API and
   conflict surface for a benefit (live spectating) nobody asked for.

## Decision

1. **Device-local live session; the backend sees only outcomes (option 2).**
   The in-progress session is owned by the phone — persisted in the
   IMPL-AND-20 encrypted Room store so it survives process death — and is
   uploaded once, on finish/skip, through the offline outbox. There is no
   server-side "active session" resource. If a session is never finished, it
   never reaches the backend; the local draft is resumable and discardable.

2. **Reuse the performed-session model; capture full actuals per set.**
   Finishing a session updates the existing `ScheduledWorkout`:
   `status=COMPLETED` (or `SKIPPED`), `completedAt`, `durationSeconds`, and
   per-prescription `loggedSets` inside the `session` snapshot — the same
   records the history import writes and the history view reads. `LoggedSet`
   expands from `(weightLbs, reps)` to **full actuals**:
   `(weightLbs, reps, rpe, restSeconds, completedAt)`. Every new field is
   nullable, so imported-history rows (weight-only) remain valid and the
   logger UI keeps everything beyond weight/reps skippable — collecting RPE
   and rest from day one feeds IMPL-15's deferred auto-progression without a
   later migration, but never slows down set entry.

3. **v1 logs program sessions only.** The logger operates on materialized
   `ScheduledWorkout`s — "today's session" from the active program. Ad-hoc
   quick-logging (a workout not tied to a program) is deferred to a
   follow-up; when it lands it reuses this same model (a `ScheduledWorkout`
   with null `programId`/`phaseId` and a user-picked, gym-scoped exercise
   list), not a parallel one, so nothing in v1 may bake in the assumption
   that every session belongs to a program.

4. **Abandoned sessions auto-finalize on the device.** A draft left untouched
   for 24 hours is finalized as `COMPLETED` with exactly the sets that were
   logged, `durationSeconds` derived from session start to the last set's
   `completedAt` (the per-set timestamps from Decision 2), and uploaded
   through the normal completion path. A stale draft with **zero** logged
   sets is discarded instead — there is nothing real to save. Either way the
   user can amend afterwards via the edit path in Decision 6.

5. **One idempotent completion endpoint, with server-side fan-out.**
   `PUT /api/me/workouts/sessions/{scheduledId}` upserts the outcome
   (status, completedAt, durationSeconds, loggedSets keyed by prescription).
   Retried deliveries from the outbox are safe. On completion the backend —
   not the client — writes the session-level `Workout` record and recomputes
   the week's `WeeklyWorkoutAggregate` (tonnage = Σ weight×reps over sets
   where both are present; weight-only sets count toward `sessionCount` but
   contribute no tonnage, matching the reps-null imported history). This
   turns on the dormant `workouts.count` / `workouts.weeklyVolume` goal
   metrics with no Goals changes.

6. **Android-first capture; web reads and edits, never logs live.** The v1
   logger is the phone: `WorkoutSessionService` as a
   `foregroundServiceType="health"` foreground service owning the timer and
   notification, Compose UI in `feature-workouts` (today's session → blocks →
   check off sets, edit weight/reps, rest timer). The web keeps
   history/detail and gains an after-the-fact "mark completed / edit actuals"
   form against the same upsert endpoint — no live web logger.

7. **Sensors and Wear are phased, but the seams are fixed now.** v1 is manual
   logging only (sets, reps, weight, duration). Phase 2 writes the completed
   session to **Health Connect** (and may read HR samples back to enrich
   `durationSeconds`-adjacent stats). The Phase 8 **Wear** companion talks to
   the *phone's* local session over the Data Layer — mirroring current
   set/rest state and adding Health Services HR — and never to the backend
   directly. Nothing in the v1 model may assume the phone UI is the only
   writer of the local session.

8. **No AI involvement.** Logging is deterministic; no Gemini calls. AI
   re-planning from logged performance stays behind IMPL-15's deferred
   auto-progression work.

## Consequences

- `WeeklyWorkoutAggregate` gains its first producer; the
  `FirestoreMetricResolver` caveat ("no producer writes aggregates") and the
  `Workout`-repository dead path both close. Goal steps like "complete 40
  sessions" start auto-checking.
- The backend change is small (one endpoint + aggregate recompute + the
  nullable `LoggedSet` fields); the bulk of the work is the Android session
  store, foreground service, and logger UI. Scoping splits into `IMPL-16`
  (backend completion API + aggregates, plus the thin web edit form) and
  `IMPL-AND-16` (phone logger), with ad-hoc quick-logging, Wear, and Health
  Connect as follow-ups on the seams fixed in Decisions 3 and 7.
- RPE and rest actuals accumulate from the first logged session, so when
  auto-progression is eventually scoped it starts with real training data
  instead of a cold start.
- Auto-finalized partial sessions count toward `workouts.count` and tonnage
  like any other completed session; if that proves noisy for goal metrics,
  the edit path (not a new model) is the lever.
- Editing a logged session after the fact is the same upsert; re-running it
  recomputes aggregates. Program re-activation continues to never rewrite
  `COMPLETED` sessions (existing IMPL-15 invariant).
- Multi-device live logging (start on phone, continue on tablet) is
  deliberately unsupported; the session is local until completed. Revisit only
  if a real need emerges — the completion endpoint is already the
  convergence point checkpointing would need.
- Imported history (ADR-0008) and live-logged sessions are indistinguishable
  to readers, which is the point — history, aggregates, and goals treat them
  uniformly.
