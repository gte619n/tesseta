package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (#24) — offline-capable medication-adherence logging.
 *
 * Adherence has no server-minted entity id (the `today` checklist is a
 * server-derived projection of per-`(med, date, window)` log entries, and the
 * backend idempotency-keys a dose log by `(med, date)`). To make a dose log/undo
 * work offline we mirror it ourselves:
 *
 *  - The `medicationAdherence` mirror row is keyed by a **composite id**
 *    `"<medicationId>/<date>/<window>"` so the replay can recover the med + date
 *    + window path segments and the today checklist can overlay the row onto the
 *    matching dose. Its `payloadJson` is an [AdherenceMirrorPayload] carrying the
 *    `taken`/`takenAt`/`dose` the checklist reads.
 *  - [logDose] is an optimistic CREATE (PENDING) + outbox: it shows immediately
 *    and replays to `POST api/me/medications/{med}/adherence` with the
 *    `(med,date)`-derived `Idempotency-Key` ([OutboxEndpointRegistry.idempotencyKey]).
 *  - [undoDose] is an optimistic DELETE (tombstone) + outbox: it replays to
 *    `DELETE api/me/medications/{med}/adherence/{date}/{window}`.
 *
 * The server projection reconciles on the next delta pull (the engine upserts the
 * authoritative adherence rows / tombstones into the same mirror table), so an
 * offline log converges with the server's `today` computation once online.
 */
@Singleton
class AdherenceRepository @Inject internal constructor(
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val payloadAdapter = moshi.adapter(AdherenceMirrorPayload::class.java)

    suspend fun logDose(
        medicationId: String,
        window: TimeWindow,
        takenAt: Instant = Instant.now(),
        dose: Double? = null,
    ) = withContext(io) {
        // Record against the device-local calendar day of `takenAt` so the log
        // lands on the same date the `today` checklist queries (timezone-safe).
        val date = takenAt.atZone(ZoneId.systemDefault()).toLocalDate()
        val id = adherenceId(medicationId, date, window)
        val payload = AdherenceMirrorPayload(
            medicationId = medicationId,
            date = date,
            window = window.name,
            taken = true,
            takenAt = takenAt,
            dose = dose,
        )
        // Optimistic CREATE (PENDING) + outbox → POST .../adherence.
        support.createLocal(
            table = MirrorTables.MEDICATION_ADHERENCE,
            id = id,
            payloadJson = payloadAdapter.toJson(payload),
            lastUpdate = System.currentTimeMillis(),
        )
    }

    suspend fun undoDose(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
    ) = withContext(io) {
        val id = adherenceId(medicationId, date, window)
        // Optimistic DELETE (tombstone) + outbox → DELETE .../adherence/{date}/{window}.
        support.deleteLocal(MirrorTables.MEDICATION_ADHERENCE, id, System.currentTimeMillis())
    }

    companion object {
        /** Composite mirror id `"<med>/<date>/<window>"` (date as ISO yyyy-MM-dd). */
        fun adherenceId(medicationId: String, date: LocalDate, window: TimeWindow): String =
            "$medicationId/$date/${window.name}"
    }
}

/**
 * The mirror `payloadJson` for one offline adherence log. Carries exactly what the
 * `today` checklist overlay needs; the server projection replaces it on pull.
 */
data class AdherenceMirrorPayload(
    val medicationId: String,
    val date: LocalDate,
    val window: String,
    val taken: Boolean,
    val takenAt: Instant? = null,
    val dose: Double? = null,
)
