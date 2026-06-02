package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.dao.MedicationDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first medications repository.
 *
 * Reads ([list]) come from the `medications` mirror (D8); the network only fills
 * it (one-shot on a cold miss + the background SyncEngine pull). The mirror
 * `payloadJson` is the full [MedicationDto] (status, dosage periods, correlated
 * markers, embedded adherence summary), so everything the list/detail screens
 * read round-trips through Room (computed-field gap #7, per #15).
 *
 * Writes:
 *  - [create]/[update] are optimistic + outbox (D7): a create mints a client UUID,
 *    writes a PENDING row that appears instantly, and replays to `api/me/medications`.
 *  - [changeDose]/[discontinue]/[reactivate] stay on the network path. They are
 *    **server-evaluated state transitions** that close/open dated dosage periods
 *    and append `medicationHistory` entries (D9 server-derived) — replaying them as
 *    raw doc writes would clobber the server's history math. After each, the
 *    affected mirror row is refreshed so Room reflects the new state.
 *  - [get] fetches the detail (which carries the pull-only `history`, D9) from the
 *    network, falling back to the mirrored list DTO when offline.
 *  - [todaysDoses] is a server-derived, adherence-computed checklist (D9) — it
 *    reads live and is not mirrored as a writable row.
 */
@Singleton
internal class DefaultMedicationRepository @Inject constructor(
    private val api: MedicationsApi,
    private val dao: MedicationDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MedicationRepository {

    private val dtoAdapter = moshi.adapter(MedicationDto::class.java)

    override suspend fun list(status: MedicationStatus?): List<Medication> = withContext(io) {
        if (support.killSwitchOn()) {
            return@withContext api.list(status?.name).map { MedicationMapper.toDomain(it) }
        }
        if (dao.observeActive().first().isEmpty()) fillMirror()
        dao.observeActive().first()
            .mapNotNull { decode(it.payloadJson) }
            .map { MedicationMapper.toDomain(it) }
            .filter { status == null || it.status == status }
    }

    override suspend fun get(medicationId: String): MedicationDetail = withContext(io) {
        if (support.killSwitchOn()) return@withContext MedicationMapper.toDomain(api.get(medicationId))
        // Detail carries the pull-only history (D9); fetch live, fall back to the
        // mirrored list DTO (no history) when the network is unavailable offline.
        runCatching { api.get(medicationId) }
            .map { MedicationMapper.toDomain(it) }
            .getOrElse {
                val dto = mirroredDto(medicationId) ?: throw it
                MedicationDetail(medication = MedicationMapper.toDomain(dto), history = emptyList())
            }
    }

    override suspend fun create(request: CreateMedicationRequest): Medication = withContext(io) {
        val id = UUID.randomUUID().toString()
        val createDto = MedicationMapper.toDto(request)
        val optimistic = MedicationDto(
            medicationId = id,
            drugId = createDto.drugId,
            drug = null,
            customName = createDto.customName,
            status = MedicationStatus.ACTIVE.name,
            dose = createDto.dose,
            unit = createDto.unit,
            frequency = createDto.frequency,
            timeSlots = createDto.timeSlots,
            protocolId = null,
            notes = createDto.notes,
            prescribedBy = createDto.prescribedBy,
            // Server stamps the authoritative start date on the next pull; use today
            // as the optimistic placeholder (the field is non-null).
            startDate = LocalDate.now(),
            endDate = null,
            discontinueReason = null,
            discontinueNotes = null,
            correlatedMarkers = createDto.correlatedMarkers,
            dosagePeriods = emptyList(),
            adherence = null,
        )
        support.createLocal(
            table = MirrorTables.MEDICATIONS,
            id = id,
            payloadJson = dtoAdapter.toJson(optimistic),
            lastUpdate = System.currentTimeMillis(),
        )
        MedicationMapper.toDomain(optimistic)
    }

    override suspend fun update(
        medicationId: String,
        request: UpdateMedicationRequest,
    ): Medication = withContext(io) {
        val current = mirroredDto(medicationId) ?: api.get(medicationId).toListDto()
        val merged = current.copy(
            customName = request.customName ?: current.customName,
            dose = request.dose ?: current.dose,
            unit = request.unit ?: current.unit,
            frequency = request.frequency?.let { MedicationMapper.toDto(it) } ?: current.frequency,
            timeSlots = request.timeSlots?.map { MedicationMapper.toDto(it) } ?: current.timeSlots,
            notes = request.notes ?: current.notes,
            prescribedBy = request.prescribedBy ?: current.prescribedBy,
            correlatedMarkers = request.correlatedMarkers ?: current.correlatedMarkers,
            startDate = request.startDate ?: current.startDate,
        )
        support.updateLocal(
            table = MirrorTables.MEDICATIONS,
            id = medicationId,
            payloadJson = dtoAdapter.toJson(merged),
            lastUpdate = System.currentTimeMillis(),
        )
        MedicationMapper.toDomain(merged)
    }

    override suspend fun changeDose(
        medicationId: String,
        request: ChangeDoseRequest,
    ): Medication = withContext(io) {
        // Server-evaluated: closes the open period + opens a new one + writes history.
        val dto = api.changeDose(medicationId, MedicationMapper.toDto(request))
        refreshRow(dto)
        MedicationMapper.toDomain(dto)
    }

    override suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate?,
    ): Medication = withContext(io) {
        val body = DiscontinueDto(reason = reason.name, notes = notes, endDate = endDate)
        val dto = api.discontinue(medicationId, body)
        refreshRow(dto)
        MedicationMapper.toDomain(dto)
    }

    override suspend fun reactivate(
        medicationId: String,
        resumeDate: LocalDate?,
    ): Medication = withContext(io) {
        val dto = api.reactivate(medicationId, ReactivateDto(resumeDate = resumeDate))
        refreshRow(dto)
        MedicationMapper.toDomain(dto)
    }

    override suspend fun delete(medicationId: String) = withContext(io) {
        support.deleteLocal(MirrorTables.MEDICATIONS, medicationId, System.currentTimeMillis())
    }

    override suspend fun todaysDoses(): List<TodaysDose> = withContext(io) {
        // Server-derived, adherence-computed checklist (D9). Anchor "today" to the
        // device's local date so it resets at the user's local midnight.
        api.today(LocalDate.now().toString()).map { MedicationMapper.toDomain(it) }
    }

    /** Pull the full medication list from the network into the mirror as SYNCED rows. */
    private suspend fun fillMirror() {
        val dtos = api.list(null)
        support.refreshInto(MirrorTables.MEDICATIONS, dtos.map { it.toRefreshRow() })
    }

    private suspend fun refreshRow(dto: MedicationDto) {
        support.refreshInto(MirrorTables.MEDICATIONS, listOf(dto.toRefreshRow()))
    }

    private suspend fun mirroredDto(medicationId: String): MedicationDto? =
        dao.getById(medicationId)?.let { decode(it.payloadJson) }

    private fun decode(json: String): MedicationDto? =
        runCatching { dtoAdapter.fromJson(json) }.getOrNull()

    private fun MedicationDto.toRefreshRow() = MirrorRepositorySupport.RefreshRow(
        id = medicationId,
        payloadJson = dtoAdapter.toJson(this),
        lastUpdate = System.currentTimeMillis(),
    )

    /** Project a detail DTO back to the list-shaped [MedicationDto] for the mirror. */
    private fun MedicationDetailDto.toListDto() = MedicationDto(
        medicationId = medicationId,
        drugId = drugId,
        drug = drug,
        customName = customName,
        status = status,
        dose = dose,
        unit = unit,
        frequency = frequency,
        timeSlots = timeSlots,
        protocolId = protocolId,
        notes = notes,
        prescribedBy = prescribedBy,
        startDate = startDate,
        endDate = endDate,
        discontinueReason = discontinueReason,
        discontinueNotes = discontinueNotes,
        correlatedMarkers = correlatedMarkers,
        dosagePeriods = dosagePeriods,
        adherence = null,
    )
}
