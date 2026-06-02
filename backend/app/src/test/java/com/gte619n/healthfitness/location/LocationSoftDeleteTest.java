package com.gte619n.healthfitness.location;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.location.CreateLocationRequest;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.testsupport.InMemoryLocationRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import org.junit.jupiter.api.BeforeEach;
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
 * Phase 0 functional gate (IMPL-AND-20) — second collection: a DELETE on a
 * gym/location is a soft-delete (tombstone). The row drops out of list reads
 * including {@code ?include=inactive} (the includeInactive flag is the domain
 * toggle, not the sync lifecycle), and is not hard-removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class LocationSoftDeleteTest {

    @Autowired MockMvc mvc;
    @Autowired LocationRepository locationRepository;
    @Autowired ObjectMapper json;

    private static final String USER = "user-location-softdelete";

    @BeforeEach
    void setUp() {
        ((InMemoryLocationRepository) locationRepository).clear();
    }

    @Test
    void deleteArchivesLocationAndExcludesItEvenWithIncludeInactive() throws Exception {
        CreateLocationRequest request = new CreateLocationRequest(
            null, "Gold's Gym", null, false, null, null, null);

        MvcResult created = mvc.perform(post("/api/me/gyms")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locationId").exists())
            // A freshly-created record (no syncStatus written by client) is live.
            .andExpect(jsonPath("$.isActive").value(true))
            .andReturn();

        String locationId = json.readTree(created.getResponse().getContentAsString())
            .get("locationId").asText();

        // Listed while active.
        mvc.perform(get("/api/me/gyms").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.locationId == '" + locationId + "')]").exists());

        // Soft-delete.
        mvc.perform(delete("/api/me/gyms/{id}", locationId).header("X-Dev-User", USER))
            .andExpect(status().isNoContent());

        // Excluded from the default list.
        assertAbsent(locationId, "/api/me/gyms");
        // Excluded even from include=inactive (tombstone, not just inactive).
        assertAbsent(locationId, "/api/me/gyms?include=inactive");
    }

    private void assertAbsent(String locationId, String url) throws Exception {
        MvcResult result = mvc.perform(get(url).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode list = json.readTree(result.getResponse().getContentAsString());
        for (JsonNode node : list) {
            if (locationId.equals(node.path("locationId").asText())) {
                org.assertj.core.api.Assertions.fail(
                    "archived location must be excluded from " + url);
            }
        }
    }
}
