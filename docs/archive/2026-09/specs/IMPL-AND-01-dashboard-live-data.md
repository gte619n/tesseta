> **ARCHIVED 2026-09-13** — Classification: IMPLEMENTED.
> Evidence: android `mobile/dashboard/DashboardViewModel.kt`, `PhoneTodayScreen.kt`, `FoldableDashboardScreen.kt`.
> Successor / current source of truth: docs/reference/feature-catalog.md (the Readiness gap is tracked in its Dashboard section).
> Archived by the 2026-09-13 audit reaping pass (docs/audit/2026-09-13/reaping-plan.md). Do not treat as current.

# IMPL-AND-01: Android dashboard — wire to live backend data

## Goal

Replace the hard-coded `DashboardFixtures` data feeding the Android Compose
dashboard with live data from the existing backend `/api/me/*` endpoints,
so the first thing a signed-in user sees on phone or foldable is their
real weight, body composition, top-four blood markers, and today's doses.
This is Phase 1 of the Android ↔ Web parity roadmap (§4): UI widgets are
already built; only data plumbing changes. No new visual components.

## Scope

In scope:

- `DashboardViewModel` (`@HiltViewModel`) under `app/dashboard/` exposing
  one `StateFlow<DashboardUiState>` with three independent per-card
  sub-states (`bodyComposition`, `blood`, `todaysDoses`).
- `core-domain` gains dashboard domain models + repository interfaces.
- `core-data` gains a `DashboardApi` Retrofit service plus mappers and
  repository impls for `/api/me/body-composition`, `/api/me/blood`,
  `/api/me/medications/today`.
- `PhoneTodayScreen` and `FoldableDashboardScreen` read from
  `hiltViewModel<DashboardViewModel>()` and pass real data into the
  already-built widgets (`WeightChart`, `BloodPanel`, `BodyCompositionHero`,
  `StatCard`, `TodayCard`'s doses preview row).
- Per-card skeleton + error placeholders driven by `core-ui` primitives
  (`LoadingState`, `ErrorState`) from IMPL-AND-00.
- `DashboardFixtures.kt` renamed to `DashboardFallbacks.kt` and trimmed
  to only the still-fixture sections (HRV / RHR / Readiness, calories +
  macros, recent feed, quick-log labels, header chrome).
- Tests for repositories (MockWebServer), the ViewModel (Turbine), and a
  preview-driven snapshot test for the three card states.

Out of scope (deferred):

- HRV / RHR / Readiness — no backend source. Stay on fixtures, gated by
  `DashboardFlags.showVitalsFixtures`.
- Interactive today's doses card (take/skip actions) — owned by IMPL-AND-03.
  This spec wires the **read-only preview row** only.
- Recent feed — backend has no event-aggregation endpoint. Stays on
  fixtures with a documented TODO.
- Calories + macros, workout summary line — no nutrition / workout
  backends. Stay on fixtures.
- 30d / 1y / All time-range toggles on the foldable hero's Segment
  control (90d only in this IMPL).
- Time-zone-aware date formatting (UTC labels mirror the web).
- Pull-to-refresh. Refresh fires on `Lifecycle.Event.ON_RESUME`.

Depends on **IMPL-AND-00 (foundations)**: Hilt, NavHost, `core-network`
(Retrofit + OkHttp + `AuthInterceptor` + `TokenAuthenticator`), Moshi,
Coil, `core-ui` (`LoadingState`, `ErrorState`, `SnackbarController`),
`DispatcherModule`, ViewModel + Repository conventions, `UiState` sealed
interface. Not redefined here.

## Decisions

| Topic | Decision |
|---|---|
| VM granularity | One `DashboardViewModel` per screen, three independent per-card sub-states. Two-VM split deferred. |
| Card state envelope | `sealed interface CardState<out T> { Loading; Loaded(data); Error(message, cause) }`. Generic so each card uses the same shape — same intent as IMPL-AND-00's `UiState` but per-card. |
| Module placement | Lives under `app/dashboard/` for this IMPL. Extraction to a `feature-dashboard` module is deferred until a second screen depends on the same VM. |
| Sampling | 90d weight series downsampled client-side to ~30 points via bucket-average. Keeps Canvas overdraw bounded and the 7d MA smooth even for users with 8-readings-a-day smart scales. |
| 7d / 90d deltas | 7d = `latest - reading nearest (now - 7d)`. 90d = `latest - mean(window)`. Mirrors web's `loadBodyComposition`. |
| Units | Repo converts kg → lb (`KG_TO_LB = 2.20462`) before the VM/UI see anything. Imperial only for this IMPL; a `core-domain` `UnitSystem` enum is added now to keep call sites stable when a user preference lands. |
| Lean mass | Derived in the repo from the most recent `(weight, body-fat)` pair within 6h of each other. No pair → `null` → secondary tile renders `—`. Matches web. |
| Blood markers shown | Top four — Testosterone, LDL, ApoB, HbA1c — in display order. Markers with no reading are omitted (not rendered as empty rows). |
| Blood data source | Single endpoint: `GET /api/me/blood`. 12-month sparkline history built from that same list. The web's `/api/me/blood/reports` fallback for extracted markers is **deferred** to IMPL-AND-04. |
| Reference range math | Tone, `tickPct`, `goodFillPct`, `goodLeftPct` computed in `BloodMarkerSummaryMapper` from `reference.{displayMin,goodThreshold,displayMax,orientation}`. Mirrors web's `loadBloodPanel`. |
| Today's doses preview | First 3 rows from `GET /api/me/medications/today` rendered under the workout meta in `TodayCard`. Empty → one-line "No scheduled doses today". Interactivity owned by IMPL-AND-03. |
| Loading UX | Per-card skeletons (not a screen spinner). Each card renders via `CardSwitch(state) { data -> ... }`; while loading, fixed-height placeholder so the page does not reflow. |
| Error UX | `CardState.Error` swaps the card body for an inline `ErrorState` with `Retry`. Snackbar only used for one-off failures (manual refresh). |
| Refresh trigger | `init` fires the first load. `refresh()` re-fires all three loads in parallel; called from `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` in both screens. |
| Dispatchers | All IO via `@IoDispatcher` injected from `DispatcherModule` (IMPL-AND-00) — never `Dispatchers.IO` directly, so tests can swap to `StandardTestDispatcher`. |
| `DashboardFixtures` fate | Renamed `DashboardFallbacks`. `weightSeries`, `xAxisLabels`, `bloodMarkers`, `bloodPanelDate` removed. Surviving sections gated by a new `DashboardFlags` so future IMPLs can switch them off. |
| Fixture flag for HRV/RHR/Ready | Gated explicitly — preferred over silently rendering wrong data. When the data source lands, flip `DashboardFlags.showVitalsFixtures = false`. |

## Per-module deliverables

### `core-domain/` — pure-Kotlin domain types

Path: `android/core-domain/src/main/java/com/gte619n/healthfitness/domain/dashboard/`

```kotlin
package com.gte619n.healthfitness.domain.dashboard

import java.time.Instant
import java.time.LocalDate

enum class BodyMetric { WEIGHT_KG, BODY_FAT_PERCENT, LEAN_MASS_KG, BMI }

data class BodyCompositionPoint(
    val recordId: String,
    val metric: BodyMetric,
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

data class ChartXLabel(val xFraction: Float, val label: String)

data class WeightSummary(
    val latestLb: Double,
    val sevenDayDeltaLb: Double?,
    val ninetyDayDeltaLb: Double?,
    val series: List<Double>,      // downsampled to ~30 points
    val yMin: Double,
    val yMax: Double,
    val xLabels: List<ChartXLabel>,
    val latestBodyFatPct: Double?,
    val latestLeanMassLb: Double?,
)

enum class MarkerTone { Good, Warn, Alert }
data class HistoryPoint(val date: LocalDate, val value: Double)
data class BloodMarkerSummary(
    val markerKey: String,         // "LDL", "APO_B", ...
    val displayName: String,
    val value: Double,
    val unit: String,
    val tone: MarkerTone,
    val goodFillPct: Float,
    val goodLeftPct: Float,
    val tickPct: Float,
    val displayMin: Double,
    val goodThreshold: Double,
    val displayMax: Double,
    val history: List<HistoryPoint>,
)

enum class DoseWindow { MORNING, AFTERNOON, EVENING, BEDTIME }
data class TodaysDoseSummary(
    val medicationId: String,
    val drugName: String,
    val imageUrl: String?,
    val window: DoseWindow,
    val dose: Double,
    val unit: String?,
    val taken: Boolean,
    val takenAt: Instant?,
)

interface BodyCompositionRepository {
    suspend fun loadRecent(): WeightSummary?       // null = no data
}
interface BloodMarkerSummaryRepository {
    suspend fun loadDashboardMarkers(): List<BloodMarkerSummary>
}
interface TodaysDosesRepository {
    suspend fun loadToday(): List<TodaysDoseSummary>
}
```

### `core-data/` — Retrofit + mappers + repo impls

Path: `android/core-data/src/main/java/com/gte619n/healthfitness/data/dashboard/`

```kotlin
internal interface DashboardApi {
    @GET("/api/me/body-composition") suspend fun bodyComposition(): List<BodyCompositionDto>
    @GET("/api/me/blood")            suspend fun bloodReadings():   List<BloodReadingDto>
    @GET("/api/me/medications/today") suspend fun todaysDoses():    List<TodaysDoseDto>
}

@JsonClass(generateAdapter = true)
internal data class BodyCompositionDto(
    val recordId: String,
    val metric: String,              // WEIGHT_KG | BODY_FAT_PERCENT | LEAN_MASS_KG | BMI
    val value: Double,
    val sampleTime: Instant,
    val sourcePlatform: String?,
    val recordingMethod: String?,
)

@JsonClass(generateAdapter = true)
internal data class BloodReadingDto(
    val readingId: String,
    val marker: String,
    val value: Double,
    val unit: String,
    val sampleDate: String,          // ISO yyyy-MM-dd
    val labSource: String?,
    val notes: String?,
    val reference: ReferenceDto,
)
@JsonClass(generateAdapter = true)
internal data class ReferenceDto(
    val unit: String,
    val orientation: String,         // LOWER_IS_BETTER | HIGHER_IS_BETTER
    val goodThreshold: Double,
    val displayMin: Double,
    val displayMax: Double,
)

@JsonClass(generateAdapter = true)
internal data class TodaysDoseDto(
    val medicationId: String,
    val drugName: String,
    val imageUrl: String?,
    val window: String,
    val dose: Double,
    val unit: String?,
    val taken: Boolean,
    val takenAt: Instant?,
)
```

Repository impls (Hilt-bound):

```kotlin
internal class BodyCompositionRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BodyCompositionRepository {
    override suspend fun loadRecent(): WeightSummary? = withContext(io) {
        BodyCompositionMapper.toWeightSummary(api.bodyComposition())
    }
}

internal object BodyCompositionMapper {
    private const val KG_TO_LB = 2.20462
    private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000
    private const val TARGET_POINTS = 30

    fun toWeightSummary(readings: List<BodyCompositionDto>): WeightSummary? {
        // 1. Sort WEIGHT_KG readings by time, convert kg→lb.
        // 2. Window to last 90d; fall back to all if <2 in window.
        // 3. Downsample to ~30 evenly-bucketed points (mean per bucket).
        // 4. latest = last; sevenDayDelta = latest - reading nearest (now-7d);
        //    ninetyDayDelta = latest - mean(window).
        // 5. yMin/yMax = min/max with ~15% padding.
        // 6. Build 4 evenly-spaced ChartXLabels using sampleTime → UTC "MMM dd".
        // 7. latestBodyFatPct = last BODY_FAT_PERCENT reading.
        // 8. latestLeanMassLb = pair latest body-fat with nearest weight
        //    within 6h; if found, leanLb = weightLb * (1 - bf / 100).
        TODO("see decisions table")
    }
}
```

```kotlin
internal class BloodMarkerSummaryRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BloodMarkerSummaryRepository {
    override suspend fun loadDashboardMarkers() = withContext(io) {
        BloodMarkerSummaryMapper.toDashboardMarkers(api.bloodReadings())
    }
}

internal object BloodMarkerSummaryMapper {
    private val DISPLAY_ORDER = listOf("TESTOSTERONE", "LDL", "APO_B", "HBA1C")
    private val LABELS = mapOf(
        "TESTOSTERONE" to "Testosterone",
        "LDL" to "LDL",
        "APO_B" to "ApoB",
        "HBA1C" to "HbA1c",
    )

    fun toDashboardMarkers(readings: List<BloodReadingDto>): List<BloodMarkerSummary> {
        // For each key in DISPLAY_ORDER, with at least one reading:
        //   - latest = max-by sampleDate.
        //   - history = filter to last 365d, sort asc, dedupe by date (keep last).
        //   - tickPct  = clamp01((value - displayMin) / (displayMax - displayMin)).
        //   - goodLeftPct  = LOWER_IS_BETTER ? 0 : (goodThreshold - displayMin) / span.
        //   - goodFillPct  = LOWER_IS_BETTER ? (goodThreshold - displayMin) / span
        //                                    : 1 - goodLeftPct.
        //   - onGoodSide = LOWER_IS_BETTER ? value <= goodThreshold
        //                                  : value >= goodThreshold.
        //   - tone = onGoodSide ? Good : abs(value-goodThreshold)/goodThreshold < .15 ? Warn : Alert.
        TODO()
    }
}
```

```kotlin
internal class TodaysDosesRepositoryImpl @Inject constructor(
    private val api: DashboardApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TodaysDosesRepository {
    override suspend fun loadToday() = withContext(io) {
        api.todaysDoses().map { it.toDomain() }
    }
}
```

DI module:

```kotlin
@Module @InstallIn(SingletonComponent::class)
internal abstract class DashboardDataModule {
    @Binds abstract fun bindBodyComp(impl: BodyCompositionRepositoryImpl): BodyCompositionRepository
    @Binds abstract fun bindBlood(impl: BloodMarkerSummaryRepositoryImpl): BloodMarkerSummaryRepository
    @Binds abstract fun bindDoses(impl: TodaysDosesRepositoryImpl): TodaysDosesRepository

    companion object {
        @Provides internal fun provideDashboardApi(retrofit: Retrofit): DashboardApi =
            retrofit.create(DashboardApi::class.java)
    }
}
```

### `app/dashboard/` — ViewModel + screens

```kotlin
sealed interface CardState<out T> {
    data object Loading : CardState<Nothing>
    data class Loaded<T>(val data: T) : CardState<T>
    data class Error(val message: String, val cause: Throwable? = null) : CardState<Nothing>
}

data class DashboardUiState(
    val bodyComposition: CardState<WeightSummary?>,
    val blood:           CardState<List<BloodMarkerSummary>>,
    val todaysDoses:     CardState<List<TodaysDoseSummary>>,
) {
    companion object {
        val initial = DashboardUiState(CardState.Loading, CardState.Loading, CardState.Loading)
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bodyComp: BodyCompositionRepository,
    private val blood:    BloodMarkerSummaryRepository,
    private val doses:    TodaysDosesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUiState.initial)
    val uiState: StateFlow<DashboardUiState> = _ui.asStateFlow()

    init { refresh() }
    fun refresh() { loadBodyComposition(); loadBlood(); loadDoses() }
    fun retryBodyComposition() = loadBodyComposition()
    fun retryBlood() = loadBlood()
    fun retryDoses() = loadDoses()

    private fun loadBodyComposition() = viewModelScope.launch {
        _ui.update { it.copy(bodyComposition = CardState.Loading) }
        runCatching { bodyComp.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(bodyComposition = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(bodyComposition = CardState.Error("Couldn't load weight", t)) } }
    }
    // loadBlood / loadDoses follow the same shape.
}
```

`CardSwitch.kt`:

```kotlin
@Composable
fun <T> CardSwitch(
    state: CardState<T>,
    placeholderHeightDp: Int,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is CardState.Loading -> LoadingState(modifier = Modifier.fillMaxWidth().height(placeholderHeightDp.dp))
        is CardState.Loaded  -> content(state.data)
        is CardState.Error   -> ErrorState(message = state.message, onRetry = onRetry, modifier = Modifier.fillMaxWidth())
    }
}
```

Screen updates — `PhoneTodayScreen` collects the VM and feeds `PhoneVitalsGrid`
the weight `Vital` derived from `ui.bodyComposition`, plus wraps `TodayCard` in
`CardSwitch`. `FoldableDashboardScreen` wraps `BodyCompositionHero` and
`BloodPanel` in `CardSwitch`. `WeightChart`, `BloodPanel`, `BodyCompositionHero`,
`TodayCard` get new parameter lists:

```kotlin
@Composable fun WeightChart(series: List<Float>, yMin: Float, yMax: Float,
    xLabels: List<ChartXLabel> = emptyList(), modifier: Modifier = Modifier)
@Composable fun BloodPanel(markers: List<BloodMarkerSummary>, sampleDate: String?,
    showRangeLabels: Boolean, modifier: Modifier = Modifier)
@Composable fun BodyCompositionHero(summary: WeightSummary)
@Composable fun TodayCard(modifier: Modifier = Modifier, showHrInMeta: Boolean = false,
    dosesPreview: List<TodaysDoseSummary> = emptyList())
```

`PhoneTodayScreen` adds:

```kotlin
@Composable
fun PhoneTodayScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    // ... rest of the existing layout, replacing fixture calls with `ui` data ...
}
```

### `DashboardFixtures.kt` → `DashboardFallbacks.kt`

Removed: `weightSeries`, `xAxisLabels`, `bloodPanelDate`, `bloodMarkers`,
`BloodMarker` data class.

Kept (each tagged with the `DashboardFlags` flag that should turn it
off once a real source lands): header chrome (greeting / date / time /
tz / initials), HRV+RHR+Readiness `vitals` + `vitalsShortLabels`,
`recentPhone` / `recentFoldable`, `caloriesCurrent` / `caloriesTarget` /
`caloriesPct` / `macros`, `workoutTitle` / `workoutMetaPhone` /
`workoutMetaDesktop`, `phoneBottomNav` / `foldableNav`.

```kotlin
object DashboardFlags {
    const val showVitalsFixtures = true       // HRV / RHR / Readiness
    const val showRecentFeedFixtures = true
    const val showTodayCardFixtures = true    // calories / macros / workout line
}
```

## Tests

`android/core-data/src/test/.../dashboard/`:

- **`BodyCompositionRepositoryTest`** — MockWebServer. Stubs
  `/api/me/body-composition` with a 90d series spanning weight + body-fat;
  asserts kg→lb conversion, 30-point downsampling, 7d/90d deltas,
  lean-mass derivation when a body-fat pair exists, `null` return on
  empty payload, error propagation on HTTP 500.
- **`BloodMarkerSummaryRepositoryTest`** — MockWebServer. Stubs
  `/api/me/blood` with readings for the four dashboard markers plus one
  non-dashboard marker (`HDL`) that must be ignored. Asserts display
  order, 365-day history cutoff, and tone/tick/goodFill math for both
  `LOWER_IS_BETTER` (LDL) and `HIGHER_IS_BETTER` (TESTOSTERONE).
- **`BodyCompositionMapperTest`** / **`BloodMarkerSummaryMapperTest`** —
  pure unit tests covering edge cases (single reading, body-fat with no
  weight pair within 6h, value above `displayMax`, all-empty input).

`android/app/src/test/.../dashboard/`:

- **`DashboardViewModelTest`** — Turbine + fake repos +
  `StandardTestDispatcher`:
  - Initial state is `Loading` for all three cards.
  - Each card transitions to `Loaded` independently as fakes return.
  - `retryBodyComposition()` re-fires only the body-comp load.
  - Repo exception transitions one card to `Error`, others unaffected.
  - `refresh()` re-fires all three loads concurrently.

`android/app/src/androidTest/.../dashboard/`:

- **`DashboardScreenSnapshotTest`** — preview-driven snapshot in three
  fixtures: all-loading, all-loaded (realistic data), body-comp-error +
  others-loaded. Asserts no reflow between Loading and Loaded (card
  height stays constant). Paparazzi if it's already wired by
  IMPL-AND-00; otherwise renders to bitmap via Compose test rule.
- **`MoshiContractTest`** — decodes one recorded JSON payload from each
  of the three endpoints, asserts round-trip without needing
  `@Json(name = ...)` overrides.

## Acceptance criteria

1. `./gradlew :app:test :core-data:test :core-domain:test` passes.
2. With backend running (`bash infra/scripts/dev.sh`) and a signed-in
   user with body-composition data in Firestore, the foldable dashboard
   renders real weight, body-fat %, derived lean mass, a 90d sparkline,
   and the 7d delta. Phone's Weight `StatCard` shows the same latest
   weight + 7d delta + sparkline.
3. With ≥3 `BloodReading` records covering at least two of
   {TESTOSTERONE, LDL, APO_B, HBA1C}, the foldable `BloodPanel` shows
   those markers in display order with reference-range bars coloured per
   orientation. Markers without a reading are omitted (no empty rows).
4. With at least one active medication scheduled today, `TodayCard` on
   the phone shows up to three doses below the workout line, each row
   with drug name, dose, unit, and a taken indicator. Tapping is a no-op
   (handed off to IMPL-AND-03).
5. With `BACKEND_URL` unreachable (wifi off), each card renders an
   `ErrorState` + `Retry`; tapping Retry re-fires only that card's load.
6. Resume after background re-fires `refresh()`; cards transition
   `Loaded → Loading → Loaded`, or remain on the previous `Loaded` if
   the chart values are unchanged (no visible flicker on identical data).
7. `DashboardFixtures.kt` no longer exists; `DashboardFallbacks.kt`
   contains only the documented surviving sections, and `DashboardFlags`
   records exactly which sections remain on fake data.

## Open questions resolved before implementation

- **VM scope** — one `DashboardViewModel` per screen with per-card
  sub-states.
- **Module placement** — stays under `app/` for this IMPL.
- **Sampling** — bucket-average to ~30 points.
- **Unit conversion** — repo layer (centralises `KG_TO_LB`).
- **Lean mass** — derived in repo from the closest `(weight, body-fat)`
  pair within 6h.
- **Loading UX** — per-card skeletons with fixed placeholder heights.
- **HRV / RHR / Readiness** — fixtures behind `DashboardFlags`.
- **Recent feed** — fixtures; backend has no aggregation endpoint yet.
- **Foundations** — assumed in place via IMPL-AND-00.

## Open questions deferred to implementation

- **`TESTOSTERONE` not in backend `BloodMarker` enum.** The web works
  around this via extracted markers in blood reports. Until IMPL-AND-04
  brings the reports endpoint client-side, TESTOSTERONE will simply be
  absent from the panel for users who only have manual readings — the
  panel renders the markers it has. Adding the enum value is a one-line
  backend change but lives with the backend blood-testing work, not here.
- **Pull-to-refresh.** Not added in this IMPL (resume-only refresh). If
  user testing shows demand, add `PullToRefreshBox` in a follow-up.
- **Empty-state vs error-state distinction.** A 200 with no data renders
  as `Loaded(null)` (body-comp) or `Loaded(emptyList())` (blood, doses);
  exact "Connect Google Health" copy lands with IMPL-AND-02 (settings /
  profile).
- **Timezone-aware labels.** Chart x-labels and `bloodPanelDate` use UTC
  to mirror web. Localising to device zone is cross-cutting.
- **Caching.** No Room in this IMPL — repos hit the network every call.
  If flicker on resume is noticeable, add a short-lived in-memory cache
  (`MutableStateFlow` in the repo) before reaching for Room.
