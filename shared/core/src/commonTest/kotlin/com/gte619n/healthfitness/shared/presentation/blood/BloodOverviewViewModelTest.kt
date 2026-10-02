package com.gte619n.healthfitness.shared.presentation.blood

import com.gte619n.healthfitness.shared.data.BloodReadingRepository
import com.gte619n.healthfitness.shared.data.BloodTestReportRepository
import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import com.gte619n.healthfitness.shared.domain.blood.BloodReading
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.shared.domain.blood.LatestMarker
import com.gte619n.healthfitness.shared.domain.blood.ReferenceRange
import com.gte619n.healthfitness.shared.domain.blood.UploadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — the shared Blood overview VM's state composition.
 * Pure/common (kotlinx-coroutines-test) → runs identically on JVM and iOS.
 * Verifies the combine → LatestMarkers.derive fold, the report recency cap, and
 * the on-entry refresh (offline-first: never blanks back to Loading).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BloodOverviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val range = ReferenceRange(
        unit = "mg/dL",
        orientation = ReferenceRange.Orientation.LOWER_IS_BETTER,
        goodThreshold = 100.0,
        displayMin = 0.0,
        displayMax = 200.0,
    )

    private fun reading(marker: BloodMarker, value: Double, date: String) = BloodReading(
        readingId = "r-$marker-$date",
        marker = marker,
        value = value,
        unit = "mg/dL",
        sampleDate = LocalDate.parse(date),
        labSource = null,
        notes = null,
        reference = range,
    )

    private fun report(date: String, ldl: Double) = BloodTestReport(
        reportId = "rep-$date",
        sampleDate = LocalDate.parse(date),
        labSource = "Quest",
        markers = listOf(
            ExtractedMarker(name = "LDL", value = ldl, unit = "mg/dL", refRangeLow = null, refRangeHigh = null, flag = null),
        ),
        pdfDownloadPath = "/api/me/blood/reports/rep-$date/pdf",
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
    )

    @Test
    fun composesReadyFromMirrorAndDerivesLatestMarkers() = runTest {
        val readings = FakeReadingRepo(MutableStateFlow(listOf(reading(BloodMarker.LDL, 90.0, "2026-08-01"))))
        val reports = FakeReportRepo(MutableStateFlow(listOf(report("2026-09-01", ldl = 80.0))))
        val vm = BloodOverviewViewModel(readings, reports)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        val ready = assertIs<BloodOverviewViewModel.UiState.Ready>(state)
        assertEquals(1, ready.recentReports.size)
        // Latest LDL value comes from the most-recent report (80), not the reading (90).
        val ldl = ready.trackedMarkers.first { it.marker == BloodMarker.LDL }
        assertEquals(80.0, ldl.value)
        assertEquals(LatestMarker.Source.LAB, ldl.source)
        // On-entry refresh ran.
        assertTrue(readings.refreshCount >= 1)
        assertTrue(reports.refreshCount >= 1)
    }

    @Test
    fun capsRecentReportsAtTen() = runTest {
        val many = (1..15).map { i ->
            val month = i.coerceAtMost(12).toString().padStart(2, '0')
            // Distinct dates so recency sort is well-defined; the >12 ones reuse Dec.
            report("2026-$month-${(i % 28 + 1).toString().padStart(2, '0')}", ldl = i.toDouble())
        }
        val vm = BloodOverviewViewModel(
            FakeReadingRepo(MutableStateFlow(emptyList())),
            FakeReportRepo(MutableStateFlow(many)),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val ready = assertIs<BloodOverviewViewModel.UiState.Ready>(vm.state.value)
        assertEquals(10, ready.recentReports.size)
    }

    @Test
    fun reportObserveErrorSurfacesErrorState() = runTest {
        val vm = BloodOverviewViewModel(
            FakeReadingRepo(MutableStateFlow(emptyList())),
            FakeReportRepo(throwOnObserve = true),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertIs<BloodOverviewViewModel.UiState.Error>(vm.state.value)
    }
}

private class FakeReadingRepo(
    private val flow: MutableStateFlow<List<BloodReading>> = MutableStateFlow(emptyList()),
) : BloodReadingRepository {
    var refreshCount = 0
    override fun observeReadings(): Flow<List<BloodReading>> = flow
    override suspend fun refresh() { refreshCount++ }
    override suspend fun create(
        marker: BloodMarker, value: Double, unit: String?, sampleDate: LocalDate,
        labSource: String?, notes: String?,
    ): BloodReading = throw NotImplementedError()
    override suspend fun delete(readingId: String) {}
}

private class FakeReportRepo(
    private val flow: Flow<List<BloodTestReport>> = MutableStateFlow(emptyList()),
    private val throwOnObserve: Boolean = false,
) : BloodTestReportRepository {
    var refreshCount = 0
    override fun observeReports(): Flow<List<BloodTestReport>> =
        if (throwOnObserve) throwingFlow() else flow
    override suspend fun refresh() { refreshCount++ }
    override suspend fun get(reportId: String): BloodTestReport = throw NotImplementedError()
    override suspend fun delete(reportId: String) {}
    override suspend fun downloadPdf(pdfDownloadPath: String): ByteArray = ByteArray(0)
    override fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent> = emptyFlow()

    private fun throwingFlow(): Flow<List<BloodTestReport>> =
        kotlinx.coroutines.flow.flow { throw RuntimeException("boom") }
}
