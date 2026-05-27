package com.gte619n.healthfitness.mobile.dashboard

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummaryRepository
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.TodaysDosesRepository
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.CardState
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.DashboardViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading for all three cards`() = runTest(dispatcher) {
        // Hold all three repos so they never resolve — VM stays in Loading.
        val vm = DashboardViewModel(
            bodyComp = pendingBodyComp(),
            blood = pendingBlood(),
            doses = pendingDoses(),
        )
        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.bodyComposition is CardState.Loading)
            assertTrue(state.blood is CardState.Loading)
            assertTrue(state.todaysDoses is CardState.Loaded == false)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `each card transitions to Loaded independently`() = runTest(dispatcher) {
        val bodyComp = ResolvableBodyComp().apply { resolveWith(emptySnapshot()) }
        val blood = ResolvableBlood().apply { resolveWith(emptyList()) }
        val doses = ResolvableDoses().apply { resolveWith(emptyList()) }
        val vm = DashboardViewModel(bodyComp, blood, doses)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue("body=$state", state.bodyComposition is CardState.Loaded)
        assertTrue("blood=$state", state.blood is CardState.Loaded)
        assertTrue("doses=$state", state.todaysDoses is CardState.Loaded)
    }

    @Test
    fun `repo exception transitions only that card to Error`() = runTest(dispatcher) {
        val bodyComp = ResolvableBodyComp().apply { rejectWith(RuntimeException("nope")) }
        val blood = ResolvableBlood().apply { resolveWith(emptyList()) }
        val doses = ResolvableDoses().apply { resolveWith(emptyList()) }
        val vm = DashboardViewModel(bodyComp, blood, doses)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.bodyComposition is CardState.Error)
        assertTrue(state.blood is CardState.Loaded)
        assertTrue(state.todaysDoses is CardState.Loaded)
    }

    @Test
    fun `snapshot emission transitions body card to Loaded with snapshot payload`() =
        runTest(dispatcher) {
            val snap = snapshotWithLatestWeight(80.0)
            val bodyComp = ResolvableBodyComp().apply { resolveWith(snap) }
            val vm = DashboardViewModel(
                bodyComp = bodyComp,
                blood = ResolvableBlood().apply { resolveWith(emptyList()) },
                doses = ResolvableDoses().apply { resolveWith(emptyList()) },
            )
            advanceUntilIdle()
            val state = vm.uiState.value.bodyComposition as CardState.Loaded
            assertEquals(80.0, state.data.latestWeightKg!!, 0.001)
        }

    @Test
    fun `refresh re-fires all three loads`() = runTest(dispatcher) {
        val bodyComp = CountingBodyComp()
        val blood = CountingBlood()
        val doses = CountingDoses()
        val vm = DashboardViewModel(bodyComp, blood, doses)
        advanceUntilIdle()
        val before = Triple(bodyComp.calls.get(), blood.calls.get(), doses.calls.get())
        vm.refresh()
        advanceUntilIdle()
        val after = Triple(bodyComp.calls.get(), blood.calls.get(), doses.calls.get())
        assertEquals(before.first + 1, after.first)
        assertEquals(before.second + 1, after.second)
        assertEquals(before.third + 1, after.third)
    }

    @Test
    fun `retry methods re-fire only the targeted card`() = runTest(dispatcher) {
        val bodyComp = CountingBodyComp()
        val blood = CountingBlood()
        val doses = CountingDoses()
        val vm = DashboardViewModel(bodyComp, blood, doses)
        advanceUntilIdle()
        val before = Triple(bodyComp.calls.get(), blood.calls.get(), doses.calls.get())

        vm.retryBodyComposition()
        advanceUntilIdle()
        assertEquals(before.first + 1, bodyComp.calls.get())
        assertEquals(before.second, blood.calls.get())
        assertEquals(before.third, doses.calls.get())

        vm.retryBlood()
        advanceUntilIdle()
        assertEquals(before.first + 1, bodyComp.calls.get())
        assertEquals(before.second + 1, blood.calls.get())
        assertEquals(before.third, doses.calls.get())

        vm.retryDoses()
        advanceUntilIdle()
        assertEquals(before.first + 1, bodyComp.calls.get())
        assertEquals(before.second + 1, blood.calls.get())
        assertEquals(before.third + 1, doses.calls.get())
    }

    // ---- fakes ----

    private fun pendingBodyComp() = object : BodyCompositionRepository {
        private val pending = CompletableDeferred<Unit>()
        override fun observeSnapshot(): Flow<BodyCompositionSnapshot> =
            MutableSharedFlow<BodyCompositionSnapshot>(replay = 1).asSharedFlow()
        override suspend fun refresh() { pending.await() }
        override suspend fun pointsInRange(
            metric: BodyCompositionMetric,
            from: Instant,
            to: Instant,
        ): List<BodyCompositionPoint> = emptyList()
    }

    private fun pendingBlood() = object : BloodMarkerSummaryRepository {
        private val pending = CompletableDeferred<List<BloodMarkerSummary>>()
        override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> = pending.await()
    }

    private fun pendingDoses() = object : TodaysDosesRepository {
        private val pending = CompletableDeferred<List<TodaysDoseSummary>>()
        override suspend fun loadToday(): List<TodaysDoseSummary> = pending.await()
    }

    /**
     * Fake `BodyCompositionRepository`. `refresh()` either emits the
     * pre-configured snapshot through the hot replay-1 flow (success
     * path) or throws (failure path). Mirrors the production impl's
     * pattern of "refresh re-publishes through observeSnapshot".
     */
    private class ResolvableBodyComp : BodyCompositionRepository {
        private val flow = MutableSharedFlow<BodyCompositionSnapshot>(replay = 1)
        private var result: Result<BodyCompositionSnapshot>? = null
        fun resolveWith(value: BodyCompositionSnapshot) { result = Result.success(value) }
        fun rejectWith(cause: Throwable) { result = Result.failure(cause) }
        override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = flow.asSharedFlow()
        override suspend fun refresh() {
            val r = result ?: error("test fake not configured")
            r.fold(
                onSuccess = { flow.emit(it) },
                onFailure = { throw it },
            )
        }
        override suspend fun pointsInRange(
            metric: BodyCompositionMetric,
            from: Instant,
            to: Instant,
        ): List<BodyCompositionPoint> = emptyList()
    }

    private class ResolvableBlood : BloodMarkerSummaryRepository {
        private var result: Result<List<BloodMarkerSummary>>? = null
        fun resolveWith(value: List<BloodMarkerSummary>) { result = Result.success(value) }
        fun rejectWith(cause: Throwable) { result = Result.failure(cause) }
        override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> =
            (result ?: error("test fake not configured")).getOrThrow()
    }

    private class ResolvableDoses : TodaysDosesRepository {
        private var result: Result<List<TodaysDoseSummary>>? = null
        fun resolveWith(value: List<TodaysDoseSummary>) { result = Result.success(value) }
        fun rejectWith(cause: Throwable) { result = Result.failure(cause) }
        override suspend fun loadToday(): List<TodaysDoseSummary> =
            (result ?: error("test fake not configured")).getOrThrow()
    }

    private class CountingBodyComp : BodyCompositionRepository {
        val calls = AtomicInteger(0)
        private val flow = MutableSharedFlow<BodyCompositionSnapshot>(replay = 1)
        override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = flow.asSharedFlow()
        override suspend fun refresh() {
            calls.incrementAndGet()
            flow.emit(emptySnapshot())
        }
        override suspend fun pointsInRange(
            metric: BodyCompositionMetric,
            from: Instant,
            to: Instant,
        ): List<BodyCompositionPoint> = emptyList()
    }

    private class CountingBlood : BloodMarkerSummaryRepository {
        val calls = AtomicInteger(0)
        override suspend fun loadDashboardMarkers(): List<BloodMarkerSummary> {
            calls.incrementAndGet()
            return emptyList()
        }
    }

    private class CountingDoses : TodaysDosesRepository {
        val calls = AtomicInteger(0)
        override suspend fun loadToday(): List<TodaysDoseSummary> {
            calls.incrementAndGet()
            return emptyList()
        }
    }

    companion object {
        private fun emptySnapshot() = BodyCompositionSnapshot(
            latestWeightKg = null,
            latestBodyFatPercent = null,
            latestLeanMassKg = null,
            latestBmi = null,
            latestSampleTime = null,
            sevenDayDeltaKg = null,
            ninetyDayDeltaKg = null,
            series90d = emptyList(),
        )

        private fun snapshotWithLatestWeight(kg: Double) = BodyCompositionSnapshot(
            latestWeightKg = kg,
            latestBodyFatPercent = null,
            latestLeanMassKg = null,
            latestBmi = null,
            latestSampleTime = Instant.parse("2026-05-27T12:00:00Z"),
            sevenDayDeltaKg = null,
            ninetyDayDeltaKg = null,
            series90d = emptyList(),
        )
    }
}
