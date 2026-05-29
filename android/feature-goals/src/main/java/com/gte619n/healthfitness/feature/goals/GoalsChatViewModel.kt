package com.gte619n.healthfitness.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.core.chat.ChatMessage
import com.gte619n.healthfitness.core.chat.ChatScope
import com.gte619n.healthfitness.core.chat.ChatSseClient
import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.goals.ChatRepository
import com.gte619n.healthfitness.data.goals.ChatThreadResponse
import com.gte619n.healthfitness.data.goals.CommitResult
import com.gte619n.healthfitness.domain.goals.GoalProposal
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The one chat scope in v1 (assumption 16). */
val GoalChatScope = ChatScope(
    basePath = "api/me/goals/chat",
    suggestedPrompts = listOf(
        "Help me build a plan to get my ApoB into optimal range",
        "Plan a 12-week strength base",
        "I want to improve my sleep score — design a roadmap",
    ),
)

data class GoalsChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val threadId: String? = null,
    val error: String? = null,
    /** Editor state per assistant message id that carries a proposal. */
    val editors: Map<String, ProposalEdit> = emptyMap(),
    /** Message ids currently committing. */
    val savingMessageIds: Set<String> = emptySet(),
    /** message id -> created goalId, once committed (collapses the card). */
    val committedGoalIds: Map<String, String> = emptyMap(),
    /** All threads for this user, loaded on init and refreshed after delete. */
    val threads: List<ChatThreadResponse> = emptyList(),
    /** Thread ids currently being deleted (shows progress / disables trash). */
    val deletingThreadIds: Set<String> = emptySet(),
)

@HiltViewModel
class GoalsChatViewModel @Inject constructor(
    private val sseClient: ChatSseClient,
    private val chatRepository: ChatRepository,
    moshi: Moshi,
) : ViewModel() {

    private val proposalAdapter = moshi.adapter(GoalProposal::class.java)

    private val _state = MutableStateFlow(GoalsChatUiState())
    val state: StateFlow<GoalsChatUiState> = _state.asStateFlow()

    init {
        refreshThreads()
    }

    fun send(message: String) {
        if (_state.value.streaming) return
        val userMsg = ChatMessage.User(id = UUID.randomUUID().toString(), text = message)
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage.Assistant(id = assistantId, streaming = true)
        _state.update {
            it.copy(
                messages = it.messages + userMsg + assistantMsg,
                streaming = true,
                error = null,
            )
        }

        viewModelScope.launch {
            sseClient.stream(GoalChatScope.basePath, _state.value.threadId, message)
                .catch { e ->
                    finishStream(assistantId)
                    _state.update { it.copy(error = e.message ?: "Chat failed") }
                }
                .collect { event -> handleEvent(assistantId, event) }
            // Flow completed without a terminal Done (e.g. server closed): ensure
            // the streaming flag clears.
            finishStream(assistantId)
        }
    }

    private fun handleEvent(assistantId: String, event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Token -> updateAssistant(assistantId) { it.copy(text = it.text + event.text) }
            is ChatStreamEvent.Proposal -> {
                val proposal = runCatching { proposalAdapter.fromJson(event.json) }.getOrNull()
                if (proposal != null) {
                    updateAssistant(assistantId) {
                        it.copy(toolResult = proposal, toolResultId = assistantId)
                    }
                    _state.update {
                        it.copy(editors = it.editors + (assistantId to ProposalEdit.from(proposal)))
                    }
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

    private fun updateAssistant(id: String, transform: (ChatMessage.Assistant) -> ChatMessage.Assistant) {
        _state.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m is ChatMessage.Assistant && m.id == id) transform(m) else m
            })
        }
    }

    /** Editor state for an assistant message's proposal card. */
    fun editorFor(messageId: String): ProposalEdit? = _state.value.editors[messageId]

    fun commit(messageId: String) {
        val threadId = _state.value.threadId ?: run {
            _state.update { it.copy(error = "No active thread to commit to") }
            return
        }
        val editor = _state.value.editors[messageId] ?: return
        _state.update { it.copy(savingMessageIds = it.savingMessageIds + messageId, error = null) }
        viewModelScope.launch {
            try {
                when (val result = chatRepository.commit(threadId, editor.toProposal())) {
                    is CommitResult.Created -> _state.update {
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            committedGoalIds = it.committedGoalIds + (messageId to result.goalId),
                        )
                    }
                    is CommitResult.Invalid -> _state.update {
                        // Re-seed the editor with the re-flagged proposal so the
                        // offending fields show inline errors (not dropped).
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            editors = it.editors + (messageId to ProposalEdit.from(result.flagged)),
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
                editors = it.editors - messageId,
                messages = it.messages.map { m ->
                    if (m is ChatMessage.Assistant && m.id == messageId) {
                        m.copy(toolResult = null, toolResultId = null)
                    } else {
                        m
                    }
                },
            )
        }
    }

    /** Fetch/refresh the thread list. Called on init and after a successful delete. */
    private fun refreshThreads() {
        viewModelScope.launch {
            try {
                val threads = chatRepository.listThreads()
                _state.update { it.copy(threads = threads) }
            } catch (e: Exception) {
                // Non-fatal: thread list is decorative on the chat screen.
                // Error already surfaced on first load via the main error channel
                // if needed; swallow here to avoid overwriting chat errors.
            }
        }
    }

    /**
     * Delete a thread by id. While the call is in-flight the thread id is added
     * to [GoalsChatUiState.deletingThreadIds] so the UI can disable the action.
     * If the deleted thread is the active one, the screen resets to a fresh
     * (empty) conversation.
     */
    fun deleteThread(threadId: String) {
        if (threadId in _state.value.deletingThreadIds) return
        _state.update { it.copy(deletingThreadIds = it.deletingThreadIds + threadId, error = null) }
        viewModelScope.launch {
            try {
                chatRepository.deleteThread(threadId)
                // If the deleted thread was active, reset to a new conversation.
                val wasActive = _state.value.threadId == threadId
                _state.update { s ->
                    s.copy(
                        deletingThreadIds = s.deletingThreadIds - threadId,
                        threads = s.threads.filterNot { it.threadId == threadId },
                        threadId = if (wasActive) null else s.threadId,
                        messages = if (wasActive) emptyList() else s.messages,
                        editors = if (wasActive) emptyMap() else s.editors,
                        savingMessageIds = if (wasActive) emptySet() else s.savingMessageIds,
                        committedGoalIds = if (wasActive) emptyMap() else s.committedGoalIds,
                    )
                }
                // Re-fetch authoritative list from the server.
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
}
