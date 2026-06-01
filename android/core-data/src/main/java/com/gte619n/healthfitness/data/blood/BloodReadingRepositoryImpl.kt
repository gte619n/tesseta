package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
internal class BloodReadingRepositoryImpl @Inject constructor(
    private val api: BloodApi,
) : BloodReadingRepository {

    private val state = MutableStateFlow<List<BloodReading>>(emptyList())

    override fun observeReadings(): Flow<List<BloodReading>> = state.asStateFlow()

    override suspend fun refresh() {
        state.value = api.listReadings()
            .map { it.toDomain() }
            .sortedByDescending { it.sampleDate }
    }

    override suspend fun create(
        marker: BloodMarker,
        value: Double,
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading {
        val created = api.createReading(
            CreateReadingRequestDto(
                marker = marker.name,
                value = value,
                unit = unit,
                sampleDate = sampleDate.toString(),
                labSource = labSource?.takeIf { it.isNotBlank() },
                notes = notes?.takeIf { it.isNotBlank() },
            ),
        ).toDomain()
        state.update { current ->
            (current + created).sortedByDescending { it.sampleDate }
        }
        return created
    }

    override suspend fun delete(readingId: String) {
        api.deleteReading(readingId)
        state.update { current -> current.filterNot { it.readingId == readingId } }
    }
}
