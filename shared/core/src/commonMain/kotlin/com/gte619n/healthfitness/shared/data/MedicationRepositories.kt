package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.shared.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.shared.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.shared.domain.medications.Drug
import com.gte619n.healthfitness.shared.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.medications.MedicationDetail
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import com.gte619n.healthfitness.shared.domain.medications.TodaysDose
import com.gte619n.healthfitness.shared.domain.medications.UpdateMedicationRequest
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-IOS-01 Phase 3 Wave B — the write-path + supporting repository contracts
 * the medications feature ViewModels depend on. SIBLING to [MedicationRepository]
 * (declared in Repositories.kt, the reference) so the reference file is not
 * rewritten (Wave B ground rule); the reference's read-only
 * `observe()/refresh()/markTaken()/markMissed()` shape is extended here with the
 * CRUD + today's-doses + adherence + reminder-settings + drug-lookup surface that
 * Android's `data.medications.*Repository` / `data.reminders.*Repository`
 * contracts expose.
 *
 * These are the KMP ports of:
 *  - android/core-data/.../data/medications/MedicationRepository (CRUD + today)
 *  - android/core-data/.../data/medications/AdherenceRepository (dose log/undo)
 *  - android/core-data/.../data/medications/DrugRepository (catalog + AI lookup)
 *  - android/core-data/.../data/reminders/ReminderSettingsRepository
 *
 * The CONCRETE implementations (Room-KMP reads + Ktor writes through the outbox)
 * are the remaining Phase 1C body; Phase 3 VMs + SwiftUI depend only on these
 * interfaces, so the vertical is authored and unit-tested against fakes first.
 */

/** CRUD + today's-doses projection, ported from Android `MedicationRepository`. */
interface MedicationCrudRepository {
    /** Reactive Room-mirror read of the current dose checklist (offline-first). */
    fun observeTodaysDoses(): Flow<List<TodaysDose>>
    /** Best-effort network revalidation of the today projection; never resets UI. */
    suspend fun refreshTodaysDoses()
    /** The last cached today projection (no network) — used by the reminder engine. */
    suspend fun cachedTodaysDoses(): List<TodaysDose>

    /** Active/discontinued list snapshot (network with cached fallback). */
    suspend fun list(status: MedicationStatus): List<Medication>

    suspend fun create(request: CreateMedicationRequest): Medication
    suspend fun get(medicationId: String): MedicationDetail
    /** Room-mirror detail with no network — instant seed on cold open (D9). */
    suspend fun cachedDetail(medicationId: String): MedicationDetail
    suspend fun update(medicationId: String, request: UpdateMedicationRequest)
    suspend fun changeDose(medicationId: String, request: ChangeDoseRequest)
    suspend fun discontinue(
        medicationId: String,
        reason: DiscontinueReason,
        notes: String?,
        endDate: LocalDate,
    )
    suspend fun reactivate(medicationId: String, resumeDate: LocalDate?)
    suspend fun delete(medicationId: String)
}

/** Offline-first dose adherence log, ported from Android `AdherenceRepository`. */
interface AdherenceRepository {
    suspend fun logDose(medicationId: String, window: TimeWindow)
    suspend fun undoDose(medicationId: String, date: LocalDate, window: TimeWindow)
    suspend fun markMissed(medicationId: String, date: LocalDate, window: TimeWindow, dose: Double)
    /** `(med, window)` pairs already taken on [date]. */
    suspend fun takenWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>>
    /** `(med, window)` pairs with any recorded outcome (taken or missed) on [date]. */
    suspend fun recordedWindowsFor(date: LocalDate): Set<Pair<String, TimeWindow>>
}

/** Drug catalog + AI SSE lookup, ported from Android `DrugRepository`. */
interface DrugRepository {
    /** The locally-cached drug catalog for offline search. */
    suspend fun catalog(): List<Drug>
    /** Online-only AI lookup stream (SSE) for a query with no catalog match. */
    fun lookupStream(query: String): Flow<DrugLookupEvent>
}

/** Reminder-settings doc, ported from Android `ReminderSettingsRepository`. */
interface ReminderSettingsRepository {
    /** Network read with cached fallback. */
    suspend fun get(): ReminderSettings
    /** Cached-only read (no network) — used on cold entry + by the reminder engine. */
    suspend fun getCached(): ReminderSettings
    suspend fun set(settings: ReminderSettings)
}
