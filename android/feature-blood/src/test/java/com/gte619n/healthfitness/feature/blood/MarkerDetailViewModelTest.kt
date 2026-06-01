package com.gte619n.healthfitness.feature.blood

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.gte619n.healthfitness.feature.blood.nav.BloodRoutes
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkerDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val today = LocalDate.of(2026, 5, 30)

    private fun reading(value: Double, date: LocalDate) = BloodReading(
        readingId = "r-$value",
        marker = BloodMarker.LDL,
        value = value,
        unit = "mg/dL",
        sampleDate = date,
        labSource = null,
        notes = null,
        reference = ReferenceRange("mg/dL", ReferenceRange.Orientation.LOWER_IS_BETTER, 100.0, 0.0, 200.0),
    )

    private fun vm(
        readings: List<BloodReading>,
        reports: List<BloodTestReport> = emptyList(),
    ) = MarkerDetailViewModel(
        readings = FakeReadingRepository(initial = readings),
        reports = FakeReportRepository(initial = reports),
        savedState = SavedStateHandle(mapOf(BloodRoutes.ARG_MARKER_KEY to "LDL")),
    )

    @Test
    fun filtersToRouteMarkerAndBuildsRows() = runTest {
        val viewModel = vm(listOf(reading(120.0, today.minusDays(10)), reading(82.0, today)))
        viewModel.state.test {
            // skip Loading
            awaitItem()
            val ready = awaitItem() as MarkerDetailViewModel.UiState.Ready
            assertEquals(BloodMarker.LDL, ready.latest.marker)
            assertEquals(82.0, ready.latest.value!!, 0.0001)
            assertEquals(2, ready.rows.size)
            // rows are newest-first
            assertEquals(today, ready.rows.first().date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun dedupesSameDayLastWins() = runTest {
        val report = BloodTestReport(
            reportId = "rep1",
            sampleDate = today,
            labSource = "Quest Diagnostics",
            markers = listOf(ExtractedMarker("LDL", 88.0, "mg/dL", null, null, null)),
            pdfDownloadPath = "/api/me/blood/reports/rep1/pdf",
            createdAt = Instant.EPOCH,
        )
        val viewModel = vm(listOf(reading(120.0, today)), listOf(report))
        viewModel.state.test {
            awaitItem()
            val ready = awaitItem() as MarkerDetailViewModel.UiState.Ready
            assertEquals(1, ready.rows.size)
            assertEquals(88.0, ready.rows.first().value, 0.0001)
            assertEquals("Lab — Quest Diagnostics", ready.rows.first().sourceLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
