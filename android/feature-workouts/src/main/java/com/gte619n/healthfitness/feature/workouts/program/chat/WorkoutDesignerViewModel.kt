package com.gte619n.healthfitness.feature.workouts.program.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.core.chat.ChatMessage
import com.gte619n.healthfitness.core.chat.ChatScope
import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.data.net.Connectivity
import com.gte619n.healthfitness.data.workouts.LocationRepository
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramChatThreadResponse
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramCommitResult
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramProposalDto
import com.gte619n.healthfitness.data.workouts.program.chat.ScheduleDto
import com.gte619n.healthfitness.data.workouts.program.chat.WorkoutProgramChatRepository
import com.gte619n.healthfitness.data.workouts.program.toDomain
import com.gte619n.healthfitness.data.workouts.trt.TrtContextRepository
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.ProgramProposal
import com.gte619n.healthfitness.domain.workouts.trt.TrtContext
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

/** The workout-program designer chat scope (suggested empty-state prompts). */
val WorkoutDesignerChatScope = ChatScope(
    basePath = "api/me/workout-programs/chat",
    suggestedPrompts = listOf(
        "Design a 5-week ease-in program, 3 days then 4 days, I'm restarting TRT",
        "Build a 4-day upper/lower split grounded in my recent lifts",
        "Plan a deload-aware strength block around my home gym",
    ),
)

/** A gym option in the setup form. */
data class GymOption(val locationId: String, val name: String)

/** A goal option in the optional goal picker. */
data class GoalOption(val goalId: String, val title: String)

/** Setup form state: training days, a gym per day, and an optional goal link. */
data class DesignerSetupState(
    val gyms: List<GymOption> = emptyList(),
    val goals: List<GoalOption> = emptyList(),
    /** Selected training days. */
    val trainingDays: Set<DayOfWeek> = emptySet(),
    /** Per-day gym selection (only for selected days). */
    val dayLocations: Map<DayOfWeek, String> = emptyMap(),
    val goalId: String? = null,
    val loading: Boolean = true,
) {
    /** Valid once at least one day is chosen and every chosen day has a gym. */
    val isReady: Boolean
        get() = trainingDays.isNotEmpty() && trainingDays.all { dayLocations[it] != null }
}

data class WorkoutDesignerUiState(
    val setup: DesignerSetupState = DesignerSetupState(),
    /** True once the thread is opened (first send) — flips the form for the chat. */
    val started: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val threadId: String? = null,
    val error: String? = null,
    /** Editor state per assistant message id that carries a proposal. */
    val editors: Map<String, ProgramProposalEdit> = emptyMap(),
    /** Per-message validator issues to flag on the card (re-seeded on a 422 commit). */
    val proposalIssues: Map<String, List<String>> = emptyMap(),
    val savingMessageIds: Set<String> = emptySet(),
    /** message id -> created programId, once committed (collapses the card). */
    val committedProgramIds: Map<String, String> = emptyMap(),
    val threads: List<ProgramChatThreadResponse> = emptyList(),
    val deletingThreadIds: Set<String> = emptySet(),
    /** TRT/labs panel context (null until loaded; hidden unless [TrtContext.shouldShow]). */
    val trt: TrtContext? = null,
)

@HiltViewModel
class WorkoutDesignerViewModel @Inject constructor(
    private val chatClient: WorkoutProgramChatClient,
    private val chatRepository: WorkoutProgramChatRepository,
    private val locationRepository: LocationRepository,
    private val goalsRepository: GoalsRepository,
    private val trtRepository: TrtContextRepository,
    connectivity: Connectivity,
    moshi: Moshi,
) : ViewModel() {

    // Online-only SSE AI flow (D17): the screen disables the composer + shows
    // "needs connection" when offline.
    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    private val proposalAdapter = moshi.adapter(ProgramProposalDto::class.java)

    private val _state = MutableStateFlow(WorkoutDesignerUiState())
    val state: StateFlow<WorkoutDesignerUiState> = _state.asStateFlow()

    init {
        loadSetup()
        refreshThreads()
        loadTrt()
    }

    // --- Setup form ---

    private fun loadSetup() {
        viewModelScope.launch {
            val gyms = locationRepository.list().getOrNull().orEmpty()
                .filter { it.isActive }
                .map { GymOption(it.locationId, it.name) }
            val goals = runCatching { goalsRepository.goals() }.getOrNull().orEmpty()
                .map { GoalOption(it.goalId, it.title) }
            _state.update {
                it.copy(
                    setup = it.setup.copy(gyms = gyms, goals = goals, loading = false),
                )
            }
        }
    }

    private fun loadTrt() {
        viewModelScope.launch {
            trtRepository.fetch().onSuccess { ctx ->
                _state.update { it.copy(trt = ctx) }
            }
            // A TRT fetch failure is non-fatal — the panel just stays hidden.
        }
    }

    fun toggleTrainingDay(day: DayOfWeek) {
        _state.update { s ->
            val days = s.setup.trainingDays.toMutableSet()
            val locs = s.setup.dayLocations.toMutableMap()
            if (day in days) {
                days.remove(day)
                locs.remove(day)
            } else {
                days.add(day)
                // Default to the first gym (or single gym) so a day is valid by default.
                s.setup.gyms.firstOrNull()?.let { locs[day] = it.locationId }
            }
            s.copy(setup = s.setup.copy(trainingDays = days, dayLocations = locs))
        }
    }

    fun setDayGym(day: DayOfWeek, locationId: String) {
        _state.update { s ->
            s.copy(setup = s.setup.copy(dayLocations = s.setup.dayLocations + (day to locationId)))
        }
    }

    fun setGoal(goalId: String?) {
        _state.update { s -> s.copy(setup = s.setup.copy(goalId = goalId)) }
    }

    // --- Chat ---

    fun send(message: String) {
        val s = _state.value
        if (s.streaming) return
        // First send must have a ready schedule; later sends just need a thread.
        if (s.threadId == null && !s.setup.isReady) {
            _state.update { it.copy(error = "Pick your training days and a gym for each.") }
            return
        }
        val userMsg = ChatMessage.User(id = UUID.randomUUID().toString(), text = message)
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage.Assistant(id = assistantId, streaming = true)
        _state.update {
            it.copy(
                started = true,
                messages = it.messages + userMsg + assistantMsg,
                streaming = true,
                error = null,
            )
        }

        val threadId = s.threadId
        val schedule = if (threadId == null) {
            ScheduleDto.of(
                trainingDays = s.setup.trainingDays.toList(),
                dayLocations = s.setup.dayLocations,
            )
        } else {
            null
        }
        val goalId = if (threadId == null) s.setup.goalId else null

        viewModelScope.launch {
            chatClient.stream(threadId, message, schedule, goalId)
                .catch { e ->
                    finishStream(assistantId)
                    _state.update { it.copy(error = e.message ?: "Chat failed") }
                }
                .collect { event -> handleEvent(assistantId, event) }
            finishStream(assistantId)
        }
    }

    private fun handleEvent(assistantId: String, event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Token -> {
                if (event.text.isNotEmpty()) {
                    updateAssistant(assistantId) { it.copy(text = it.text + event.text) }
                }
            }
            is ChatStreamEvent.Proposal -> {
                val parsed = runCatching { proposalAdapter.fromJson(event.json) }.getOrNull()
                val deep = parsed?.program
                if (deep != null) {
                    val proposal = ProgramProposal(
                        program = deep.toDomain(),
                        issues = parsed.issues,
                    )
                    updateAssistant(assistantId) {
                        it.copy(toolResult = proposal, toolResultId = assistantId)
                    }
                    _state.update {
                        it.copy(
                            editors = it.editors + (assistantId to ProgramProposalEdit.from(proposal.program)),
                            proposalIssues = it.proposalIssues + (assistantId to proposal.issues),
                        )
                    }
                }
            }
            is ChatStreamEvent.Error -> _state.update { it.copy(error = event.message) }
            is ChatStreamEvent.Done -> {
                _state.update { it.copy(threadId = event.threadId ?: it.threadId) }
                finishStream(assistantId)
                refreshThreads()
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

    fun editorFor(messageId: String): ProgramProposalEdit? = _state.value.editors[messageId]

    fun issuesFor(messageId: String): List<String> = _state.value.proposalIssues[messageId].orEmpty()

    // --- Commit ---

    fun commit(messageId: String) {
        val threadId = _state.value.threadId ?: run {
            _state.update { it.copy(error = "No active thread to commit to") }
            return
        }
        val editor = _state.value.editors[messageId] ?: return
        val s = _state.value
        val schedule = ScheduleDto.of(
            trainingDays = s.setup.trainingDays.toList(),
            dayLocations = s.setup.dayLocations,
        )
        _state.update { it.copy(savingMessageIds = it.savingMessageIds + messageId, error = null) }
        viewModelScope.launch {
            try {
                val result = chatRepository.commit(
                    threadId = threadId,
                    program = editor.toProgram(),
                    schedule = schedule,
                    goalId = s.setup.goalId,
                )
                when (result) {
                    is ProgramCommitResult.Created -> _state.update {
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            committedProgramIds = it.committedProgramIds + (messageId to result.programId),
                        )
                    }
                    is ProgramCommitResult.Invalid -> _state.update {
                        it.copy(
                            savingMessageIds = it.savingMessageIds - messageId,
                            proposalIssues = it.proposalIssues + (messageId to result.issues),
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

    fun discard(messageId: String) {
        _state.update {
            it.copy(
                editors = it.editors - messageId,
                proposalIssues = it.proposalIssues - messageId,
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

    // --- Threads ---

    private fun refreshThreads() {
        viewModelScope.launch {
            runCatching { chatRepository.listThreads() }
                .onSuccess { threads -> _state.update { it.copy(threads = threads) } }
        }
    }

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
                        started = if (wasActive) false else s.started,
                        messages = if (wasActive) emptyList() else s.messages,
                        editors = if (wasActive) emptyMap() else s.editors,
                        proposalIssues = if (wasActive) emptyMap() else s.proposalIssues,
                        savingMessageIds = if (wasActive) emptySet() else s.savingMessageIds,
                        committedProgramIds = if (wasActive) emptyMap() else s.committedProgramIds,
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
}
