package com.gte619n.healthfitness.data.workouts.program.chat

import com.gte619n.healthfitness.data.workouts.program.toDto
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a workout-program commit: the created programId, or re-flagged issues. */
sealed interface ProgramCommitResult {
    data class Created(val programId: String) : ProgramCommitResult

    /** 422: the backend re-validated and returned `{ issues: [] }`. */
    data class Invalid(val issues: List<String>) : ProgramCommitResult
}

/**
 * JSON half of the workout-program designer chat (commit + thread list/delete).
 * The SSE stream lives in feature-workouts' WorkoutProgramChatClient. A 422 from
 * commit carries `{ issues: [] }` in the error body, which Retrofit doesn't
 * decode for us, so we parse it with Moshi here (mirrors the goals ChatRepository).
 */
@Singleton
class WorkoutProgramChatRepository @Inject constructor(
    private val api: WorkoutProgramChatApi,
    private val moshi: Moshi,
) {
    private val issuesAdapter = moshi.adapter<Map<String, List<String>>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Types.newParameterizedType(List::class.java, String::class.java),
        ),
    )

    /**
     * Commit a (user-edited) program. [program] is the editable proposal's domain
     * model; [schedule] + [goalId] come from the thread's form. On 201 returns the
     * created programId; on 422 returns the re-flagged validator issues.
     */
    suspend fun commit(
        threadId: String,
        program: WorkoutProgram,
        schedule: ScheduleDto,
        goalId: String?,
    ): ProgramCommitResult {
        val response = api.commit(threadId, program.toCreateRequest(schedule, goalId))
        if (response.isSuccessful) {
            val programId = response.body()?.programId
                ?: throw IllegalStateException("Commit succeeded but returned no programId")
            return ProgramCommitResult.Created(programId)
        }
        if (response.code() == 422) {
            val raw = response.errorBody()?.string()
            val issues = raw?.let {
                runCatching { issuesAdapter.fromJson(it)?.get("issues") }.getOrNull()
            }
            if (issues != null) return ProgramCommitResult.Invalid(issues)
        }
        throw IllegalStateException("Commit failed: HTTP ${response.code()}")
    }

    suspend fun listThreads(): List<ProgramChatThreadResponse> = api.listThreads()

    /** Persisted turns of a thread (oldest-first) for rehydrating a reopened chat. */
    suspend fun listMessages(threadId: String): List<ProgramChatMessageResponse> =
        api.messages(threadId)

    /** Delete a thread; ignores 404 (already gone is success enough). */
    suspend fun deleteThread(threadId: String) {
        val response = api.deleteThread(threadId)
        if (!response.isSuccessful && response.code() != 404) {
            throw IllegalStateException("Delete thread failed: HTTP ${response.code()}")
        }
    }
}

/**
 * Map an editable proposal (domain [WorkoutProgram], deep tree) into the
 * `CreateProgramRequest` wire shape the commit endpoint deserializes. Round-trips
 * the IMPL-18 additive fields (targetWeightLbs / loadBasis per prescription,
 * per-phase + program-level nutritionGuidance). [schedule] + [goalId] come from
 * the thread's fixed form.
 */
internal fun WorkoutProgram.toCreateRequest(
    schedule: ScheduleDto,
    goalId: String?,
): CreateProgramRequestDto = CreateProgramRequestDto(
    title = title,
    description = description,
    goalId = goalId ?: this.goalId,
    schedule = schedule,
    startDate = startDate,
    source = source.name,
    phases = phases.map { phase ->
        CommitPhaseDto(
            phaseId = phase.phaseId.takeIf { it.isNotBlank() },
            title = phase.title,
            focus = phase.focus,
            orderIndex = phase.orderIndex,
            status = phase.status.name,
            weeks = phase.weeks,
            deloadWeekIndex = phase.deloadWeekIndex,
            targetStartDate = phase.targetStartDate,
            targetEndDate = phase.targetEndDate,
            days = phase.days.map { day ->
                CommitDayDto(
                    dayId = day.dayId.takeIf { it.isNotBlank() },
                    label = day.label,
                    dayOfWeek = day.dayOfWeek.name,
                    locationId = day.locationId.takeIf { it.isNotBlank() },
                    orderIndex = day.orderIndex,
                    blocks = day.blocks.map { block ->
                        CommitBlockDto(
                            blockId = block.blockId.takeIf { it.isNotBlank() },
                            type = block.type.name,
                            title = block.title,
                            orderIndex = block.orderIndex,
                            prescriptions = block.prescriptions.map { rx ->
                                CommitPrescriptionDto(
                                    exerciseId = rx.exerciseId,
                                    orderIndex = rx.orderIndex,
                                    sets = rx.sets,
                                    repsMin = rx.repsMin,
                                    repsMax = rx.repsMax,
                                    durationSeconds = rx.durationSeconds,
                                    intensity = rx.intensity?.let {
                                        CommitIntensityDto(kind = it.kind.name, value = it.value)
                                    },
                                    restSeconds = rx.restSeconds,
                                    tempo = rx.tempo,
                                    notes = rx.notes,
                                    deloadModifier = rx.deloadModifier?.let {
                                        CommitDeloadDto(it.setsMultiplier, it.intensityDelta)
                                    },
                                    targetWeightLbs = rx.targetWeightLbs,
                                    loadBasis = rx.loadBasis,
                                )
                            },
                        )
                    },
                )
            },
            nutritionGuidance = phase.nutritionGuidance?.toDto(),
        )
    },
    nutritionGuidance = nutritionGuidance?.toDto(),
)
