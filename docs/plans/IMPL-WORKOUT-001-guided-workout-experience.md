# IMPL-WORKOUT-001: Guided workout experience (Future.co-style)

## Goal

Build the **in-workout guidance experience** for Tesseta — the screens that
take a user from "today's workout is waiting" on the dashboard, through a
coached, video-forward set-by-set player, to a post-workout summary. The
target UX is **Future.co** (the 1-on-1 personal-training app) reproduced
"almost exactly", with one substitution: Future's human coach is replaced by
**AI-generated coaching** (per the project's `gemini-3.5-flash` text rule).

This plan covers the product research, the end-to-end data model, the backend
read/execution surface, the dashboard entry points, and the platform split. It
is the umbrella for:

- **This document** — research, decisions, data model, backend API, web card,
  sequencing.
- [`IMPL-AND-07`](../specs/IMPL-AND-07-guided-workout-player.md) — the detailed
  Android implementation spec (the bulk of the work).

### Explicit scoping (from the requester)

> "Assume that the set/exercises/weights are already loaded into the
> application and all upstream requirements are already done."

So **program/session generation is out of scope**. Some upstream process (the
AI planner) has already written, for each user, the scheduled sessions with
their blocks, exercises, prescribed sets, target weights, rest periods, and
demo-video references. This plan **consumes** that data: it reads a planned
session, guides the user through executing it, records what they actually did,
and finalizes a summary.

Platform split (decided with the requester):

| Surface | Scope |
|---|---|
| **Android (phone)** | Full guided experience: dashboard start card, overview, player, summary. **Primary deliverable.** |
| **Web** | A **results card** on the dashboard reflecting the latest completed session. No player on web. |
| **Wear OS** | Excluded for now (noted as future work). |
| **Coach / social** | No human coach. AI-only intro + recap. No messaging. |

---

## How Future.co works (research teardown)

Synthesized from Future's product and multiple long-form reviews (sources at
the bottom). The experience has four beats:

### 1. Weekly program → a scheduled "today"

Future delivers a fresh week of sessions on a cadence (their coaches push
programs every Sunday). The home screen surfaces **today's session** as the
primary call to action, with the rest of the week visible as a strip/calendar.
Rest days are first-class (the day simply has no session).

> **Tesseta mapping:** the upstream planner writes scheduled sessions keyed by
> date. The dashboard reads "today's session" and shows a **start card**. The
> week strip is a phase-2 nicety.

### 2. Workout overview (pre-flight)

Before starting, the user opens an **Overview**: a coach intro message, the
estimated duration, and the full exercise list with **sets × reps and target
weight** per move. Each exercise can be tapped to **preview** its demonstration
video and notes. One prominent **Start** button.

### 3. The in-workout player (the heart of it)

Full-screen and **video-forward**. For the current movement:

- A **high-quality demonstration video loops** continuously (clean angle,
  steady tempo), so it's glanceable mid-set.
- The exercise **name**, the **set indicator** ("Set 2 of 4"), and the
  **target reps × weight** are overlaid.
- **Audio/voice coaching cues** play — a motivating intro at the start, plus
  form tips and encouragement during the work. (Future = human coach voice;
  Tesseta = AI-generated cue text, optionally spoken via on-device TTS.)
- A **timer** governs timed movements (planks, intervals); it counts the
  interval and **auto-advances** when it expires.
- For rep-based movements the user **logs the set** — confirm/adjust the reps
  actually performed and **adjust the suggested weight** up or down — then taps
  **Next** to advance. (Future also lets an Apple Watch tap advance hands-free;
  out of scope here.)
- A **rest timer** appears between sets, showing a countdown and an **"up
  next"** preview; the user can skip or add time.
- **Supersets / circuits**: within a grouped block the player cycles one set of
  each exercise in turn before resting, then loops the round.
- The user can **pause anytime** (water break) without losing state.

### 4. Post-workout summary

On completion the app **records the stats** (duration, volume, est. calories),
**saves progress**, and surfaces a recap. In Future the coach is notified and
the user leaves quick feedback. Tesseta substitutes an **AI recap** and an
optional self-rating/notes field.

### Visual language

Future's UI is described consistently as "simple, sleek, aesthetically
pleasing" — minimal chrome, large type, and the **exercise video as the hero**.
The player leans **immersive/dark** so the video reads in a gym; the rest of
the app is clean and uncluttered. Tesseta's existing surfaces are a **light**
"Hf" theme; we keep that for dashboard/overview/summary and make the **player
an immersive dark surface** (a dark scrim over the looping video), which is
both closer to Future and more legible over video.

---

## Product decisions

| Topic | Decision |
|---|---|
| Platform priority | Android-first, full experience. Web gets a read-only results card. Wear OS deferred. |
| Coach substitution | No human coach. AI (`gemini-3.5-flash`, the project's text model) generates the optional intro line and the post-workout recap. Per-exercise form cues come **pre-loaded on the exercise** (authored upstream), not generated live. No messaging UI. |
| Source of session data | Upstream/AI planner already wrote the scheduled sessions (blocks, exercises, prescribed sets, weights, rest, demo-video refs). This feature is **read + execute + log + finalize** only. |
| "Today" lookup | Dashboard reads a single `GET …/sessions/today`. The week/calendar strip is deferred to a later phase. |
| Player media | `androidx.media3` ExoPlayer loops a **silent** demo video per exercise; next exercise's clip is **preloaded**. Graceful fallback to a looping still/animated image when a clip is missing. |
| Set logging | Optimistic + locally buffered. The workout must continue with no network; logged sets sync in the background and reconcile at completion. |
| Superset/circuit semantics | The execution engine **flattens** a session's blocks into an ordered list of steps. A `SUPERSET`/`CIRCUIT` block emits one set of each exercise per round, then a rest, repeated for the round count — matching Future's cycling behavior. |
| Immersive theme | The player is a **dark immersive surface** regardless of app theme; dashboard/overview/summary stay on the existing light Hf theme. Adds a small dark token set scoped to the player. |
| Calories | Estimated from duration × activity MET on the backend at completion (no live HR dependency on phone; HR enrichment is future work). |
| AI recap | A short post-workout recap generated at completion. Optional and non-blocking — if the model call fails, the summary renders without it. |

---

## End-to-end data model

The existing `backend/core/.../workout/Workout.java` is a thin "a workout
happened" record (`activityType`, `locationId`, `startTime`, `endTime`,
`source`). We keep it as the **completed-activity log** it already is and add
the **structured session** model on top. `WeeklyWorkoutAggregate` (already
present: `totalTonnage`, `sessionCount`) is updated on completion.

Firestore layout (per the Location precedent — collection-per-user, `Instant`
timestamps, soft delete via status):

```
users/{userId}/workoutSessions/{sessionId}      # the planned + live session
exercises/{exerciseId}                            # shared exercise catalog (demos, cues)
users/{userId}/workoutSessions/{sessionId}/summary  # finalized recap (or inline)
```

### `WorkoutSession` (planned + live)

The single document the player reads and writes as the user progresses.

```
WorkoutSession
  sessionId        String
  userId           String
  scheduledDate    LocalDate        # the day this belongs to (schedule key)
  title            String           # "Pull Day"
  focus            String?          # "Back & biceps"
  status           SessionStatus    # SCHEDULED | IN_PROGRESS | COMPLETED | SKIPPED
  estimatedMinutes Int
  blocks           List<Block>      # ordered; authored upstream
  startedAt        Instant?
  completedAt      Instant?
  summary          SessionSummary?  # set on completion
  createdAt        Instant
  updatedAt        Instant

Block
  blockId          String
  type             BlockType        # WARMUP | STRENGTH | SUPERSET | CIRCUIT | CARDIO | COOLDOWN
  label            String?          # "Superset A"
  rounds           Int              # 1 for straight sets; N for circuits/supersets
  restSecondsAfter Int              # rest after the block
  exercises        List<PrescribedExercise>

PrescribedExercise
  exerciseId       String           # → exercises/{exerciseId}
  name             String           # denormalized for offline render
  prescribedSets   List<PrescribedSet>
  restSecondsBetweenSets Int
  notes            String?          # coach/AI cue text authored upstream

PrescribedSet
  setId            String
  targetReps       Int?             # rep-based
  targetSeconds    Int?             # time-based (exactly one of reps/seconds)
  targetWeight     Double?          # null = bodyweight
  weightUnit       WeightUnit       # LB | KG

# Execution data, written by the player:
LoggedSet                            # keyed by setId within the session
  setId            String
  actualReps       Int?
  actualWeight     Double?
  completed        Boolean
  loggedAt         Instant

SessionSummary
  durationSeconds  Int
  totalVolume      Double           # Σ reps × weight (tonnage)
  setsCompleted    Int
  setsPrescribed   Int
  estimatedCalories Int
  perExercise      List<ExerciseResult>   # name, topSet, volume
  aiRecap          String?
```

`LoggedSet`s are stored as a `Map<setId, LoggedSet>` on the session so a single
PATCH appends/updates one entry. (Same untyped-map-on-the-document pattern the
gym feature uses for `equipmentSpecs`.)

### `Exercise` (shared catalog)

Authored upstream; read-only to this feature. Links to the equipment catalog
that IMPL-GYM-001 already established.

```
Exercise
  exerciseId       String
  name             String
  primaryMuscle    String
  equipmentId      String?          # → equipment/{equipmentId}
  demoVideoUrl     String?          # looping silent clip
  demoImageUrl     String?          # fallback still/animated
  cues             List<String>     # short form tips (1–3)
  createdAt / updatedAt
```

---

## Backend API (`/api/me/workouts`)

Mirrors the `LocationController` conventions exactly: DTOs are Java records,
controller → `core` service → `persistence` repository, `currentUser` from
`CurrentUserProvider`, `ResponseStatusException(NOT_FOUND)` for user errors,
`Instant` timestamps. **Read + execute + finalize only** (no create — sessions
are authored upstream).

| Method & path | Purpose |
|---|---|
| `GET /api/me/workouts/sessions/today` | Today's scheduled session for the dashboard start card (or `204` on a rest day). |
| `GET /api/me/workouts/sessions?from=&to=` | Sessions in a date range (schedule strip / history). |
| `GET /api/me/workouts/sessions/{id}` | Full session detail — drives overview + player. |
| `POST /api/me/workouts/sessions/{id}/start` | Transition `SCHEDULED → IN_PROGRESS`; stamps `startedAt`; idempotent. |
| `PATCH /api/me/workouts/sessions/{id}/sets/{setId}` | Log/adjust one set (`actualReps`, `actualWeight`, `completed`). Body is partial. Returns the updated `LoggedSet`. |
| `POST /api/me/workouts/sessions/{id}/complete` | Finalize: compute `SessionSummary`, optional AI recap, bump `WeeklyWorkoutAggregate`, write the recent-feed/activity log. Returns the summary. |
| `GET /api/me/workouts/sessions/{id}/summary` | The finalized summary (web results card + summary screen). |
| `GET /api/me/workouts/sessions/latest-completed` | Most recently completed session's summary (web dashboard card). |
| `GET /api/exercises/{id}` | Single exercise (demo + cues) for the preview sheet. |

Notes:

- **Completion is the only place** that writes the aggregate and the activity
  feed, so the existing dashboard "recent feed" ("Pull Day completed · 5
  exercises · 18 sets") becomes live for free once this lands.
- The AI recap uses `gemini-3.5-flash` (the project text model — no other
  provider/model without an ADR, per root `CLAUDE.md`). It is generated
  server-side at completion and stored on the summary so clients never call the
  model directly. Failure degrades to `aiRecap = null`.
- Batch-friendly logging: the client may also `POST …/sets:batch` a buffered
  set of logs at completion if it was offline. (Optional; `PATCH` per set is the
  baseline.)

Backend module placement (per `backend/CLAUDE.md` layering):

- `core/workout/` — `WorkoutSession`, `Block`, `PrescribedExercise`,
  `PrescribedSet`, `LoggedSet`, `SessionSummary`, `Exercise`, the enums, and
  `WorkoutSessionService` (start/log/complete logic, volume + calorie math,
  recap orchestration). Pure Java.
- `api/workout/` — `WorkoutSessionController`, request/response records.
- `persistence/workout/` — Firestore repository impls (extend the existing
  `WorkoutRepository`).
- `integrations` / existing Gemini client — the recap generator.

---

## Dashboard entry points

### Android — "Today's workout" start card

A new `TodaysWorkoutCard` on `PhoneTodayScreen` (and the foldable dashboard),
above the existing nutrition `TodayCard`. It reads `…/sessions/today` via a
`TodayWorkoutViewModel` and renders one of:

| Session state | Card |
|---|---|
| `SCHEDULED` | Title + focus, "N exercises · ~M min", a prominent **Start** button. |
| `IN_PROGRESS` | Same, with a **Resume** button and a progress hint ("3 of 12 sets"). |
| `COMPLETED` | Collapsed result row (duration · volume) + **View summary**. |
| Rest day (`204`) | "Rest day" message, no CTA. |

The existing **"Workout" quick-log tile** (currently a no-op in
`PhoneTodayScreen.kt:115`) is wired to the same Start/Resume action. The
workout row already inside `TodayCard` stays as the nutrition card's summary
line; the new dedicated card owns the *call to action*.

### Web — results card

`WorkoutResultsCard` (server component) on the web dashboard reads
`…/sessions/latest-completed` and renders the latest finished session: title,
duration, total volume, sets, and a small per-exercise list, linking to
`/me/workouts`. It replaces the hardcoded `today.workout` fixture in
`web/lib/fixtures/dashboard.ts` with live data and **falls back to the fixture**
when the call fails (matching how the dashboard already tolerates missing data).
New `web/lib/types/workout.ts` + `web/lib/workout-api.ts` (server-only helper,
per `web/CLAUDE.md`).

---

## Phasing & sequencing

Phases are independently shippable; later phases depend on earlier ones.

| Phase | Deliverable | Depends on |
|---|---|---|
| **0 — Backend** | Session read model, `start`/`log`/`complete`/`summary`/`today`/`latest-completed` endpoints, volume+calorie math, aggregate + feed write, AI recap. | Upstream session data exists. |
| **1 — Android plumbing** | `core-domain` session/exercise models, `core-data` Retrofit services + repository + mappers, nav routes, Media3 dependency. | Phase 0; IMPL-AND-00 foundations. |
| **2 — Dashboard + Overview** | `TodaysWorkoutCard`, wired quick-tile, `WorkoutOverviewScreen` (intro, list, exercise preview sheet). | Phase 1. |
| **3 — Player (core)** | `WorkoutPlayerScreen` + execution engine: looping video, set indicator, set logger, rest timer, superset cycling, pause, auto-advance for timed moves. | Phase 2. |
| **4 — Summary + completion** | `WorkoutSummaryScreen`, `complete` wiring, live recent feed/aggregate. | Phase 3. |
| **5 — Web results card** | `WorkoutResultsCard`, types + api helper, fixture fallback. | Phase 0. |
| **6 — Deferred** | On-device TTS for spoken cues, offline buffering hardening, week/calendar strip, history list, music (Spotify) integration, Wear OS companion, live HR/calorie enrichment. | — |

Phases 0 and 5 (backend + web card) can proceed in parallel with the Android
work once the API shape is frozen.

---

## Risks & open questions

- **Demo-video delivery.** Looping clips per exercise are bandwidth- and
  storage-heavy. Assumed upstream concern (CDN/Firebase Storage URLs on the
  `Exercise`). The player must preload-next and fall back to a still. Confirm
  the upstream pipeline produces web-friendly (HLS or short MP4) loops.
- **Immersive dark theme.** The Hf theme is light-only today. The player adds a
  scoped dark token set; we should confirm this doesn't fork the design system
  (it's contained to the player surface).
- **Audio cues.** Future uses recorded coach audio. We start with **on-screen**
  cue text (cheap, reliable); on-device **TTS** is a phase-6 follow-on rather
  than synthesizing/serving audio files.
- **Set-log conflict model.** Optimistic local writes + server PATCH; last
  write wins per `setId`. Acceptable because a session is single-user,
  single-device in practice. Multi-device handoff is out of scope.
- **Where the engine lives.** The superset-flattening / step sequencing logic is
  specified to live in a pure-Kotlin `SessionEngine` in `core-domain` so it's
  unit-testable without Compose. See IMPL-AND-07.

---

## Sources (Future.co research)

- [Future — Custom, 1-on-1 Personal Training](https://future.co/)
- [Future App Review 2026 (onbetterliving)](https://onbetterliving.com/future-app/)
- [I Tried the Future App for 30 Days (GymBird)](https://www.gymbird.com/fitness-apps/i-tried-the-future-app-for-30-days-here-are-the-results)
- [Future Fitness App Review (Athletic Insight)](https://www.athleticinsight.com/exercise/future-fitness-app-review)
- [Future Workout App Review 2025 (Sports Nerd)](https://sports-nerd.com/brand/future/)
- [Future Pro: Personal Training — App Store](https://apps.apple.com/us/app/future-pro-personal-training/id1288178982)
</content>
</invoke>
