package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.ChatStreamEvent
import com.gte619n.healthfitness.shared.data.LocationRepository
import com.gte619n.healthfitness.shared.data.ProgramChatThreadResponse
import com.gte619n.healthfitness.shared.data.ProgramCommitResult
import com.gte619n.healthfitness.shared.data.ProgramProposal
import com.gte619n.healthfitness.shared.data.ProgramProposalPayload
import com.gte619n.healthfitness.shared.data.ScheduleDto
import com.gte619n.healthfitness.shared.data.SseClient
import com.gte619n.healthfitness.shared.data.WorkoutGoalsRepository
import com.gte619n.healthfitness.shared.data.WorkoutProgramChatRepository
import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) — shared port of the Android
 * `feature-workouts/.../program/chat/WorkoutDesignerViewModel`.
 *
 * The AI program-designer chat: a setup form (training days + a gym per day +
 * an optional goal) then an SSE conversation that streams an editable program
 * proposal. This VM OWNS the message stream + the `send` intent: it appends the
 * user bubble + an empty streaming assistant bubble, opens the [SseClient]
 * stream, and folds each [ChatStreamEvent] into the growing assistant message
 * (token append, proposal parse+attach, error, terminal done).
 *
 * SSE reuse: this is the SECOND consumer of the shared one-interface
 * [SseClient] (Goals is the first). The shared `stream(basePath, threadId,
 * message)` carries only a text message, so the first-turn context (schedule +
 * goal) is packed into a JSON envelope prefixed onto the message
 * ([FIRST_TURN_ENVELOPE_PREFIX]); the Phase 1C Ktor reader splits that off into
 * the POST body. Follow-up turns send the raw message.
 */

/** The workout-program designer chat scope (backend path + empty-state prompts). */
object WorkoutDesignerScope {
    const val BASE_PATH: String = "api/me/workout-programs/chat"
    val suggestedPrompts: List<String> = listOf(
        "Design a 5-week ease-in program, 3 days then 4 days, I'm restarting TRT",
        "Build a 4-day upper/lower split grounded in my recent lifts",
        "Plan a deload-aware strength block around my home gym",
    )
}

/** A gym option in the setup form. */
data class GymOption(val locationId: String, val name: String)

/** A goal option in the optional goal picker. */
data class GoalOption(val goalId: String, val title: String)

/** Setup form state: training days, a gym per day, and an optional goal link. */
data class DesignerSetupState(
    val gyms: List<GymOption> = emptyList(),
    val goals: List<GoalOption> = emptyList(),
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

/** A single message in the designer chat thread. */
sealed interface DesignerMessage {
    val id: String

    data class User(override val id: String, val text: String) : DesignerMessage

    data class Assistant(
        override val id: String,
        val text: String = "",
        val streaming: Boolean = false,
        val proposal: ProgramProposal? = null,
    ) : DesignerMessage
}

data class WorkoutDesignerUiState(
    val setup: DesignerSetupState = DesignerSetupState(),
    /** True once the thread is opened (first send) — flips the form for the chat. */
    val started: Boolean = false,
    val messages: List<DesignerMessage> = emptyList(),
    val streaming: Boolean = false,
    val threadId: String? = null,
    val error: String? = null,
    /** Per-message hard issues to flag on the card (re-seeded on a 422 commit). */
    val proposalIssues: Map<String, List<String>> = emptyMap(),
    /** Per-message soft advisories (volume/deload/ramp) — shown, never block. */
    val proposalWarnings: Map<String, List<String>> = emptyMap(),
    val savingMessageIds: Set<String> = emptySet(),
    /** message id -> created programId, once committed (collapses the card). */
    val committedProgramIds: Map<String, String> = emptyMap(),
    val threads: List<ProgramChatThreadResponse> = emptyList(),
    val deletingThreadIds: Set<String> = emptySet(),
)

/** Monotonic id source. Injected so tests get a deterministic sequence. */
fun interface IdGenerator {
    fun next(): String
}

class WorkoutDesignerViewModel(
    private val sseClient: SseClient,
    private val chatRepository: WorkoutProgramChatRepository,
    private val locationRepository: LocationRepository,
    private val goalsRepository: WorkoutGoalsRepository,
    private val idGenerator: IdGenerator,
    private val json: Json = DefaultJson,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutDesignerUiState())
    val state: StateFlow<WorkoutDesignerUiState> = _state.asStateFlow()

    init {
        loadSetup()
        refreshThreads()
    }

    // --- Setup form ---

    private fun loadSetup() {
        viewModelScope.launch {
            val gyms = locationRepository.list().getOrNull().orEmpty()
                .filter { it.isActive }
                .map { GymOption(it.locationId, it.name) }
            val goals = runCatching { goalsRepository.activeGoals() }.getOrNull().orEmpty()
                .map { GoalOption(it.goalId, it.title) }
            _state.update {
                it.copy(setup = it.setup.copy(gyms = gyms, goals = goals, loading = false))
            }
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
                // Default to the first gym so a day is valid by default.
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
        val userMsg = DesignerMessage.User(id = idGenerator.next(), text = message)
        val assistantId = idGenerator.next()
        val assistantMsg = DesignerMessage.Assistant(id = assistantId, streaming = true)
        _state.update {
            it.copy(
                started = true,
                messages = it.messages + userMsg + assistantMsg,
                streaming = true,
                error = null,
            )
        }

        val threadId = s.threadId
        // First turn: prefix the schedule + goal envelope so the SSE reader can
        // split it into the POST body. Follow-ups send the raw text.
        val wireMessage = if (threadId == null) {
            val envelope = FirstTurnEnvelope(
                schedule = ScheduleDto.of(s.setup.trainingDays.toList(), s.setup.dayLocations),
                goalId = s.setup.goalId,
                message = message,
            )
            FIRST_TURN_ENVELOPE_PREFIX + json.encodeToString(FirstTurnEnvelope.serializer(), envelope)
        } else {
            message
        }

        viewModelScope.launch {
            sseClient.stream(WorkoutDesignerScope.BASE_PATH, threadId, wireMessage)
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
            is ChatStreamEvent.Token ->
                if (event.text.isNotEmpty()) {
                    updateAssistant(assistantId) { it.copy(text = it.text + event.text) }
                }

            is ChatStreamEvent.Proposal -> {
                val payload = runCatching {
                    json.decodeFromString(ProgramProposalPayload.serializer(), event.json)
                }.getOrNull()
                if (payload == null) {
                    // Surface a malformed proposal instead of silently dropping the
                    // card (the failure mode where the text lands but no program).
                    _state.update { it.copy(error = "Couldn't read the proposed program. Try asking again.") }
                } else {
                    updateAssistant(assistantId) { it.copy(proposal = payload.program) }
                    _state.update {
                        it.copy(
                            proposalIssues = it.proposalIssues + (assistantId to payload.issues),
                            proposalWarnings = it.proposalWarnings + (assistantId to payload.warnings),
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

    private fun updateAssistant(
        id: String,
        transform: (DesignerMessage.Assistant) -> DesignerMessage.Assistant,
    ) {
        _state.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m is DesignerMessage.Assistant && m.id == id) transform(m) else m
                },
            )
        }
    }

    fun issuesFor(messageId: String): List<String> = _state.value.proposalIssues[messageId].orEmpty()

    fun warningsFor(messageId: String): List<String> = _state.value.proposalWarnings[messageId].orEmpty()

    // --- Commit ---

    fun commit(messageId: String, edited: ProgramProposal) {
        val threadId = _state.value.threadId ?: run {
            _state.update { it.copy(error = "No active thread to commit to") }
            return
        }
        val s = _state.value
        val schedule = ScheduleDto.of(s.setup.trainingDays.toList(), s.setup.dayLocations)
        _state.update { it.copy(savingMessageIds = it.savingMessageIds + messageId, error = null) }
        viewModelScope.launch {
            try {
                when (val result = chatRepository.commit(threadId, edited, schedule, s.setup.goalId)) {
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
                proposalIssues = it.proposalIssues - messageId,
                proposalWarnings = it.proposalWarnings - messageId,
                messages = it.messages.map { m ->
                    if (m is DesignerMessage.Assistant && m.id == messageId) m.copy(proposal = null) else m
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

    /**
     * Reopen a thread by reloading its persisted turns. Only the LATEST proposal
     * renders an editable card; earlier ones collapse to text.
     */
    fun openThread(threadId: String) {
        viewModelScope.launch {
            val msgs = runCatching { chatRepository.listMessages(threadId) }.getOrNull() ?: return@launch
            val chatMessages = mutableListOf<DesignerMessage>()
            val issues = mutableMapOf<String, List<String>>()
            val warnings = mutableMapOf<String, List<String>>()
            val latestProposalIdx = msgs.indexOfLast { it.role == "ASSISTANT" && !it.proposalJson.isNullOrBlank() }
            msgs.forEachIndexed { idx, m ->
                if (m.role == "USER") {
                    chatMessages += DesignerMessage.User(m.messageId, m.content.orEmpty())
                } else {
                    var proposal: ProgramProposal? = null
                    if (idx == latestProposalIdx && !m.proposalJson.isNullOrBlank()) {
                        val payload = runCatching {
                            json.decodeFromString(ProgramProposalPayload.serializer(), m.proposalJson!!)
                        }.getOrNull()
                        if (payload != null) {
                            proposal = payload.program
                            issues[m.messageId] = payload.issues
                            warnings[m.messageId] = payload.warnings
                        }
                    }
                    chatMessages += DesignerMessage.Assistant(
                        id = m.messageId,
                        text = m.content.orEmpty(),
                        streaming = false,
                        proposal = proposal,
                    )
                }
            }
            _state.update {
                it.copy(
                    started = true,
                    threadId = threadId,
                    messages = chatMessages,
                    proposalIssues = issues,
                    proposalWarnings = warnings,
                    error = null,
                )
            }
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
                        proposalIssues = if (wasActive) emptyMap() else s.proposalIssues,
                        proposalWarnings = if (wasActive) emptyMap() else s.proposalWarnings,
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

    companion object {
        /**
         * Marker prefixing the first-turn JSON envelope in the streamed message,
         * so the Phase 1C SSE reader can split the schedule/goal into the POST
         * body without the shared [SseClient] needing extra params.
         */
        const val FIRST_TURN_ENVELOPE_PREFIX: String = "DESIGNER_SETUP"
        val DefaultJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** First-turn wire envelope: the setup context + the user's first message. */
@kotlinx.serialization.Serializable
data class FirstTurnEnvelope(
    val schedule: ScheduleDto,
    val goalId: String? = null,
    val message: String,
)
