package com.gte619n.healthfitness.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.goals.dto.ChatMessageRequest;
import com.gte619n.healthfitness.api.goals.dto.GoalProposalDto;
import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.chat.RawProposal;
import com.gte619n.healthfitness.integrations.goals.GoalChatClient;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.LocalDate;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, GoalChatControllerTest.FakeChatClientConfig.class})
class GoalChatControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static final String USER = "user-chat-1";

    /**
     * A fake that streams two tokens then surfaces a complete, valid
     * propose_goal_structure proposal. Marked @Primary so it overrides the
     * default no-op fake in TestPersistenceConfig.
     */
    @TestConfiguration
    static class FakeChatClientConfig {
        @Bean
        @Primary
        GoalChatClient fakeGoalChatClient() {
            return (history, userMessage, healthContext, onToken) -> {
                onToken.accept("Here is ");
                onToken.accept("a plan.");
                RawProposal proposal = new RawProposal(
                    "Lower LDL", "Get LDL optimal", "CARDIOVASCULAR", "2026-12-01",
                    List.of(new RawProposal.RawPhase("Foundation", "diet",
                        "2026-06-01", "2026-08-01",
                        List.of(new RawProposal.RawStep("LDL under 100", "THRESHOLD",
                            new RawProposal.RawMetric("blood.ldl", "LT", 100.0, null, null))))));
                return new GoalChatClient.StreamResult("Here is a plan.", proposal);
            };
        }
    }

    @Test
    void chatStreamsTokensThenProposalThenDone() throws Exception {
        ChatMessageRequest req = new ChatMessageRequest(null, "Help me lower my LDL");

        MvcResult async = mvc.perform(post("/api/me/goals/chat")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(request().asyncStarted())
            .andReturn();

        String body = mvc.perform(asyncDispatch(async))
            .andDo(print())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event:token"), "expected token events: " + body);
        assertTrue(body.contains("event:proposal"), "expected proposal event: " + body);
        assertTrue(body.contains("event:done"), "expected done event: " + body);
        assertTrue(body.contains("blood.ldl"), "expected validated metric key in proposal: " + body);
    }

    @Test
    void threadsListReflectsChat() throws Exception {
        String user = "user-chat-threads-" + java.util.UUID.randomUUID();
        ChatMessageRequest req = new ChatMessageRequest(null, "Plan a strength base");
        MvcResult async = mvc.perform(post("/api/me/goals/chat")
                .header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(request().asyncStarted())
            .andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        mvc.perform(get("/api/me/goals/chat/threads")
                .header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Plan a strength base"));
    }

    @Test
    void commitValidProposalCreatesGoal() throws Exception {
        String threadId = openThread(USER);

        GoalProposalDto dto = new GoalProposalDto(
            "Lower LDL", "optimal", GoalDomain.CARDIOVASCULAR, LocalDate.of(2026, 12, 1),
            List.of(new GoalProposalDto.PhaseDto("Foundation", "diet",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1),
                List.of(new GoalProposalDto.StepDto("LDL under 100", StepKind.THRESHOLD,
                    new GoalProposalDto.MetricDto("blood.ldl", Comparator.LT, 100.0, null, null, null),
                    null)),
                null)),
            null);

        MvcResult res = mvc.perform(post("/api/me/goals/chat/" + threadId + "/commit")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goalId").exists())
            .andReturn();

        String goalId = objectMapper.readTree(res.getResponse().getContentAsString())
            .get("goalId").asText();

        // The committed Goal is fully readable as a deep Goal.
        mvc.perform(get("/api/me/goals/" + goalId).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Lower LDL"))
            .andExpect(jsonPath("$.source").value("AI_ASSISTED"))
            .andExpect(jsonPath("$.phases.length()").value(1))
            .andExpect(jsonPath("$.phases[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.phases[0].steps[0].metric.metricKey").value("blood.ldl"));
    }

    @Test
    void commitInvalidProposalReturns400WithFlaggedStructure() throws Exception {
        String threadId = openThread(USER);

        // Unknown metric key → flagged inline, commit rejected.
        GoalProposalDto dto = new GoalProposalDto(
            "Bad goal", "d", GoalDomain.OTHER, LocalDate.of(2026, 12, 1),
            List.of(new GoalProposalDto.PhaseDto("P", null, null, null,
                List.of(new GoalProposalDto.StepDto("S", StepKind.THRESHOLD,
                    new GoalProposalDto.MetricDto("blood.nonsense", Comparator.LT, 1.0, null, null, null),
                    null)),
                null)),
            null);

        mvc.perform(post("/api/me/goals/chat/" + threadId + "/commit")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.phases[0].steps[0].metric.validationError").exists())
            .andExpect(jsonPath("$.phases[0].steps[0].metric.metricKey").value("blood.nonsense"));
    }

    @Test
    void commitUnknownThreadReturns404() throws Exception {
        GoalProposalDto dto = new GoalProposalDto(
            "X", "d", GoalDomain.OTHER, LocalDate.of(2026, 12, 1), List.of(), null);
        mvc.perform(post("/api/me/goals/chat/does-not-exist/commit")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteThreadRemovesItFromListThen404OnRepeat() throws Exception {
        String user = "user-chat-delete-" + java.util.UUID.randomUUID();
        String threadId = openThread(user);

        // It shows up in the thread list before deletion.
        mvc.perform(get("/api/me/goals/chat/threads").header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // DELETE returns 204 No Content.
        mvc.perform(delete("/api/me/goals/chat/threads/" + threadId)
                .header("X-Dev-User", user))
            .andExpect(status().isNoContent());

        // It's gone from the list.
        mvc.perform(get("/api/me/goals/chat/threads").header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Deleting again (now non-existent) is a 404.
        mvc.perform(delete("/api/me/goals/chat/threads/" + threadId)
                .header("X-Dev-User", user))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownThreadReturns404() throws Exception {
        mvc.perform(delete("/api/me/goals/chat/threads/does-not-exist")
                .header("X-Dev-User", USER))
            .andExpect(status().isNotFound());
    }

    /** Open a thread by sending one chat message; returns the threadId. */
    private String openThread(String user) throws Exception {
        ChatMessageRequest req = new ChatMessageRequest(null, "start");
        MvcResult async = mvc.perform(post("/api/me/goals/chat")
                .header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(request().asyncStarted())
            .andReturn();
        String body = mvc.perform(asyncDispatch(async))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        // The done event carries { "threadId": "..." }.
        int idx = body.indexOf("\"threadId\":\"");
        assertTrue(idx >= 0, "no threadId in stream: " + body);
        int start = idx + "\"threadId\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
