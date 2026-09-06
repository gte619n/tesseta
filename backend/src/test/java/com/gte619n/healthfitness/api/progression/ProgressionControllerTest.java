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
}
