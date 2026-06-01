package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.net.SseClient
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes the backend drug-lookup SSE stream
 * (`POST /api/drugs/lookup/stream`, body `{"query": "..."}`) and maps each
 * `data` payload (a JSON object carrying a `phase` field) to a
 * [DrugLookupEvent]. Terminal phases (`complete`, `not_found`, `failed`) end
 * the flow.
 *
 * Built on the shared [SseClient] (OkHttp EventSource); JSON parsed with Moshi.
 */
@Singleton
internal class DrugLookupStreamClient @Inject constructor(
    private val sseClient: SseClient,
    moshi: Moshi,
) {
    private val phaseAdapter = moshi.adapter(LookupPhaseDto::class.java)
    private val queryAdapter = moshi.adapter(LookupQueryDto::class.java)

    fun stream(query: String): Flow<DrugLookupEvent> = flow {
        val body = queryAdapter.toJson(LookupQueryDto(query))
        sseClient.streamJsonPost("/api/drugs/lookup/stream", body).collect { sseEvent ->
            val dto = phaseAdapter.fromJson(sseEvent.data) ?: return@collect
            val mapped: DrugLookupEvent = when (dto.phase.lowercase()) {
                "complete" -> {
                    val drug = dto.drug
                    if (drug != null) {
                        DrugLookupEvent.Found(MedicationMapper.toDomain(drug))
                    } else {
                        DrugLookupEvent.NotFound(dto.message)
                    }
                }
                "not_found" -> DrugLookupEvent.NotFound(dto.message)
                "failed", "error" -> DrugLookupEvent.Failed(dto.error ?: dto.message ?: "Lookup failed")
                else -> DrugLookupEvent.Progress(dto.phase, dto.message)
            }
            emit(mapped)
        }
    }
}

internal data class LookupQueryDto(val query: String)

internal data class LookupPhaseDto(
    val phase: String,
    val message: String? = null,
    val error: String? = null,
    val drug: DrugDto? = null,
)
