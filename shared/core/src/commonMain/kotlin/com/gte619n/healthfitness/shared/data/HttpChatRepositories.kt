package com.gte619n.healthfitness.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 — the JSON halves of the two SSE chat surfaces (Goals coach +
 * Workout designer). The streaming halves are [KtorSseClient]; these repos own
 * the non-streamed commit + thread list/messages/delete calls, over the SAME
 * backend endpoints Android's `data.goals.ChatApi` / `WorkoutProgramChatApi` hit.
 *
 * Ports of Android `data.goals.ChatRepository` / `data.workouts.program.chat.
 * WorkoutProgramChatRepository`. The shared Ktor client runs `expectSuccess=true`,
 * so a validation failure (goals 400 / designer 422) throws a
 * [ClientRequestException] whose response body carries the re-flagged structure;
 * we catch it, inspect the status, and decode the body — exactly as Android reads
 * the Retrofit error body.
 */

private val ChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

// --- Goals coach chat ------------------------------------------------------

class HttpChatRepository(
    private val client: HttpClient,
) : ChatRepository {

    override suspend fun commit(threadId: String, proposal: GoalProposal): CommitResult {
        return try {
            val response: CommitResponseWire =
                client.post("api/me/goals/chat/$threadId/commit") {
                    setBody(proposal)
                }.body()
            val goalId = response.goalId
                ?: error("Commit succeeded but returned no goalId")
            CommitResult.Created(goalId)
        } catch (e: ClientRequestException) {
            // 400: the backend re-validated and returned the flagged proposal.
            if (e.response.status == HttpStatusCode.BadRequest) {
                val raw = e.response.bodyAsText()
                val flagged = runCatching {
                    ChatJson.decodeFromString(GoalProposal.serializer(), raw)
                }.getOrNull()
                if (flagged != null) return CommitResult.Invalid(flagged)
            }
            throw e
        }
    }

    override suspend fun listThreads(): List<ChatThreadResponse> {
        val wire: List<ChatThreadWire> = client.get("api/me/goals/chat/threads").body()
        return wire.map {
            ChatThreadResponse(
                threadId = it.threadId,
                title = it.title.orEmpty(),
                createdAt = it.createdAt.orEmpty(),
                updatedAt = it.updatedAt.orEmpty(),
            )
        }
    }

    override suspend fun deleteThread(threadId: String) {
        try {
            client.delete("api/me/goals/chat/threads/$threadId")
        } catch (e: ClientRequestException) {
            // Already gone is success enough.
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    @Serializable
    private data class CommitResponseWire(val goalId: String? = null)

    @Serializable
    private data class ChatThreadWire(
        val threadId: String,
        val title: String? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
    )
}

// --- Workout designer chat -------------------------------------------------

class HttpWorkoutProgramChatRepository(
    private val client: HttpClient,
) : WorkoutProgramChatRepository {

    override suspend fun commit(
        threadId: String,
        program: ProgramProposal,
        schedule: ScheduleDto,
        goalId: String?,
    ): ProgramCommitResult {
        // The commit endpoint deserializes the FULL deep CreateProgramRequest
        // (exerciseId / orderIndex / block types per prescription). The shared
        // [ProgramProposal] is the display-only SUBSET streamed on the SSE
        // `proposal` event (exerciseName, no exerciseId), so it can't reconstruct
        // the committable program faithfully. We send the best-effort shape; the
        // backend validator returns its actionable 422 issues when a prescription
        // lacks a resolvable exercise. See the designer deferral note in
        // IMPL-IOS-01-OFFLINE-SYNC §"Decisions for review".
        val request = buildCreateRequest(program, schedule, goalId)
        return try {
            val response: CreateProgramResponseWire =
                client.post("api/me/workout-programs/chat/$threadId/commit") {
                    setBody(request)
                }.body()
            val programId = response.programId
                ?: error("Commit succeeded but returned no programId")
            ProgramCommitResult.Created(programId)
        } catch (e: ClientRequestException) {
            // 422: the backend re-validated and returned `{issues: []}`.
            if (e.response.status == HttpStatusCode.UnprocessableEntity) {
                val raw = e.response.bodyAsText()
                val issues = runCatching {
                    ChatJson.decodeFromString(IssuesWire.serializer(), raw).issues
                }.getOrNull()
                if (issues != null) return ProgramCommitResult.Invalid(issues)
            }
            throw e
        }
    }

    override suspend fun listThreads(): List<ProgramChatThreadResponse> {
        val wire: List<ProgramThreadWire> =
            client.get("api/me/workout-programs/chat/threads").body()
        return wire.map {
            ProgramChatThreadResponse(
                threadId = it.threadId,
                title = it.title.orEmpty(),
                createdAt = it.createdAt.orEmpty(),
                updatedAt = it.updatedAt.orEmpty(),
            )
        }
    }

    override suspend fun listMessages(threadId: String): List<ProgramChatMessage> {
        val wire: List<ProgramMessageWire> =
            client.get("api/me/workout-programs/chat/$threadId").body()
        return wire.map {
            ProgramChatMessage(
                messageId = it.messageId,
                role = it.role.orEmpty(),
                content = it.content,
                proposalJson = it.proposalJson,
            )
        }
    }

    override suspend fun deleteThread(threadId: String) {
        try {
            client.delete("api/me/workout-programs/chat/threads/$threadId")
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    /**
     * Best-effort map of the flat proposal → the deep CreateProgramRequest wire.
     * Synthesizes the structural indices the editor-card proposal omits
     * (orderIndex, dayOfWeek fallbacks, block type). The one field it CANNOT
     * supply is a resolvable `exerciseId` (the proposal carries only a name), so
     * it emits `exerciseName` for display parity; the backend validator decides.
     */
    private fun buildCreateRequest(
        program: ProgramProposal,
        schedule: ScheduleDto,
        goalId: String?,
    ): CreateProgramRequestWire = CreateProgramRequestWire(
        title = program.title,
        description = program.description,
        goalId = goalId,
        schedule = schedule,
        source = "AI_CHAT",
        phases = program.phases.mapIndexed { pi, phase ->
            CommitPhaseWire(
                title = phase.title,
                focus = phase.focus,
                orderIndex = pi,
                status = "UPCOMING",
                weeks = phase.weeks,
                days = phase.days.mapIndexed { di, day ->
                    CommitDayWire(
                        label = day.label,
                        dayOfWeek = (day.dayOfWeek ?: schedule.trainingDays.getOrNull(di))?.name,
                        orderIndex = di,
                        blocks = day.blocks.mapIndexed { bi, block ->
                            CommitBlockWire(
                                type = "MAIN",
                                title = block.title,
                                orderIndex = bi,
                                prescriptions = block.prescriptions.mapIndexed { ri, rx ->
                                    CommitPrescriptionWire(
                                        exerciseName = rx.exerciseName,
                                        orderIndex = ri,
                                        sets = rx.sets,
                                        repsMin = rx.repsMin,
                                        repsMax = rx.repsMax,
                                        durationSeconds = rx.durationSeconds,
                                        restSeconds = rx.restSeconds,
                                        notes = rx.notes,
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    @Serializable
    private data class CreateProgramResponseWire(val programId: String? = null)

    @Serializable
    private data class IssuesWire(val issues: List<String> = emptyList())

    @Serializable
    private data class ProgramThreadWire(
        val threadId: String,
        val title: String? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
    )

    @Serializable
    private data class ProgramMessageWire(
        val messageId: String,
        val role: String? = null,
        val content: String? = null,
        val proposalJson: String? = null,
    )

    // Deep commit wire (subset of the backend CreateProgramRequest the flat
    // proposal can populate).
    @Serializable
    private data class CreateProgramRequestWire(
        val title: String,
        val description: String? = null,
        val goalId: String? = null,
        val schedule: ScheduleDto,
        val source: String,
        val phases: List<CommitPhaseWire> = emptyList(),
    )

    @Serializable
    private data class CommitPhaseWire(
        val title: String,
        val focus: String? = null,
        val orderIndex: Int,
        val status: String,
        val weeks: Int? = null,
        val days: List<CommitDayWire> = emptyList(),
    )

    @Serializable
    private data class CommitDayWire(
        val label: String,
        val dayOfWeek: String? = null,
        val orderIndex: Int,
        val blocks: List<CommitBlockWire> = emptyList(),
    )

    @Serializable
    private data class CommitBlockWire(
        val type: String,
        val title: String,
        val orderIndex: Int,
        val prescriptions: List<CommitPrescriptionWire> = emptyList(),
    )

    @Serializable
    private data class CommitPrescriptionWire(
        val exerciseName: String,
        val orderIndex: Int,
        val sets: Int? = null,
        val repsMin: Int? = null,
        val repsMax: Int? = null,
        val durationSeconds: Int? = null,
        val restSeconds: Int? = null,
        val notes: String? = null,
    )
}
