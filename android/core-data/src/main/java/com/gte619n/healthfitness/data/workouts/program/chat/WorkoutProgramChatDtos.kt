package com.gte619n.healthfitness.data.workouts.program.chat

import com.gte619n.healthfitness.data.workouts.program.NutritionGuidanceDto
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramDeepDto
import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.LocalDate

// (DayOfWeek imported for ScheduleDto.of's typed convenience factory.)

// Wire shapes for the workout-program designer chat (IMPL-AND-18). The SSE half
// (token/proposal/error/done) is consumed by WorkoutProgramChatClient over the
// shared SseClient; commit + thread list/delete are plain Retrofit JSON.
// Names mirror the LOCKED backend contract (WorkoutProgramChatController).

/**
 * Body of `POST api/me/workout-programs/chat`. [schedule] + [goalId] ride only
 * on the FIRST message (threadId null); later turns send just threadId+message.
 */
data class ProgramChatRequest(
    val threadId: String?,
    val message: String,
    val schedule: ScheduleDto?,
    val goalId: String?,
)

/**
 * The pre-chat form selections: which weekdays + the gym for each. Days are
 * carried as UPPERCASE enum-name strings ("MON".."SUN") to match the LOCKED
 * contract exactly. (Using the domain [DayOfWeek] here would serialize lowercase
 * via the shared Moshi adapter; the backend's `trainingDays` list expects
 * uppercase, so we control the casing directly with plain strings.)
 */
data class ScheduleDto(
    val trainingDays: List<String>,
    val dayLocations: Map<String, String>,
) {
    companion object {
        fun of(trainingDays: List<DayOfWeek>, dayLocations: Map<DayOfWeek, String>) = ScheduleDto(
            trainingDays = trainingDays.map { it.name },
            dayLocations = dayLocations.mapKeys { it.key.name },
        )
    }
}

/** The `proposal` SSE event `data`: `{ program: <deep>, issues: [] }`. */
data class ProgramProposalDto(
    val program: WorkoutProgramDeepDto? = null,
    val issues: List<String> = emptyList(),
)

/** Mirrors backend ThreadResponse (only the fields the client surfaces). */
data class ProgramChatThreadResponse(
    val threadId: String,
    val title: String? = null,
    val schedule: ScheduleDto? = null,
    val goalId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ---- Commit (CreateProgramRequest) — the core domain shape the backend's
// ---- commit endpoint deserializes (phases: List<ProgramPhase> core records). ----

data class CreateProgramRequestDto(
    val title: String?,
    val description: String?,
    val goalId: String?,
    val schedule: ScheduleDto?,
    val startDate: LocalDate?,
    val source: String?,
    val phases: List<CommitPhaseDto>,
    val nutritionGuidance: NutritionGuidanceDto?,
)

data class CommitPhaseDto(
    val phaseId: String?,
    val title: String?,
    val focus: String?,
    val orderIndex: Int,
    val status: String?,
    val weeks: Int,
    val deloadWeekIndex: Int?,
    val targetStartDate: LocalDate?,
    val targetEndDate: LocalDate?,
    val days: List<CommitDayDto>,
    val nutritionGuidance: NutritionGuidanceDto?,
)

data class CommitDayDto(
    val dayId: String?,
    val label: String?,
    // UPPERCASE enum-name string ("MON".."SUN") — same casing rationale as
    // [ScheduleDto]; the backend's WorkoutDay.dayOfWeek expects the uppercase form.
    val dayOfWeek: String,
    val locationId: String?,
    val orderIndex: Int,
    val blocks: List<CommitBlockDto>,
)

data class CommitBlockDto(
    val blockId: String?,
    val type: String,
    val title: String?,
    val orderIndex: Int,
    val prescriptions: List<CommitPrescriptionDto>,
)

data class CommitPrescriptionDto(
    val exerciseId: String,
    val orderIndex: Int,
    val sets: Int?,
    val repsMin: Int?,
    val repsMax: Int?,
    val durationSeconds: Int?,
    val intensity: CommitIntensityDto?,
    val restSeconds: Int?,
    val tempo: String?,
    val notes: String?,
    val deloadModifier: CommitDeloadDto?,
    val targetWeightLbs: Double?,
    val loadBasis: String?,
)

data class CommitIntensityDto(val kind: String, val value: Double?)

data class CommitDeloadDto(val setsMultiplier: Double?, val intensityDelta: Double?)
