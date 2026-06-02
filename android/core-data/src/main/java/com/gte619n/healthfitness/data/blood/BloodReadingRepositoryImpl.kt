package com.gte619n.healthfitness.data.blood

import com.gte619n.healthfitness.data.db.dao.BloodReadingDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReading
import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.squareup.moshi.Moshi
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first blood-readings repository.
 *
 * Reads come from the `bloodReadings` mirror table (D8); the network's only job
 * is to fill it via [refresh] (and the background [com.gte619n.healthfitness.data.sync.SyncEngine] pull).
 * Writes are optimistic + outbox (D7): a create mints a client UUID, writes a
 * PENDING mirror row that appears instantly, and enqueues the mutation to replay
 * to `POST /api/me/blood`.
 *
 * **Computed-field gap (spec #7):** the domain [BloodReading] carries a
 * server-computed [ReferenceRange] absent from the sanitized delta `doc`. We
 * persist the full [BloodReadingDto] (which includes `reference`) as the mirror
 * `payloadJson` on refresh, so the screen always has it. The delta-pull path
 * stores the sanitized doc; for an optimistic offline create we synthesize a
 * placeholder reference (see [placeholderReference]) until the server's
 * authoritative value arrives on the next pull/replay-reconcile.
 */
@Singleton
internal class BloodReadingRepositoryImpl @Inject constructor(
    private val api: BloodApi,
    private val dao: BloodReadingDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
) : BloodReadingRepository {

    private val dtoAdapter = moshi.adapter(BloodReadingDto::class.java)

    override fun observeReadings(): Flow<List<BloodReading>> = support.observeWithState(
        rows = dao.observeActive(),
        // #40: carry the mirror row's syncState onto the domain model for the
        // per-row PENDING/FAILED SyncBadge.
        decode = { json, syncState ->
            runCatching { dtoAdapter.fromJson(json)?.toDomain()?.copy(syncState = syncState) }.getOrNull()
        },
        liveFallback = {
            api.listReadings().map { it.toDomain() }.sortedByDescending { it.sampleDate }
        },
    )

    override suspend fun refresh() {
        if (support.killSwitchOn()) return
        val dtos = api.listReadings()
        support.refreshInto(
            MirrorTables.BLOOD_READINGS,
            dtos.map { dto ->
                MirrorRepositorySupport.RefreshRow(
                    id = dto.readingId,
                    payloadJson = dtoAdapter.toJson(dto),
                    lastUpdate = dto.sampleDate.toEpochMillisOrNow(),
                )
            },
        )
    }

    override suspend fun create(
        marker: BloodMarker,
        value: Double,
        unit: String?,
        sampleDate: LocalDate,
        labSource: String?,
        notes: String?,
    ): BloodReading {
        val id = UUID.randomUUID().toString()
        val resolvedUnit = unit?.takeIf { it.isNotBlank() } ?: marker.defaultUnit()
        val dto = BloodReadingDto(
            readingId = id,
            marker = marker.name,
            value = value,
            unit = resolvedUnit,
            sampleDate = sampleDate.toString(),
            labSource = labSource?.takeIf { it.isNotBlank() },
            notes = notes?.takeIf { it.isNotBlank() },
            reference = placeholderReference(resolvedUnit),
        )
        support.createLocal(
            table = MirrorTables.BLOOD_READINGS,
            id = id,
            payloadJson = dtoAdapter.toJson(dto),
            lastUpdate = System.currentTimeMillis(),
        )
        return dto.toDomain()
    }

    override suspend fun delete(readingId: String) {
        support.deleteLocal(MirrorTables.BLOOD_READINGS, readingId, System.currentTimeMillis())
    }

    private fun BloodMarker.defaultUnit(): String = when (this) {
        BloodMarker.HBA1C -> "%"
        BloodMarker.HS_CRP -> "mg/L"
        BloodMarker.TESTOSTERONE -> "ng/dL"
        else -> "mg/dL"
    }

    /**
     * A neutral display range for an optimistic offline create. The server stamps
     * the authoritative [ReferenceRange] on write; it overwrites this on the next
     * pull. Chosen wide so the optimistic row never renders as out-of-range.
     */
    private fun placeholderReference(unit: String) = ReferenceDto(
        unit = unit,
        orientation = "LOWER_IS_BETTER",
        goodThreshold = Double.MAX_VALUE,
        displayMin = 0.0,
        displayMax = Double.MAX_VALUE,
    )

    private fun String.toEpochMillisOrNow(): Long =
        runCatching { LocalDate.parse(this).toEpochDay() * 86_400_000L }
            .getOrDefault(System.currentTimeMillis())
}
