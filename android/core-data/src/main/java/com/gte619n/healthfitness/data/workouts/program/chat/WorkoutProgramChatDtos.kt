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
 * [programId] (IMPL-18b) also rides on the first turn to bind the thread to an
 * active program for in-place editing; null in the design-a-new-program flow.
 */
data class ProgramChatRequest(
    val threadId: String?,
    val message: String,
    val schedule: ScheduleDto?,
    val goalId: String?,
    val programId: String? = null,
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

// SSE token/error/done payloads consumed by WorkoutProgramChatClient.dispatch.
// They live here in the data layer ON PURPOSE: R8 full-mode keeps `data.**`
// (app/proguard-rules.pro) but not the `feature.**` module packages. These are
// parsed reflectively via moshi.adapter(...), so a private copy inside the
// feature module would have its constructor stripped by R8 and the designer chat
// would silently degrade in release builds (every token -> "", done -> no
// threadId). Keeping them in a kept package removes that risk by construction.
/** The `token` SSE event `data`: an assistant text delta. */
data class ChatTokenData(val text: String?)

/** The `error` SSE event `data`: a server-side error message. */
data class ChatErrorData(val error: String?)

/** The `done` SSE event `data`: the (possibly newly-created) threadId. */
data class ChatDoneData(val threadId: String?)

/** The `proposal` SSE event `data`: `{ program: <deep>, issues: [], warnings: [] }`. */
data class ProgramProposalDto(
    val program: WorkoutProgramDeepDto? = null,
    val issues: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Mirrors backend ThreadResponse (only the fields the client surfaces). */
data class ProgramChatThreadResponse(
    val threadId: String,
    val title: String? = null,
    val schedule: ScheduleDto? = null,
    val goalId: String? = null,
    val programId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Mirrors backend MessageResponse — a persisted turn in the thread. [proposalJson]
 * is the same `{ program, issues, warnings }` payload the SSE `proposal` event
 * carries, so the same adapter parses it when rehydrating a reopened thread.
 */
data class ProgramChatMessageResponse(
    val messageId: String,
    val role: String? = null,
    val content: String? = null,
    val proposalJson: String? = null,
    val createdAt: String? = null,
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
