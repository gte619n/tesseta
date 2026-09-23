package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.ChatStreamEvent
import com.gte619n.healthfitness.shared.data.Location
import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The port's core value: SSE message accumulation for the program designer. A
 * scripted token stream must fold into ONE growing assistant message, a
 * `proposal` event must parse+attach the program, and the terminal `done` must
 * record the threadId + clear streaming. Plus the setup-gate + first-turn
 * envelope behaviour unique to the designer (vs. the Goals chat).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDesignerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun readyVm(script: List<ChatStreamEvent>, sse: FakeSseClient = FakeSseClient(script)): Pair<WorkoutDesignerViewModel, FakeSseClient> {
        val vm = WorkoutDesignerViewModel(
            sseClient = sse,
            chatRepository = FakeWorkoutProgramChatRepository(),
            locationRepository = FakeLocationRepository(listOf(Location(locationId = "gym-1", name = "Home Gym"))),
            goalsRepository = FakeWorkoutGoalsRepository(),
            idGenerator = SequentialIdGenerator(),
        )
        return vm to sse
    }

    /** Pick a training day (auto-assigns the single gym) so the setup is ready. */
    private fun WorkoutDesignerViewModel.readySetup() {
        toggleTrainingDay(DayOfWeek.MON)
    }

    @Test
    fun accumulatesScriptedTokensIntoOneAssistantMessageThenFinishes() = runTest {
        val (vm, _) = readyVm(
            listOf(
                ChatStreamEvent.Token("Here "),
                ChatStreamEvent.Token("is "),
                ChatStreamEvent.Token("**your** block"),
                ChatStreamEvent.Done(threadId = "thread-42"),
            ),
        )
        advanceUntilIdle()
        vm.readySetup()

        vm.send("Build me a 4-day split")
        advanceUntilIdle()

        val messages = vm.state.value.messages
        assertEquals(2, messages.size, "one user + one assistant bubble")
        assertEquals("Build me a 4-day split", (messages[0] as DesignerMessage.User).text)

        val assistant = messages[1] as DesignerMessage.Assistant
        assertEquals("Here is **your** block", assistant.text)
        assertFalse(assistant.streaming, "done clears the per-message streaming flag")
        assertFalse(vm.state.value.streaming)
        assertEquals("thread-42", vm.state.value.threadId)
    }

    @Test
    fun proposalEventParsesAndAttachesTheProgramWithWarnings() = runTest {
        val proposalJson = """
            {
              "program": {
                "title": "4-Day Upper/Lower",
                "phases": [
                  { "title": "Base", "weeks": 4, "days": [
                    { "label": "Upper A", "blocks": [
                      { "title": "Main", "prescriptions": [
                        { "exerciseName": "Bench Press", "sets": 3, "repsMin": 6, "repsMax": 10 }
                      ] }
                    ] }
                  ] }
                ]
              },
              "warnings": ["Weekly push volume is on the high side."]
            }
        """.trimIndent()
        val (vm, _) = readyVm(
            listOf(
                ChatStreamEvent.Token("Drafted a program:"),
                ChatStreamEvent.Proposal(proposalJson),
                ChatStreamEvent.Done(threadId = "t1"),
            ),
        )
        advanceUntilIdle()
        vm.readySetup()

        vm.send("plan it")
        advanceUntilIdle()

        val assistant = vm.state.value.messages.filterIsInstance<DesignerMessage.Assistant>().single()
        assertEquals("Drafted a program:", assistant.text)
        val proposal = assistant.proposal
        assertNotNull(proposal)
        assertEquals("4-Day Upper/Lower", proposal.title)
        assertEquals("Bench Press", proposal.phases.first().days.first().blocks.first().prescriptions.first().exerciseName)
        assertEquals(listOf("Weekly push volume is on the high side."), vm.warningsFor(assistant.id))
    }

    @Test
    fun sendBeforeSetupIsReadyIsBlockedWithAGuidingError() = runTest {
        val (vm, sse) = readyVm(listOf(ChatStreamEvent.Done(threadId = "x")))
        advanceUntilIdle()
        // No training day picked yet → not ready.

        vm.send("go")
        advanceUntilIdle()

        assertEquals("Pick your training days and a gym for each.", vm.state.value.error)
        assertTrue(vm.state.value.messages.isEmpty(), "nothing streamed")
        assertNull(sse.lastMessage, "SSE never opened")
    }

    @Test
    fun firstTurnPacksTheSetupEnvelopeButFollowUpsSendRawText() = runTest {
        val (vm, sse) = readyVm(listOf(ChatStreamEvent.Done(threadId = "thread-9")))
        advanceUntilIdle()
        vm.readySetup()

        vm.send("first")
        advanceUntilIdle()
        assertNull(sse.lastThreadId, "first turn has no thread yet")
        assertTrue(
            sse.lastMessage!!.startsWith(WorkoutDesignerViewModel.FIRST_TURN_ENVELOPE_PREFIX),
            "first turn prefixes the schedule/goal envelope",
        )
        assertTrue(sse.lastMessage!!.contains("\"message\":\"first\""), "envelope carries the user message")

        vm.send("second")
        advanceUntilIdle()
        assertEquals("thread-9", sse.lastThreadId, "follow-up reuses the thread")
        assertEquals("second", sse.lastMessage, "follow-up sends raw text, no envelope")
    }

    @Test
    fun malformedProposalSurfacesAnErrorInsteadOfDroppingSilently() = runTest {
        val (vm, _) = readyVm(
            listOf(
                ChatStreamEvent.Token("here"),
                ChatStreamEvent.Proposal("{ this is not valid json"),
                ChatStreamEvent.Done(threadId = "t1"),
            ),
        )
        advanceUntilIdle()
        vm.readySetup()

        vm.send("plan")
        advanceUntilIdle()

        assertEquals("Couldn't read the proposed program. Try asking again.", vm.state.value.error)
        assertNull(vm.state.value.messages.filterIsInstance<DesignerMessage.Assistant>().single().proposal)
    }

    @Test
    fun streamThatClosesWithoutDoneStillClearsStreaming() = runTest {
        val (vm, _) = readyVm(listOf(ChatStreamEvent.Token("half")))
        advanceUntilIdle()
        vm.readySetup()

        vm.send("hi")
        advanceUntilIdle()

        assertFalse(vm.state.value.streaming)
        val assistant = vm.state.value.messages.filterIsInstance<DesignerMessage.Assistant>().single()
        assertEquals("half", assistant.text)
        assertFalse(assistant.streaming)
    }
}
