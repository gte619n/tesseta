package com.gte619n.healthfitness.data.goals

import com.gte619n.healthfitness.domain.goals.GoalProposal
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a commit attempt: either the new goalId, or the re-flagged proposal. */
sealed interface CommitResult {
    data class Created(val goalId: String) : CommitResult

    /** 400: the backend re-validated and returned the proposal with inline errors. */
    data class Invalid(val flagged: GoalProposal) : CommitResult
}

/**
 * JSON half of the chat surface (commit + thread list). The SSE stream lives
 * in core-chat's ChatSseClient. A 400 from commit carries the flagged
 * GoalProposal in the error body, which Retrofit doesn't decode for us, so we
 * parse it with Moshi here.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ChatApi,
    private val moshi: Moshi,
) {
    suspend fun commit(threadId: String, proposal: GoalProposal): CommitResult {
        val response = api.commit(threadId, proposal)
        if (response.isSuccessful) {
            val goalId = response.body()?.goalId
                ?: throw IllegalStateException("Commit succeeded but returned no goalId")
            return CommitResult.Created(goalId)
        }
        if (response.code() == 400) {
            val raw = response.errorBody()?.string()
            val flagged = raw?.let {
                runCatching { moshi.adapter(GoalProposal::class.java).fromJson(it) }.getOrNull()
            }
            if (flagged != null) return CommitResult.Invalid(flagged)
        }
        throw IllegalStateException("Commit failed: HTTP ${response.code()}")
    }

    suspend fun listThreads(): List<ChatThreadResponse> = api.listThreads()

    /**
     * Delete a thread. Throws [IllegalStateException] on HTTP errors other
     * than 404 (which is silently ignored — already gone is success enough).
     */
    suspend fun deleteThread(threadId: String) {
        val response = api.deleteThread(threadId)
        if (!response.isSuccessful && response.code() != 404) {
            throw IllegalStateException("Delete thread failed: HTTP ${response.code()}")
        }
    }
}
