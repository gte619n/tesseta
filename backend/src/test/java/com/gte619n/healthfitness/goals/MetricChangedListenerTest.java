package com.gte619n.healthfitness.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.blood.BloodController;
import com.gte619n.healthfitness.api.goals.dto.CreateGoalRequest;
import com.gte619n.healthfitness.api.goals.dto.CreatePhaseRequest;
import com.gte619n.healthfitness.api.goals.dto.CreateStepRequest;
import com.gte619n.healthfitness.api.goals.dto.StepMetricBindingDto;
import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.LocalDate;
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

/**
 * End-to-end test for the MetricChangedEvent pipeline:
 *
 * <ol>
 *   <li>Create a Goal → Phase → THRESHOLD Step bound to {@code blood.ldl < 100}.</li>
 *   <li>POST a blood reading with LDL = 95 via the real {@link BloodController}.</li>
 *   <li>Assert that the Step is now {@code done = true} — the event listener
 *       in {@link com.gte619n.healthfitness.core.goals.eval.StepEvaluationService}
 *       ran synchronously and wrote the state transition through
 *       {@link com.gte619n.healthfitness.core.goals.GoalService}.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class MetricChangedListenerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StepRepository stepRepo;

    private static final String USER = "user-listener-e2e";

    @Test
    void postingBloodReadingUnderTargetAutoCompletesThresholdStep() throws Exception {
        // 1 — create goal
        CreateGoalRequest goalReq = new CreateGoalRequest(
            null, "Lower LDL", "get to optimal range", GoalDomain.CARDIOVASCULAR,
            null, LocalDate.now().plusMonths(6), null
        );
        MvcResult goalRes = mvc.perform(post("/api/me/goals")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(goalReq)))
            .andExpect(status().isCreated())
            .andReturn();
        String goalId = objectMapper.readTree(goalRes.getResponse().getContentAsString())
            .get("goalId").asText();

        // 2 — add phase
        CreatePhaseRequest phaseReq = new CreatePhaseRequest(null, "Phase 1", null, null, null);
        MvcResult phaseRes = mvc.perform(post("/api/me/goals/" + goalId + "/phases")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(phaseReq)))
            .andExpect(status().isCreated())
            .andReturn();
        String phaseId = objectMapper.readTree(phaseRes.getResponse().getContentAsString())
            .get("phaseId").asText();

        // 3 — add THRESHOLD step: blood.ldl < 100
        CreateStepRequest stepReq = new CreateStepRequest(
            null,
            "LDL under 100",
            StepKind.THRESHOLD,
            new StepMetricBindingDto("blood.ldl", Comparator.LT, 100.0, null, null)
        );
        MvcResult stepRes = mvc.perform(post("/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stepReq)))
            .andExpect(status().isCreated())
            .andReturn();
        String stepId = objectMapper.readTree(stepRes.getResponse().getContentAsString())
            .get("stepId").asText();

        // Confirm step starts undone.
        List<Step> before = stepRepo.findByMetricKey(USER, "blood.ldl");
        assertThat(before).hasSize(1);
        assertThat(before.get(0).done()).isFalse();

        // 4 — POST a blood reading with LDL = 95 (< 100 → condition holds).
        BloodController.CreateRequest bloodReq = new BloodController.CreateRequest(
            null, BloodMarker.LDL, 95.0, "mg/dL", LocalDate.now(), null, null
        );
        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bloodReq)))
            .andExpect(status().isCreated());

        // 5 — the event listener ran synchronously; the step must now be done.
        Step after = stepRepo.findById(USER, goalId, phaseId, stepId).orElseThrow();
        assertThat(after.done())
            .as("THRESHOLD Step should auto-complete when LDL crosses target via event pipeline")
            .isTrue();
        assertThat(after.doneAt()).isNotNull();
    }
}
