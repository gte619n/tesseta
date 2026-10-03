package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.shared.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.shared.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.shared.domain.medications.Drug
import com.gte619n.healthfitness.shared.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.shared.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationDetail
import com.gte619n.healthfitness.shared.domain.medications.MedicationHistoryEntry
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeSlot
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import com.gte619n.healthfitness.shared.domain.medications.TodaysDose
import com.gte619n.healthfitness.shared.domain.medications.UpdateMedicationRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 — online-first concrete implementations of the four medications
 * write/support repository contracts ([MedicationCrudRepository],
 * [AdherenceRepository], [DrugRepository], [ReminderSettingsRepository]) over the
 * EXISTING backend endpoints Android's medications/reminders `*Api` classes use.
 * Mirror the shape of [HttpMedicationRepository]:
 * a [MutableStateFlow] + `onStart` lazy-load for the reactive reads, plain suspend
 * calls for the writes. No offline mirror/outbox — that's the later sync layer; the
 * mirror-backed read is already shipped for the medications LIST only
 * ([MirrorMedicationRepository]).
 *
 * Wire shapes verified against the backend controllers:
 *  - list `GET /api/me/medications?status=` → bare `List<MedicationResponse>` (== [Medication]).
 *  - detail `GET /api/me/medications/{id}` → FLAT `MedicationDetailResponse`
 *    (all [Medication] fields + a `history` array) — decoded into [MedicationDetailWire]
 *    and assembled into the nested shared [MedicationDetail].
 *  - create/update/dosage/discontinue/reactivate → `WriteResult<MedicationResponse>`
 *    = `{ data, lastUpdate }` → `.data` unwrapped.
 *  - delete → 204.
 *  - today `GET /api/me/medications/today?date=` → bare `List<TodaysDoseResponse>` (== [TodaysDose]).
 *  - adherence log `POST /api/me/medications/{id}/adherence`, undo
 *    `DELETE /api/me/medications/{id}/adherence/{date}/{window}`.
 *  - reminder-settings `GET`/`PUT /api/me/medications/reminder-settings` (== [ReminderSettings]).
 *  - drug catalog `GET /api/drugs` → bare `List<Drug>`.
 */
class HttpMedicationCrudRepository(
    private val client: HttpClient,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : MedicationCrudRepository {

    // The reactive today's-doses source. Online-first (no mirror): seeded by the
    // first observe + every refresh; the adherence repo kicks a refresh after a
    // log/undo so the checklist re-emits live (parity with Android's mirror re-emit).
    private val todaysDoses = MutableStateFlow<List<TodaysDose>>(emptyList())
    private var todayLoaded = false

    override fun observeTodaysDoses(): Flow<List<TodaysDose>> = todaysDoses.onStart {
        if (!todayLoaded) runCatching { refreshTodaysDoses() }
    }

    override suspend fun refreshTodaysDoses() {
        val today = clock.todayIn(timeZone).toString()
        todaysDoses.value = client.get("api/me/medications/today") {
            parameter("date", today)
        }.body()
        todayLoaded = true
    }

    override suspend fun cachedTodaysDoses(): List<TodaysDose> = todaysDoses.value

    override suspend fun list(status: MedicationStatus): List<Medication> =
        client.get("api/me/medications") { parameter("status", status.name) }.body()

    override suspend fun create(request: CreateMedicationRequest): Medication {
        val wrapped: WriteResultWire<Medication> = client.post("api/me/medications") {
            setBody(request.toWire())
        }.body()
        return wrapped.data
    }

    override suspend fun get(medicationId: String): MedicationDetail {
        val wire: MedicationDetailWire = client.get("api/me/medications/$medicationId").body()
        return wire.toDomain()
    }

    override suspend fun cachedDetail(medicationId: String): MedicationDetail =
        // No offline mirror on this online-first impl; the VM falls back to the
        // network `get()` when this throws (its cold-open seed is best-effort).
        throw UnsupportedOperationException("no cached detail (online-first)")

    override suspend fun update(medicationId: String, request: UpdateMedicationRequest) {
        client.put("api/me/medications/$medicationId") { setBody(request.toWire()) }
    }

    override suspend fun changeDose(medicationId: String, request: ChangeDoseRequest) {
        client.post("api/me/medications/$medicationId/dosage") { setBody(request.toWire()) }
    }

    override suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate,
    ) {
        client.post("api/me/medications/$medicationId/discontinue") {
            setBody(DiscontinueWire(reason = reason.name, notes = notes, endDate = endDate.toString()))
        }
    }

    override suspend fun reactivate(medicationId: String, resumeDate: LocalDate?) {
        client.post("api/me/medications/$medicationId/reactivate") {
            setBody(ReactivateWire(resumeDate = resumeDate?.toString()))
        }
    }

    override suspend fun delete(medicationId: String) {
        client.delete("api/me/medications/$medicationId")
    }

    // --- wire shims (private; only the request bodies + the envelope differ from
    // the shared @Serializable domain shapes) ------------------------------------

    private fun CreateMedicationRequest.toWire() = CreateWire(
        drugId = drugId,
        customName = customName,
        dose = dose,
        unit = unit,
        frequency = frequency,
        timeSlots = timeSlots,
        notes = notes,
        prescribedBy = prescribedBy,
        correlatedMarkers = correlatedMarkers,
    )

    private fun UpdateMedicationRequest.toWire() = UpdateWire(
        customName = customName,
        dose = dose,
        unit = unit,
        frequency = frequency,
        timeSlots = timeSlots,
        notes = notes,
        prescribedBy = prescribedBy,
        correlatedMarkers = correlatedMarkers,
        startDate = startDate?.toString(),
        changeNotes = changeNotes,
    )

    private fun ChangeDoseRequest.toWire() = ChangeDoseWire(
        dose = dose,
        unit = unit,
        startDate = startDate?.toString(),
        changeNotes = changeNotes,
    )
}

/**
 * Online-first adherence log over the existing endpoints. It shares the today's-doses
 * StateFlow by holding the [crud] repo instance and kicking a [MedicationCrudRepository.refreshTodaysDoses]
 * after each write, so the reactive checklist re-emits (the online-first stand-in for
 * Android's mirror re-emit). `takenWindowsFor`/`recordedWindowsFor`/`markMissed` are
 * NOT on any wired view's path (only `logDose`/`undoDose` are, from TodaysDosesViewModel);
 * they're implemented best-effort against the per-med adherence range endpoint.
 */
class HttpAdherenceRepository(
    private val client: HttpClient,
    private val crud: MedicationCrudRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : AdherenceRepository {

    override suspend fun logDose(medicationId: String, window: TimeWindow) {
        val today = clock.todayIn(timeZone)
        client.post("api/me/medications/$medicationId/adherence") {
            setBody(LogDoseWire(date = today.toString(), window = window.name, missed = false))
        }
        runCatching { crud.refreshTodaysDoses() }
    }

    override suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow) {
        client.delete("api/me/medications/$medicationId/adherence/$date/${window.name}")
        runCatching { crud.refreshTodaysDoses() }
    }

    override suspend fun markMissed(
        medicationId: String,
        date: LocalDate,
        window: TimeWindow,
        dose: Double,
    ) {
        client.post("api/me/medications/$medicationId/adherence") {
            setBody(LogDoseWire(date = date.toString(), window = window.name, dose = dose, missed = true))
        }
        runCatching { crud.refreshTodaysDoses() }
    }

    override suspend fun takenWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> =
        takenWindows(date)

    override suspend fun recordedWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>> =
        takenWindows(date)

    // Best-effort: the backend has no cross-medication adherence-by-date endpoint
    // (Android reads these from the local mirror), so derive the taken windows from
    // the cached today's-doses for today, else return empty. Not on any currently-
    // wired view's path (only logDose/undoDose are, from TodaysDosesViewModel).
    private suspend fun takenWindows(date: LocalDate): Set<Pair<String, TimeWindow>> {
        if (date != clock.todayIn(timeZone)) return emptySet()
        return runCatching { crud.cachedTodaysDoses() }.getOrDefault(emptyList())
            .filter { it.taken }
            .map { it.medicationId to it.window }
            .toSet()
    }
}

/**
 * Online-first drug catalog + AI lookup. The catalog read is live; the AI SSE
 * lookup DEGRADES (emits a single [DrugLookupEvent.Failed]) because there is no
 * shared KMP SSE client on iOS yet (same gap as the lab-PDF / Designer / GoalsChat
 * flows). The Add-medication screen stays usable via catalog search + manual entry.
 */
class HttpDrugRepository(private val client: HttpClient) : DrugRepository {

    override suspend fun catalog(): List<Drug> = client.get("api/drugs").body()

    override fun lookupStream(query: String): Flow<DrugLookupEvent> =
        MutableStateFlow<DrugLookupEvent>(
            // TODO(SSE): wire POST api/drugs/lookup/stream once a shared SSE client
            // exists. Degrade to NotFound so the view falls back to manual entry
            // rather than showing a hard error for an un-wired transport.
            DrugLookupEvent.NotFound("AI drug lookup is not available on iOS yet"),
        )
}

/** Online-first reminder-settings doc; decodes 1:1 into the shared [ReminderSettings]. */
class HttpReminderSettingsRepository(private val client: HttpClient) : ReminderSettingsRepository {

    private var cached: ReminderSettings? = null

    override suspend fun get(): ReminderSettings {
        val settings: ReminderSettings = client.get("api/me/medications/reminder-settings").body()
        cached = settings
        return settings
    }

    override suspend fun getCached(): ReminderSettings =
        // No persisted cache on the online-first impl; serve the last in-memory read,
        // else defaults (the VMs seed from this then revalidate via get()).
        cached ?: ReminderSettings()

    override suspend fun set(settings: ReminderSettings) {
        val stored: ReminderSettings =
            client.put("api/me/medications/reminder-settings") { setBody(settings) }.body()
        cached = stored
    }
}

// --- shared wire envelope + request DTOs -------------------------------------

/** The `{ data, lastUpdate }` write envelope the medications controllers return. */
@Serializable
private data class WriteResultWire<T>(val data: T, val lastUpdate: String? = null)

/** FLAT detail response: all [Medication] fields inline + a history array. */
@Serializable
private data class MedicationDetailWire(
    val medicationId: String,
    val drugId: String? = null,
    val drug: Drug? = null,
    val customName: String? = null,
    val status: MedicationStatus,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfig,
    val timeSlots: List<TimeSlot> = emptyList(),
    val protocolId: String? = null,
    val notes: String? = null,
    val prescribedBy: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val discontinueReason: DiscontinueReason? = null,
    val discontinueNotes: String? = null,
    val correlatedMarkers: List<String> = emptyList(),
    val dosagePeriods: List<com.gte619n.healthfitness.shared.domain.medications.DosagePeriod> = emptyList(),
    val adherence: com.gte619n.healthfitness.shared.domain.medications.AdherenceSummary? = null,
    val history: List<MedicationHistoryEntry> = emptyList(),
) {
    fun toDomain() = MedicationDetail(
        medication = Medication(
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
            adherence = adherence,
        ),
        history = history,
    )
}

@Serializable
private data class CreateWire(
    val drugId: String?,
    val customName: String?,
    val dose: Double,
    val unit: String,
    val frequency: FrequencyConfig,
    val timeSlots: List<TimeSlot>,
    val notes: String?,
    val prescribedBy: String?,
    val correlatedMarkers: List<String>,
)

@Serializable
private data class UpdateWire(
    val customName: String?,
    val dose: Double?,
    val unit: String?,
    val frequency: FrequencyConfig?,
    val timeSlots: List<TimeSlot>?,
    val notes: String?,
    val prescribedBy: String?,
    val correlatedMarkers: List<String>?,
    val startDate: String?,
    val changeNotes: String?,
)

@Serializable
private data class ChangeDoseWire(
    val dose: Double,
    val unit: String?,
    val startDate: String?,
    val changeNotes: String?,
)

@Serializable
private data class DiscontinueWire(val reason: String, val notes: String?, val endDate: String?)

@Serializable
private data class ReactivateWire(val resumeDate: String?)

@Serializable
private data class LogDoseWire(
    val date: String,
    val window: String,
    val dose: Double? = null,
    val missed: Boolean = false,
)
