package com.gte619n.healthfitness.shared.presentation.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.ChatRepository
import com.gte619n.healthfitness.shared.data.ChatStreamEvent
import com.gte619n.healthfitness.shared.data.ChatThreadResponse
import com.gte619n.healthfitness.shared.data.CommitResult
import com.gte619n.healthfitness.shared.data.GoalProposal
import com.gte619n.healthfitness.shared.data.SseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 Phase 3 Wave E3 (Goals) — shared port of the Android
 * `feature-goals/.../GoalsChatViewModel` + the `core-chat` SSE-consumption
 * logic. This VM OWNS the message stream and the `send` intent: it appends the
 * user bubble + an empty streaming assistant bubble, opens the [SseClient]
 * stream, and folds each [ChatStreamEvent] into the growing assistant message
 * (token append, proposal attach, error, terminal done).
 *
 * The chat scope is fixed (v1 has one — goal chat). SKIE exposes [state] to
 * Swift; the SwiftUI view renders [ChatMessage.Assistant.text] as markdown via
 * iOS 26's native `AttributedString(markdown:)` and re-renders as tokens land.
 *
 * Parity note: the Android VM carries the same proposal-editor (`ProposalEdit`)
 * intermediate for inline field-validation on the commit card. Here the editor
 * is the [GoalProposal] itself (round-tripped through commit), keeping the
 * shared VM free of an Android-Compose-specific edit-state type; the SwiftUI
 * card edits the proposal fields directly.
 */

/** Backend path + empty-state prompts for the single v1 chat scope. */
object GoalChatScope {
    const val BASE_PATH: String = "api/me/goals/chat"
    val suggestedPrompts: List<String> = listOf(
        "Help me build a plan to get my ApoB into optimal range",
        "Plan a 12-week strength base",
        "I want to improve my sleep score — design a roadmap",
    )
}

/** A single message in the goal-chat thread. */
sealed interface ChatMessage {
    val id: String

    /** Right-aligned user bubble. */
    data class User(
        override val id: String,
        val text: String,
    ) : ChatMessage

    /**
     * Left-aligned assistant bubble. [text] renders as markdown and grows as
     * tokens stream in; [streaming] drives the typing indicator; [proposal] is
     * an attached AI-drafted goal rendered by the editable card slot.
     */
    data class Assistant(
        override val id: String,
        val text: String = "",
        val streaming: Boolean = false,
        val proposal: GoalProposal? = null,
    ) : ChatMessage
}

data class GoalsChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val threadId: String? = null,
    val error: String? = null,
    /** Message ids currently committing. */
    val savingMessageIds: Set<String> = emptySet(),
    /** message id -> created goalId, once committed (collapses the card). */
    val committedGoalIds: Map<String, String> = emptyMap(),
    /** All threads for this user, loaded on init and refreshed after delete. */
    val threads: List<ChatThreadResponse> = emptyList(),
    /** Thread ids currently being deleted. */
    val deletingThreadIds: Set<String> = emptySet(),
)

/**
 * Monotonic id source. Injected so tests get a deterministic sequence; the
 * platform passes a UUID generator (Android `UUID.randomUUID()`, iOS `UUID()`).
 */
fun interface IdGenerator {
    fun next(): String
}

class GoalsChatViewModel(
    private val sseClient: SseClient,
    private val chatRepository: ChatRepository,
    private val idGenerator: IdGenerator,
    private val json: Json = DefaultJson,
) : ViewModel() {

    private val _state = MutableStateFlow(GoalsChatUiState())
    val state: StateFlow<GoalsChatUiState> = _state.asStateFlow()

    init {
        refreshThreads()
    }

    fun send(message: String) {
        if (_state.value.streaming) return
        val userMsg = ChatMessage.User(id = idGenerator.next(), text = message)
        val assistantId = idGenerator.next()
        val assistantMsg = ChatMessage.Assistant(id = assistantId, streaming = true)
        _state.update {
            it.copy(
                messages = it.messages + userMsg + assistantMsg,
                streaming = true,
                error = null,
            )
        }

        viewModelScope.launch {
            sseClient.stream(GoalChatScope.BASE_PATH, _state.value.threadId, message)
                .catch { e ->
                    finishStream(assistantId)
                    _state.update { it.copy(error = e.message ?: "Chat failed") }
                }
                .collect { event -> handleEvent(assistantId, event) }
            // Flow completed without a terminal Done (e.g. server closed the
            // stream): make sure the streaming flag clears.
            finishStream(assistantId)
        }
    }

    private fun handleEvent(assistantId: String, event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Token ->
                updateAssistant(assistantId) { it.copy(text = it.text + event.text) }

            is ChatStreamEvent.Proposal -> {
                val proposal = runCatching {
                    json.decodeFromString(GoalProposal.serializer(), event.json)
                }.getOrNull()
                if (proposal != null) {
                    updateAssistant(assistantId) { it.copy(proposal = proposal) }
                }
            }

            is ChatStreamEvent.Error -> _state.update { it.copy(error = event.message) }

            is ChatStreamEvent.Done -> {
                _state.update { it.copy(threadId = event.threadId ?: it.threadId) }
                finishStream(assistantId)
            }
        }
    }

    private fun finishStream(assistantId: String) {
        updateAssistant(assistantId) { it.copy(streaming = false) }
        _state.update { it.copy(streaming = false) }
    }

    private fun updateAssistant(
        id: String,
        transform: (ChatMessage.Assistant) -> ChatMessage.Assistant,
    ) {
        _state.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m is ChatMessage.Assistant && m.id == id) transform(m) else m
                },
            )
        }
    }

    /** Commit a (user-edited) proposal card to a real goal. */
    fun commit(messageId: String, edited: GoalProposal) {
        val threadId = _state.value.threadId ?: run {
            _state.update { it.copy(error = "No active thread to commit to") }
            return
        }
        _state.update { it.copy(savingMessageIds = it.savingMessageIds + messageId, error = null) }
        viewModelScope.launch {
            try {
                when (val result = chatRepository.commit(threadId, edited)) {
                    is CommitResult.Created -> _state.update {
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            committedGoalIds = it.committedGoalIds + (messageId to result.goalId),
                        )
                    }
                    is CommitResult.Invalid -> _state.update {
                        // Re-seed the card with the re-flagged proposal so the
                        // offending fields show inline errors (not dropped).
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            messages = it.messages.map { m ->
                                if (m is ChatMessage.Assistant && m.id == messageId) {
                                    m.copy(proposal = result.flagged)
                                } else {
                                    m
                                }
                            },
                            error = "Some fields need fixing before saving.",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        savingMessageIds = it.savingMessageIds - messageId,
                        error = e.message ?: "Save failed",
                    )
                }
            }
        }
    }

    /** Discard a proposal card without committing. */
    fun discard(messageId: String) {
        _state.update {
            it.copy(
                messages = it.messages.map { m ->
                    if (m is ChatMessage.Assistant && m.id == messageId) m.copy(proposal = null) else m
                },
            )
        }
    }

    /** Fetch/refresh the thread list. Called on init and after a successful delete. */
    private fun refreshThreads() {
        viewModelScope.launch {
            runCatching { chatRepository.listThreads() }
                .onSuccess { threads -> _state.update { it.copy(threads = threads) } }
            // Non-fatal: the thread list is decorative; swallow to avoid
            // overwriting a live chat error.
        }
    }

    /**
     * Delete a thread by id; if it was the active one, reset to a fresh
     * (empty) conversation.
     */
    fun deleteThread(threadId: String) {
        if (threadId in _state.value.deletingThreadIds) return
        _state.update { it.copy(deletingThreadIds = it.deletingThreadIds + threadId, error = null) }
        viewModelScope.launch {
            try {
                chatRepository.deleteThread(threadId)
                val wasActive = _state.value.threadId == threadId
                _state.update { s ->
                    s.copy(
                        deletingThreadIds = s.deletingThreadIds - threadId,
                        threads = s.threads.filterNot { it.threadId == threadId },
                        threadId = if (wasActive) null else s.threadId,
                        messages = if (wasActive) emptyList() else s.messages,
                        savingMessageIds = if (wasActive) emptySet() else s.savingMessageIds,
                        committedGoalIds = if (wasActive) emptyMap() else s.committedGoalIds,
                    )
                }
                refreshThreads()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        deletingThreadIds = it.deletingThreadIds - threadId,
                        error = e.message ?: "Delete failed",
                    )
                }
            }
        }
    }

    private companion object {
        val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
