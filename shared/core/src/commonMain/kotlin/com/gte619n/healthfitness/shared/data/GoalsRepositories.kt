package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.goals.Comparator
import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalDeep
import com.gte619n.healthfitness.shared.domain.goals.GoalDomain
import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import com.gte619n.healthfitness.shared.domain.goals.StepKind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave E3 (Goals) — repository + SSE-client interfaces the
 * shared Goals ViewModels observe. KMP ports of the Android
 * `data.goals.GoalsRepository` / `data.goals.ChatRepository` +
 * `core-chat.ChatSseClient` contracts.
 *
 * Same shape as [MedicationRepository] / [NutritionRepository]: each repo
 * exposes reactive `observe*()` over the offline-first mirror plus best-effort
 * mutators. The CONCRETE implementations (Room-KMP DAO reads + Ktor writes
 * through the outbox for the JSON half, and a Ktor SSE stream for the chat
 * half) are the remaining Phase 1C body — the ViewModels + SwiftUI views depend
 * only on these interfaces, so the Goals vertical is authored and unit-tested
 * (with fakes) before the concrete store/SSE client land.
 */

interface GoalsRepository {
    /** Reactive list of goals, optionally filtered by [status]. Offline-first. */
    fun observeGoals(status: GoalStatus? = null): Flow<List<Goal>>

    /** Reactive deep goal (phases + steps); null until first fill. */
    fun observeGoalDeep(goalId: String): Flow<GoalDeep?>

    /** Best-effort list revalidation; never flips the UI back to Loading. */
    suspend fun refresh()

    /**
     * Toggle a step done/undone through the PATCH intent. Returns the refreshed
     * deep goal so the roadmap VM can optimistically swap in the authoritative
     * server shape (recomputed phase status, etc.).
     */
    suspend fun setStepDone(goalId: String, phaseId: String, stepId: String, done: Boolean): GoalDeep

    /** Reset a manually-overridden step back to metric-auto evaluation. */
    suspend fun resetStepToAuto(goalId: String, phaseId: String, stepId: String): GoalDeep
}

// --- Chat proposal (AI-drafted goal) ---------------------------------------
//
// Port of the Android core-domain `domain.goals.GoalProposal` (D3:
// Moshi→kotlinx.serialization). Streamed as the SSE `proposal` event's JSON and
// POSTed back (user-edited) to the commit endpoint. Each level carries an
// optional `validationError` so the editable card can flag offending fields
// inline rather than dropping them. Kept in the data layer (co-located with the
// chat repo/commit types that consume it) rather than in the reference
// `domain/goals/Goals.kt`, which this vertical does not touch. Dates are ISO
// strings, parsed in the UI, exactly as the shared Goals domain keeps them.

@Serializable
data class GoalProposal(
    val title: String? = null,
    val description: String? = null,
    val domain: GoalDomain? = null,
    val targetDate: String? = null,
    val phases: List<ProposalPhase> = emptyList(),
    val validationError: String? = null,
)

@Serializable
data class ProposalPhase(
    val title: String? = null,
    val description: String? = null,
    val targetStartDate: String? = null,
    val targetEndDate: String? = null,
    val steps: List<ProposalStep> = emptyList(),
    val validationError: String? = null,
)

@Serializable
data class ProposalStep(
    val title: String? = null,
    val kind: StepKind = StepKind.MANUAL,
    val metric: ProposalMetric? = null,
    val validationError: String? = null,
)

@Serializable
data class ProposalMetric(
    val metricKey: String? = null,
    val comparator: Comparator? = null,
    val targetValue: Double? = null,
    val windowDays: Int? = null,
    val countFrom: String? = null,
    val validationError: String? = null,
)

/** Outcome of a chat proposal commit: the new goalId, or the re-flagged proposal. */
sealed interface CommitResult {
    data class Created(val goalId: String) : CommitResult

    /** 400: backend re-validated and returned the proposal with inline field errors. */
    data class Invalid(val flagged: GoalProposal) : CommitResult
}

/** Mirrors the backend `api/goals/dto/ChatThreadResponse`. */
data class ChatThreadResponse(
    val threadId: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * JSON half of the chat surface (commit + thread list). Port of Android
 * `data.goals.ChatRepository`. The SSE stream itself is [SseClient], not here.
 */
interface ChatRepository {
    suspend fun commit(threadId: String, proposal: GoalProposal): CommitResult
    suspend fun listThreads(): List<ChatThreadResponse>
    suspend fun deleteThread(threadId: String)
}

// --- Server-Sent Events stream --------------------------------------------

/**
 * One event parsed off the backend chat SSE wire. Mirrors the Android
 * `core.chat.ChatStreamEvent` and the GoalChatController event names
 * (`token`, `proposal`, `error`, `done`).
 */
sealed interface ChatStreamEvent {
    /** An assistant text delta. */
    data class Token(val text: String) : ChatStreamEvent

    /** Raw JSON of the validated proposal (the `proposal` event `data`). */
    data class Proposal(val json: String) : ChatStreamEvent

    /** A server-side error message. */
    data class Error(val message: String) : ChatStreamEvent

    /** Terminal event; carries the (possibly newly-created) threadId. */
    data class Done(val threadId: String?) : ChatStreamEvent
}

/**
 * Streams the backend SSE chat endpoint as a cold [Flow] of [ChatStreamEvent].
 *
 * Port of Android `core.chat.ChatSseClient`. Kept an INTERFACE in commonMain
 * (unlike the OkHttp-backed Android class) because the concrete SSE reader is a
 * Ktor-client engine detail: the Phase 1C impl POSTs `{threadId?, message}`
 * with `Accept: text/event-stream` through the shared authenticated Ktor
 * `HttpClient`, then drives Ktor's `bodyAsChannel()` line reader — accumulating
 * `event:`/`data:` lines and emitting one [ChatStreamEvent] per blank line, per
 * the SSE wire format (no third-party SSE library, exactly as Android does with
 * OkHttp's `charStream()`). Declaring only the interface here lets the chat VM
 * + its message-accumulation logic be authored and tested against a scripted
 * fake stream before that engine code exists.
 */
interface SseClient {
    /**
     * POST [basePath] with `{threadId?, message}` and stream events. Cancelling
     * the collector cancels the underlying read loop.
     */
    fun stream(basePath: String, threadId: String?, message: String): Flow<ChatStreamEvent>
}
