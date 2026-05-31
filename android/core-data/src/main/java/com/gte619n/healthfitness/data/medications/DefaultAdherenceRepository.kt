package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.AdherenceRepository
import com.gte619n.healthfitness.domain.medications.TimeWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultAdherenceRepository @Inject constructor(
    private val api: AdherenceApi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AdherenceRepository {

    override suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant,
        dose: Double?,
    ) = withContext(io) {
        api.log(medicationId, LogDoseDto(window = window.name, takenAt = takenAt, dose = dose))
    }

    override suspend fun undoDose(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
    ) = withContext(io) {
        // Date as ISO-8601 (LocalDate.toString()), window as the enum name.
        api.undo(medicationId, date.toString(), window.name)
    }
}
