package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.dao.MedicationAdherenceDao
import com.gte619n.healthfitness.data.db.entity.MedicationAdherenceEntity
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
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
    private val adherenceDao: MedicationAdherenceDao,
    moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val payloadAdapter = moshi.adapter(AdherenceMirrorPayload::class.java)
    private val dayPayloadAdapter = moshi.adapter(ServerDayPayload::class.java)

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

    /**
     * IMPL-21: record a dose as MISSED (the day ended without it being taken). Writes
     * an optimistic mirror row (taken=false, missed=true) + outbox → `POST .../adherence`
     * with `missed:true`, so the miss syncs to the backend for adherence history/stats
     * (spec D11) without counting as a take. No-op semantics for the today overlay: a
     * missed row surfaces as not-taken.
     */
    suspend fun markMissed(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
        dose: Double? = null,
    ) = withContext(io) {
        val id = adherenceId(medicationId, date, window)
        val payload = AdherenceMirrorPayload(
            medicationId = medicationId,
            date = date,
            window = window.name,
            taken = false,
            takenAt = null,
            dose = dose,
            missed = true,
        )
        support.createLocal(
            table = MirrorTables.MEDICATION_ADHERENCE,
            id = id,
            payloadJson = payloadAdapter.toJson(payload),
            lastUpdate = System.currentTimeMillis(),
        )
    }

    /**
     * IMPL-21: `(medicationId, window)` pairs that already have ANY adherence record
     * (taken OR missed) for [date] — this device's flat per-window rows plus the
     * pulled server day-rows (see [dayRowWindows]). Used by the midnight/boot
     * missed-rollover so a dose that was taken — or already marked missed, on any
     * device — is never (re-)marked missed.
     */
    suspend fun recordedWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> =
        withContext(io) {
            val rows = adherenceDao.observeAll().first()
            flatWindows(rows, date) { true } + dayRowWindows(rows, date) { true }
        }

    /**
     * IMPL-21: `(medicationId, window)` pairs recorded as TAKEN for [date]. Two
     * sources, both read from the mirror (no network):
     *  - the flat `med/date/window` rows THIS device wrote — the authoritative
     *    record of what the user checked off here, including a dose just logged
     *    from the rolling reminder's "✓"/"Take all" action;
     *  - the day-shaped `med/date` rows the sync pull lands for takes made on
     *    OTHER devices (each carrying the server day's `doses` array).
     * The reminder engine unions this with the cached `today` projection so a
     * tapped dose always clears from the notification without a network round-trip.
     */
    suspend fun takenWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> =
        withContext(io) {
            val rows = adherenceDao.observeAll().first()
            flatWindows(rows, date) { it.taken && !it.missed } +
                dayRowWindows(rows, date) { !it.missed }
        }

    /** Non-tombstoned flat per-window rows for [date] matching [include]. */
    private fun flatWindows(
        rows: List<MedicationAdherenceEntity>,
        date: LocalDate,
        include: (AdherenceMirrorPayload) -> Boolean,
    ): Set<Pair<String, TimeWindow>> =
        rows.filter { it.status != "ARCHIVED" }
            .mapNotNull { row ->
                val payload = decodePayload(row.payloadJson)
                    ?.takeIf { it.date == date && include(it) }
                    ?: return@mapNotNull null
                val window = runCatching { TimeWindow.valueOf(payload.window) }.getOrNull()
                    ?: return@mapNotNull null
                payload.medicationId to window
            }
            .toSet()

    /**
     * Windows carried by the server day-rows (`"{med}/{date}"` composite id, the
     * backend's `{date, doses:[{window, missed}]}` document) for [date]. A flat
     * row for the same `(med, date, window)` — even a tombstoned one (a local
     * undo) — wins over the pulled day-row, which may lag this device's writes.
     */
    private fun dayRowWindows(
        rows: List<MedicationAdherenceEntity>,
        date: LocalDate,
        include: (ServerDayPayload.DoseEntry) -> Boolean,
    ): Set<Pair<String, TimeWindow>> {
        val locallyRecorded = rows.mapNotNull { row ->
            // Any flat row for the date, regardless of status/taken.
            val payload = decodePayload(row.payloadJson)?.takeIf { it.date == date }
                ?: return@mapNotNull null
            val window = runCatching { TimeWindow.valueOf(payload.window) }.getOrNull()
                ?: return@mapNotNull null
            payload.medicationId to window
        }.toSet()
        return rows
            .filter { it.status != "ARCHIVED" && it.id.count { c -> c == '/' } == 1 }
            .mapNotNull { row ->
                val payload = decodeDayPayload(row.payloadJson)?.takeIf { it.date == date }
                payload?.let { row.id.substringBefore('/') to it }
            }
            .flatMap { (medicationId, payload) ->
                payload.doses.filter(include).mapNotNull { dose ->
                    runCatching { TimeWindow.valueOf(dose.window) }.getOrNull()
                        ?.let { medicationId to it }
                }
            }
            .filter { it !in locallyRecorded }
            .toSet()
    }

    private fun decodePayload(json: String): AdherenceMirrorPayload? =
        runCatching { payloadAdapter.fromJson(json) }.getOrNull()

    private fun decodeDayPayload(json: String): ServerDayPayload? =
        runCatching { dayPayloadAdapter.fromJson(json) }.getOrNull()

    companion object {
        /** Composite mirror id `"<med>/<date>/<window>"` (date as ISO yyyy-MM-dd). */
        fun adherenceId(medicationId: String, date: LocalDate, window: TimeWindow): String =
            "$medicationId/$date/${window.name}"
    }
}

/**
 * The day-shaped payload of a PULLED server adherence row (Firestore doc
 * `users/{u}/medications/{med}/adherence/{date}`, mirrored under the composite
 * id `"{med}/{date}"`). Only the fields the window-set readers need; Moshi
 * skips the rest (`takenAt`, `notes`, timestamps). The medication id comes from
 * the row id, not the payload.
 */
internal data class ServerDayPayload(
    val date: LocalDate,
    val doses: List<DoseEntry> = emptyList(),
) {
    internal data class DoseEntry(
        val window: String,
        val missed: Boolean = false,
    )
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
    // IMPL-21: an auto-recorded miss (taken=false, missed=true). Replays to the
    // backend log endpoint as `missed:true`; the today-overlay treats it as not-taken.
    val missed: Boolean = false,
)
