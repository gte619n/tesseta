# IMPL-ADHOC-01: Ad-hoc Workout Library

> **STATUS: PLANNED (not yet built)** — greenfield feature on branch
> `ad-hoc-workouts`. A new, **standalone** entity alongside the existing
> `WorkoutProgram` tree; deliberately does **not** reuse programs as fake
> single-day programs. Owner decisions locked via interview **2026-09-20**
> (see [Decisions](#decisions)). Backend-first, then Android, then web.

## Goal

Give the user a **library of purpose-driven, reusable workouts** they can
generate on demand and "knock out whenever" — travelling, a Sunday at home,
a hotel gym, or with whatever single implement is on hand. Examples the
feature must satisfy verbatim:

- *"I'd like a 30-minute workout in a hotel gym."*
- *"I'd like a 45-minute full-body workout that is body-weight only and one
  35 lb kettlebell."*

Each library entry is a **durable, reusable template**. Creation is
**AI-first** from a natural-language prompt (with quick equipment presets),
and the generated result is a **fully editable** workout. Templates have full
**CRUD + archival**. Doing a template spawns an independently-logged **run**
(session) that flows into the **same unified workout history, stats/streaks,
and calendar** as program sessions — but ad-hoc runs do **not** drive the
program's forward progression engine.

## Background — what already exists (reuse, don't reinvent)

### Domain / persistence
- **Workout template tree** (`backend/.../core/workoutprogram/`):
  `WorkoutProgram → ProgramPhase → WorkoutDay → Block → Prescription →
  LoggedSet`. **`WorkoutDay` (blocks + prescriptions) is the exact unit an
  ad-hoc workout needs** — reuse it as the template body; ignore the
  phase/schedule machinery above it.
- **Materialized session**: `ScheduledWorkout` (record) holds a denormalized
  `session: WorkoutDay` snapshot + `status (PLANNED|COMPLETED|SKIPPED)`,
  `completedAt`, `durationSeconds`, `feeling`, and per-prescription
  `loggedSets`. Its **shape is what the guided player consumes** — the ad-hoc
  run object mirrors it so the client player is reused with minimal change.
- **Flat history fan-out (ADR-0012)**: on completion a flat `Workout` record
  (`users/{userId}/workouts/{workoutId}`) is written. This is what history
  aggregates read.
- **Soft-delete / archival**: `syncStatus = ACTIVE|ARCHIVED` tombstone
  (`SyncStatus`, `FirestoreMapper.isArchived`), `delete()` = merge
  `syncStatus=ARCHIVED` + bump `updatedAt`; list/find filter archived,
  `findByUserIncludingArchived()` for full history. **Reuse verbatim.**
- **ID minting convention**: `wp_`, `ex_`, `eq_` + 12-char UUID via
  `syncWrite.idempotentCreate()` (idempotent on `Idempotency-Key` header).

### Equipment / exercises (the constraint spine)
- **Global exercise catalog** (`core/exercise/Exercise.java`): `exerciseId`
  (`ex_…`), muscles, `movementPattern`, `mechanic`, `isTimed`,
  `suitableBlockTypes`, `demoFrames` (guided-player media), `mediaStatus`,
  `status (DRAFT|PUBLISHED|ARCHIVED)`, and **`requiredEquipment`** =
  list of `EquipmentRequirement` **any-of** groups.
- **Availability seam** (`core/exercise/ExerciseAvailabilityService.java`):
  `satisfiedBy(exercise, Set<equipmentId>)` — pure boolean, true iff **every**
  requirement group intersects the equipment set; bodyweight (zero
  requirements) is executable everywhere. `executableAt(userId, locationIds…)`
  builds per-gym allow-lists with no N+1. **This is the hard-constraint check
  for the repair loop.**
- **Equipment catalog** (`core/equipment/Equipment.java`): global catalog,
  `SpecSchema` incl. `WEIGHT_SET` with `specs = {minWeight, maxWeight,
  increment, weights[]}` — **captures specific available loads** (e.g. a 35 lb
  kettlebell). `Location` (per-user gym) carries `equipmentIds[]` +
  `equipmentSpecs` per-gym overrides.
- **Progression / history** (`core/progression/`, `core/workoutprogram/
  ExercisePerformanceDigestService.java`): per-(user,exercise)
  `ExerciseDigest` with `estimated1Rm` (Epley), `bestRecentWeight/Reps`,
  `lastPerformed`, `lowConfidence`. `LoadingProfileResolver` derives increment
  / offset (bar/machine/bodyweight). Backs weight prefill + `last-sets`.

### AI generation rails
- **Program designer** (`integrations/workoutprogram/
  GeminiWorkoutProgramChatClient.java`): agentic Gemini loop (model
  `gemini-3.1-pro-preview`, `app.workout-programs.gemini-model`) with terminal
  `propose_workout_program` tool + reusable data tools `get_exercise_history`,
  `get_lab_history`; system prompt injects **per-gym executable-exercise
  allow-lists** and enforces "**only prescribe an `exerciseId` in the
  allow-list**" and "**never prescribe `targetWeightLbs` above e1RM**". Load
  grounding: `targetWeightLbs = e1RM × target% × staleness-discount`
  (<2 wk none, 2–6 −10%, 6–12 −20%, >12 −30%). **We build a dedicated,
  single-day generator that reuses these tools + grounding, not this
  multi-phase client.**
- **Preview→confirm UX precedent**: nutrition `AdjustWithAi.tsx`
  (prompt → *Thinking…* → preview card w/ delta → Apply/Discard). Mirror for
  the generate flow.

### Clients
- **Android guided player** (`feature-workouts/.../session/
  WorkoutSessionViewModel.kt`, ADR-0012): device-local `WorkoutSessionDraft`
  in Room keyed by `(programId, scheduledId)` → set logging survives process
  death / offline → terminal COMPLETE/SKIP via **outbox**. Rest/get-ready
  timers, exercise swap, e1RM load prediction, AI recap. **Reuse the player;
  generalize the draft key from "program session" to a `WorkoutRef`.**
- **Web** (`web/app/me/workouts/`): program **chat designer**, **history**
  (with idempotent `logSession` upsert + `customizeSession`), gyms,
  progression. The hub's **"Log Workout" card is literally *Coming soon*** —
  this feature fills that gap. Offline **outbox** exists (`web/lib/offline/
  outbox.ts`, IndexedDB).
- **Sync**: pull `GET /api/me/sync` + `MirrorOps` + `CollectionRegistry` on
  each client; push via outbox. ⚠️ **Memory `nutrition-sync-slash-collection-bug`:
  the Android `CollectionRegistry` must register BOTH the dotted and the
  **slash-form** subcollection alias (`adhocWorkouts/sessions`) the backend
  delta emits, or cross-device runs resolve to null and get SKIPPED.**

**Nothing ad-hoc exists today** — no unscheduled/free-workout concept on any
platform, no half-built endpoints or flags. Clean gap.

## Scope

**In scope (v1):**
- New standalone `AdHocWorkout` **template** entity + its **run/session**
  lifecycle; full **CRUD + archival + restore**; sync registration on both
  clients.
- **Dedicated single-workout AI generator** (one `WorkoutDay`, time-budgeted,
  equipment-constrained) with **post-generation constraint validation +
  repair**, **server-side duration estimator**, and **equipment presets +
  free-text** resolution.
- **Prompt → preview (editable) → Save to library and/or Start now**, plus
  **regenerate / refine-by-chat**.
- **Organization**: tags/categories (AI-suggested, user-editable), search +
  filter (tag, duration, equipment), **favorites/pin**, **sort by
  recent/most-done**.
- **Android**: library screen + generate/preview + guided run (reuse player) +
  offline run.
- **Web**: library CRUD/manage + generate/preview + template editor
  (+ log-after-the-fact via existing upsert).
- Ad-hoc runs feed **exercise history / e1RM / last-sets prefill** and appear
  in **unified history, stats/streaks, and calendar**.

**Out of scope / explicit NON-goals (v1):**
- Sharing / community / public template gallery.
- Converting an ad-hoc workout into a scheduled multi-week program.
- Run-over-run **auto-progression** of a repeated template (ad-hoc never
  drives the program's forward "next session" engine).
- A from-scratch **manual exercise-by-exercise builder** with no AI (editing an
  AI-seeded draft **is** in scope).
- AI proposing **new catalog exercises** — generation is restricted to the
  existing published catalog (see D9).
- Web **guided in-session player** parity with Android (web v1 = create/manage/
  edit + log-after; live guided run stays Android). Revisit post-v1.

## Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Data model | **New standalone entity** `AdHocWorkout` (one `WorkoutDay`, no phases/schedule) in its own collection; runs reuse the `ScheduledWorkout`/`Workout` **shape** for history. Not modelled as a 1-phase program. |
| D2 | Template vs run | **Reusable template, many runs.** Each "knock it out" spawns a new dated session; the same template repeats forever, each run independently logged. |
| D3 | Creation | **AI-generate, then fully editable.** NL prompt is primary; Update = manual edit of the AI-seeded draft (swap/add/remove exercises, tweak sets/reps/weight). |
| D4 | Equipment input | **Free-text prompt, AI infers**, helped by **quick preset chips** (Hotel gym / Home / Bodyweight only / Full gym / my saved gyms). Free text always available. |
| D5 | Duration | **AI best-effort against the requested minutes + a server-side duration estimator** shown as "~30 min" on cards/preview. Not a hard guarantee. |
| D6 | Progression | **Counts for history, not forward-planning.** Logged sets feed exercise history / e1RM / last-sets prefill; ad-hoc does not get double-progression auto-adjust. |
| D7 | Organization | **Tags/categories + search/filter + favorites/pin + sort by recent/most-done**, all in v1. |
| D8 | Platforms & order | **Backend → Android → Web.** Phone-first (travel / Sunday-at-home); the offline guided player already lives on Android. |
| D9 | Catalog gap | **Restrict to existing published catalog** exercises (same hard rule as the program designer). Guarantees demo media + progression linkage. Grow the catalog separately over time. |
| D10 | Load targeting | **Constrain to available loads** from equipment specs; where a gym is well-equipped, still ground in e1RM (× target% × staleness). Prefill `last-sets` when the exercise has history. |
| D11 | Constraint strictness | **Post-generation validation + repair.** After generation, verify every prescribed exercise passes `ExerciseAvailabilityService.satisfiedBy(resolvedEquipmentSet)`; regenerate/swap failures. Belt-and-suspenders over the soft prompt. |
| D12 | Archival semantics | **Archive hides the template (`syncStatus=ARCHIVED`) but keeps every past run** in history/stats. Viewable via an Archived filter; **restorable**. |
| D13 | Offline | **Generate online (Gemini call), run offline.** Templates sync to the device; start & fully log a run offline via the existing Room draft + outbox. Pre-generate before flying. |
| D14 | Gen flow | **Prompt → preview (editable) → Save to library and/or Start now**, with **regenerate / refine-by-chat**. |
| D15 | AI client | **Dedicated single-workout generator** reusing shared tools (`get_exercise_history`, availability allow-lists) + load-grounding; not the multi-phase designer. |
| D16 | Verification bar | **Automated E2E on the real API is the required DoD gate** for every backend phase (generate→save→edit→run→log→complete→history + constraint + archival). Golden-prompt evals guard AI behaviour. UI walkthrough/manual are supplementary, not the gate. |

## Data model

New collection — **template (library entry)**:
`users/{userId}/adhocWorkouts/{adhocId}`  (`adhocId` = `aw_` + 12-char UUID)

```
AdHocWorkout (record)
  userId, adhocId
  title            String            // AI-suggested, user-editable
  summary          String?           // one-line "what/why" (purpose-driven)
  source           AdHocSource        // AI_GENERATED | AI_ASSISTED | MANUAL
  prompt           String?           // original NL prompt (for regenerate/ref)
  equipmentContext EquipmentContext  // { presetId?, label, equipmentIds[], freeText?, specificLoads? }
  targetDurationMinutes  Integer?    // what the user asked for
  estimatedDurationSeconds Integer   // computed by WorkoutDurationEstimator (D5)
  tags             List<String>      // AI-suggested + user-editable (D7)
  pinned           boolean           // favorite (D7)
  day              WorkoutDay        // REUSED body: blocks → prescriptions
                                     //   (dayOfWeek/locationId nullable/ignored)
  runCount         int               // denormalized for sort-by-most-done (D7)
  lastPerformedAt  Instant?          // denormalized for sort-by-recent (D7)
  syncStatus       SyncStatus        // ACTIVE | ARCHIVED (D12)
  createdAt, updatedAt  Instant
```

New subcollection — **run (session instance)**:
`users/{userId}/adhocWorkouts/{adhocId}/sessions/{sessionId}`
(`sessionId` = `aws_` + 12-char UUID, **client-minted** — not date-keyed; the
same template can be run twice in a day, and offline starts must not need the
server)

```
AdHocSession (record) — mirrors ScheduledWorkout shape for player reuse
  userId, adhocId, sessionId
  date             LocalDate         // caller local day (X-Timezone)
  status           ScheduledStatus   // COMPLETED | SKIPPED only — see below
  session          WorkoutDay        // snapshot of template.day at start (immutable to later edits)
  loggedSets       (per prescription, via block/orderIndex key)
  completedAt, durationSeconds, feeling
  createdAt, updatedAt
```

**Sessions persist only at terminal state (ADR-0012 alignment).** There is no
server-side "start"/PLANNED doc: the client mints `sessionId`, snapshots the
synced template into the local draft, runs entirely locally, and the terminal
`PUT` **upsert-materializes + completes** in one call (same pattern as
`LogSessionRequest`'s optional materialization, and the meal-sync
client-minted-create fix). Consequences: offline start Just Works (D13),
abandoned/discarded runs never leave stale PLANNED docs server-side, and the
`PUT` must be **idempotent under outbox replay** — `runCount` is derived from
the create/transition edge (or a session count), never a blind increment.

**Cross-template reads** (history, calendar, digest) use a Firestore
**collection-group query** over `sessions` filtered by `userId`, with a
collection-group composite index `(userId, status, date DESC)`.

**History fan-out**: on COMPLETE, write/patch the existing flat `Workout`
record with `source = "ADHOC"` and a reference field `adhocId`/`sessionId`, so
unified history / stats / streaks / calendar pick it up (badged as ad-hoc).

**Why not date-keyed IDs like programs**: templates are repeatable and a user
may do the same one twice in a day; a UUID session id avoids the
`{date}_{dayId}` collision the program path has.

## API

Base: `/api/me/adhoc-workouts` (new `AdHocWorkoutController`).

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | List templates. Query: `includeArchived`, `tag`, `q`, `maxDurationMin`, `sort=recent\|mostDone\|created`, `pinnedFirst`. Shallow (no `day`) for list speed. |
| POST | `/generate` | **Preview.** Body `{ prompt, targetDurationMinutes?, equipment:{ presetId?\|equipmentIds?\|freeText }, refineOf?, instruction? }` → transient `{ proposal: AdHocWorkoutDraft, estimatedDurationSeconds, constraintReport, chatId }`. **Not persisted.** Handles regenerate + refine (D14). |
| POST | `/` | **Persist** a template (idempotent `Idempotency-Key`). Body = draft (title, summary, tags, day, equipmentContext, targetDurationMinutes, prompt, source) → `AdHocWorkoutResponse`. |
| GET | `/{adhocId}` | Deep fetch (full `day` + resolved exercise names/media). |
| PATCH | `/{adhocId}` | Update title/summary/tags/pinned/`day` edits → `AdHocWorkoutResponse`. |
| DELETE | `/{adhocId}` | **Archive** (syncStatus=ARCHIVED). 204. |
| POST | `/{adhocId}/restore` | Un-archive. |
| PUT | `/{adhocId}/sessions/{sessionId}` | **Upsert-materialize + log/complete** in one call (`sessionId` client-minted `aws_…`). Body `{ status: COMPLETED\|SKIPPED, date?, completedAt, durationSeconds, logged[], feeling }` → response (+ optional `aiRecap`). Snapshots `template.day` if the doc doesn't exist; fan-out to flat `Workout` (`workoutId = {adhocId}_{sessionId}`, `source=ADHOC`); derive `runCount`/`lastPerformedAt` **idempotently** (safe under outbox replay). |
| POST | `/{adhocId}/last-sets` | Prior-performance prefill for the run's `exerciseIds` (reuse `ExercisePerformanceDigest`/`LastSetView`). Online-only; clients must tolerate absence (see offline prefill below). |

There is **no `POST /sessions` start endpoint** — starts are local-only
(client draft); the backend learns at terminal state. Web log-after-the-fact
uses the same `PUT` with a freshly minted `sessionId`.

**Validation endpoint is internal** to `/generate` (the repair loop runs
server-side; the `constraintReport` surfaces any residual swaps for
transparency).

## Backend components

- **`AdHocWorkout` / `AdHocSession` records + `AdHocSource` enum** (`core/adhoc/`).
- **`FirestoreAdHocWorkoutRepository`** — CRUD + `syncStatus` archival
  (mirror `FirestoreWorkoutProgramRepository`), `findByUserIncludingArchived`,
  sessions subcollection writes.
- **`AdHocWorkoutService`** — orchestration: generate→validate/repair→estimate;
  persist; run materialize; complete + fan-out (reuse
  `WorkoutSessionCompletionService` pathway) + `runCount`/`lastPerformedAt`.
- **`GeminiAdHocWorkoutClient`** (`integrations/adhoc/`) — dedicated generator
  (D15). Terminal tool `propose_adhoc_workout` emits ONE `WorkoutDay` +
  suggested `title`, `summary`, `tags`. Reuses `get_exercise_history` +
  `get_lab_history`; injects the **resolved equipment allow-list** and a
  **time budget**. Model via `app.adhoc-workouts.gemini-model` (default same
  family as designer). System prompt (sketch):
  > You design **one** workout for a single session. Fit ~`{targetMin}`
  > minutes (the estimator will check). You may ONLY prescribe `exerciseId`s
  > in the allow-list below (they are executable with the available
  > equipment). Prefer loads the equipment actually offers (`{weights}`);
  > never prescribe `targetWeightLbs` above the user's e1RM. Structure into
  > WARMUP / MAIN / ACCESSORY / (COOLDOWN) blocks. Suggest 1–3 purpose tags.
- **`EquipmentPresetCatalog`** — maps preset ids (`hotel-gym`, `home`,
  `bodyweight`, `full-gym`) → concrete `equipmentIds` sets; `my saved gyms`
  resolves to a `Location`'s equipment. Free-text → AI-inferred equipment,
  reconciled to a concrete `equipmentIds` set for the hard check (D4/D11).
- **`AdHocConstraintValidator`** — after generation, for every prescription run
  `ExerciseAvailabilityService.satisfiedBy(exercise, resolvedEquipmentIds)`;
  collect failures, request a bounded repair round (swap to an allowed
  alternative), and surface residuals in `constraintReport` (D11).
- **`WorkoutDurationEstimator`** (`core/workoutprogram/`, shared) — pure
  function over a `WorkoutDay`:
  `Σ prescriptions [ sets × (repWork + restSeconds) ] + Σ timed durationSeconds
  + perExerciseSetupOverhead`, where `repWork = reps × secPerRep(tempo, default
  ~3.5s) + intraSetSetup`. Returns `estimatedDurationSeconds`. Unit-tested
  against hand-computed fixtures; also used to show "~N min". (D5)

### Read-path extensions (⚠️ without these, D6 and "unified surfacing" are silently false)
- **`ExercisePerformanceDigestService`** today flattens logged sets by
  iterating `programs.findByUserIncludingArchived(userId)` ONLY
  (`ExercisePerformanceDigestService.java:135`). **Extend the flattener** to
  also scan ad-hoc sessions (collection-group query) so ad-hoc sets feed
  e1RM / `last-sets` / staleness — this also transitively fixes the Gemini
  `get_exercise_history` tool and load grounding for future generations.
- **`WorkoutHistoryController`** today builds history by looping programs →
  `completedSessions(userId, programId)`
  (`WorkoutHistoryController.java:73`). **Extend the aggregation** to merge
  ad-hoc sessions (badged, with template title) into the same
  newest-first pages.
- **Calendar**: the only calendar endpoint is per-program
  (`GET /{programId}/calendar`). Add ad-hoc runs to calendar reads via a
  cross-source read (either a new `/api/me/workouts/calendar` merge endpoint
  or extending the history controller's range read) — decide at build with
  IMPL-WEB-WORKOUT-01 (below).
- **⚠️ Cross-spec dependency — IMPL-WEB-WORKOUT-01** (unmerged,
  `web-workout-improvement` branch) introduces server-side **streak/stats
  endpoints**. Those must read ad-hoc sessions too (or read the flat
  `Workout` fan-out, which ad-hoc writes) or streaks/stats will silently
  exclude ad-hoc. Coordinate whichever spec lands second; record the
  resolution in both decision logs.
- **`WorkoutSessionCoach` recap adapter** — the coach distills
  `ScheduledWorkoutResponse`; add a small adapter so a completed
  `AdHocSession` produces the same `SessionRecap` input (the `aiRecap` the
  API promises).

## Clients

### Android (feature-workouts) — D8 priority
- **Library screen**: list (shallow), search box, tag chips, duration filter,
  **pinned-first**, sort toggle (recent / most-done / created), Archived
  filter, per-row overflow (Edit / Pin / Archive). One-tap **Start**.
- **Generate screen**: prompt field + **preset chips** (D4) + duration input →
  `POST /generate` → **preview card** (editable: swap/add/remove, sets/reps/
  weight) with **Regenerate** + **refine text** → **Save to library** and/or
  **Start now** (D14). Shows estimated "~N min" + any constraint swaps.
- **Guided run**: **generalize `WorkoutSessionDraft` key** from
  `(programId, scheduledId)` to a `WorkoutRef` sealed type
  `Program(programId, scheduledId) | AdHoc(adhocId, sessionId)`. Draft created
  from an `AdHocSession`; the entire player (set logging, rest timers, swap,
  finish/recap, outbox completion) is reused unchanged behind the ref.
- **Offline** (D13): templates sync to Room; run start (client-minted
  `sessionId`, no server call) + full logging + finish work offline via the
  existing draft + outbox. Generation shows a clear "needs connection" state.
- **Offline last-sets prefill** (⚠️ known trap, memory
  `last-sets-404-unpersisted-session`): the `last-sets` fetch blanks prefill
  when it fails — and an offline ad-hoc start *never* has network. Prefill
  must **prefetch at template sync time** (embed digest-derived
  `targetWeightLbs`/last-sets alongside the synced template) and/or fall back
  to the locally mirrored ad-hoc run history; never render blank weights just
  because the network call failed.
- **Sync**: register `adhocWorkouts` **and** the slash-form
  `adhocWorkouts/sessions` alias in `CollectionRegistry` + `MirrorOps`
  (⚠️ the nutrition-sync bug class).
- **⚠️ Cross-branch — `notification-log`**: the active-workout ongoing
  notification's "Log set" action bakes `(programId, scheduledId)` into its
  PendingIntents/`WorkoutSetLogReceiver`. The `WorkoutRef` refactor must flow
  through those extras so ad-hoc runs get the same shade quick-log; coordinate
  whichever branch merges second.

### Web (web/app/me/workouts) — after Android
- New **`/me/workouts/library`** page: list + CRUD/manage + Archived filter +
  pin/tags/search/filter/sort. Wire the hub's **"Log Workout"** card here
  (replaces *Coming soon*).
- **Generate flow**: reuse the chat/preview pattern (`AdjustWithAi`-style
  preview→confirm) → Save to library / edit.
- **Template editor**: edit `day` (exercise swap via existing
  `suggestExercises`, sets/reps/weight), title/summary/tags.
- **Running on web (v1)**: **log-after-the-fact** via the existing idempotent
  session upsert (`logSession`-style) against the ad-hoc session endpoint. Live
  guided player parity is out of scope (see NON-goals).

## Sync / configuration / flags

- **CollectionRegistry** (both clients) + backend `syncNotifier.changed(userId,
  null, "adhocWorkouts", "adhocWorkouts/sessions")` on every write.
- **Feature flag**: `app.adhoc-workouts.enabled` (bean-gate the Gemini client
  like nutrition `capture.enabled`, so unit tests load without a live key).
- **Model config**: `app.adhoc-workouts.gemini-model`.
- **Firestore index**: `adhocWorkouts` composite `(syncStatus, updatedAt DESC)`
  for library lists; sessions need none beyond default.

## Phases of development & status tracker

**Legend:** ☐ not started · ◐ in progress · ☑ done · N/A. "Pushed" = merged PR #.

### Phase 0 — Baseline & spikes
Verify assumptions before building.
- Catalog coverage sanity: can the published catalog satisfy the two golden
  prompts (hotel gym; bodyweight + 35 lb KB)? Enumerate `satisfiedBy` results.
- Confirm `EquipmentPresetCatalog` mappings resolve to real catalog
  `equipmentId`s (incl. a kettlebell equipment id with a 35 lb spec/weight).
- Confirm the duration-estimator formula against 3 real hand-computed workouts.
- Confirm the guided player can be keyed off a `WorkoutRef` without regressions.

| Item | Impl | Tested | Pushed |
|---|---|---|---|
| Catalog/equipment coverage spike | ☐ | ☐ | ☐ |
| Preset→equipmentId mapping verified | ☐ | ☐ | ☐ |
| Duration estimator fixtures agreed | ☐ | ☐ | ☐ |
| `WorkoutRef` refactor feasibility | ☐ | ☐ | ☐ |

### Phase 1 — Backend: model + CRUD + archival + sync (no AI)
Accept a **client-provided `day`** so the whole persistence/sync path is
provable before the AI exists.
- Records/enums, repository, `AdHocWorkoutService` (CRUD), controller
  (`GET /`, `POST /`, `GET/PATCH/DELETE /{id}`, `restore`), archival + restore,
  filter/search/sort, sync notify + index.

| Item | Impl | Tested | Pushed |
|---|---|---|---|
| Records + `AdHocSource` | ☑ | ☑ | ☐ |
| Repository (CRUD + archival + includeArchived) | ☑ | ☑ | ☐ |
| Controller CRUD + list filters/sort | ☑ | ☑ | ☐ |
| Archive/restore + sync notify | ☑ | ☑ | ☐ |
| **E2E: create→read→run→last-sets→archive→restore (HTTP)** | ☑ | ☑ | ☐ |

Now covered at the **HTTP layer** by `AdHocWorkoutControllerE2ETest` (MockMvc,
full journey incl. list membership + archived filter) as well as the
service-layer `AdHocWorkoutServiceTest`.

### Phase 2 — Backend: AI generator + estimator + constraint repair
- `GeminiAdHocWorkoutClient` (`propose_adhoc_workout`), `EquipmentPresetCatalog`,
  free-text→equipment resolution, `AdHocConstraintValidator` + repair loop,
  `WorkoutDurationEstimator`, `POST /generate` (+ regenerate/refine),
  `constraintReport`, tag suggestion.

| Item | Impl | Tested | Pushed |
|---|---|---|---|
| Duration estimator + unit fixtures | ☑ | ☑ | ☐ |
| Preset catalog + free-text resolution | ☑ | ◐³ | ☐ |
| Gemini generator + load grounding | ☑ | ☐⁴ | ☐ |
| Constraint validate (+ report) | ☑ | ☑ | ☐ |
| `POST /generate` orchestration | ☑ | ◐³ | ☐ |
| **Golden-prompt eval suite (see Testing)** | ☐⁴ | ☐ | ☐ |

³ Preset→id resolution + generate orchestration compile & are wired; a
controller-slice/eval test needs a live catalog + stub generator (follow-up).
⁴ Live Gemini generation + golden-prompt evals require an API key not present in
CI; gated behind `app.adhoc-workouts.enabled` like the existing designer client.
**Repair loop**: the validator produces a `constraintReport`; an automated
regenerate-on-violation loop is not yet wired (report is surfaced to the client;
deferred — see Open items).

### Phase 3 — Backend: run lifecycle + history surfacing
- `PUT …/sessions/{sid}` upsert-materialize + complete (client-minted id),
  fan-out to flat `Workout` (source ADHOC), **idempotent**
  `runCount`/`lastPerformedAt`, `last-sets` prefill, collection-group index,
  digest-flattener + history-controller extensions, calendar mechanism,
  recap adapter.

| Item | Impl | Tested | Pushed |
|---|---|---|---|
| PUT upsert-materialize + snapshot + complete | ☑ | ☑ | ☐ |
| Fan-out + idempotent counters (replay-safe) | ☑ | ☑ | ☐ |
| Per-user session read (walk, no cross-user group) | ☑ | ☑⁵ | ☐ |
| **Digest flattener extended to ad-hoc sessions** | ☑ | ☑ | ☐ |
| **Weekly stats/streaks include ad-hoc (unified recompute)** | ☑ | ☑ | ☐ |
| last-sets prefill endpoint (e1RM/history) | ☑ | ☑ | ☐ |
| WorkoutHistoryController merges ad-hoc runs | ☐⁶ | ☐ | ☐ |
| Calendar inclusion (w/ IMPL-WEB-WORKOUT-01) | ☐⁶ | ☐ | ☐ |
| Recap adapter (`aiRecap` on complete) | ☐⁶ | ☐ | ☐ |
| **E2E: run→complete→stats/digest (service + HTTP)** | ☑ | ☑ | ☐ |

⁵ Per-user enumeration verified via the in-memory repo; the AD-06 completed-run
read + digest/weekly inclusion is covered by `AdHocSessionServiceTest`.
⁶ Stats/streaks + e1RM/last-sets inclusion (the load-bearing D6 read paths) ARE
done. The unified **history list** merge, **calendar** view, and **AI recap**
adapter are surfacing niceties deferred to the client phase / IMPL-WEB-WORKOUT-01
coordination — see decision log Open items. Runs already reach the flat
`Workout` fan-out, so any history built on that includes them.

### Phase 4 — Android: library + generate + guided run + offline
| Item | Impl | Tested | Pushed |
|---|---|---|---|
| **Sync mirror: entities/DAOs/DB/migration/MirrorStore** | ☑ | ☑ | ☐ |
| **Sync registration (+ slash alias) + Room schema v8** | ☑ | ☑ | ☐ |
| **Library tab on the hub (IA placement, mirrors web)** | ☑ | ☑ | ☐ |
| Library screen — read-only list of synced templates | ☑ | ☑ | ☐ |
| `WorkoutRef` refactor of the player | ☐ | ☐ | ☐ |
| Library screen — full (search/tag/sort/pin/archive actions) | ◐ | ☐ | ☐ |
| Generate + preview (editable) + presets + refine | ☐ | ☐ | ☐ |
| Save / Start-now → guided run | ☐ | ☐ | ☐ |
| Offline run (client-minted id, draft + outbox) | ☐ | ☐ | ☐ |
| Offline last-sets/weight prefill (prefetch at sync) | ☐ | ☐ | ☐ |
| Notification "Log set" WorkoutRef plumbing (cross-branch) | ☐ | ☐ | ☐ |
| Android instrumentation/unit tests | ◐ | ◐ | ☐ |

The **data-layer/sync backbone is done + verified** (compiles, Room KSP schema
8.json, JVM contract + migration-coverage tests green). The Android **feature
module** — repository/API client, library + generate Compose UI, `WorkoutRef`
player refactor, offline run — is the remaining work; instrumented tests
(emulator) are the ◐.

### Phase 5 — Web: library CRUD/manage + generate + editor
| Item | Impl | Tested | Pushed |
|---|---|---|---|
| `/me/workouts/library` list + CRUD/manage | ☑ | ☑¹ | ☐ |
| Generate + preview | ☑ | ☑¹ | ☐ |
| "Library" tab in WorkoutTabs (tabbed IA, post-#267 merge) | ☑ | ☑¹ | ☐ |
| Template editor (day/tags/pin) | ◐² | ☐ | ☐ |
| Log-after-the-fact run upsert | ☐³ | ☐ | ☐ |
| Web tests (component + mocked API) | ☐ | ☐ | ☐ |

¹ Verified by `tsc --noEmit` (exit 0) + eslint clean. ² Pin/tags/archive editing
is live via server actions; a full day-body editor (exercise swap / sets-reps)
is a follow-up. ³ `logAdHocRun` client + backend PUT exist; the web run/log
screen UI is the remaining piece (per NON-goal, web's live player is deferred —
this is the log-after path).

### Phase 6 — Rollout & polish
| Item | Impl | Tested | Pushed |
|---|---|---|---|
| Flag default-on plan + docs update | ☐ | ☐ | ☐ |
| Deploy watch (`deploy-*-on-main`) | ☐ | ☐ | ☐ |
| Decision-log addendum (as-built deltas) | ☐ | ☐ | ☐ |

## Testing approach

Testing must prove **both** the technical wiring **and** the functional
behaviour. **Automated E2E through the real API is the required gate (D16).**

### 1. Backend unit
- `WorkoutDurationEstimator` — table of hand-computed `WorkoutDay` → seconds
  (rep/rest/timed/overhead); boundary cases (all-timed, zero-rest,
  bodyweight-only).
- `AdHocConstraintValidator.satisfiedBy` composition — any-of groups,
  bodyweight-everywhere, a barbell move failing a KB-only set.
- Archival filter — archived excluded from list, present in
  `includeArchived`, restore round-trips.
- Repository idempotency (`Idempotency-Key`), `runCount`/`lastPerformedAt`.

### 2. Backend E2E (per-phase gate — MUST pass before a phase is "done")
Full HTTP journeys against a running backend (real Firestore emulator/test
project), asserting on responses **and** persisted state:
- **P1**: `POST /` → `GET /` (appears) → `PATCH` (change reflected) →
  `DELETE` (archived, absent from default list, present with
  `includeArchived`) → `restore` (reappears). Filter/sort assertions
  (tag, `maxDurationMin`, pinnedFirst, sort=recent/mostDone).
- **P2**: `POST /generate` for each golden prompt → assert **every** returned
  `exerciseId` is published + `satisfiedBy(resolvedEquipment)` true (no
  hallucinated/over-reaching equipment) + `estimatedDurationSeconds` within
  tolerance of the requested minutes + tags present. Regenerate returns a
  distinct valid workout; refine ("make it shorter") reduces estimate.
- **P3**: generate→save→`PUT` complete (client-minted `sessionId`) with logged
  sets → assert flat `Workout` written (source ADHOC), `runCount`==1,
  `lastPerformedAt` set, session snapshot immutable to later template edits,
  **replaying the identical `PUT` (outbox retry) leaves `runCount`==1 and no
  duplicate `Workout`**, the digest/`last-sets` returns the just-logged sets
  on the next run (proves the flattener extension), the run appears in the
  `WorkoutHistoryController` pages **and** the calendar read, and archiving
  the template afterwards leaves the run visible in history (D12).

### 3. Golden-prompt eval suite (functional AI guard — Phase 2)
Fixed prompts incl. the two verbatim examples plus edge cases ("15 min core,
no equipment", "60 min upper, full gym", "one 35 lb KB only"). Assertions per
output: fits duration estimate (±tolerance), **only allowed equipment**, valid
published `exerciseId`s, sane block structure, ≥1 tag. Run in CI (mockable) and
as a live smoke check pre-rollout. Record pass-rate; a prompt that repeatedly
fails the equipment check is a P0 for the repair loop.

### 4. Client tests
- **Android**: ViewModel/unit tests for generate/preview/edit state, library
  filter/sort, `WorkoutRef` draft creation; instrumentation for the offline
  run (airplane-mode start→log→reconnect→drain) reusing existing player test
  patterns.
- **Web**: component tests for library CRUD, generate preview/refine, editor,
  mocked-API happy + error paths.

### 5. Functional walkthrough (supplementary, not the gate)
Owner checklist on a real device once per client phase (generate a hotel-gym
workout on the plane in airplane mode after pre-generating; run it; verify it
lands in history). Captured as sign-off notes, not a substitute for E2E.

## Definition of Done

**Per-phase DoD** — a phase item flips to ☑ only when:
1. Code merged behind `app.adhoc-workouts.enabled` (or client equivalent).
2. Unit tests for the item pass.
3. **The phase's E2E journey passes against the real API** (P1–P3 above) —
   this is the hard gate for backend phases.
4. Client phases: the relevant ViewModel/component tests pass **and** the
   scripted client test (offline run for Android) passes.

**Feature DoD (v1 complete):**
- Both golden prompts produce valid, equipment-correct, time-appropriate
  workouts that can be saved, run end-to-end, and appear in history/stats/
  calendar.
- Full CRUD + archival + restore verified by E2E on backend, and exercised
  from Android and web.
- Offline run proven on Android.
- Golden-prompt eval pass-rate meets bar (target ≥95% equipment-correct,
  100% valid `exerciseId`s) with the repair loop.
- No sync SKIPPED entries for ad-hoc collections (slash-alias registered).
- Deploy pipeline green on merge (`deploy-*-on-main`).

## Agent self-verification protocol (before marking anything done)

The implementing agent **must not** mark an item ☑ on assertion alone. For each:

1. **State the claim** ("Phase 1 CRUD + archival works").
2. **Run the gate command** and **paste the actual output** into the phase's
   decision log — e.g.
   `./gradlew :backend:test --tests '*AdHoc*'` and the E2E harness invocation
   (a script/HTTP runner hitting the real API), showing PASS counts.
3. **Show the persisted-state assertion**, not just the HTTP 200 (e.g. the
   archived doc has `syncStatus=ARCHIVED`; the flat `Workout` fan-out exists).
4. For AI phases, **paste one real generated workout per golden prompt** and
   the `constraintReport` proving zero equipment violations.
5. Only then edit the tracker cell to ☑ and record the PR # under "Pushed"
   when merged. If a step is skipped or fails, the cell stays ◐/☐ with a note.

A companion **decision log** (`IMPL-ADHOC-01-decision-log.md`) captures as-built
deltas, spike results, and the pasted verification evidence per phase.

## Effort (rough)
- Backend P1–P3: ~4–6 d (model/CRUD ~1.5, AI generator + estimator + repair
  ~2, run lifecycle + fan-out + E2E ~1.5).
- Android P4: ~3–4 d (WorkoutRef refactor + library + generate/preview + offline
  tests).
- Web P5: ~2–2.5 d.
- Rollout/polish P6: ~0.5 d.

## Open risks / spikes to resolve at build
- **Catalog breadth**: if the published catalog can't cover common hotel-gym /
  single-KB movements, generated variety will be thin (D9 restricts to
  catalog). Phase 0 must quantify; growing the catalog is a separate track.
- **`WorkoutRef` refactor blast radius** in the Android player (draft key,
  outbox routing, recap fetch) — de-risk in Phase 0.
- **Duration estimator accuracy** — first cut is heuristic; treat the shown
  "~N min" as an estimate and calibrate against real logged `durationSeconds`
  post-launch.
- **Free-text equipment ambiguity** — the repair loop is the safety net, but
  weird phrasings ("resistance bands and a chair") may resolve poorly; the
  `constraintReport` must surface swaps transparently.
- **Cross-spec / cross-branch collisions** — IMPL-WEB-WORKOUT-01
  (streak/stats endpoints must include ad-hoc) and `notification-log`
  (`WorkoutSetLogReceiver` extras must carry a `WorkoutRef`). Whichever lands
  second owns the reconciliation; both decision logs must record it.
- **Digest scan cost** — the digest flattener already re-reads every program
  incl. archived; adding a collection-group scan over ad-hoc sessions grows
  that. Fine at current scale, but note it feeds the existing "lighten
  refresh jobs" architecture thread (Cloud Run jobs memory incident).
