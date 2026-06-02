# IMPL-15: Workout Programs

## Goal of this spec

Let the user **create periodized workout programs** — by chatting with Gemini,
or by hand. The user describes what they're training for; Gemini reads their
goals, body composition / DEXA, blood work, and vitals (the same health
snapshot Goals uses), takes the user's chosen **training days** and the **gym**
each day happens at, and designs a complete program:

**Program → Phase → Workout Day → Block → Exercise prescription.**

Phases are multi-week chapters with progressive overload and **deload weeks**
(e.g. "4 weeks: 3 build + 1 deload"). Each Workout Day is a session at a
specific gym, broken into **blocks** the user explicitly asked for — warm-up,
mobility, cardio, main work, accessory, core, cool-down, stretch. Every
prescribed exercise is drawn from the global Exercise catalog
([IMPL-14](IMPL-14-exercise-library.md)) and is **guaranteed executable with
the equipment at that day's gym** — the model is only ever shown the allowed
set, and the backend rejects any violation.

The program designer reuses the IMPL-12 Goals architecture wholesale: Pro-model
tool calling, an editable proposal card streamed over SSE, a strict-sequence
phase model, and the `UserHealthSnapshotService`. It builds on IMPL-14 for the
exercise vocabulary and equipment constraint, and on the existing
`Location` / `Equipment` modules for gyms.

Backend + web in this spec. Android deferred to a future `IMPL-AND-15`.

## Decisions locked

| Decision | Choice |
|---|---|
| Structure | Program → Phase → Workout Day → Block → Exercise prescription |
| Phase = block of weeks | A phase spans N weeks and may flag one as a **deload week** (lighter volume/intensity), per the user's "four weeks with a deload week" |
| Phase ordering | Strict sequence — exactly one phase active at a time (same rule as Goals phases) |
| Weekly template | A phase owns a **weekly microcycle**: one Workout Day per chosen training day, repeated each week of the phase |
| Schedule input | User specifies training **days of week** and the **gym (Location)** for each day; the gym drives the equipment constraint |
| Equipment constraint | **Hard.** A day's exercises must all be executable at that day's gym (`ExerciseAvailabilityService` from IMPL-14). Generator is shown only the allowed set; validator rejects violations |
| Block types | WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH — the user composes which blocks a day has |
| Planner model | `gemini-3.1-pro-preview` via tool calling (see [ADR-0007](../decisions/ADR-0007-workout-program-design-gemini-pro.md)) |
| Proposal UX | Editable proposal card streamed over SSE, edited in place, committed — exactly like Goals' `<GoalProposalCard>` |
| Goal linkage | A program may link to a Goal (`goalId`); its sessions feed the existing `workouts.count` / `workouts.weeklyVolume` Goal metrics |
| Materialization | Activating a program writes dated `ScheduledWorkout`s across the phase weeks for the calendar view |
| Logging actuals | Out of scope — recording the weights actually lifted is a later spec; the existing `Workout` record is the bridge |

---

## Concepts

**Program** — the whole plan (a mesocycle or training block). Has a title,
description, an optional linked Goal, a schedule (which weekdays the user
trains and at which gym), a start date, and an ordered list of Phases.
Example: *"12-week hypertrophy base, 4 days/week."*

**Phase** — a chapter of the program spanning a number of weeks, with a focus
and an intensity character. One week of a phase may be flagged the **deload
week** (reduced volume/intensity for recovery). Phases run in strict sequence:
phase N+1 starts only when N completes. Example: *"Weeks 1–4: Accumulation
(week 4 deload)."*

**Workout Day** — a single training session in the phase's weekly microcycle,
pinned to one **training day** (e.g. Monday) and one **gym** (`Location`). Has
a label ("Push", "Lower A") and an ordered list of Blocks. The same Workout Day
repeats each week of the phase.

**Block** — an ordered section of a session, typed: warm-up, mobility, cardio,
main, accessory, core, cool-down, stretch. Holds an ordered list of exercise
prescriptions. This is how the user gets "a warm-up / cool-down, cardio block,
stretching block" — each is just a typed block.

**Exercise prescription** — one exercise (from the IMPL-14 catalog) as
performed in a block: sets, reps or duration, intensity (RPE or %1RM), rest,
optional tempo and notes, and a deload modifier.

**Scheduled workout** — a concrete dated instance of a Workout Day, produced
when the program is activated, so the calendar can show "Monday 8 Jun — Lower A
at Home Gym."

---

## Data model

### Firestore collections

User-scoped, mirroring the Goals layout. Blocks and prescriptions are
**embedded** in the Workout Day document (bounded lists) so one read renders a
full session — the same nesting choice the persistence layer already makes
elsewhere:

```
users/{userId}/workoutPrograms/{programId}
users/{userId}/workoutPrograms/{programId}/phases/{phaseId}
users/{userId}/workoutPrograms/{programId}/phases/{phaseId}/days/{dayId}
   └─ blocks[] embedded, each with prescriptions[] embedded
users/{userId}/workoutPrograms/{programId}/scheduled/{yyyy-MM-dd_dayId}
users/{userId}/workoutProgramChatThreads/{threadId}
users/{userId}/workoutProgramChatThreads/{threadId}/messages/{messageId}
```

### Program document

```
programId: string
title: string
description: string
goalId: string | null              optional link to a Goal (IMPL-12)
status: enum  DRAFT | ACTIVE | COMPLETED | ARCHIVED
startDate: date
source: enum  MANUAL | AI_GENERATED | AI_ASSISTED
phaseOrder: string[]               ordered phaseIds — source of truth for sequence

# schedule: which weekdays the user trains, and the gym for each
schedule: {
  trainingDays: enum[]             MON | TUE | ... | SUN
  dayLocations: { <DayOfWeek>: locationId }   gym per training day
}

createdAt / updatedAt / completedAt
```

### Phase document

```
phaseId: string
programId: string
title: string                      "Accumulation"
focus: string                      "Hypertrophy — upper emphasis"
orderIndex: int                    0-based position in the sequence
status: enum  LOCKED | ACTIVE | COMPLETED     derived + persisted, like Goal phases
weeks: int                         number of weeks this phase runs
deloadWeekIndex: int | null        1-based week that is the deload (null = none)
targetStartDate: date
targetEndDate: date
completedAt: timestamp | null
dayOrder: string[]                 ordered workoutDayIds (the weekly microcycle)
```

Phase status follows the Goals rule: phase 0 starts `ACTIVE`, the rest
`LOCKED`; completing a phase activates the next; completion is sticky and
one-way.

### Workout Day document

```
dayId: string
phaseId: string
programId: string
label: string                      "Push A", "Lower"
dayOfWeek: enum  MON..SUN          which training day this maps to
locationId: string                 the gym — drives the equipment constraint
orderIndex: int

blocks: [
  {
    blockId: string
    type: enum  WARMUP | MOBILITY | CARDIO | MAIN | ACCESSORY |
                CORE | COOLDOWN | STRETCH
    title: string                  "Main — Squat", "Cardio finisher"
    orderIndex: int
    prescriptions: [
      {
        exerciseId: string         → global exercises/{exerciseId} (IMPL-14)
        orderIndex: int
        sets: int | null
        repsMin: int | null        rep target / range; null when timed
        repsMax: int | null
        durationSeconds: int | null   for timed/cardio/holds
        intensity: {                  optional
          kind: enum  RPE | PERCENT_1RM | NONE
          value: number | null
        }
        restSeconds: int | null
        tempo: string | null       e.g. "3-1-1"
        notes: string | null
        # how this line changes on the phase's deload week
        deloadModifier: {
          setsMultiplier: number | null     e.g. 0.5
          intensityDelta: number | null     e.g. -2 RPE
        } | null
      }
    ]
  }
]
```

### Scheduled workout document

Materialized on activation; the unit the calendar and (future) logging read:

```
scheduledId: string                "{date}_{dayId}"
date: date
programId / phaseId / dayId: string
weekIndexInPhase: int              1-based
isDeload: boolean                  date falls in the phase's deload week
locationId: string
status: enum  PLANNED | COMPLETED | SKIPPED
# a denormalized snapshot of the day's blocks at materialization time,
# so editing the template later doesn't silently rewrite past sessions
sessionSnapshot: { ...blocks }
```

### Embedded exercise summary on read responses

A prescription stores only `exerciseId`. To let clients render a session
(exercise name, muscles, demo stills, form cues) **without an N+1 fetch per
prescription**, the **deep** program response (`GET /api/me/workout-programs/{id}`)
and the **calendar** response (`GET …/{id}/calendar`) embed a compact,
read-only `ExerciseSummary` alongside each prescription, hydrated from the
IMPL-14 catalog:

```
exercise: {
  exerciseId, name,
  primaryMuscles: string[],
  equipmentNames: string[],          # resolved from requiredEquipment ids
  formCues: string[],
  demoFrames: [ { phase, imageUrl } ]   # START/MID/END thumbnails
}
```

It is **read-only and denormalized for display** — the prescription's
`exerciseId` remains the source of truth, and the shallow list response omits
it. The web Exercise detail sheet and the Android viewer
([IMPL-AND-15](IMPL-AND-15-workout-programs.md)) both consume it; the resolved
`locationName` on each Workout Day / scheduled session is hydrated the same way.
(If a client predates this field, a batch `GET /api/exercises?ids=` is the
fallback.)

When the program is linked to a Goal, the deep response also carries the
linked goal's **title** (`goalTitle`) next to `goalId`, so clients can render
an "Attached to goal: {title}" link without a second fetch.

---

## The equipment constraint (the headline requirement)

> *"the workouts should EXPLICITLY only give me exercises that are executable
> with my existing equipment."*

This is enforced in three places, all routed through IMPL-14's
`ExerciseAvailabilityService`:

1. **Generation.** For each training day, the program-design prompt is given
   **only** the exercises returned by `executableAt(dayLocationId)` — a
   compact, gym-specific allow-list. The model literally cannot see an
   exercise the gym can't run, so it cannot prescribe one.

2. **Validation on commit.** Before any program is written (chat-committed or
   manual), every prescription is re-checked with
   `isExecutableAt(exerciseId, day.locationId)`. Any violation is flagged
   **inline on the proposal card** (the offending prescription is marked), not
   silently dropped — same behavior as the Goals validator. The user can swap
   it for an allowed alternative or change the day's gym.

3. **Editing.** The manual editor's exercise picker for a given Workout Day is
   sourced from `GET /api/exercises/available?locationId=…`, so a hand-built
   day can only contain executable exercises in the first place. Changing a
   day's gym re-validates its blocks and flags anything the new gym can't run.

A bodyweight exercise (no requirements) is available at every gym, so a
travel/hotel day still produces a real session.

---

## Backend — Spring

### Module placement

New `workoutprogram` package, following Goals/Equipment:

- `core/workoutprogram` — records, enums, repository interfaces,
  `WorkoutProgramService`, `WorkoutScheduleService` (materialization),
  `WorkoutProgramValidator`, and the `WorkoutProgramChatClient` port.
- `persistence/workoutprogram` — Firestore repositories.
- `api/workoutprogram` — controllers + DTOs.
- `integrations/workoutprogram` — `GeminiWorkoutProgramChatClient`.

Depends on `core/exercise` (IMPL-14) for `ExerciseAvailabilityService`, and on
the existing `location` and `goals/chat` (`UserHealthSnapshotService`) code.

### REST endpoints

Modeled directly on the Goals endpoints:

```
GET    /api/me/workout-programs                    list (shallow)
POST   /api/me/workout-programs                    create
GET    /api/me/workout-programs/{id}               deep (phases + days + blocks)
PATCH  /api/me/workout-programs/{id}               update program fields / schedule
DELETE /api/me/workout-programs/{id}               archive (soft)
POST   /api/me/workout-programs/{id}/activate      materialize ScheduledWorkouts
GET    /api/me/workout-programs/{id}/calendar?from=&to=   scheduled sessions

POST   /api/me/workout-programs/{id}/phases                add phase
PATCH  /api/me/workout-programs/{id}/phases/{pid}          update phase (weeks, deload)
DELETE /api/me/workout-programs/{id}/phases/{pid}
PUT    /api/me/workout-programs/{id}/phases/order

POST   .../phases/{pid}/days                  add workout day
PATCH  .../phases/{pid}/days/{did}            update (label, dayOfWeek, locationId, blocks)
DELETE .../phases/{pid}/days/{did}
PUT    .../phases/{pid}/days/order

POST   /api/me/workout-programs/chat                       send a message (SSE stream)
POST   /api/me/workout-programs/chat/{threadId}/commit     persist a proposed program
GET    /api/me/workout-programs/chat/threads               list threads
```

Blocks and prescriptions are edited as part of the Workout Day `PATCH` payload
(they're embedded), so they need no separate endpoints.

### Materialization (`WorkoutScheduleService`)

`activate` walks the phases in order from `startDate`, laying each phase's
weekly microcycle across its `weeks`, mapping each Workout Day's `dayOfWeek`
onto real calendar dates, and writing a `ScheduledWorkout` per session.
Sessions whose week equals the phase's `deloadWeekIndex` get `isDeload = true`
and carry the deload-modified snapshot. Re-activating regenerates future
`PLANNED` sessions but never rewrites past `COMPLETED` ones.

---

## Gemini integration

### Approach

Identical machinery to Goals: the Pro model designs the structure through **tool
calling**, the backend validates, and the structure streams to the UI as an
editable card. New tool, new system prompt, same plumbing.

### The tool — `propose_workout_program`

```
tool: propose_workout_program
description: Propose a complete periodized workout program for the user to
             review and edit.
parameters: {
  title, description,
  phases: [
    {
      title, focus, weeks, deloadWeekIndex?,
      days: [
        {
          label, dayOfWeek, locationId,
          blocks: [
            {
              type,                        # WARMUP | MAIN | CARDIO | ...
              title,
              prescriptions: [
                {
                  exerciseId,              # MUST come from the day's allow-list
                  sets?, repsMin?, repsMax?, durationSeconds?,
                  intensity?: { kind, value },
                  restSeconds?, tempo?, notes?,
                  deloadModifier?: { setsMultiplier?, intensityDelta? }
                }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

### System prompt essentials

The prompt assembles, for this user:

- **Health snapshot** — reuse `UserHealthSnapshotService` (body comp / DEXA,
  blood panel, vitals, meds, current goals) verbatim, exactly as Goals does.
- **Schedule** — the chosen training days and, per day, the gym name.
- **Per-day equipment allow-list** — for each training day, the exercises
  `executableAt(locationId)` returned by IMPL-14, listed by `exerciseId` +
  name + `suitableBlockTypes`. The prompt states plainly: *a day may only use
  exercise ids from that day's list; using any other id is an error.*
- **Periodization rules** — phases run in strict sequence and build on each
  other; apply progressive overload across a phase; place a deload week where
  flagged (reduced volume/intensity); balance weekly volume across muscle
  groups and across the training days; respect the block types the user asked
  for; match each exercise to a block its `suitableBlockTypes` allows (no
  deadlifts in the stretch block).
- Call `propose_workout_program` once it has enough context, rather than
  describing the plan in prose. Don't invent exercise ids, past dates, or
  prescriptions a beginner couldn't recover from given the snapshot.

### Validation before the card renders

`WorkoutProgramValidator` checks every proposal:

- Every `exerciseId` exists, is `PUBLISHED`+`APPROVED`, and **is executable at
  its day's gym** (`isExecutableAt`). This is the hard constraint.
- Each prescription's block type is in the exercise's `suitableBlockTypes`.
- `deloadWeekIndex`, if present, is within `1..weeks`.
- Reps/sets/duration are coherent (timed exercises use `durationSeconds`,
  others use reps); intensity kind matches a value.
- `dayOfWeek` values are within the program's `schedule.trainingDays`, and each
  references a gym in `schedule.dayLocations`.

Invalid fields are flagged inline on the proposal card, never silently dropped
— matching the Goals validator contract.

### Commit flow

Same as Goals: chat → tool call → validated structure streams in → editable
program card → user edits (swap exercises from the allowed set, tweak
sets/reps, move blocks, adjust weeks/deload) → **Save program** posts to
`/api/me/workout-programs/chat/{threadId}/commit` → backend writes
Program/Phase/Day docs and returns the `programId`. Manual creation bypasses
chat: the same editable card, blank, posting to `POST /api/me/workout-programs`.

### Config (proposed `application.yml` additions)

```yaml
app:
  workout-programs:
    enabled: ${WORKOUT_PROGRAMS_ENABLED:true}
    gemini-api-key: ${GEMINI_API_KEY:}
    # Second deliberate exception to flash-only — see ADR-0007. Own env var,
    # independent of GEMINI_MODEL and GOALS_GEMINI_MODEL.
    gemini-model: ${WORKOUT_PROGRAM_GEMINI_MODEL:gemini-3.1-pro-preview}
```

---

## Web UI — Next.js

Lives under the existing Workouts area, replacing the "Programs — coming soon"
placeholder card on `/me/workouts`.

### Programs list (`/me/workouts/programs`)

Vertical list of program cards (a handful expected, like Goals): title, status
pill, a compact **phase spine** (one segment per phase, colored by
locked/active/completed, deload weeks marked), "Week 2 of 12" progress, and a
"behind schedule" treatment if past a phase's target end. "New program" opens
the chat; "Create manually" opens the blank editor.

### Program detail (`/me/workouts/programs/{id}`)

Two views:

- **Roadmap** — a vertical phase timeline (reusing the Goals `RoadmapTimeline`
  pattern): each phase node shows weeks, focus, and its weekly microcycle as a
  row of Workout-Day chips (label + gym + day-of-week). Expanding a day shows
  its blocks and prescriptions; each prescription row links to the IMPL-14
  **Exercise detail sheet** (phase-still sequence + form cues). Deload weeks are
  badged.
- **Calendar** — the materialized `ScheduledWorkout`s for the active range
  (`GET …/calendar`), so the user sees "this week: Mon Lower @ Home, Wed Push @
  Office, …", deload weeks tinted.

### Program designer chat (`/me/workouts/programs/chat`)

Built with **assistant-ui** like the Goals chat. The `propose_workout_program`
tool result renders as a custom **`<WorkoutProgramProposalCard>`** — the
program editor embedded in a chat bubble: editable phase weeks + deload flag,
add/remove/reorder Workout Days, per-day **gym picker** (re-validates the day's
exercises against the new gym), block editor with typed blocks, and an exercise
picker **scoped to the day's gym** (`/api/exercises/available?locationId=`).
Invalid prescriptions flagged by the validator render with the offending row
highlighted. Card actions: **Save program** / **Discard**. Suggested starter
prompts on the empty state, e.g. *"Build me a 4-day upper/lower for
hypertrophy, deload every 4th week"*, *"I train Mon/Wed/Fri at my home gym and
Saturdays at the office gym — design a strength block."*

All UI uses the existing tokens and the server-action-as-prop rule via a new
`lib/workout-program-api.ts`; SSE for the chat stream (`web/CLAUDE.md`).

---

## Integration with Goals (IMPL-12)

A program can link to a Goal via `goalId`. The existing Goal metrics
`workouts.count` and `workouts.weeklyVolume` already exist as registry keys;
once workout logging lands (a later spec writes `Workout` / `WeeklyWorkoutAggregate`
records), a Goal step like *"complete 40 sessions"* auto-checks from the program
the user is actually following. No Goals changes are required in this spec — the
linkage is a stored `goalId` and a shared vocabulary, surfaced as an "Attached
to goal: …" line on the program detail.

---

## Build sequence

1. `core/workoutprogram`: records + enums + repository interfaces +
   `WorkoutProgramService` (CRUD, phase sequencing) + `WorkoutProgramValidator`.
2. `persistence/workoutprogram`: Firestore repositories (embedded blocks).
3. `WorkoutScheduleService` materialization + `ScheduledWorkout` + calendar
   endpoint.
4. CRUD + activate + calendar controllers.
5. `integrations/workoutprogram`: `GeminiWorkoutProgramChatClient` (Pro tool
   calling, per-day allow-list in the prompt) + config + ADR-0007.
6. Web: programs list + detail (roadmap + calendar), manual editor.
7. Web: assistant-ui designer chat + `<WorkoutProgramProposalCard>` with
   gym-scoped exercise pickers and inline validation.
8. End-to-end: chat a 4-day hypertrophy block with a deload week, confirm every
   prescribed exercise is executable at its day's gym, edit one, commit,
   activate, and see the calendar populate with deload weeks marked.

## Out of scope for IMPL-15

- **Logging actual performed workouts** (weights/reps achieved, RPE felt) — a
  later spec; the existing `Workout` record is the bridge.
- Auto-progression that rewrites future prescriptions from logged performance
  (e.g. autoregulation) — needs logging first.
- Android program UI (future `IMPL-AND-15`).
- Gemini revising a live program in place (v1: chat proposes; edits to a live
  program are manual on the editor — same stance as Goals).
- Sharing/exporting programs; a template library of pre-built programs.
- Parallel/overlapping phases (strict sequence only, like Goals).
- True video exercise demos (deferred in IMPL-14, behind its own ADR).
- 1RM tracking / true %1RM auto-calculation (intensity is a hint in v1).

## Acceptance criteria

- A user can chat *"4-day upper/lower hypertrophy block, deload every 4th
  week, Mon/Thu home gym and Tue/Fri office gym"* and receive an editable
  `<WorkoutProgramProposalCard>` with phases, a deload week, weekly Workout
  Days mapped to those days and gyms, and typed blocks (warm-up → main →
  accessory → cool-down).
- **Every prescribed exercise is executable at its day's gym**: an exercise
  requiring a barbell never appears on a dumbbell-only day, and the validator
  flags it inline if an edit introduces one.
- A user can compose blocks freely — add a cardio block, a stretching block, a
  warm-up and cool-down — and each holds only block-type-appropriate exercises.
- Editing a Workout Day's gym re-validates its prescriptions and flags any that
  the new gym can't run.
- Manual creation produces the same structure with no chat, and its exercise
  pickers only offer gym-executable exercises.
- Phases run in strict sequence; completing the active phase activates the
  next; the program completes when the last phase does (Goals semantics).
- Activating a program materializes dated `ScheduledWorkout`s across the phase
  weeks, with deload weeks flagged; the calendar renders them; re-activating
  doesn't rewrite past completed sessions.
- A program linked to a Goal shows the linkage and stores `goalId`.
- An invalid proposal (unknown/unapproved exercise, exercise not executable at
  its gym, block-type mismatch, deload week out of range) renders with the
  offending fields flagged inline, not silently dropped.
