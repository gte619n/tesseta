package com.gte619n.healthfitness.core.chat

import androidx.compose.runtime.Immutable

// Reusable chat domain for the shared chat surface (IMPL-12, assumption 16).
// The surface is parameterized by a thread scope + a tool-result renderer so
// later modules can reuse it; in v1 there is one scope (goal chat). The
// proposal payload is carried as an opaque [Any?] so this module stays free of
// the goals domain — feature-goals supplies both the parser (in the stream
// collector) and the renderer (a composable slot), keeping the chat generic.

/** A single message in the thread. */
sealed interface ChatMessage {
    val id: String

    /** Right-aligned user bubble. */
    data class User(
        override val id: String,
        val text: String,
    ) : ChatMessage

    /**
     * Left-aligned assistant bubble. [text] is rendered as markdown and grows
     * as tokens stream in. [streaming] is true while the SSE stream is open,
     * driving the typing indicator. [toolResult] is an opaque payload (e.g. a
     * parsed goal proposal) rendered by a caller-supplied slot; [toolResultId]
     * lets the slot key its editable state across recompositions.
     */
    data class Assistant(
        override val id: String,
        val text: String = "",
        val streaming: Boolean = false,
        val toolResult: Any? = null,
        val toolResultId: String? = null,
    ) : ChatMessage
}

/**
 * Events parsed from the backend SSE stream. Mirrors the GoalChatController
 * event names: `token`, `proposal`, `error`, `done`.
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
 * Scope of a chat thread. Parameterizes the SSE client + composables so the
 * surface is reusable. v1 has exactly one scope.
 */
@Immutable
data class ChatScope(
    /** Backend path under the base URL, e.g. "api/me/goals/chat". */
    val basePath: String,
    /** Empty-state suggested prompts shown before the first message. */
    val suggestedPrompts: List<String> = emptyList(),
)
