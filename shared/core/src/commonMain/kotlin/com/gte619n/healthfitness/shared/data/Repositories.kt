package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.medications.Medication
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-IOS-01 Phase 1C/1D — repository interfaces the shared ViewModels observe.
 * These are the KMP ports of the Android `data.*Repository` contracts: each
 * exposes a reactive `observe*()` over the Room mirror (offline-first — emits
 * the last-synced data instantly, updates as optimistic writes + sync deltas
 * land) plus a best-effort `refresh()` (network revalidation that never flips
 * the UI back to Loading).
 *
 * The CONCRETE implementations (Room-KMP DAO reads + Ktor writes through the
 * outbox) are the remaining Phase 1C body; the ViewModels and SwiftUI views in
 * Phase 3 depend only on these interfaces, so a feature vertical can be authored
 * and unit-tested (with a fake repo) before the concrete store lands.
 */

interface MedicationRepository {
    fun observe(): Flow<List<Medication>>
    suspend fun refresh()
    suspend fun markTaken(doseId: String)
    suspend fun markMissed(doseId: String, reason: String?)
}

interface NutritionRepository {
    fun observeDay(date: String): Flow<NutritionDay>
    suspend fun refresh(date: String)
}

// Additional repositories (workouts, blood, bodycomposition, goals, profile,
// dashboard) follow this exact shape; each Phase 3 feature agent adds the
// interface it needs here (or in a sibling file in this package).
