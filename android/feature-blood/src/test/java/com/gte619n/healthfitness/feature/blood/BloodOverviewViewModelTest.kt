package com.gte619n.healthfitness.feature.blood

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BloodOverviewViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun reading() = BloodReading(
        readingId = "r1",
        marker = BloodMarker.LDL,
        value = 82.0,
        unit = "mg/dL",
        sampleDate = LocalDate.of(2026, 5, 1),
        labSource = null,
        notes = null,
        reference = ReferenceRange("mg/dL", ReferenceRange.Orientation.LOWER_IS_BETTER, 100.0, 0.0, 200.0),
    )

    @Test
    fun emitsLoadingThenReadyWithDerivedMarkers() = runTest {
        val readings = FakeReadingRepository(initial = listOf(reading()))
        val reports = FakeReportRepository()
        val vm = BloodOverviewViewModel(readings, reports)

        vm.state.test {
            assertEquals(BloodOverviewViewModel.UiState.Loading, awaitItem())
            val ready = awaitItem() as BloodOverviewViewModel.UiState.Ready
            assertTrue(ready.trackedMarkers.isNotEmpty())
            val ldl = ready.trackedMarkers.first { it.marker == BloodMarker.LDL }
            assertEquals(82.0, ldl.value!!, 0.0001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emitsErrorWhenStreamThrows() = runTest {
        val readings = FakeReadingRepository()
        val reports = ThrowingReportRepository()
        val vm = BloodOverviewViewModel(readings, reports)
        vm.state.test {
            assertEquals(BloodOverviewViewModel.UiState.Loading, awaitItem())
            val item = awaitItem()
            assertTrue(item is BloodOverviewViewModel.UiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
