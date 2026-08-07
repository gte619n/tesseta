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
 * Proves the {@code Idempotency-Key} replay guard now covers {@code POST /api/foods}
 * (the label / meal-item confirm path a durable client op-worker replays). Posting
 * the same key twice returns the original food's id and never creates a duplicate
 * catalog food — the prerequisite that lets the Android client retry these creates
 * safely across process death.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class IdempotentFoodCreateControllerTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-idempotent-foods";

    @Test
    void foodCreateReplayIsSingleDocument() throws Exception {
        String key = "food-idem-001";
        // A unique name so the catalog search below matches only our own creates.
        String name = "Zzq Idempotent Test Food 8412";
        String body = "{\"name\":\"" + name + "\",\"macrosPer100g\":{\"caloriesKcal\":100}}";

        String firstId = postId("/api/foods", key, body);
        String secondId = postId("/api/foods", key, body);
        assertThat(secondId).isEqualTo(firstId);

        // Exactly one catalog food with this name (replay must not create a second).
        MvcResult res = mvc.perform(
                get("/api/foods/search").param("q", name).header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode results = json.readTree(res.getResponse().getContentAsString());
        long matching = 0;
        for (JsonNode f : results) {
            if (name.equalsIgnoreCase(f.path("name").asText())) {
                matching++;
            }
        }
        assertThat(matching).as("food create replay must not duplicate").isEqualTo(1);
    }

    /** POST a create, asserting 201, and return the foodId from the body. */
    private String postId(String url, String idempotencyKey, String body) throws Exception {
        var req = post(url)
            .header("X-Dev-User", USER)
            .header("X-HF-Origin-Device", "device-A")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        MvcResult res = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("foodId").asText();
    }
}
