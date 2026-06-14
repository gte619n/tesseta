package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.data.workouts.program.LoggedSetDto
import java.time.Instant

// ADR-0012 / IMPL-17 D2 — the wire contract for the idempotent completion
// upsert: PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}.
// Repeat PUTs replace actuals and re-run the backend fan-out, so an outbox
// retry of the same body is always safe. Encoded by the base Moshi
// (KotlinJsonAdapterFactory + the java.time adapters from NetworkModule).

/**
 * The completion upsert body. `completedAt` + `durationSeconds` are required
 * for COMPLETED; SKIPPED sends neither and clears actuals server-side (D4).
 */
data class CompleteSessionRequest(
    /** `COMPLETED` or `SKIPPED` ([ScheduledStatus] name). */
    val status: String,
    val completedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val logged: List<LoggedPrescriptionDto> = emptyList(),
)

/**
 * The performed sets for one prescription. Prescriptions have no id, so they
 * key by `(blockId, orderIndex)` against the session snapshot; an unknown key
 * is a backend 400 (nothing silently dropped).
 */
data class LoggedPrescriptionDto(
    val blockId: String,
    val orderIndex: Int = 0,
    val sets: List<LoggedSetDto> = emptyList(),
)
