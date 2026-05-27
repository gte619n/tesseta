package com.gte619n.healthfitness.goals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.goals.dto.CreateGoalRequest;
import com.gte619n.healthfitness.api.goals.dto.CreatePhaseRequest;
import com.gte619n.healthfitness.api.goals.dto.ReorderRequest;
import com.gte619n.healthfitness.api.goals.dto.UpdatePhaseRequest;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class PhaseControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GoalRepository goals;

    private static final String USER = "user-phase-1";

    @Test
    void createPhaseAppendsToPhaseOrderAndFirstIsActive() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        String p2 = createPhase(goalId, "Phase 2");

        Goal goal = goals.findById(USER, goalId).orElseThrow();
        if (!goal.phaseOrder().equals(List.of(p1, p2))) {
            throw new AssertionError("phaseOrder mismatch: " + goal.phaseOrder());
        }

        // Phase 1 should be ACTIVE, Phase 2 LOCKED
        mvc.perform(get("/api/me/goals/" + goalId)
                .header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phases[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.phases[1].status").value("LOCKED"));
    }

    @Test
    void patchUpdatesSingleField() throws Exception {
        String goalId = createGoal();
        String phaseId = createPhase(goalId, "Original");

        UpdatePhaseRequest patch = new UpdatePhaseRequest("Renamed", null, null, null);
        mvc.perform(patch("/api/me/goals/" + goalId + "/phases/" + phaseId)
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patch)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Renamed"));
    }

    @Test
    void deleteRemovesPhaseAndUpdatesPhaseOrder() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        String p2 = createPhase(goalId, "Phase 2");

        mvc.perform(delete("/api/me/goals/" + goalId + "/phases/" + p1)
                .header("X-Dev-User", USER))
            .andExpect(status().isNoContent());

        Goal goal = goals.findById(USER, goalId).orElseThrow();
        if (!goal.phaseOrder().equals(List.of(p2))) {
            throw new AssertionError("phaseOrder mismatch after delete: " + goal.phaseOrder());
        }
    }

    @Test
    void reorderPermutesPhaseOrder() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        String p2 = createPhase(goalId, "Phase 2");
        String p3 = createPhase(goalId, "Phase 3");

        ReorderRequest req = new ReorderRequest(List.of(p3, p1, p2));
        mvc.perform(put("/api/me/goals/" + goalId + "/phases/order")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());

        Goal goal = goals.findById(USER, goalId).orElseThrow();
        if (!goal.phaseOrder().equals(List.of(p3, p1, p2))) {
            throw new AssertionError("phaseOrder mismatch after reorder: " + goal.phaseOrder());
        }
    }

    @Test
    void reorderWithNonPermutationFails() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        createPhase(goalId, "Phase 2");

        ReorderRequest req = new ReorderRequest(List.of(p1));
        mvc.perform(put("/api/me/goals/" + goalId + "/phases/order")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    // helpers

    private String createGoal() throws Exception {
        CreateGoalRequest req = new CreateGoalRequest(
            "Goal " + java.util.UUID.randomUUID(),
            "desc", GoalDomain.STRENGTH,
            null, java.time.LocalDate.now().plusMonths(6), null
        );
        MvcResult res = mvc.perform(post("/api/me/goals")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
            .get("goalId").asText();
    }

    private String createPhase(String goalId, String title) throws Exception {
        CreatePhaseRequest req = new CreatePhaseRequest(title, null, null, null);
        MvcResult res = mvc.perform(post("/api/me/goals/" + goalId + "/phases")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
            .get("phaseId").asText();
    }
}
