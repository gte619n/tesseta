package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Phase 1 idempotent-write gate (IMPL-AND-20, D7). POSTing the same
 * {@code Idempotency-Key} twice produces a single document; the replay returns
 * the current state rather than creating a duplicate. Also verifies a
 * client-minted {@code id} is honored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class IdempotentWriteControllerTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-idempotent-write";

    @Test
    void replayedIdempotencyKeyCreatesSingleDocument() throws Exception {
        String key = "idem-key-001";
        String bodyJson = """
            {"marker":"TESTOSTERONE","value":650,"unit":"ng/dL","sampleDate":"2026-05-30"}
            """;

        MvcResult first = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header("Idempotency-Key", key)
                .header("X-HF-Origin-Device", "device-A")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson))
            .andExpect(status().isCreated())
            .andReturn();
        String firstId = json.readTree(first.getResponse().getContentAsString())
            .get("readingId").asText();

        // Same key again ⇒ no new document; returns the original's current state.
        MvcResult second = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header("Idempotency-Key", key)
                .header("X-HF-Origin-Device", "device-A")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson))
            .andExpect(status().isCreated())
            .andReturn();
        String secondId = json.readTree(second.getResponse().getContentAsString())
            .get("readingId").asText();

        assertThat(secondId).isEqualTo(firstId);

        // Exactly one reading exists for the user.
        MvcResult list = mvc.perform(get("/api/me/blood").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = json.readTree(list.getResponse().getContentAsString());
        long matching = 0;
        for (JsonNode n : arr) {
            if (firstId.equals(n.path("readingId").asText())) {
                matching++;
            }
        }
        assertThat(matching).as("idempotent replay must not duplicate").isEqualTo(1);
    }

    @Test
    void honorsClientMintedId() throws Exception {
        String clientId = "11111111-2222-3333-4444-555555555555";
        mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","marker":"HDL","value":55,"unit":"mg/dL","sampleDate":"2026-05-31"}
                    """.formatted(clientId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.readingId").value(clientId));
    }

    @Test
    void noKeyBehavesAsPlainCreate() throws Exception {
        // Two creates without an Idempotency-Key produce two distinct rows
        // (unchanged legacy behaviour).
        String body = """
            {"marker":"LDL","value":90,"unit":"mg/dL","sampleDate":"2026-05-29"}
            """;
        MvcResult a = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", "user-nokey")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        MvcResult b = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", "user-nokey")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        String idA = json.readTree(a.getResponse().getContentAsString()).get("readingId").asText();
        String idB = json.readTree(b.getResponse().getContentAsString()).get("readingId").asText();
        assertThat(idA).isNotEqualTo(idB);
    }
}
