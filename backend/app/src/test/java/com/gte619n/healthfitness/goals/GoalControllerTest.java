package com.gte619n.healthfitness.goals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.goals.dto.CreateGoalRequest;
import com.gte619n.healthfitness.api.goals.dto.CreatePhaseRequest;
import com.gte619n.healthfitness.api.goals.dto.CreateStepRequest;
import com.gte619n.healthfitness.api.goals.dto.StepMetricBindingDto;
import com.gte619n.healthfitness.api.goals.dto.UpdateGoalRequest;
import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
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
class GoalControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GoalRepository goals;

    private static final String USER = "user-goals-1";

    @Test
    void createThenGetDeepRoundTrip() throws Exception {
        CreateGoalRequest req = new CreateGoalRequest(
            "Lower ApoB", "into optimal range", GoalDomain.CARDIOVASCULAR,
            null, java.time.LocalDate.now().plusMonths(6), null
        );

        MvcResult result = mvc.perform(post("/api/me/goals")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.goalId").exists())
            .andExpect(jsonPath("$.title").value("Lower ApoB"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.phaseOrder.length()").value(0))
            .andReturn();

        String goalId = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("goalId").asText();

        // Add a Phase
        CreatePhaseRequest phaseReq = new CreatePhaseRequest("Phase 1", "lay groundwork", null, null);
        MvcResult phaseRes = mvc.perform(post("/api/me/goals/" + goalId + "/phases")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(phaseReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))   // first Phase auto-active
            .andExpect(jsonPath("$.orderIndex").value(0))
            .andReturn();

        String phaseId = objectMapper.readTree(phaseRes.getResponse().getContentAsString())
            .get("phaseId").asText();

        // Add a Step
        CreateStepRequest stepReq = new CreateStepRequest(
            "Reduce ApoB below 80",
            StepKind.THRESHOLD,
            new StepMetricBindingDto("blood.apoB", Comparator.LT, 80.0, null, null)
        );
        mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stepReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.kind").value("THRESHOLD"))
            .andExpect(jsonPath("$.metric.metricKey").value("blood.apoB"));

        // GET deep
        mvc.perform(get("/api/me/goals/" + goalId)
                .header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goalId").value(goalId))
            .andExpect(jsonPath("$.phases.length()").value(1))
            .andExpect(jsonPath("$.phases[0].steps.length()").value(1))
            .andExpect(jsonPath("$.phases[0].steps[0].metric.targetValue").value(80.0));
    }

    @Test
    void patchUpdatesSingleField() throws Exception {
        String goalId = createGoal();

        UpdateGoalRequest patch = new UpdateGoalRequest(
            "Renamed", null, null, null, null, null
        );
        mvc.perform(patch("/api/me/goals/" + goalId)
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patch)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Renamed"))
            .andExpect(jsonPath("$.domain").value("CARDIOVASCULAR"));   // unchanged
    }

    @Test
    void deleteSoftArchivesGoal() throws Exception {
        String goalId = createGoal();

        mvc.perform(delete("/api/me/goals/" + goalId)
                .header("X-Dev-User", USER))
            .andExpect(status().isNoContent());

        Goal archived = goals.findById(USER, goalId).orElseThrow();
        if (archived.status() != GoalStatus.ARCHIVED) {
            throw new AssertionError("expected ARCHIVED, got " + archived.status());
        }
    }

    @Test
    void listFiltersByStatus() throws Exception {
        // Use an isolated user id so this test doesn't see Goals from other tests.
        String isolatedUser = "user-goals-list-" + java.util.UUID.randomUUID();
        CreateGoalRequest activeReq = new CreateGoalRequest(
            "Active goal", "desc", GoalDomain.CARDIOVASCULAR,
            null, java.time.LocalDate.now().plusMonths(3), null
        );
        mvc.perform(post("/api/me/goals")
                .header("X-Dev-User", isolatedUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(activeReq)))
            .andExpect(status().isCreated());

        MvcResult archiveRes = mvc.perform(post("/api/me/goals")
                .header("X-Dev-User", isolatedUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateGoalRequest(
                    "To archive", "desc", GoalDomain.CARDIOVASCULAR,
                    null, java.time.LocalDate.now().plusMonths(3), null
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        String archiveId = objectMapper.readTree(archiveRes.getResponse().getContentAsString())
            .get("goalId").asText();
        mvc.perform(delete("/api/me/goals/" + archiveId)
                .header("X-Dev-User", isolatedUser))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/me/goals?status=ACTIVE")
                .header("X-Dev-User", isolatedUser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/me/goals?status=ARCHIVED")
                .header("X-Dev-User", isolatedUser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void reevaluateReturns204() throws Exception {
        // Phase 3: reevaluate is wired to StepEvaluationService.evaluateGoal
        // and returns 204 No Content. A Goal with no metric-bound Steps
        // still re-evaluates cleanly (the for-loop is just a no-op).
        String goalId = createGoal();
        mvc.perform(post("/api/me/goals/" + goalId + "/reevaluate")
                .header("X-Dev-User", USER))
            .andExpect(status().isNoContent());
    }

    private String createGoal() throws Exception {
        CreateGoalRequest req = new CreateGoalRequest(
            "Goal " + java.util.UUID.randomUUID(),
            "desc", GoalDomain.CARDIOVASCULAR,
            null, java.time.LocalDate.now().plusMonths(3), null
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
}
