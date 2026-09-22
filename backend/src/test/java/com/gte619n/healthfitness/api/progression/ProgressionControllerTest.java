package com.gte619n.healthfitness.api.progression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the progression API surface wires in a real Spring context (IMPL-PROG-01 §12). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class ProgressionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProgressionStateRepository states;

    private static final String USER = "user-prog-1";

    @Test
    void stateReturns404WhenAbsentAnd200WhenPresent() throws Exception {
        mvc.perform(get("/api/me/progression/state/ex1").header("X-Dev-User", USER))
            .andExpect(status().isNotFound());

        states.save(new ProgressionState(USER, "ex1", 200.0, 6.0, Instant.parse("2026-09-01T00:00:00Z"),
            8, 0, Instant.parse("2026-08-01T00:00:00Z"), 3));

        mvc.perform(get("/api/me/progression/state/ex1").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e1rmLbs").value(200.0))
            .andExpect(jsonPath("$.confidence").value("HIGH"))   // 6/200 = 3% → HIGH
            .andExpect(jsonPath("$.kalmanLive").value(true));    // eligibleAt in the past
    }

    @Test
    void blockParametersDefaultThenManualOverride() throws Exception {
        mvc.perform(get("/api/me/progression/block-parameters").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").exists())
            .andExpect(jsonPath("$.manualOverride").value(false));

        mvc.perform(put("/api/me/progression/block-parameters")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("mode", "RECOVERY"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("RECOVERY"))
            .andExpect(jsonPath("$.manualOverride").value(true));
    }

    @Test
    void weekReviewAndResetRespond() throws Exception {
        mvc.perform(get("/api/me/progression/week-review").header("X-Dev-User", USER))
            .andExpect(status().isOk());
        mvc.perform(post("/api/me/progression/state/ex1/reset").header("X-Dev-User", USER))
            .andExpect(status().isNoContent());
    }

    @Autowired com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository programs;
    @Autowired com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository scheduled;

    /** IMPL-DELOAD-01 P2 gate: /log derives target-vs-achieved rows from completed sessions. */
    @Test
    void progressionLogReturnsTargetVsAchievedRows() throws Exception {
        String user = "user-prog-log";
        programs.save(new com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram(
            user, "p1", "Prog", null, null,
            com.gte619n.healthfitness.core.workoutprogram.ProgramStatus.ACTIVE,
            com.gte619n.healthfitness.core.workoutprogram.ProgramSource.MANUAL,
            null, null, null, java.util.List.of(), null, null, null));

        var rationale = new com.gte619n.healthfitness.core.progression.PrescriptionRationale(
            com.gte619n.healthfitness.core.progression.ProgressionPath.DELOAD,
            com.gte619n.healthfitness.core.progression.Direction.DOWN,
            -5.0, null, -1, com.gte619n.healthfitness.core.progression.Confidence.HIGH,
            java.util.List.of("deload week → 10% lighter, sets ×0.5"));
        var rx = new com.gte619n.healthfitness.core.workoutprogram.Prescription(
            "ohp", 0, 2, 8, 12, null, null, 120, null, null, null,
            java.util.List.of(
                new com.gte619n.healthfitness.core.workoutprogram.LoggedSet(35.0, 12, null, null, null),
                new com.gte619n.healthfitness.core.workoutprogram.LoggedSet(35.0, 12, null, null, null)),
            35.0, "deload week", rationale);
        var day = new com.gte619n.healthfitness.core.workoutprogram.WorkoutDay(
            "d1", "Push", null, null, 0, java.util.List.of(
                new com.gte619n.healthfitness.core.workoutprogram.Block(
                    "b1", com.gte619n.healthfitness.core.exercise.BlockType.MAIN, "Main", 0,
                    java.util.List.of(rx))));
        scheduled.save(new com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout(
            user, "p1", "2026-09-21_d1", java.time.LocalDate.of(2026, 9, 21), "ph1", "d1", "Push",
            4, true, null, com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus.COMPLETED,
            day, Instant.parse("2026-09-21T17:00:00Z"), 3600, null));

        mvc.perform(get("/api/me/progression/log").param("exerciseId", "ohp")
                .header("X-Dev-User", user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exerciseId").value("ohp"))
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].date").value("2026-09-21"))
            .andExpect(jsonPath("$.rows[0].path").value("DELOAD"))
            .andExpect(jsonPath("$.rows[0].direction").value("DOWN"))
            .andExpect(jsonPath("$.rows[0].targetWeightLbs").value(35.0))
            .andExpect(jsonPath("$.rows[0].topSetWeightLbs").value(35.0))
            .andExpect(jsonPath("$.rows[0].topSetReps").value(12))
            .andExpect(jsonPath("$.rows[0].loggedSetCount").value(2))
            .andExpect(jsonPath("$.rows[0].isDeload").value(true));
    }
}
