# IMPL-AND-07: Android — guided workout player (Future.co-style)

## Goal

Bring the **in-workout guidance experience** to the Android phone app: a
dashboard **start card** for today's scheduled session, a **workout overview**,
a video-forward **set-by-set player**, and a **post-workout summary** — modeled
on Future.co, with AI-generated coaching in place of a human coach. Populates
the `feature-workouts/` module (alongside the gym screens from IMPL-AND-06) with
a `session/` package and adds the player routes to the central `NavHost`.

Product context, the Future.co teardown, the full data model, and the backend
API are in [`IMPL-WORKOUT-001`](../plans/IMPL-WORKOUT-001-guided-workout-experience.md).
This spec is the Android implementation only.

## Scope

In scope:

- Domain models matching the IMPL-WORKOUT-001 session shape: `WorkoutSession`,
  `Block`, `PrescribedExercise`, `PrescribedSet`, `LoggedSet`, `SessionSummary`,
  `Exercise`, and the `BlockType` / `SessionStatus` / `WeightUnit` enums.
- A pure-Kotlin **`SessionEngine`** in `core-domain` that flattens a session's
  blocks into an ordered list of `PlayerStep`s (handling supersets/circuits and
  rests) — unit-testable with no Android/Compose deps.
- Retrofit `WorkoutSessionApi` + repository + Moshi DTOs/mappers under
  `core-data/workouts/` (the package IMPL-AND-06 already introduces).
- A **`TodaysWorkoutCard`** on the phone + foldable dashboards, backed by a
  `TodayWorkoutViewModel`, plus wiring the existing no-op "Workout" quick-tile.
- Four screens in `feature-workouts/session/`: **Overview**, **Player**,
  **Summary**, and an **Exercise-preview** bottom sheet.
- **`androidx.media3`** (ExoPlayer) for the looping demo video, with
  preload-next and a still-image fallback.
- An **immersive dark surface** scoped to the player (a small dark token set in
  `core-ui`), leaving the rest of the app on the light Hf theme.
- Optimistic, **locally-buffered set logging** (Room) that survives loss of
  network and reconciles at completion.
- Navigation: `Overview → Player → Summary` routes nested under the existing
  `Workouts` destination.

Out of scope (deferred — see IMPL-WORKOUT-001 §Phasing):

- **Session generation / authoring** — sessions, weights, and demo refs are
  written upstream. This module only reads/executes/logs/finalizes.
- **Spoken audio cues (TTS)** — first cut shows cue **text** overlays. On-device
  `TextToSpeech` is a phase-6 follow-on.
- **Week / calendar strip** — the dashboard shows only *today*. The schedule
  range endpoint exists but the strip UI lands later.
- **Workout history list** — `/me/workouts` keeps its "History (coming soon)"
  card; the summary is reachable from the dashboard's completed state only.
- **Music / Spotify integration** — Future has it; deferred.
- **Wear OS companion** — excluded (no tap-to-advance from the watch yet).
- **Live HR / on-device calorie computation** — calories are estimated
  server-side at completion; no Health Connect read in this module.
- **Multi-device session handoff** — single-device, last-write-wins per set.

## Dependencies

Landed prereqs (defined elsewhere — do **not** redefine here):

- **IMPL-AND-00**: Hilt graph, `@HiltViewModel` / `hiltViewModel()`,
  `core-network` (`AuthInterceptor`, `TokenAuthenticator`, base `Moshi`,
  `BuildConfig.BACKEND_BASE_URL`), the central `NavHost` with type-safe routes,
  `core-ui` primitives (`LoadingState`, `EmptyState`, `ErrorState`,
  `SnackbarController`, `AsyncImage`, `ConfirmDialog`), `@IoDispatcher`,
  ViewModel/UiState conventions.
- **IMPL-AND-06**: populates `feature-workouts/` and adds the `Workouts`
  bottom-nav / foldable-sidebar slot + `workoutsGraph(navController)`. This spec
  **extends** that graph with the session routes. If AND-06 has not landed, this
  spec creates the module + nav slot first (same `build.gradle.kts` shown
  there).
- **IMPL-WORKOUT-001 (backend)**: the `/api/me/workouts/sessions*` and
  `/api/exercises/{id}` endpoints. The client tolerates `204` from
  `…/sessions/today` (rest day).

New third-party dependency: `androidx.media3:media3-exoplayer` +
`media3-ui` (add to the version catalog as `media3`).

## Decisions

| Topic | Decision |
|---|---|
| Execution engine location | A pure-Kotlin `SessionEngine` in `core-domain` flattens `blocks` into `List<PlayerStep>`. No Android types — fully unit-testable. The `WorkoutPlayerViewModel` is a thin driver over it (current index, timers, logging). |
| Superset/circuit flattening | A `STRAIGHT`-style block (`rounds == 1`, or `WARMUP`/`STRENGTH`/`COOLDOWN`) emits, per exercise, each `PrescribedSet` as a `PerformSet` step followed by a `Rest(restSecondsBetweenSets)` (except after the last set). A `SUPERSET`/`CIRCUIT` block (`rounds == N`) emits, **per round**, one set of *each* exercise back-to-back, then a `Rest(restSecondsBetweenSets)` between rounds — matching Future's cycling. `restSecondsAfter` emits a `Rest` between blocks. The final step is never a rest. |
| Set index identity | Steps reference sets by `setId` (stable, authored upstream), not positional index, so logged data survives if the engine ordering changes. |
| Media | `androidx.media3` ExoPlayer, one reusable player instance hoisted in the Player screen, `REPEAT_MODE_ONE`, muted, `resizeMode = FILL`. The **next** step's exercise clip is prepared into a second `MediaItem` and swapped on advance. Missing `demoVideoUrl` → Coil `AsyncImage` of `demoImageUrl`, or a category placeholder. |
| Timed vs rep sets | `PrescribedSet` carries exactly one of `targetReps` / `targetSeconds`. Time-based steps show a countdown ring and **auto-advance + auto-log `completed=true`** when it hits zero (user can still adjust). Rep-based steps require an explicit **Log set** → **Next**. |
| Set logger UI | A compact bottom panel on the player: a **reps stepper** (pre-filled with `targetReps`) and a **weight adjuster** (± `equipment.increment` when known, else ±2.5 / ±5 lb; pre-filled with `targetWeight`). "Log set" writes a `LoggedSet` and advances. |
| Logging transport | Optimistic: write to a Room `pending_set_logs` table immediately, advance the UI, and fire `PATCH …/sets/{setId}` from a sync worker. The workout never blocks on the network. On `complete`, flush any pending logs first (batch), then call `…/complete`. |
| Rest UI | A full-bleed rest overlay: countdown, "Up next: {exercise} · set X" preview (thumbnail + target), **Skip** and **+15s**. Auto-dismiss to the next step at zero. |
| Pause | A pause toggle freezes any running timer and the video; state is fully in the ViewModel + Room, so process death mid-workout resumes from the last logged step (`SavedStateHandle` holds `sessionId`; engine state is recomputed from logged sets). |
| Immersive theme | The player composables read a `HfDark` token set (added to `core-ui/theme`) directly rather than flipping the global theme. Overview/Summary/dashboard stay light. Status/nav bars go edge-to-edge dark for the player only. |
| Dashboard card states | `TodaysWorkoutCard` renders `SCHEDULED` (Start), `IN_PROGRESS` (Resume + "k of n sets"), `COMPLETED` (result row + View summary), or rest-day (`204` → quiet message). The existing no-op "Workout" quick-tile calls the same Start/Resume action. |
| Calories / volume | Computed server-side at `complete`; the Summary screen renders the returned `SessionSummary` verbatim (no client math beyond live in-progress volume display). |
| AI recap | Rendered if `summary.aiRecap != null`; absent silently otherwise. Never blocks the Summary screen. |

## Deliverables

### `core-domain/workouts/`

Package: `com.gte619n.healthfitness.core.domain.workouts` (same package
IMPL-AND-06 uses for `Location`/`Equipment`).

```kotlin
// WorkoutSession.kt
enum class SessionStatus { SCHEDULED, IN_PROGRESS, COMPLETED, SKIPPED }
enum class BlockType { WARMUP, STRENGTH, SUPERSET, CIRCUIT, CARDIO, COOLDOWN }
enum class WeightUnit { LB, KG }

data class PrescribedSet(
    val setId: String,
    val targetReps: Int?,        // exactly one of reps/seconds is non-null
    val targetSeconds: Int?,
    val targetWeight: Double?,   // null = bodyweight
    val weightUnit: WeightUnit,
)

data class PrescribedExercise(
    val exerciseId: String,
    val name: String,
    val prescribedSets: List<PrescribedSet>,
    val restSecondsBetweenSets: Int,
    val notes: String?,          // pre-authored cue text
)

data class Block(
    val blockId: String,
    val type: BlockType,
    val label: String?,
    val rounds: Int,
    val restSecondsAfter: Int,
    val exercises: List<PrescribedExercise>,
)

data class LoggedSet(
    val setId: String,
    val actualReps: Int?,
    val actualWeight: Double?,
    val completed: Boolean,
    val loggedAt: Instant,
)

data class SessionSummary(
    val durationSeconds: Int,
    val totalVolume: Double,
    val setsCompleted: Int,
    val setsPrescribed: Int,
    val estimatedCalories: Int,
    val perExercise: List<ExerciseResult>,
    val aiRecap: String?,
)
data class ExerciseResult(val name: String, val topSet: String, val volume: Double)

data class WorkoutSession(
    val sessionId: String,
    val scheduledDate: LocalDate,
    val title: String,
    val focus: String?,
    val status: SessionStatus,
    val estimatedMinutes: Int,
    val blocks: List<Block>,
    val loggedSets: Map<String, LoggedSet>,  // setId → log
    val startedAt: Instant?,
    val completedAt: Instant?,
    val summary: SessionSummary?,
)

data class Exercise(
    val exerciseId: String,
    val name: String,
    val primaryMuscle: String,
    val equipmentId: String?,
    val demoVideoUrl: String?,
    val demoImageUrl: String?,
    val cues: List<String>,
)
```

```kotlin
// SessionEngine.kt — pure Kotlin, no Android imports.
sealed interface PlayerStep {
    val blockId: String
    data class PerformSet(
        override val blockId: String,
        val exercise: PrescribedExercise,
        val set: PrescribedSet,
        val round: Int,            // 1-based; meaningful for supersets/circuits
        val setOrdinal: Int,       // "Set X of Y" for this exercise
        val setCount: Int,
    ) : PlayerStep
    data class Rest(
        override val blockId: String,
        val seconds: Int,
        val upNext: PerformSet?,   // null when the rest precedes nothing (won't be emitted)
    ) : PlayerStep
}

object SessionEngine {
    /** Flatten blocks into the ordered step list. Never ends on a Rest. */
    fun steps(session: WorkoutSession): List<PlayerStep>

    /** Resume index: first step whose set has no completed LoggedSet. */
    fun resumeIndex(steps: List<PlayerStep>, logged: Map<String, LoggedSet>): Int

    /** Running volume from logged sets (Σ reps × weight). */
    fun volume(logged: Map<String, LoggedSet>): Double
}
```

The flattening rules are the [decisions table](#decisions) "Superset/circuit
flattening" entry, encoded here. `Rest` steps with a null/absent next set are
dropped so the list never ends on a rest.

```kotlin
// WorkoutSessionRepository.kt
interface WorkoutSessionRepository {
    suspend fun today(): Result<WorkoutSession?>           // null = rest day (HTTP 204)
    suspend fun get(sessionId: String): Result<WorkoutSession>
    suspend fun range(from: LocalDate, to: LocalDate): Result<List<WorkoutSession>>
    suspend fun start(sessionId: String): Result<WorkoutSession>
    suspend fun logSet(sessionId: String, log: LoggedSet): Result<Unit>   // buffered
    suspend fun complete(sessionId: String): Result<SessionSummary>
    suspend fun summary(sessionId: String): Result<SessionSummary>
    suspend fun latestCompleted(): Result<SessionSummary?>
}

interface ExerciseRepository {
    suspend fun get(exerciseId: String): Result<Exercise>
}
```

### `core-data/workouts/`

Package: `com.gte619n.healthfitness.core.data.workouts`.

```kotlin
// DTOs mirror the wire format (String enums, ISO Instants, LocalDate "yyyy-MM-dd").
interface WorkoutSessionApi {
    @GET("api/me/workouts/sessions/today")
    suspend fun today(): Response<WorkoutSessionDto>          // 204 → null

    @GET("api/me/workouts/sessions/{id}")
    suspend fun get(@Path("id") id: String): WorkoutSessionDto

    @GET("api/me/workouts/sessions")
    suspend fun range(@Query("from") from: String, @Query("to") to: String): List<WorkoutSessionDto>

    @POST("api/me/workouts/sessions/{id}/start")
    suspend fun start(@Path("id") id: String): WorkoutSessionDto

    @PATCH("api/me/workouts/sessions/{id}/sets/{setId}")
    suspend fun logSet(@Path("id") id: String, @Path("setId") setId: String,
                       @Body body: LoggedSetDto): Response<Unit>

    @POST("api/me/workouts/sessions/{id}/complete")
    suspend fun complete(@Path("id") id: String): SessionSummaryDto

    @GET("api/me/workouts/sessions/{id}/summary")
    suspend fun summary(@Path("id") id: String): SessionSummaryDto

    @GET("api/me/workouts/sessions/latest-completed")
    suspend fun latestCompleted(): Response<SessionSummaryDto>   // 204 → null
}

interface ExerciseApi {
    @GET("api/exercises/{id}") suspend fun get(@Path("id") id: String): ExerciseDto
}
```

```kotlin
// Local buffer for offline-tolerant logging.
@Entity(tableName = "pending_set_logs")
data class PendingSetLog(
    @PrimaryKey val setId: String,   // one pending row per set; last write wins
    val sessionId: String,
    val actualReps: Int?,
    val actualWeight: Double?,
    val completed: Boolean,
    val loggedAtEpochMs: Long,
    val synced: Boolean = false,
)

@Dao interface PendingSetLogDao {
    @Upsert suspend fun upsert(log: PendingSetLog)
    @Query("SELECT * FROM pending_set_logs WHERE sessionId = :s AND synced = 0")
    suspend fun unsynced(sessionId: String): List<PendingSetLog>
    @Query("UPDATE pending_set_logs SET synced = 1 WHERE setId = :setId")
    suspend fun markSynced(setId: String)
}
```

`WorkoutSessionRepositoryImpl` writes the `PendingSetLog` (and updates the
in-memory `WorkoutSession.loggedSets`) on `logSet`, returns immediately, and a
`SetLogSyncWorker` (`WorkManager`, already a transitive dep via core-data; if
not, add `androidx.work:work-runtime-ktx`) drains unsynced rows. `complete()`
awaits a final drain (best-effort), then calls the endpoint.

```kotlin
@Module @InstallIn(SingletonComponent::class)
object WorkoutSessionDataModule {
    @Provides fun provideWorkoutSessionApi(retrofit: Retrofit): WorkoutSessionApi = retrofit.create()
    @Provides fun provideExerciseApi(retrofit: Retrofit): ExerciseApi = retrofit.create()
}
@Module @InstallIn(SingletonComponent::class)
abstract class WorkoutSessionRepositoryModule {
    @Binds abstract fun bindSessionRepo(impl: WorkoutSessionRepositoryImpl): WorkoutSessionRepository
    @Binds abstract fun bindExerciseRepo(impl: ExerciseRepositoryImpl): ExerciseRepository
}
```

### `core-ui/theme/`

Add a scoped dark token set for the immersive player (does **not** flip the
global theme):

```kotlin
// HfDark.kt — dark palette used only by the player surface.
val HfDarkColors = HfColors(
    canvas = Color(0xFF0C0D0B),
    surface = Color(0xFF16181400),  // translucent over video
    textPrimary = Color(0xFFF4F2EC),
    textTertiary = Color(0xFF9A9B94),
    accent = Color(0xFF8FB14C),     // brightened Hf accent for dark
    /* … remaining tokens … */
)

@Composable fun HfImmersiveSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHfColors provides HfDarkColors) {
        // edge-to-edge, dark system bars
        content()
    }
}
```

### `feature-workouts/session/`

Package: `com.gte619n.healthfitness.feature.workouts.session`.

Routes (added to `workoutsGraph` from IMPL-AND-06):

```kotlin
// nav/WorkoutsRoutes.kt — extend the existing sealed interface
@Serializable data class WorkoutOverview(val sessionId: String) : WorkoutsRoute
@Serializable data class WorkoutPlayer(val sessionId: String)   : WorkoutsRoute
@Serializable data class WorkoutSummary(val sessionId: String)  : WorkoutsRoute

fun NavGraphBuilder.workoutSessionGraph(navController: NavController) {
    composable<WorkoutsRoute.WorkoutOverview> {
        WorkoutOverviewScreen(
            onStart = { id -> navController.navigate(WorkoutsRoute.WorkoutPlayer(id)) },
            onBack = { navController.popBackStack() },
        )
    }
    composable<WorkoutsRoute.WorkoutPlayer> {
        WorkoutPlayerScreen(
            onFinished = { id ->
                navController.navigate(WorkoutsRoute.WorkoutSummary(id)) {
                    popUpTo<WorkoutsRoute.WorkoutOverview> { inclusive = true }
                }
            },
            onExit = { navController.popBackStack() },
        )
    }
    composable<WorkoutsRoute.WorkoutSummary> {
        WorkoutSummaryScreen(onDone = { navController.popBackStack() })
    }
}
```

#### Overview

```kotlin
// WorkoutOverviewViewModel.kt
@HiltViewModel
class WorkoutOverviewViewModel @Inject constructor(
    private val repo: WorkoutSessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<WorkoutsRoute.WorkoutOverview>().sessionId
    data class UiState(
        val loading: Boolean = true,
        val session: WorkoutSession? = null,
        val error: String? = null,
    )
    val state: StateFlow<UiState>
    init { load() }
    fun start(onStarted: (String) -> Unit) { /* repo.start → navigate to player */ }
}

// WorkoutOverviewScreen.kt — light Hf theme.
// - Header: title, focus, "N exercises · ~M min".
// - Optional AI intro line (from session, if present).
// - Block list: each block labeled (Warmup / Superset A / …), each exercise row
//   shows name + "S×R @ weight" summary; tap → ExercisePreviewSheet.
// - Sticky bottom CTA: "Start workout" (or "Resume" if IN_PROGRESS).
```

#### Exercise preview sheet

```kotlin
// ExercisePreviewSheet.kt — ModalBottomSheet.
// Loads Exercise via ExerciseRepository; loops demoVideo (ExoPlayer) or shows
// demoImage; lists cues; shows the prescribed sets for this exercise.
```

#### Player (the core)

```kotlin
// WorkoutPlayerViewModel.kt
@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(
    private val repo: WorkoutSessionRepository,
    private val exercises: ExerciseRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<WorkoutsRoute.WorkoutPlayer>().sessionId

    data class UiState(
        val loading: Boolean = true,
        val steps: List<PlayerStep> = emptyList(),
        val index: Int = 0,
        val phase: Phase = Phase.Working,        // Working | Resting | Paused | Finishing
        val currentExercise: Exercise? = null,   // demo media + cues for the active set
        val nextExercise: Exercise? = null,      // preloaded
        val secondsRemaining: Int? = null,       // timed set or rest countdown
        val runningVolume: Double = 0.0,
        val error: String? = null,
    )
    enum class Phase { Working, Resting, Paused, Finishing }

    val state: StateFlow<UiState>

    fun logCurrentSet(reps: Int?, weight: Double?)   // writes LoggedSet, advances
    fun advance()                                    // Next: → rest or next set
    fun previous()
    fun togglePause()
    fun skipRest()
    fun addRestTime(seconds: Int)
    fun onTimedSetExpired()                          // auto-log completed + advance
    fun finish(onDone: (String) -> Unit)            // flush logs → repo.complete → Summary
}
```

`WorkoutPlayerScreen.kt` — wrapped in `HfImmersiveSurface`:

- **Hero**: ExoPlayer `PlayerView` (or `AsyncImage` fallback) filling the
  screen, with a dark gradient scrim top+bottom for legibility.
- **Top bar**: close (X, confirms abandon via `ConfirmDialog`), block label,
  pause toggle, running volume.
- **Center/lower**: exercise name, **"Set X of Y"**, target **reps × weight**
  (or the countdown ring for timed sets), and the current cue text (rotates
  through `exercise.cues`).
- **Set logger panel** (rep sets): reps stepper + weight ± adjuster prefilled
  from the prescription, **Log set** primary button. Timed sets show **Start /
  Pause** + the ring and auto-advance.
- **Rest overlay** (`phase == Resting`): big countdown, "Up next" card
  (thumbnail + name + "Set X · target"), **Skip** + **+15s**.
- Reusable `core-ui` `LoadingState` / `ErrorState` on load.

Lifecycle: the ExoPlayer instance is created in `remember` + released in
`DisposableEffect`/`onDispose`; it's paused on `ON_PAUSE` and resumed on
`ON_RESUME` via a `LifecycleEventObserver`. The next step's clip is prepared
whenever `index` changes.

#### Summary

```kotlin
// WorkoutSummaryViewModel.kt — loads SessionSummary (repo.summary) for the id.
// WorkoutSummaryScreen.kt — light Hf theme.
// - Hero stat row: duration · total volume · sets completed/prescribed · calories.
// - AI recap card (if aiRecap != null).
// - Per-exercise list (name · top set · volume).
// - Optional self-rating (1–5) + notes (local only this phase; PATCH later).
// - "Done" → pops back to the dashboard.
```

### `app/` — dashboard start card

```kotlin
// dashboard/TodayWorkoutViewModel.kt  (mobile.dashboard package)
@HiltViewModel
class TodayWorkoutViewModel @Inject constructor(
    private val repo: WorkoutSessionRepository,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data object RestDay : UiState
        data class Ready(val session: WorkoutSession) : UiState   // SCHEDULED/IN_PROGRESS/COMPLETED
        data class Error(val message: String) : UiState
    }
    val state: StateFlow<UiState>
    init { refresh() }
    fun refresh()
    fun startOrResume(onGoToPlayer: (String) -> Unit) { /* start if SCHEDULED, then nav */ }
}

// dashboard/TodaysWorkoutCard.kt — HfCard, sits above the nutrition TodayCard.
//   SCHEDULED  → title + focus, "N exercises · ~M min", primary "Start".
//   IN_PROGRESS→ "Resume" + "k of n sets" progress.
//   COMPLETED  → result row (duration · volume) + "View summary".
//   RestDay    → quiet "Rest day" message, no CTA.
```

Wiring:

- `PhoneTodayScreen` and the foldable dashboard render `TodaysWorkoutCard` above
  the existing `TodayCard`, passing nav callbacks that route to
  `WorkoutOverview` / `WorkoutPlayer` / `WorkoutSummary`.
- The existing no-op **"Workout" quick-tile** (`PhoneTodayScreen.kt:115`) calls
  `startOrResume`.
- `AppNavHost` registers `workoutSessionGraph(navController)` (and, if not
  already present from AND-06, the `Workouts` destination + `workoutsGraph`).

## Tests

`core-domain/src/test/java/` (pure JVM):

- **SessionEngineTest** — the crux:
  - Straight `STRENGTH` block (3 sets) → `PerformSet, Rest, PerformSet, Rest,
    PerformSet` (no trailing rest).
  - `SUPERSET` block, 2 exercises × 3 rounds → round-cycled order
    `A1, B1, Rest, A2, B2, Rest, A3, B3` and correct `round` / `setOrdinal`.
  - `restSecondsAfter` inserts a between-block rest; list never ends on a `Rest`.
  - Time-based set carries `targetSeconds` and no `targetReps`.
  - `resumeIndex` returns the first step whose set lacks a completed log;
    fully-logged session resumes at the end (finish).
  - `volume` sums reps × weight, ignoring incomplete/bodyweight sets.

`feature-workouts/src/test/java/` (MockK + Turbine + `MainDispatcherRule`):

- **WorkoutPlayerViewModelTest** — load builds steps + sets `index` from resume;
  `logCurrentSet` writes a log and advances to the next step; a timed set's
  `onTimedSetExpired` auto-logs `completed=true`; `finish` flushes then calls
  `repo.complete` and emits the summary id; repo failure on start surfaces
  `error`.
- **TodayWorkoutViewModelTest** — `today()` returning null → `RestDay`;
  `SCHEDULED` → `Ready`; `startOrResume` calls `repo.start` only when
  `SCHEDULED`, not when `IN_PROGRESS`.
- **WorkoutOverviewViewModelTest** — happy-path load; `start` transitions and
  navigates.

`core-data/src/test/java/`:

- **WorkoutSessionDtoMapperTest** — wire ↔ domain: `LocalDate` "yyyy-MM-dd",
  enum case, `loggedSets` map passthrough, the timed-vs-rep `PrescribedSet`
  invariant.
- **OfflineLoggingTest** (`MockWebServer` + in-memory Room) — `logSet` returns
  immediately and persists a `PendingSetLog`; the sync worker drains it; a 5xx
  leaves the row `synced = 0` for retry; `complete` flushes pending logs before
  calling the endpoint.

`feature-workouts/src/androidTest/java/` (Compose snapshots, AND-00 runner):

- **TodaysWorkoutCardTest** — snapshot each state (scheduled / in-progress /
  completed / rest-day).
- **WorkoutPlayerScreenTest** — snapshot the working state (rep set with logger)
  and the rest overlay, on the immersive dark surface, with a still-image
  fallback (no live ExoPlayer in snapshots).

## Acceptance

Automated:

1. `./gradlew :core-domain:test :feature-workouts:test :core-data:test` passes
   (the `SessionEngineTest` superset/circuit ordering is the key gate).
2. Compose snapshots match recorded baselines.
3. `./gradlew :app:assembleDebug` builds with the session routes + dashboard
   card wired in.

Manual (end-to-end against staging, with an upstream-seeded session for today):

1. Open the app. The dashboard shows **Today's workout** ("Pull Day · Back &
   biceps · 6 exercises · ~52 min") with a **Start** button.
2. Tap **Start** → **Overview**: AI intro line, the block/exercise list with
   sets×reps + weights. Tap an exercise → the preview sheet loops its demo and
   lists cues. Close the sheet.
3. Tap **Start workout** → the **player** opens immersive/dark with the first
   exercise's video looping, "Set 1 of 4", target reps × weight, and a cue.
4. Adjust reps to 10, bump weight +5 lb, tap **Log set**. A **rest** overlay
   counts down with "Up next: Set 2". Tap **+15s**, then **Skip**.
5. Reach a **superset** block — the player cycles A1 → B1 → rest → A2 → B2 …,
   the set indicator and round update correctly.
6. Reach a **timed** move (e.g. plank 45s) — the ring counts down and
   **auto-advances** at zero, logging it complete.
7. Toggle **pause** (water break); the video and timer freeze; resume continues
   from the same set. Background the app and return — it resumes at the same
   step (no lost logs).
8. Put the device in airplane mode mid-workout, log two more sets — the UI never
   blocks. Re-enable network; the pending logs sync.
9. Finish the last set → **Summary**: duration, total volume, sets
   completed/prescribed, est. calories, the AI recap, and the per-exercise
   breakdown. Tap **Done** → back on the dashboard the card now shows
   **Completed** with **View summary**, and the **recent feed** shows "Pull Day
   completed · 6 exercises · …".

## Open questions

Resolved (in IMPL-WORKOUT-001):

- **No human coach** — AI intro + recap only (`gemini-3.5-flash`, server-side).
- **Sessions are authored upstream** — this module reads/executes/logs/finalizes.
- **Web** — results card only; no player on web.
- **Wear OS / TTS / music / history / week-strip** — deferred phases.

Deferred to implementation:

- **ExoPlayer reuse across overview-preview and player** — likely one
  `ExoPlayer` provided per-screen via `remember`; revisit if memory pressure
  shows on low-end devices (release aggressively in `onDispose`).
- **Cue rotation cadence** — first cut rotates `exercise.cues` on a fixed timer
  during the working phase; tune timing during polish.
- **Self-rating persistence** — the Summary's optional 1–5 rating + notes are
  local-only this phase; a `PATCH …/summary` to persist them folds in when the
  backend grows the field.
- **Calorie model** — server estimates from duration × MET; a Health-Connect HR
  enrichment path is a separate spec.
</content>
