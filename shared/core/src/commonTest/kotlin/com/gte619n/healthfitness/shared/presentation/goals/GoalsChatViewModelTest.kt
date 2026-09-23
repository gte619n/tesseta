package com.gte619n.healthfitness.shared.presentation.goals

import com.gte619n.healthfitness.shared.data.ChatStreamEvent
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
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave E3 — the port's core value: the SSE message-
 * accumulation logic. A scripted token stream must fold into ONE growing
 * assistant message, a `proposal` event must attach a parsed proposal, and the
 * terminal `done` must record the threadId and clear the streaming flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun accumulatesScriptedTokensIntoOneAssistantMessageThenFinishes() = runTest {
        val sse = FakeSseClient(
            script = listOf(
                ChatStreamEvent.Token("Here "),
                ChatStreamEvent.Token("is "),
                ChatStreamEvent.Token("**your** plan"),
                ChatStreamEvent.Done(threadId = "thread-42"),
            ),
        )
        val vm = GoalsChatViewModel(sse, FakeChatRepository(), SequentialIdGenerator())
        advanceUntilIdle()

        vm.send("Plan a strength base")
        advanceUntilIdle()

        val messages = vm.state.value.messages
        assertEquals(2, messages.size, "one user + one assistant bubble")

        val user = messages[0] as ChatMessage.User
        assertEquals("Plan a strength base", user.text)

        val assistant = messages[1] as ChatMessage.Assistant
        // All three tokens folded into ONE message, in order (markdown preserved).
        assertEquals("Here is **your** plan", assistant.text)
        assertFalse(assistant.streaming, "done clears the per-message streaming flag")

        assertFalse(vm.state.value.streaming, "done clears the global streaming flag")
        assertEquals("thread-42", vm.state.value.threadId, "done records the new threadId")
    }

    @Test
    fun proposalEventAttachesAParsedProposalToTheAssistantMessage() = runTest {
        val proposalJson = """
            {
              "title": "Lower ApoB",
              "domain": "CARDIOVASCULAR",
              "phases": [
                { "title": "Baseline", "steps": [ { "title": "Get a lipid panel", "kind": "MANUAL" } ] }
              ]
            }
        """.trimIndent()
        val sse = FakeSseClient(
            script = listOf(
                ChatStreamEvent.Token("Drafted a roadmap:"),
                ChatStreamEvent.Proposal(proposalJson),
                ChatStreamEvent.Done(threadId = "t1"),
            ),
        )
        val vm = GoalsChatViewModel(sse, FakeChatRepository(), SequentialIdGenerator())
        advanceUntilIdle()

        vm.send("Help me lower ApoB")
        advanceUntilIdle()

        val assistant = vm.state.value.messages.filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals("Drafted a roadmap:", assistant.text)
        val proposal = assistant.proposal
        assertNotNull(proposal, "proposal event parsed and attached")
        assertEquals("Lower ApoB", proposal.title)
        assertEquals(1, proposal.phases.size)
        assertEquals("Get a lipid panel", proposal.phases.first().steps.first().title)
    }

    @Test
    fun errorEventSurfacesMessageButLeavesAccumulatedTextIntact() = runTest {
        val sse = FakeSseClient(
            script = listOf(
                ChatStreamEvent.Token("Partial"),
                ChatStreamEvent.Error("model overloaded"),
                ChatStreamEvent.Done(threadId = null),
            ),
        )
        val vm = GoalsChatViewModel(sse, FakeChatRepository(), SequentialIdGenerator())
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        assertEquals("model overloaded", vm.state.value.error)
        val assistant = vm.state.value.messages.filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals("Partial", assistant.text)
        assertFalse(vm.state.value.streaming)
    }

    @Test
    fun secondSendReusesTheThreadIdFromTheFirstStreamsDone() = runTest {
        val sse = FakeSseClient(script = listOf(ChatStreamEvent.Done(threadId = "thread-99")))
        val vm = GoalsChatViewModel(sse, FakeChatRepository(), SequentialIdGenerator())
        advanceUntilIdle()

        vm.send("first")
        advanceUntilIdle()
        assertEquals("thread-99", vm.state.value.threadId)

        vm.send("second")
        advanceUntilIdle()
        // The client sees the established thread id on the follow-up turn.
        assertEquals("thread-99", sse.lastThreadId)
    }

    @Test
    fun streamThatClosesWithoutDoneStillClearsTheStreamingFlag() = runTest {
        // Server closes the SSE stream with no terminal `done` (just tokens):
        // the flow completes and finishStream must still clear streaming.
        val sse = FakeSseClient(script = listOf(ChatStreamEvent.Token("half")))
        val vm = GoalsChatViewModel(sse, FakeChatRepository(), SequentialIdGenerator())
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        assertFalse(vm.state.value.streaming)
        val assistant = vm.state.value.messages.filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals("half", assistant.text)
        assertFalse(assistant.streaming)
    }
}
