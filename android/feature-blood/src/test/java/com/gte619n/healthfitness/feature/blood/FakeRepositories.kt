package com.gte619n.healthfitness.feature.blood

import com.gte619n.healthfitness.data.blood.BloodReadingRepository
import com.gte619n.healthfitness.data.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.UploadEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

// The repositories are concrete @Inject classes now, so test doubles are MockK
// mocks (MockK mocks final Kotlin classes fine). Backing MutableStateFlows
// preserve the stateful behavior the ViewModels rely on (observe → read via get,
// mutate via delete).

fun fakeReadingRepository(
    initial: List<BloodReading> = emptyList(),
    refreshError: Throwable? = null,
): BloodReadingRepository {
    val state = MutableStateFlow(initial)
    val repo = mockk<BloodReadingRepository>()
    every { repo.observeReadings() } returns state.asStateFlow()
    coEvery { repo.refresh() } answers { refreshError?.let { throw it } }
    coEvery { repo.create(any(), any(), any(), any(), any(), any()) } throws UnsupportedOperationException()
    coEvery { repo.delete(any()) } returns Unit
    return repo
}

fun fakeReportRepository(
    initial: List<BloodTestReport> = emptyList(),
    uploadEvents: List<UploadEvent> = emptyList(),
    uploadError: Throwable? = null,
): BloodTestReportRepository {
    val state = MutableStateFlow(initial)
    val repo = mockk<BloodTestReportRepository>()
    every { repo.observeReports() } returns state.asStateFlow()
    coEvery { repo.refresh() } returns Unit
    coEvery { repo.get(any()) } answers { state.value.first { it.reportId == firstArg<String>() } }
    coEvery { repo.delete(any()) } answers {
        state.value = state.value.filterNot { it.reportId == firstArg<String>() }
    }
    coEvery { repo.downloadPdf(any()) } returns ByteArray(0)
    every { repo.upload(any(), any()) } returns flow {
        uploadError?.let { throw it }
        uploadEvents.forEach { emit(it) }
    }
    return repo
}

/** A report repository whose [observeReports] flow throws, to exercise error mapping. */
fun throwingReportRepository(): BloodTestReportRepository {
    val repo = mockk<BloodTestReportRepository>()
    every { repo.observeReports() } returns flow { throw RuntimeException("boom") }
    coEvery { repo.refresh() } returns Unit
    coEvery { repo.get(any()) } throws UnsupportedOperationException()
    coEvery { repo.delete(any()) } returns Unit
    coEvery { repo.downloadPdf(any()) } returns ByteArray(0)
    every { repo.upload(any(), any()) } returns flow {}
    return repo
}
