package com.gte619n.healthfitness.blood;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Phase 0 functional gate (IMPL-AND-20): a DELETE on an in-scope per-user
 * collection is a soft-delete (tombstone) — the record stops appearing in
 * list reads but is not hard-removed, and a record with no syncStatus field
 * reads back as ACTIVE (is returned by list).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class BloodSoftDeleteTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-blood-softdelete";

    @Test
    void deleteArchivesReadingAndExcludesItFromList() throws Exception {
        // Create a reading (no syncStatus is ever sent by the client; the
        // server stamps it). It must read back as a live row.
        MvcResult created = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"marker":"TESTOSTERONE","value":650,"unit":"ng/dL","sampleDate":"2026-05-30"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.readingId").exists())
            .andReturn();

        String readingId = json.readTree(created.getResponse().getContentAsString())
            .get("readingId").asText();

        // A freshly-created record (effectively ACTIVE / no archive) is listed.
        mvc.perform(get("/api/me/blood").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.readingId == '" + readingId + "')]").exists());

        // Delete it — soft-delete tombstone.
        mvc.perform(delete("/api/me/blood/{id}", readingId)
                .header("X-Dev-User", USER))
            .andExpect(status().isNoContent());

        // The tombstoned row must NOT come back from the list.
        MvcResult afterDelete = mvc.perform(get("/api/me/blood").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode list = json.readTree(afterDelete.getResponse().getContentAsString());
        boolean stillPresent = false;
        for (JsonNode node : list) {
            if (readingId.equals(node.path("readingId").asText())) {
                stillPresent = true;
                break;
            }
        }
        org.assertj.core.api.Assertions.assertThat(stillPresent)
            .as("deleted reading must be excluded from list (tombstone)")
            .isFalse();
    }

    @Test
    void recordWithoutStatusReadsBackAsActiveAndIsListed() throws Exception {
        // The client never writes syncStatus; the create path is the "missing
        // status reads as ACTIVE" case end-to-end — the row is returned.
        MvcResult created = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"marker":"HDL","value":55,"unit":"mg/dL","sampleDate":"2026-05-31"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();

        String readingId = json.readTree(created.getResponse().getContentAsString())
            .get("readingId").asText();

        mvc.perform(get("/api/me/blood").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.readingId == '" + readingId + "')]").exists());
    }
}
