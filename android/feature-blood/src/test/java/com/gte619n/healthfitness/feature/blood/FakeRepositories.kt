package com.gte619n.healthfitness.feature.blood

import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.UploadEvent
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

open class FakeReadingRepository(
    initial: List<BloodReading> = emptyList(),
    private val refreshError: Throwable? = null,
) : BloodReadingRepository {
    val state = MutableStateFlow(initial)
    override fun observeReadings(): Flow<List<BloodReading>> = state.asStateFlow()
    override suspend fun refresh() {
        refreshError?.let { throw it }
    }

    override suspend fun create(
        marker: BloodMarker,
        value: Double,
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading = throw UnsupportedOperationException()

    override suspend fun delete(readingId: String) = Unit
}

open class FakeReportRepository(
    initial: List<BloodTestReport> = emptyList(),
    private val uploadEvents: List<UploadEvent> = emptyList(),
    private val uploadError: Throwable? = null,
) : BloodTestReportRepository {
    val state = MutableStateFlow(initial)
    override fun observeReports(): Flow<List<BloodTestReport>> = state.asStateFlow()
    override suspend fun refresh() = Unit
    override suspend fun get(reportId: String): BloodTestReport =
        state.value.first { it.reportId == reportId }

    override suspend fun delete(reportId: String) {
        state.value = state.value.filterNot { it.reportId == reportId }
    }

    override suspend fun downloadPdf(pdfDownloadPath: String): ByteArray = ByteArray(0)

    override fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent> = flow {
        uploadError?.let { throw it }
        uploadEvents.forEach { emit(it) }
    }
}

/** A report repository whose [observeReports] flow throws, to exercise error mapping. */
class ThrowingReportRepository : BloodTestReportRepository {
    override fun observeReports(): Flow<List<BloodTestReport>> = flow { throw RuntimeException("boom") }
    override suspend fun refresh() = Unit
    override suspend fun get(reportId: String): BloodTestReport = throw UnsupportedOperationException()
    override suspend fun delete(reportId: String) = Unit
    override suspend fun downloadPdf(pdfDownloadPath: String): ByteArray = ByteArray(0)
    override fun upload(fileName: String, pdfBytes: ByteArray): Flow<UploadEvent> = flow {}
}
