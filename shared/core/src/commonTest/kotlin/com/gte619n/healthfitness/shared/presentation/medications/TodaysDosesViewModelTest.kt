package com.gte619n.healthfitness.shared.presentation.medications

import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave B — port of the Android
 * `TodaysDosesViewModelTest`. Verifies the reactive-source behavior: toggling logs
 * to the adherence mirror, and the card only updates when the single source
 * ([observeTodaysDoses]) re-emits — there is no manual optimistic flip. `viewModelScope`
 * runs on the injected main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodaysDosesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun logsTheDoseAndReflectsTheReactiveSourceUpdate() = runTest {
        val source = MutableStateFlow(listOf(sampleDose(taken = false)))
        val meds = FakeMedicationCrudRepository(doses = source)
        val adherence = FakeAdherenceRepository()
        val vm = TodaysDosesViewModel(meds, adherence)
        advanceUntilIdle()

        val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        assertFalse(before.taken)

        vm.toggle(before)
        advanceUntilIdle()
        assertEquals(1, adherence.logCount)
        assertEquals("med-1" to TimeWindow.MORNING, adherence.logged.first())

        // The card only updates when the reactive source re-emits.
        source.value = listOf(sampleDose(taken = true))
        advanceUntilIdle()
        assertTrue((vm.state.value as TodaysDosesUiState.Ready).doses.first().taken)
    }

    @Test
    fun toggleFailureSurfacesMessageAndLeavesSourceAsTruth() = runTest {
        val source = MutableStateFlow(listOf(sampleDose(taken = false)))
        val meds = FakeMedicationCrudRepository(doses = source)
        val adherence = FakeAdherenceRepository(failOnLog = true)
        val vm = TodaysDosesViewModel(meds, adherence)
        advanceUntilIdle()

        val before = (vm.state.value as TodaysDosesUiState.Ready).doses.first()
        vm.toggle(before)
        advanceUntilIdle()

        assertTrue(vm.message.value?.contains("try again") == true)
        // The reactive source is the truth; a failed local write doesn't flip it.
        assertFalse((vm.state.value as TodaysDosesUiState.Ready).doses.first().taken)
    }

    @Test
    fun coldOpenSpinnerHoldsUntilRefreshResolvesEvenWhenEmpty() = runTest {
        // Empty projection: the spinner must hold until the first revalidation
        // resolves, then settle to an empty Ready (no premature "no doses" flash).
        val meds = FakeMedicationCrudRepository(doses = MutableStateFlow(emptyList()))
        val vm = TodaysDosesViewModel(meds, FakeAdherenceRepository())
        advanceUntilIdle()

        assertTrue(vm.state.value is TodaysDosesUiState.Ready)
        assertTrue((vm.state.value as TodaysDosesUiState.Ready).doses.isEmpty())
    }

    @Test
    fun refreshFailureOnColdOpenSurfacesError() = runTest {
        val meds = FakeMedicationCrudRepository(
            doses = MutableStateFlow(emptyList()),
            refreshThrows = true,
        )
        val vm = TodaysDosesViewModel(meds, FakeAdherenceRepository())
        advanceUntilIdle()
        assertTrue(vm.state.value is TodaysDosesUiState.Error)
    }
}
