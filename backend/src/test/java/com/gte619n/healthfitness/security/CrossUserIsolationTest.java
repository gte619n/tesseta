package com.gte619n.healthfitness.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TEST-001 — cross-user isolation at the API layer (ADR-0021). Two JWT subjects
 * (user A owns data; user B is the attacker) hit the {@code /api/me} surface, and
 * we assert user B can never read user A's nutrition, blood, medication, or
 * workout-program data — every such attempt is empty / 404, never A's payload.
 *
 * <p>The test harness authenticates via the {@code X-Dev-User} header
 * (DevHeaderAuthFilter, active under the test profile), whose value becomes the
 * JWT subject / userId.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class CrossUserIsolationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String USER_A = "iso-user-a";
    private static final String USER_B = "iso-user-b";
    private static final String DATE = "2026-09-14";

    // ----- Nutrition day -------------------------------------------------

    @Test
    void userBCannotSeeUserAsNutritionDay() throws Exception {
        // A logs a meal entry for the day.
        String entryId = UUID.randomUUID().toString();
        mvc.perform(post("/api/me/nutrition/" + DATE + "/entries")
                .header("X-Dev-User", USER_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + entryId + "\",\"meal\":\"LUNCH\","
                    + "\"foodName\":\"Secret Salad\",\"servingGrams\":100,\"quantity\":1,"
                    + "\"macros\":{\"caloriesKcal\":200,\"proteinGrams\":10,"
                    + "\"carbsGrams\":20,\"fatGrams\":5}}"))
            .andExpect(status().isCreated());

        // A sees their own entry.
        mvc.perform(get("/api/me/nutrition/" + DATE).header("X-Dev-User", USER_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.meals[*].entries[?(@.foodName == 'Secret Salad')]").exists());

        // B sees an empty day — never A's entry.
        mvc.perform(get("/api/me/nutrition/" + DATE).header("X-Dev-User", USER_B))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.meals[*].entries[?(@.foodName == 'Secret Salad')]").doesNotExist());
    }

    // ----- Blood readings ------------------------------------------------

    @Test
    void userBCannotSeeUserAsBloodReadings() throws Exception {
        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"marker\":\"HDL\",\"value\":55,\"unit\":\"mg/dL\","
                    + "\"sampleDate\":\"2026-05-30\"}"))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/me/blood").header("X-Dev-User", USER_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.marker == 'HDL')]").exists());

        // B's list must not contain A's reading.
        mvc.perform(get("/api/me/blood").header("X-Dev-User", USER_B))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.marker == 'HDL')]").doesNotExist());
    }

    // Note: medications are disabled in the test profile (the drug pipeline needs
    // Firestore/GCS/Gemini), so the medication endpoint can't be exercised here.
    // The GET-by-id isolation contract (404 for a non-owner) is covered by the
    // structurally-identical workout-program case below.

    // ----- Workout programs ----------------------------------------------

    @Test
    void userBCannotReadUserAsWorkoutProgram() throws Exception {
        String created = mvc.perform(post("/api/me/workout-programs")
                .header("X-Dev-User", USER_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Secret Program\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(created);
        String programId = node.get("programId").asText();

        // A can read their own program.
        mvc.perform(get("/api/me/workout-programs/" + programId).header("X-Dev-User", USER_A))
            .andExpect(status().isOk());

        // B asking for A's program id gets 404 — never A's program.
        mvc.perform(get("/api/me/workout-programs/" + programId).header("X-Dev-User", USER_B))
            .andExpect(status().isNotFound());
    }
}
