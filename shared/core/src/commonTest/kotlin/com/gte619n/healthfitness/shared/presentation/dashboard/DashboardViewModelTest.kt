package com.gte619n.healthfitness.shared.presentation.dashboard

import app.cash.turbine.test
import com.gte619n.healthfitness.shared.data.BloodMarkerSummary
import com.gte619n.healthfitness.shared.data.DailyMetricPoint
import com.gte619n.healthfitness.shared.data.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.shared.data.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.shared.data.DashboardDailyMetricsRepository
import com.gte619n.healthfitness.shared.data.DashboardNutritionRepository
import com.gte619n.healthfitness.shared.data.DashboardProfile
import com.gte619n.healthfitness.shared.data.DashboardProfileRepository
import com.gte619n.healthfitness.shared.data.DashboardRecentActivityRepository
import com.gte619n.healthfitness.shared.data.DashboardWorkoutRepository
import com.gte619n.healthfitness.shared.data.RecentActivityEntry
import com.gte619n.healthfitness.shared.data.RecentActivityKind
import com.gte619n.healthfitness.shared.data.TodayWorkout
import com.gte619n.healthfitness.shared.data.WeightSummary
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave A1 — the shared dashboard VM's state composition.
 * Pure/common (kotlinx-coroutines-test + turbine) → runs identically on JVM and
 * iOS. Verifies the offline-first CardState composition, the reactive
 * draft-wins-over-resolved workout pick, and the initials/hidden-biometrics fold.
 */
class DashboardViewModelTest {

    private val nutritionDay = NutritionDay(
        date = "2026-09-23",
        totals = Macros(caloriesKcal = 1200.0, proteinGrams = 90.0),
        target = Macros(caloriesKcal = 2000.0, proteinGrams = 150.0),
    )

    private val weight = WeightSummary(
        latestLb = 180.0,
        sevenDayDeltaLb = -1.0,
        ninetyDayDeltaLb = -4.0,
        series = listOf(182.0, 181.0, 180.0),
        yMin = 178.0,
        yMax = 184.0,
        xLabels = emptyList(),
        latestBodyFatPct = 18.0,
        latestLeanMassLb = 148.0,
        lastUpdatedAt = Instant.parse("2026-09-23T08:00:00Z"),
    )

    private val metrics = listOf(
        DailyMetricPoint(LocalDate(2026, 9, 23), steps = 9000, restingHeartRate = 55, sleepMinutes = 420, hrvMs = 60, sleepScore = 82),
    )

    private val recentEntries = listOf(
        RecentActivityEntry(RecentActivityKind.FOOD, "Lunch", "620 kcal", Instant.parse("2026-09-23T12:00:00Z")),
    )

    @Test
    fun composesEveryCardIntoReadyState() = runTest {
        val repo = FakeRepo()
        val vm = DashboardViewModel(
            bodyComp = repo, dailyMetrics = repo, blood = repo, nutrition = repo,
            recent = repo, workouts = repo, profile = repo,
        )
        vm.uiState.test {
            // Settles to a fully-loaded composition (skip intermediate emissions).
            var s = awaitItem()
            while (s.bodyComposition !is CardState.Loaded ||
                s.dailyMetrics !is CardState.Loaded ||
                s.nutrition !is CardState.Loaded ||
                s.recentActivity !is CardState.Loaded ||
                s.user == null
            ) {
                s = awaitItem()
            }

            assertEquals(weight, (s.bodyComposition as CardState.Loaded).data)
            assertEquals(metrics, (s.dailyMetrics as CardState.Loaded).data)
            assertEquals(nutritionDay, (s.nutrition as CardState.Loaded).data)
            assertEquals(recentEntries, (s.recentActivity as CardState.Loaded).data)
            // Profile fold: "Evan Glazier" → "EG", hidden biometrics carried through.
            assertEquals("EG", s.user?.initials)
            assertEquals(setOf("STEPS"), s.hiddenBiometrics)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inProgressDraftResumesOverResolvedPick() = runTest {
        val repo = FakeRepo(
            resolved = TodayWorkout.Start("p1", "s-plan", "Push", isToday = true),
        )
        val vm = DashboardViewModel(
            bodyComp = repo, dailyMetrics = repo, blood = repo, nutrition = repo,
            recent = repo, workouts = repo, profile = repo,
        )
        // Push an in-progress draft; it must win over the Start pick.
        repo.drafts.value = listOf(draft(scheduledId = "s-draft", label = "Pull", sets = 3))

        vm.uiState.test {
            var s = awaitItem()
            while (s.todayWorkout !is TodayWorkout.Resume) s = awaitItem()
            val resume = s.todayWorkout as TodayWorkout.Resume
            assertEquals("s-draft", resume.scheduledId)
            assertEquals("Pull", resume.label)
            assertEquals(3, resume.setsLogged)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedLoadWithNoCacheSurfacesError() = runTest {
        val repo = FakeRepo(failNutrition = true, cacheNutrition = null)
        val vm = DashboardViewModel(
            bodyComp = repo, dailyMetrics = repo, blood = repo, nutrition = repo,
            recent = repo, workouts = repo, profile = repo,
        )
        vm.uiState.test {
            var s = awaitItem()
            while (s.nutrition !is CardState.Error) s = awaitItem()
            assertIs<CardState.Error>(s.nutrition)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun failedLoadKeepsCachedValue() = runTest {
        // Nutrition network fails but a cache exists → card shows the cache, not Error.
        val repo = FakeRepo(failNutrition = true, cacheNutrition = nutritionDay)
        val vm = DashboardViewModel(
            bodyComp = repo, dailyMetrics = repo, blood = repo, nutrition = repo,
            recent = repo, workouts = repo, profile = repo,
        )
        vm.uiState.test {
            var s = awaitItem()
            while (s.nutrition !is CardState.Loaded) {
                assertTrue(s.nutrition !is CardState.Error, "must never surface Error when a cache is present")
                s = awaitItem()
            }
            assertEquals(nutritionDay, (s.nutrition as CardState.Loaded).data)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- fakes --------------------------------------------------------------

    private fun draft(scheduledId: String, label: String, sets: Int): WorkoutSessionDraft {
        val scheduled = ScheduledWorkout(
            scheduledId = scheduledId,
            date = LocalDate(2026, 9, 23),
            phaseId = "ph1",
            dayId = "d1",
            dayLabel = label,
            weekIndexInPhase = 0,
            isDeload = false,
            locationId = "loc1",
            locationName = null,
            status = ScheduledStatus.PLANNED,
        )
        return WorkoutSessionDraft(
            programId = "p1",
            scheduledId = scheduledId,
            startedAt = Instant.parse("2026-09-23T07:00:00Z"),
            lastActivityAt = Instant.parse("2026-09-23T07:10:00Z"),
            status = DraftStatus.ACTIVE,
            scheduled = scheduled,
            logged = mapOf(
                com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey("b1", 0) to
                    List(sets) { com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet(weightLbs = 100.0, reps = 8) },
            ),
        )
    }

    private inner class FakeRepo(
        val resolved: TodayWorkout = TodayWorkout.Hidden,
        val failNutrition: Boolean = false,
        val cacheNutrition: NutritionDay? = null,
    ) : DashboardBodyCompositionRepository,
        DashboardDailyMetricsRepository,
        DashboardBloodMarkerRepository,
        DashboardNutritionRepository,
        DashboardRecentActivityRepository,
        DashboardWorkoutRepository,
        DashboardProfileRepository {

        val drafts = MutableStateFlow<List<WorkoutSessionDraft>>(emptyList())

        override suspend fun cachedRecent(): WeightSummary? = null
        override suspend fun loadRecent(): WeightSummary = weight

        override suspend fun cachedRecentMetrics(): List<DailyMetricPoint> = emptyList()
        override suspend fun loadRecentMetrics(): List<DailyMetricPoint> = metrics

        override suspend fun cachedDashboardMarkers(): List<BloodMarkerSummary> = emptyList()
        override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> = emptyList()

        override suspend fun cachedToday(): NutritionDay? = cacheNutrition
        override suspend fun loadToday(): NutritionDay =
            if (failNutrition) throw RuntimeException("offline") else nutritionDay

        override suspend fun cachedRecentActivity(): List<RecentActivityEntry>? = null
        override suspend fun loadRecentActivity(): List<RecentActivityEntry> = recentEntries

        override fun observeDrafts(): Flow<List<WorkoutSessionDraft>> = drafts
        override suspend fun resolveTodayWorkout(): TodayWorkout = resolved

        override suspend fun get(): DashboardProfile = DashboardProfile(
            displayName = "Evan Glazier",
            photoUrl = null,
            hiddenBiometrics = setOf("STEPS"),
        )
    }
}
