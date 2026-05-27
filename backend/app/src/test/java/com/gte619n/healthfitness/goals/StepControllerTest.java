package com.gte619n.healthfitness.goals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.goals.dto.CreateGoalRequest;
import com.gte619n.healthfitness.api.goals.dto.CreatePhaseRequest;
import com.gte619n.healthfitness.api.goals.dto.CreateStepRequest;
import com.gte619n.healthfitness.api.goals.dto.ReorderRequest;
import com.gte619n.healthfitness.api.goals.dto.StepMetricBindingDto;
import com.gte619n.healthfitness.api.goals.dto.UpdateStepRequest;
import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.goals.StepKind;
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
class StepControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GoalRepository goals;
    @Autowired PhaseRepository phases;

    private static final String USER = "user-step-1";

    @Test
    void invalidMetricBindingReturns400() throws Exception {
        String goalId = createGoal();
        String phaseId = createPhase(goalId, "Phase 1");

        // THRESHOLD without metric -> 400
        CreateStepRequest req = new CreateStepRequest("bad", StepKind.THRESHOLD, null);
        mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());

        // SUSTAINED without windowDays -> 400
        CreateStepRequest sustainedNoWindow = new CreateStepRequest(
            "bad-sustained", StepKind.SUSTAINED,
            new StepMetricBindingDto("vitals.restingHr", Comparator.LT, 55.0, null, null)
        );
        mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sustainedNoWindow)))
            .andExpect(status().isBadRequest());

        // MANUAL with metric -> 400
        CreateStepRequest manualWithMetric = new CreateStepRequest(
            "bad-manual", StepKind.MANUAL,
            new StepMetricBindingDto("blood.ldl", Comparator.LT, 100.0, null, null)
        );
        mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(manualWithMetric)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reorderPermutesStepOrder() throws Exception {
        String goalId = createGoal();
        String phaseId = createPhase(goalId, "Phase 1");
        String s1 = createManualStep(goalId, phaseId, "Step 1");
        String s2 = createManualStep(goalId, phaseId, "Step 2");
        String s3 = createManualStep(goalId, phaseId, "Step 3");

        ReorderRequest req = new ReorderRequest(List.of(s3, s2, s1));
        mvc.perform(put("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps/order")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());

        Phase p = phases.findById(USER, goalId, phaseId).orElseThrow();
        if (!p.stepOrder().equals(List.of(s3, s2, s1))) {
            throw new AssertionError("stepOrder mismatch: " + p.stepOrder());
        }
    }

    @Test
    void phaseAutoProgressionWhenAllStepsDone() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        String p2 = createPhase(goalId, "Phase 2");
        String s1 = createManualStep(goalId, p1, "Step 1");
        String s2 = createManualStep(goalId, p1, "Step 2");

        // Mark both done.
        markStepDone(goalId, p1, s1, true);
        markStepDone(goalId, p1, s2, true);

        Phase phase1 = phases.findById(USER, goalId, p1).orElseThrow();
        if (phase1.status() != PhaseStatus.COMPLETED) {
            throw new AssertionError("expected p1 COMPLETED, got " + phase1.status());
        }
        if (phase1.completedAt() == null) {
            throw new AssertionError("expected p1 completedAt set");
        }
        Phase phase2 = phases.findById(USER, goalId, p2).orElseThrow();
        if (phase2.status() != PhaseStatus.ACTIVE) {
            throw new AssertionError("expected p2 ACTIVE, got " + phase2.status());
        }
    }

    @Test
    void completingLastPhaseCompletesGoal() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Only Phase");
        String s1 = createManualStep(goalId, p1, "Step 1");

        markStepDone(goalId, p1, s1, true);

        Goal g = goals.findById(USER, goalId).orElseThrow();
        if (g.status() != GoalStatus.COMPLETED) {
            throw new AssertionError("expected COMPLETED, got " + g.status());
        }
        if (g.completedAt() == null) {
            throw new AssertionError("expected completedAt set");
        }
    }

    @Test
    void completedPhaseStaysCompletedWhenStepManuallyUnchecked() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Phase 1");
        String p2 = createPhase(goalId, "Phase 2");
        String s1 = createManualStep(goalId, p1, "Step 1");
        markStepDone(goalId, p1, s1, true);

        // Sanity: phase 1 completed, phase 2 active.
        Phase phase1 = phases.findById(USER, goalId, p1).orElseThrow();
        if (phase1.status() != PhaseStatus.COMPLETED) {
            throw new AssertionError("setup failed: " + phase1.status());
        }

        // Manually un-check s1.
        markStepDone(goalId, p1, s1, false);

        // Phase 1 must remain COMPLETED — sticky.
        Phase phase1After = phases.findById(USER, goalId, p1).orElseThrow();
        if (phase1After.status() != PhaseStatus.COMPLETED) {
            throw new AssertionError("expected sticky COMPLETED, got " + phase1After.status());
        }
        if (phase1After.completedAt() == null) {
            throw new AssertionError("expected completedAt to persist");
        }
    }

    @Test
    void completedGoalStaysCompletedWhenLastStepUnchecked() throws Exception {
        String goalId = createGoal();
        String p1 = createPhase(goalId, "Only Phase");
        String s1 = createManualStep(goalId, p1, "Step 1");
        markStepDone(goalId, p1, s1, true);

        Goal g = goals.findById(USER, goalId).orElseThrow();
        if (g.status() != GoalStatus.COMPLETED) {
            throw new AssertionError("setup failed: " + g.status());
        }

        markStepDone(goalId, p1, s1, false);
        Goal after = goals.findById(USER, goalId).orElseThrow();
        if (after.status() != GoalStatus.COMPLETED) {
            throw new AssertionError("expected sticky COMPLETED, got " + after.status());
        }
    }

    // helpers

    private String createGoal() throws Exception {
        CreateGoalRequest req = new CreateGoalRequest(
            "Goal " + java.util.UUID.randomUUID(),
            "desc", GoalDomain.METABOLIC,
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

    private String createManualStep(String goalId, String phaseId, String title) throws Exception {
        CreateStepRequest req = new CreateStepRequest(title, StepKind.MANUAL, null);
        MvcResult res = mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
            .get("stepId").asText();
    }

    private void markStepDone(String goalId, String phaseId, String stepId, boolean done) throws Exception {
        UpdateStepRequest req = new UpdateStepRequest(null, null, done, null, null);
        mvc.perform(patch("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps/" + stepId)
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }
}
