package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * IMPL-AND-20 #8 — proves the {@code Idempotency-Key} replay guard yields a
 * single document for the nested/in-scope create endpoints brought under the
 * {@code SyncWriteContext} contract: goal <b>phases</b> POST, goal <b>steps</b>
 * POST, and <b>nutrition entries</b> POST. Posting the same key twice returns
 * the original document's current state (same id) and never creates a
 * duplicate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class IdempotentNestedWriteControllerTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-idempotent-nested";

    @Test
    void goalPhaseCreateReplayIsSingleDocument() throws Exception {
        String goalId = createGoal();
        String key = "phase-idem-001";
        String body = "{\"title\":\"Phase A\"}";

        String firstId = postId("/api/me/goals/" + goalId + "/phases", key, body, "phaseId");
        String secondId = postId("/api/me/goals/" + goalId + "/phases", key, body, "phaseId");
        assertThat(secondId).isEqualTo(firstId);

        // Exactly one phase on the goal (replay must not append a second).
        MvcResult deep = mvc.perform(get("/api/me/goals/" + goalId).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode phases = json.readTree(deep.getResponse().getContentAsString()).get("phases");
        long matching = 0;
        for (JsonNode p : phases) {
            if (firstId.equals(p.path("phaseId").asText())) {
                matching++;
            }
        }
        assertThat(matching).as("phase replay must not duplicate").isEqualTo(1);
    }

    @Test
    void goalStepCreateReplayIsSingleDocument() throws Exception {
        String goalId = createGoal();
        String phaseId = postId("/api/me/goals/" + goalId + "/phases", null,
            "{\"title\":\"Phase A\"}", "phaseId");

        String key = "step-idem-001";
        String body = "{\"title\":\"Do a thing\",\"kind\":\"MANUAL\"}";
        String base = "/api/me/goals/" + goalId + "/phases/" + phaseId + "/steps";

        String firstId = postId(base, key, body, "stepId");
        String secondId = postId(base, key, body, "stepId");
        assertThat(secondId).isEqualTo(firstId);

        // Exactly one step under the phase.
        MvcResult deep = mvc.perform(get("/api/me/goals/" + goalId).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode phases = json.readTree(deep.getResponse().getContentAsString()).get("phases");
        long matching = 0;
        for (JsonNode p : phases) {
            for (JsonNode s : p.path("steps")) {
                if (firstId.equals(s.path("stepId").asText())) {
                    matching++;
                }
            }
        }
        assertThat(matching).as("step replay must not duplicate").isEqualTo(1);
    }

    @Test
    void nutritionEntryCreateReplayIsSingleDocument() throws Exception {
        String date = "2026-05-30";
        String key = "entry-idem-001";
        String body = """
            {"meal":"BREAKFAST","foodName":"Oats","servingGrams":80,"quantity":1,
             "macros":{"caloriesKcal":300,"proteinGrams":10,"carbsGrams":50,"fatGrams":5,
                       "fiberGrams":8,"sugarGrams":2},"source":"MANUAL"}
            """;
        String base = "/api/me/nutrition/" + date + "/entries";

        String firstId = postId(base, key, body, "entryId");
        String secondId = postId(base, key, body, "entryId");
        assertThat(secondId).isEqualTo(firstId);

        // Exactly one entry for the day.
        MvcResult day = mvc.perform(get("/api/me/nutrition/" + date).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode meals = json.readTree(day.getResponse().getContentAsString()).get("meals");
        long matching = 0;
        for (JsonNode m : meals) {
            for (JsonNode e : m.path("entries")) {
                if (firstId.equals(e.path("entryId").asText())) {
                    matching++;
                }
            }
        }
        assertThat(matching).as("entry replay must not duplicate").isEqualTo(1);
    }

    // helpers

    private String createGoal() throws Exception {
        String body = """
            {"title":"Strength","domain":"STRENGTH","targetDate":"2026-12-01"}
            """;
        return postId("/api/me/goals", null, body, "goalId");
    }

    /** POST a create, asserting 201, and return the named id field from the body. */
    private String postId(String url, String idempotencyKey, String body, String idField)
        throws Exception {
        var req = post(url)
            .header("X-Dev-User", USER)
            .header("X-HF-Origin-Device", "device-A")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (idempotencyKey != null) {
            req = req.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult res = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get(idField).asText();
    }
}
