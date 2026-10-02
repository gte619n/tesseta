package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.medications.Medication
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart

/**
 * IMPL-IOS-01 Phase 1C — online-first [MedicationRepository] over the existing
 * `GET /api/me/medications` endpoint. Backed by a [MutableStateFlow] that fetches
 * lazily on first observe and on [refresh], so the list screen is reactive to
 * refreshes without an offline mirror (the mirror/outbox is the later sync layer).
 *
 * [markTaken]/[markMissed] are the dose-adherence actions used by the Today's
 * Doses screen, not the medications list this repository backs; they're left as
 * online-first no-ops until that screen is wired to the adherence endpoints.
 */
class HttpMedicationRepository(private val client: HttpClient) : MedicationRepository {

    private val cache = MutableStateFlow<List<Medication>>(emptyList())
    private var loaded = false

    override fun observe(): Flow<List<Medication>> = cache.onStart {
        if (!loaded) refresh()
    }

    override suspend fun refresh() {
        cache.value = client.get("api/me/medications").body()
        loaded = true
    }

    override suspend fun markTaken(doseId: String) {
        // TODO(Phase 1C — Today's Doses): POST api/me/medications/{id}/adherence.
    }

    override suspend fun markMissed(doseId: String, reason: String?) {
        // TODO(Phase 1C — Today's Doses): adherence endpoint with a MISSED status.
    }
}
