package com.gte619n.healthfitness.feature.blood

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.UploadEvent
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadLabReportViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val report = BloodTestReport(
        reportId = "rep1",
        sampleDate = LocalDate.of(2026, 5, 1),
        labSource = "LabCorp",
        markers = emptyList(),
        pdfDownloadPath = "/api/me/blood/reports/rep1/pdf",
        createdAt = Instant.EPOCH,
    )

    @Test
    fun transitionsThroughPhasesToComplete() = runTest {
        val reports = FakeReportRepository(
            uploadEvents = listOf(
                UploadEvent.Uploading,
                UploadEvent.Extracting,
                UploadEvent.Saving,
                UploadEvent.Complete(report),
            ),
        )
        val vm = UploadLabReportViewModel(reports)

        vm.state.test {
            assertEquals(UploadLabReportViewModel.UiState.Idle, awaitItem())
            vm.upload("report.pdf", ByteArray(4))
            assertEquals(UploadLabReportViewModel.UiState.Uploading, awaitItem())
            assertEquals(UploadLabReportViewModel.UiState.Extracting, awaitItem())
            assertEquals(UploadLabReportViewModel.UiState.Saving, awaitItem())
            val complete = awaitItem() as UploadLabReportViewModel.UiState.Complete
            assertEquals("rep1", complete.report.reportId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun streamFailedSurfacesFailedState() = runTest {
        val reports = FakeReportRepository(
            uploadEvents = listOf(UploadEvent.Uploading, UploadEvent.Failed("Could not read PDF")),
        )
        val vm = UploadLabReportViewModel(reports)
        vm.upload("report.pdf", ByteArray(4))
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is UploadLabReportViewModel.UiState.Failed)
        assertEquals("Could not read PDF", (state as UploadLabReportViewModel.UiState.Failed).error)
    }

    @Test
    fun thrownErrorMapsToFailed() = runTest {
        val reports = FakeReportRepository(uploadError = RuntimeException("network down"))
        val vm = UploadLabReportViewModel(reports)
        vm.upload("report.pdf", ByteArray(4))
        advanceUntilIdle()
        assertTrue(vm.state.value is UploadLabReportViewModel.UiState.Failed)
    }
}
