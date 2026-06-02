# IMPL-AND-15: Android — Workout Programs (read-only)

## Goal

Bring **workout programs** to the Android phone app as a **read-only** viewing
surface, consuming the backend from [`IMPL-15-workout-programs.md`](IMPL-15-workout-programs.md)
(which builds on the exercise library, [`IMPL-14-exercise-library.md`](IMPL-14-exercise-library.md)).
The user can browse their programs as a card list, open a program to see its
periodized roadmap (Phases → Workout Days → Blocks → Exercise prescriptions),
see "this week's" scheduled sessions, and tap any exercise to view its demo
(the phase-still sequence + form cues from IMPL-14).

**This is the initial implementation: viewing only.** No creation, no AI
designer chat, no manual editor, no edit/reorder/delete, no program activation,
and no workout *logging*. Those land in a follow-up (`IMPL-AND-15b`) once the
read surface is proven, mirroring how the web shipped.

This extends the existing `feature-workouts` module (which already owns gyms +
equipment from IMPL-AND-06) — programs live alongside gyms under the
**Workouts** area, exactly as the web nests them under `/me/workouts/programs`.

> Sequenced **after** backend IMPL-14 + IMPL-15 land. It adapts those contracts
> to the Android stack (Hilt, Retrofit/Moshi, Compose, Coil) and requires one
> small backend addition (an embedded exercise summary on the deep response —
> see Decisions), not a new feature.

## Scope

In scope:

- **Workouts hub** at `workouts` — the Workouts destination becomes a hub with
  two cards, **Gyms** (the existing IMPL-AND-06 list, rebased to
  `workouts/gyms`) and **Programs**, mirroring the web Workouts area.
- **Programs list** at a `workouts/programs` route — cards mirroring the web
  programs list: title, status pill, a compact **phase spine** (segment per
  phase, colored by `LOCKED`/`ACTIVE`/`COMPLETED`, deload weeks marked),
  "Week N of M" progress, and training-days/week. Sourced from
  `GET /api/me/workout-programs` (shallow).
- **Program detail** at `workouts/programs/{programId}` —
  `GET /api/me/workout-programs/{id}` (deep): a **phase timeline** (reusing the
  Goals `RoadmapTimeline` idiom) where each phase expands to its weekly
  microcycle of **Workout Day** cards; each day expands to its typed **Blocks**
  (warm-up, main, cardio, cool-down, stretch, …) and the **Exercise
  prescriptions** in each (sets × reps or duration, intensity, rest, tempo,
  notes, deload modifier). Each day shows its **gym** (`Location` name) and
  day-of-week. When the program is linked to a Goal, an **"Attached to goal:
  {title}"** row deep-links into `feature-goals`.
- **This week / schedule** — `GET /api/me/workout-programs/{id}/calendar?from=&to=`
  rendered as the upcoming `ScheduledWorkout`s (date, day label, gym, deload
  tint, status). V1 shows the current week; a full month calendar is a
  follow-up.
- **Exercise detail sheet** — a `ModalBottomSheet` opened from a prescription
  row: the IMPL-14 **demo phase stills** (START/MID/END) loaded via Coil and
  shown as a simple stepper/loop, plus name, primary muscles, equipment, and
  form cues. Fed by the embedded `ExerciseSummary` (see Decisions) — no N+1
  per-exercise fetch.
- **Read-only states** — `LoadingState` / `EmptyState` / `ErrorState` from
  `core-ui`, online-only, pull-to-refresh.

Out of scope (explicitly deferred to `IMPL-AND-15b` / later):

- **All mutation.** No create, AI designer chat (SSE), manual editor,
  edit/reorder/delete of programs/phases/days/blocks/prescriptions, or
  program activation. The phone is a viewer in v1.
- **Workout logging** (recording weights/reps actually performed) — greenfield
  on both clients, Phase 7 / its own ADR, out of scope here and in IMPL-15.
- **Admin / exercise-catalog management** — desktop-only, per parity roadmap §2.8.
- **Full month calendar & schedule editing** — v1 shows the current week.
- **Offline caching** — online-only V1 (Room → AND-09), matching IMPL-AND-12.
- **Wear OS surface.**
- **Backend feature changes** beyond the one small embedded-summary addition
  below — the IMPL-15 read contract is otherwise consumed as-is.

## Decisions

| Topic | Decision |
|---|---|
| Read-only v1 | Viewing only. No mutation, no chat, no logging. Matches the user's "see the workouts; don't need edit yet." |
| Spec numbering | **IMPL-AND-15** to line up with backend/web `IMPL-15`, as IMPL-AND-12 lined up with IMPL-12. |
| Module | Extend the existing **`feature-workouts`** module (don't create a new one). Programs sit beside gyms under the Workouts area, mirroring web `/me/workouts/programs`. |
| Domain placement | New models under `core-domain/.../workouts/program/` (reuse the existing `DayOfWeek` from IMPL-AND-06's `workouts` package). |
| Endpoint shapes | Reuse the IMPL-15 response shapes byte-for-byte (`WorkoutProgramResponse` shallow, `WorkoutProgramDeepResponse`, `PhaseResponse`, `WorkoutDayResponse`, `BlockResponse`, `PrescriptionResponse`, `ScheduledWorkoutResponse`). |
| Exercise rendering | **Backend embeds a compact `ExerciseSummary` on each prescription in the deep + calendar responses** (exerciseId, name, primaryMuscles, demoFrames thumbnail URLs, formCues, equipment names). One round trip; no per-exercise N+1 from the phone. This is a **small addition to IMPL-15** (see the amendment note in that spec), needed for web too. Fallback if not embedded: a batch `GET /api/exercises?ids=` consumed once per detail load. |
| Demo media | Phase **stills** (IMPL-14) via Coil `AsyncImage`, shown as a 3-step pager/loop. No video in v1 (Veo deferred behind its own ADR). |
| Nav routes | **String routes** appended to the existing `WorkoutsRoutes` object (the module's convention), with `programId` as a path arg read via `SavedStateHandle`. |
| Entry point | The Workouts destination becomes a **hub**: tapping **Workouts** (bottom nav / "More" / sidebar) lands on a new `WorkoutsHubScreen` with two cards — **Gyms** and **Programs**. The gyms list moves down one level to `workouts/gyms`; programs live at `workouts/programs`. This reorganizes IMPL-AND-06's landing route (called out below). |
| Goal linkage | **In scope.** When a program has a `goalId`, the detail screen shows an "Attached to goal: {title}" line that deep-links into `feature-goals`. Requires the deep response to carry the goal **title** (not just the id) — folded into the IMPL-15 embed note. |
| Status display | All status (`PhaseStatus`, deload, behind-schedule, scheduled status) is **rendered from the backend** — no client computation, same stance as IMPL-AND-12's "Android only displays evaluated state." |
| Date types | `startDate` / dates as `LocalDate` via the IMPL-AND-00 `LocalDateAdapter`; `Instant` for `createdAt`/`updatedAt`/`completedAt`. `DayOfWeek` reuses the lowercase-wire Moshi adapter from IMPL-AND-03/06. |
| Theme | Filled `HfCard` for data cards (programs, days, blocks); `Hf.colors.accent` (olive) for active phase/spine; a muted/deload tint for deload weeks; `caps-mono` for day-of-week + date labels. Edge-to-edge insets + back affordance per `android/CLAUDE.md`. |

## Dependencies

- **Backend IMPL-14 + IMPL-15** — must land first. This spec consumes their
  read endpoints; the one required addition is the embedded `ExerciseSummary`
  on the deep + calendar responses (folded into IMPL-15).
- **IMPL-AND-00 (Foundations)** — Hilt graph, single `NavHost` + nested
  per-feature graphs + phone "More" hub, `core-data/net` (Retrofit + OkHttp +
  Moshi, `AuthInterceptor` + 401 silent-refresh), Coil, `core-ui`
  (`LoadingState`/`EmptyState`/`ErrorState`, `AsyncImage`, `HfCard`, `Hf`
  theme, `SnackbarController`), ViewModel/UiState conventions, the
  `LocalDate`/`Instant`/`DayOfWeek` Moshi adapters.
- **IMPL-AND-06 (Gym & Equipment)** — owns `feature-workouts`, the
  `WorkoutsRoutes` object + `workoutsGraph(navController)`, the `workouts`
  bottom-nav/More slot, and the `DayOfWeek` / `Location` domain this spec
  reuses for gym names.
- **IMPL-AND-12 (Goals)** — reference implementation for the read-only
  roadmap/timeline rendering (`RoadmapTimeline`, phase accordion, status pills)
  this spec mirrors.

## Per-module deliverables

### `core-domain/.../workouts/program/`

Pure-Kotlin models mirroring IMPL-15 (read subset — no proposal/chat types):

```kotlin
package com.gte619n.healthfitness.domain.workouts.program

import com.gte619n.healthfitness.domain.workouts.DayOfWeek   // reuse IMPL-AND-06
import java.time.Instant
import java.time.LocalDate

enum class ProgramStatus { DRAFT, ACTIVE, COMPLETED, ARCHIVED }
enum class ProgramSource { MANUAL, AI_GENERATED, AI_ASSISTED }
enum class ProgramPhaseStatus { LOCKED, ACTIVE, COMPLETED }
enum class BlockType { WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH }
enum class IntensityKind { RPE, PERCENT_1RM, NONE }
enum class ScheduledStatus { PLANNED, COMPLETED, SKIPPED }

data class Intensity(val kind: IntensityKind, val value: Double?)
data class DeloadModifier(val setsMultiplier: Double?, val intensityDelta: Double?)

/** Compact, embedded exercise info for rendering a prescription + its demo. */
data class ExerciseSummary(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String>,
    val equipmentNames: List<String>,
    val formCues: List<String>,
    val demoFrames: List<DemoFrame>,        // START/MID/END image URLs
)
data class DemoFrame(val phase: String, val imageUrl: String?)

data class Prescription(
    val exerciseId: String,
    val orderIndex: Int,
    val sets: Int?,
    val repsMin: Int?,
    val repsMax: Int?,
    val durationSeconds: Int?,
    val intensity: Intensity?,
    val restSeconds: Int?,
    val tempo: String?,
    val notes: String?,
    val deloadModifier: DeloadModifier?,
    val exercise: ExerciseSummary?,         // embedded; null only if backend omits
)

data class Block(
    val blockId: String,
    val type: BlockType,
    val title: String,
    val orderIndex: Int,
    val prescriptions: List<Prescription>,
)

data class WorkoutDay(
    val dayId: String,
    val label: String,
    val dayOfWeek: DayOfWeek,
    val locationId: String,
    val locationName: String?,              // resolved by the backend for display
    val orderIndex: Int,
    val blocks: List<Block>,
)

data class ProgramPhase(
    val phaseId: String,
    val title: String,
    val focus: String?,
    val orderIndex: Int,
    val status: ProgramPhaseStatus,
    val weeks: Int,
    val deloadWeekIndex: Int?,
    val targetStartDate: LocalDate?,
    val targetEndDate: LocalDate?,
    val days: List<WorkoutDay> = emptyList(),   // empty in the shallow list response
)

data class WorkoutProgram(
    val programId: String,
    val title: String,
    val description: String?,
    val goalId: String?,
    val status: ProgramStatus,
    val source: ProgramSource,
    val startDate: LocalDate?,
    val trainingDays: List<DayOfWeek>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val phases: List<ProgramPhase> = emptyList(),   // empty in the shallow list
) {
    /** "Week N of M" — phases sum their weeks; current week comes from the backend's active phase + dates. */
    val totalWeeks: Int get() = phases.sumOf { it.weeks }
    val phaseProgress: Pair<Int, Int>
        get() = phases.count { it.status == ProgramPhaseStatus.COMPLETED } to phases.size
}

data class ScheduledWorkout(
    val scheduledId: String,
    val date: LocalDate,
    val phaseId: String,
    val dayId: String,
    val dayLabel: String,
    val weekIndexInPhase: Int,
    val isDeload: Boolean,
    val locationId: String,
    val locationName: String?,
    val status: ScheduledStatus,
)
```

Read-only repository:

```kotlin
interface WorkoutProgramRepository {
    suspend fun list(): Result<List<WorkoutProgram>>                 // shallow
    suspend fun get(programId: String): Result<WorkoutProgram>       // deep
    suspend fun calendar(
        programId: String, from: LocalDate, to: LocalDate,
    ): Result<List<ScheduledWorkout>>
}
```

A small `MuscleLabels` / `BlockTypeLabels` helper maps enum keys to friendly
display strings (e.g. `WARMUP` → "Warm-up", `quadriceps` → "Quads"), falling
back to the raw value — same pattern as `MetricKeyLabels` in IMPL-AND-12.

### `core-data/.../workouts/program/`

```kotlin
interface WorkoutProgramApi {
    @GET("api/me/workout-programs")
    suspend fun list(): List<WorkoutProgramDto>                      // shallow

    @GET("api/me/workout-programs/{id}")
    suspend fun get(@Path("id") id: String): WorkoutProgramDeepDto   // deep + embedded summaries

    @GET("api/me/workout-programs/{id}/calendar")
    suspend fun calendar(
        @Path("id") id: String,
        @Query("from") from: String,        // ISO LocalDate
        @Query("to") to: String,
    ): List<ScheduledWorkoutDto>
}
```

- DTOs with `@JsonClass(generateAdapter = true)` mirroring each IMPL-15
  response, including the embedded `ExerciseSummaryDto` / `DemoFrameDto`.
- `WorkoutProgramMapper` (DTO ↔ domain) with `enumValueOf<T>()` + safe
  fallback (unknown `BlockType`/`status` degrade rather than crash).
- Hilt `WorkoutProgramDataModule` providing `WorkoutProgramApi` and binding
  `WorkoutProgramRepository` → `WorkoutProgramRepositoryImpl` (try/catch →
  `Result`, map DTO ↔ domain, on `Dispatchers.IO`).

No SSE client (read-only — nothing streams).

### `feature-workouts/` additions

Package `com.gte619n.healthfitness.feature.workouts.program`.

**Compose conventions (match the shipped `feature-goals` / `feature-workouts`
code):** each screen is a **`*Route` + `*Screen` pair** — `*Route` injects the
ViewModel via `hiltViewModel()`, collects state with
`collectAsStateWithLifecycle()`, and passes plain state + callbacks down to a
**stateless `*Screen`** (so screens stay previewable/snapshot-testable). Root
container is a `Column` on `Hf.colors.canvas` with
`.windowInsetsPadding(WindowInsets.systemBars)`, topped by `HfScreenHeader(title,
subtitle, onBack = …)` for the back affordance. Status/empty/error use the
`core-ui` `LoadingState` / `EmptyState` / `ErrorState` shells; lists are
`LazyColumn` with `items(…, key = { … })`. Reuse the `core-ui` primitives
`HfCard` (filled, for data cards), `Pill(text, HfTone)`, `CapsLabel`,
`ProgressTrack(pct, color)`, and the Coil `AsyncImage` wrapper rather than
re-rolling them. ViewModels follow the `@HiltViewModel` + private
`MutableStateFlow(UiState())` → public `asStateFlow()` convention, with
`SavedStateHandle[ARG_PROGRAM_ID]` for the detail id.

ViewModels + screens (one `*Route`/`*Screen`/`*ViewModel` set per destination):

- `WorkoutsHubScreen` — stateless two-card hub (Gyms → `WorkoutsRoutes.GYMS`,
  Programs → `WorkoutsRoutes.PROGRAMS`). No ViewModel; pure navigation.
- `ProgramsListViewModel` / `ProgramsListScreen` — shallow list; `ProgramCard`s;
  empty state "No programs yet" (with a hint that programs are created on the
  web in v1). Pull-to-refresh.
- `ProgramDetailViewModel` / `ProgramDetailScreen` — deep load via
  `SavedStateHandle` `programId`; an **"Attached to goal: {title}"** row
  (when `goalId` present) wired to `onOpenGoal`; phase timeline; expandable
  Workout Days; blocks; prescription rows; a "This week" section backed by
  `calendar(...)` for the current week. Tapping a prescription opens
  `ExerciseDetailSheet`.

Composables (under `feature.workouts.program.ui/`, styled with `Hf`/`HfCard`):

- `ProgramCard(program, onClick)` — title, status pill, phase spine
  (`Row` of segments, olive/active/locked + deload mark), "Week N of M",
  training-days summary; behind-schedule treatment (dim + warn pill) when the
  backend flags it.
- `PhaseTimeline(phases)` — vertical spine, one node per phase
  (completed/active/locked tints), each expanding to its Workout Days. Reuses
  the Goals `RoadmapTimeline` visual idiom.
- `WorkoutDayCard(day)` — label, `caps-mono` day-of-week + gym name; expands to
  its blocks.
- `BlockSection(block)` — typed header (`BlockTypeLabels`), ordered
  `PrescriptionRow`s.
- `PrescriptionRow(prescription, onOpenExercise)` — exercise name, a compact
  "3 × 8–10 @ RPE 8 · rest 90s" line (or "12 min" for timed), a small demo
  thumbnail (`AsyncImage` of the START frame), deload chip when a modifier is
  present; tap → sheet.
- `ExerciseDetailSheet(summary)` — `ModalBottomSheet`: a 3-step demo viewer
  (START/MID/END `AsyncImage` with prev/next + auto-loop toggle), name,
  primary-muscle chips, equipment chips, and a bulleted form-cues list. The
  anatomical "demo is a guide" framing is fine to omit on the user surface
  (review happened in admin, IMPL-14).
- `ThisWeekStrip(scheduled)` — horizontal cards of the week's
  `ScheduledWorkout`s (date, day label, gym, deload tint, status dot).
- `DeloadBadge`, `BehindScheduleBadge`.

### Navigation — Workouts hub + extend `WorkoutsRoutes`

The Workouts destination becomes a hub. `"workouts"` now renders
`WorkoutsHubScreen` (two cards: Gyms, Programs); the gyms list moves to
`"workouts/gyms"`. This is a small reorg of the IMPL-AND-06 route constants —
all existing IMPL-AND-06 navigation references the `WorkoutsRoutes.GYMS`
constant, so changing the constant's value (and adding the `HUB` route) is
contained; `popUpTo(WorkoutsRoutes.GYMS)` and friends keep working.

```kotlin
object WorkoutsRoutes {
    const val HUB = "workouts"               // NEW — landing hub
    const val GYMS = "workouts/gyms"         // CHANGED from "workouts"
    const val NEW_GYM = "workouts/gyms/new"  // CHANGED to nest under gyms (optional, keep consistent)
    // ...existing DETAIL / EDIT, rebased under workouts/gyms/{locationId}...

    const val PROGRAMS = "workouts/programs"
    const val ARG_PROGRAM_ID = "programId"
    const val PROGRAM_DETAIL = "workouts/programs/{programId}"
    fun programDetail(programId: String): String = "workouts/programs/$programId"
}

// inside fun NavGraphBuilder.workoutsGraph(navController: NavHostController):
composable(WorkoutsRoutes.HUB) {
    WorkoutsHubScreen(
        onBack = { navController.popBackStack() },
        onOpenGyms = { navController.navigate(WorkoutsRoutes.GYMS) },
        onOpenPrograms = { navController.navigate(WorkoutsRoutes.PROGRAMS) },
    )
}
composable(WorkoutsRoutes.PROGRAMS) {
    ProgramsListRoute(
        onBack = { navController.popBackStack() },
        onOpenProgram = { id -> navController.navigate(WorkoutsRoutes.programDetail(id)) },
    )
}
composable(
    route = WorkoutsRoutes.PROGRAM_DETAIL,
    arguments = listOf(navArgument(WorkoutsRoutes.ARG_PROGRAM_ID) { type = NavType.StringType }),
) {
    ProgramDetailRoute(
        onBack = { navController.popBackStack() },
        onOpenGoal = { goalId -> navController.navigate(Routes.goalDetail(goalId)) },
    )
}
// existing gyms-list composable rebinds to WorkoutsRoutes.GYMS ("workouts/gyms")
```

`WorkoutsHubScreen` is a plain two-card screen on `Hf.colors.canvas` with
`HfScreenHeader(title = "Workouts", onBack = …)` and two `HfCard`s ("Gyms",
"Programs"). All pushed screens render a back affordance and apply system-bar
insets, per `android/CLAUDE.md`.

### `app/` changes

- The phone **"More"** hub / bottom-nav / foldable-sidebar **"Workouts"** entry
  now lands on `WorkoutsRoutes.HUB` (the hub) instead of the gyms list. No new
  top-level nav item is added — Programs is reached through the hub.
- `goalDetail(goalId)` is the existing `feature-goals` route used for the
  "Attached to goal" deep link from the program detail.
- No `NavHost` restructure: `workoutsGraph` already registered; the hub +
  program composables are added inside it, and the gyms-list composable rebinds
  to the new `GYMS` constant. Hilt extends automatically via the new `@Module`.

### Gradle wiring

No new module. `feature-workouts` already depends on `core-domain`,
`core-data`, `core-ui`, `core-network`, Compose/Hilt/navigation. New files only.

## Tests

`core-data/.../workouts/program/`:

- **WorkoutProgramMapperTest** — every enum round-trips with safe fallback for
  an unknown `BlockType`/`ProgramStatus`; deep mapping carries embedded
  `ExerciseSummary` + `DemoFrame`; null `intensity`/`deloadModifier`/`exercise`
  tolerated; `DayOfWeek` lowercase wire round-trips.
- **WorkoutProgramApiTest** — `MockWebServer` shapes for list, deep get
  (with embedded summaries), and calendar (`from`/`to` query encoding).

`feature-workouts/.../program/` (Robolectric/JUnit + Turbine +
`MainDispatcherRule`):

- **ProgramsListViewModelTest** — initial loading; success populates list;
  repo failure surfaces `error`; `refresh()` re-invokes.
- **ProgramDetailViewModelTest** — deep load populates phases/days/blocks;
  parallel `calendar()` for the current week; repo failure → error state;
  exercise summary present on prescriptions.

`feature-workouts/.../androidTest/` (Compose snapshots, IMPL-AND-00 runner):

- **ProgramCardTest** — active program with deload-marked spine; behind-schedule
  variant; completed variant.
- **ExerciseDetailSheetTest** — sheet with 3 demo frames + cues; sheet with
  missing frames (placeholder) — light + dark themes.

## Acceptance

Automated:

1. `./gradlew :core-domain:test :core-data:test :feature-workouts:test` passes
   (includes the mapper + MockWebServer + ViewModel tests above).
2. Compose snapshot tests match recorded baselines.
3. `./gradlew :app:assembleDebug` builds with the new screens wired into the
   Workouts area and the "More" hub.

Manual (real device, connected account, with a program created on the web):

1. Open **Workouts → Programs** (from the More hub or the gyms-list Programs
   card). Existing programs render as cards with status, phase spine (deload
   marked), and "Week N of M".
2. Tap a program. The phase timeline renders; expanding the active phase shows
   its Workout Days with gym names and days-of-week; expanding a day shows its
   typed blocks (warm-up → main → cardio → cool-down) and prescriptions with
   "sets × reps @ intensity · rest".
3. The **This week** strip shows the upcoming scheduled sessions, with deload
   weeks tinted.
4. Tap an exercise row → the demo sheet opens with the START/MID/END phase
   stills (Coil), muscle + equipment chips, and form cues; stepping/looping the
   frames works.
5. **No mutation affordances** appear anywhere (no add/edit/delete/chat) —
   the surface is read-only, as specified.
6. Airplane-mode on a cold open → `ErrorState` with retry; retry after
   reconnect loads the list.

## Resolved decisions

- **Workouts entry — RESOLVED: hub.** The Workouts destination is a hub
  (Gyms / Programs); the gyms list rebases to `workouts/gyms`. See
  Navigation + `app/` changes.
- **Standalone exercise library on phone — RESOLVED: no.** There is no
  browsable Android exercise catalog in this effort; exercises surface only
  in-context via the `ExerciseDetailSheet`. (No `IMPL-AND-14`.)
- **Goal linkage — RESOLVED: in scope.** The program detail shows "Attached
  to goal: {title}" deep-linking into `feature-goals` when `goalId` is set.
  Requires the IMPL-15 deep response to carry the goal **title** (folded into
  the embed note).
- **Exercise summary — RESOLVED: embed.** The backend embeds `ExerciseSummary`
  on the deep + calendar responses (folded into IMPL-15). The batch
  `GET /api/exercises?ids=` is a documented fallback only; the model's
  `Prescription.exercise` field accommodates either.

## Open questions (to confirm during implementation)

- **Current-week / deload authority — CONFIRM.** The "This week" range is
  derived from the device date, but `weekIndexInPhase` / `isDeload` must come
  from the backend `ScheduledWorkout` so the client never recomputes deload
  status. Confirm these fields are authoritative once IMPL-15 is built.
```
