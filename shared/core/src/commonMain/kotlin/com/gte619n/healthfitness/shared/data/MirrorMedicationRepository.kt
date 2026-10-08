package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.sync.MirrorTables
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 (#2) — mirror-backed [MedicationRepository]: the medications list now
 * reads from the on-device mirror (populated by the sync engine's delta pull), so it
 * renders offline + instantly on cold start. [observe] decodes the synced medication
 * docs from the MEDICATIONS mirror rows; a first-collect triggers a best-effort pull
 * (parity with the old online-first `onStart { refresh() }`), and [refresh] re-pulls.
 *
 * [markTaken]/[markMissed] are the dose-adherence actions for the (not-yet-wired)
 * Today's-Doses screen; they stay no-ops here (as in the prior online-first repo)
 * until that screen routes adherence through the outbox (MEDICATION_ADHERENCE).
 */
class MirrorMedicationRepository(
    private val mirror: SqlDelightMirrorStore,
    private val engine: SyncEngine,
    private val json: Json = LENIENT,
) : MedicationRepository {

    override fun observe(): Flow<List<Medication>> =
        mirror.observeActiveRecords(MirrorTables.MEDICATIONS)
            .onStart { runCatching { engine.pull() } }
            .map { records ->
                records.mapNotNull {
                    runCatching { json.decodeFromString(Medication.serializer(), it.payloadJson) }.getOrNull()
                }
            }

    override suspend fun refresh() {
        runCatching { engine.pull() }
    }

    override suspend fun markTaken(doseId: String) {
        // TODO(Today's Doses): POST api/me/medications/{id}/adherence via the outbox.
    }

    override suspend fun markMissed(doseId: String, reason: String?) {
        // TODO(Today's Doses): adherence endpoint with a MISSED status via the outbox.
    }

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }
}
