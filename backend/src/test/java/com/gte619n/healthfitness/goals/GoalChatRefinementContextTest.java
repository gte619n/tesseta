package com.gte619n.healthfitness.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.goals.dto.ChatMessageRequest;
import com.gte619n.healthfitness.integrations.goals.GoalChatClient;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the multi-turn refinement fix: when an assistant turn carries
 * a proposal, a concise text summary of the proposal is folded into the
 * persisted assistant message content so a follow-up turn replays it as
 * context for the model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, GoalChatRefinementContextTest.CapturingChatClientConfig.class})
class GoalChatRefinementContextTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CapturingChatClient chatClient;

    private static final String USER = "user-refine-1";

    @Test
    void proposalSummaryIsPersisted_andReplayedOnNextTurn() throws Exception {
        // Turn 1: model proposes a structure.
        String threadId = sendChat(null, "Help me lower my LDL");

        // Turn 2: a follow-up "refine the plan" reuses the same thread.
        chatClient.capturedHistory.clear();
        sendChat(threadId, "Refine the plan to be more aggressive");

        // The history replayed to the model on turn 2 must include the
        // proposal summary built from turn 1 (the proposal went out as
        // structured data, but the summary is now in the assistant text).
        List<GoalChatClient.Turn> history = new ArrayList<>(chatClient.capturedHistory);
        String assistantHistory = history.stream()
            .filter(t -> !t.userTurn())
            .map(GoalChatClient.Turn::text)
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(assistantHistory).contains("[Proposed goal: Lower LDL");
        assertThat(assistantHistory).contains("Foundation");
        assertThat(assistantHistory).contains("LDL under 100");
        assertThat(assistantHistory).contains("blood.ldl");
    }

    private String sendChat(String threadId, String message) throws Exception {
        ChatMessageRequest req = new ChatMessageRequest(threadId, message);
        MvcResult async = mvc.perform(post("/api/me/goals/chat")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(request().asyncStarted())
            .andReturn();
        String body = mvc.perform(asyncDispatch(async))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        int idx = body.indexOf("\"threadId\":\"");
        int start = idx + "\"threadId\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /** Fake chat client that records the history it was handed each call. */
    static class CapturingChatClient implements GoalChatClient {
        final List<Turn> capturedHistory = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public StreamResult streamChat(
            List<Turn> history, String userMessage, String healthContext,
            java.util.function.Consumer<String> onToken
        ) {
            capturedHistory.clear();
            capturedHistory.addAll(history);
            onToken.accept("OK.");
            com.gte619n.healthfitness.core.goals.chat.RawProposal proposal =
                new com.gte619n.healthfitness.core.goals.chat.RawProposal(
                    "Lower LDL", "Get LDL optimal", "CARDIOVASCULAR", "2026-12-01",
                    List.of(new com.gte619n.healthfitness.core.goals.chat.RawProposal.RawPhase(
                        "Foundation", "diet", "2026-06-01", "2026-08-01",
                        List.of(new com.gte619n.healthfitness.core.goals.chat.RawProposal.RawStep(
                            "LDL under 100", "THRESHOLD",
                            new com.gte619n.healthfitness.core.goals.chat.RawProposal.RawMetric(
                                "blood.ldl", "LT", 100.0, null, null))))));
            // Thin assistant text — the structure carries the substance.
            return new StreamResult("OK.", proposal);
        }
    }

    @TestConfiguration
    static class CapturingChatClientConfig {
        @Bean
        @Primary
        CapturingChatClient capturingChatClient() {
            return new CapturingChatClient();
        }
    }
}
