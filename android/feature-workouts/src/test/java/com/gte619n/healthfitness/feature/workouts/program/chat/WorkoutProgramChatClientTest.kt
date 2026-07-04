package com.gte619n.healthfitness.feature.workouts.program.chat

import com.gte619n.healthfitness.core.chat.ChatStreamEvent
import com.gte619n.healthfitness.data.net.SseClient
import com.gte619n.healthfitness.data.net.SseEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2: locks the designer-chat SSE dispatch contract (token/proposal/error/
 * done + malformed/heartbeat). This is the exact surface that silently degrades
 * when R8 strips the reflectively-parsed DTOs — the DTOs now live in the kept
 * `data.**` package, and this test pins the mapping so a regression is caught in
 * the unit suite (the release build in CI is the R8-specific gate).
 */
class WorkoutProgramChatClientTest {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sse: SseClient = mockk()

    private fun clientEmitting(vararg events: SseEvent): WorkoutProgramChatClient {
        every { sse.streamJsonPost(any(), any()) } returns flowOf(*events)
        return WorkoutProgramChatClient(sse, moshi)
    }

    @Test
    fun `maps token proposal error and done frames`() = runTest {
        val client = clientEmitting(
            SseEvent("token", """{"text":"Hello "}"""),
            SseEvent("token", """{"text":"world"}"""),
            SseEvent("proposal", """{"program":{}}"""),
            SseEvent("error", """{"error":"boom"}"""),
            SseEvent("done", """{"threadId":"t-1"}"""),
        )

        val events = client.stream(
            threadId = null, message = "hi", schedule = null, goalId = null,
        ).toList()

        assertEquals(ChatStreamEvent.Token("Hello "), events[0])
        assertEquals(ChatStreamEvent.Token("world"), events[1])
        assertTrue(events[2] is ChatStreamEvent.Proposal)
        assertEquals("""{"program":{}}""", (events[2] as ChatStreamEvent.Proposal).json)
        assertEquals(ChatStreamEvent.Error("boom"), events[3])
        assertEquals(ChatStreamEvent.Done("t-1"), events[4])
    }

    @Test
    fun `malformed and unknown frames degrade safely`() = runTest {
        val client = clientEmitting(
            SseEvent("token", "not json"),
            SseEvent("error", "not json"),
            SseEvent("done", "not json"),
            SseEvent("heartbeat", "ping"),
        )

        val events = client.stream(null, "hi", null, null).toList()

        assertEquals(ChatStreamEvent.Token(""), events[0])          // token parse fails -> ""
        assertEquals(ChatStreamEvent.Error("Chat failed"), events[1]) // error parse fails -> fallback
        assertEquals(ChatStreamEvent.Done(null), events[2])          // done parse fails -> null
        assertEquals(ChatStreamEvent.Token(""), events[3])          // unknown event -> ""
    }
}
