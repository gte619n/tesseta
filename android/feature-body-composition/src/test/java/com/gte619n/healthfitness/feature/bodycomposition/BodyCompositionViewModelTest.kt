package com.gte619n.healthfitness.feature.bodycomposition

import com.gte619n.healthfitness.feature.bodycomposition.overview.BodyCompositionViewModel
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BodyCompositionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `initial refresh populates snapshot and clears loading`() = runTest(mainRule.dispatcher) {
        val bodyRepo = fakeBodyCompositionRepository(snapshotToEmit = sampleSnapshot())
        val dexaRepo = fakeDexaScanRepository(summaries = listOf())
        val vm = BodyCompositionViewModel(bodyRepo, dexaRepo, FakeUnitPreferencesRepository())

        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.snapshot)
        assertEquals(80.0, state.snapshot!!.latestWeightKg!!, 0.001)
        assertNull(state.error)
        coVerify(exactly = 1) { bodyRepo.refresh() }
    }

    @Test
    fun `refresh re-pulls`() = runTest(mainRule.dispatcher) {
        val bodyRepo = fakeBodyCompositionRepository()
        val dexaRepo = fakeDexaScanRepository()
        val vm = BodyCompositionViewModel(bodyRepo, dexaRepo, FakeUnitPreferencesRepository())
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 2) { bodyRepo.refresh() }
    }

    @Test
    fun `repository error surfaces as state error`() = runTest(mainRule.dispatcher) {
        val bodyRepo = fakeBodyCompositionRepository(failRefresh = true)
        val dexaRepo = fakeDexaScanRepository()
        val vm = BodyCompositionViewModel(bodyRepo, dexaRepo, FakeUnitPreferencesRepository())

        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.error)
        assertFalse(state.loading)
    }
}
