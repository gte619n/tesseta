package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.sync.InMemorySyncChangeReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 1 delta-read gate (IMPL-AND-20): create N docs across ≥2 collections
 * plus one tombstone, then page through {@code GET /api/me/sync} from an empty
 * cursor with a small {@code limit} to {@code hasMore=false}. Asserts the full
 * set — including the tombstone (status ARCHIVED, doc null) — returns exactly
 * once with no duplicates and no skips across pages.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class SyncDeltaControllerTest {

    @Autowired MockMvc mvc;
    @Autowired InMemorySyncChangeReader reader;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-sync-delta";

    @BeforeEach
    void reset() {
        reader.clear();
    }

    @Test
    void pagesThroughAllChangesIncludingTombstoneExactlyOnce() throws Exception {
        // Seed 4 live docs across 3 collections + a 5th that is then archived.
        reader.put(USER, "bloodReadings", "b1", Map.of("marker", "HDL", "value", 55));
        reader.put(USER, "medications", "m1", Map.of("dose", 5.0));
        reader.put(USER, "medications", "m2", Map.of("dose", 10.0));
        reader.put(USER, "locations", "l1", Map.of("name", "Gym"));
        reader.put(USER, "bloodReadings", "b2", Map.of("marker", "LDL", "value", 90));
        // Soft-delete one of them ⇒ tombstone (newer timestamp, doc null).
        reader.archive(USER, "bloodReadings", "b2");

        // Page through with a small limit.
        Set<String> seenActive = new HashSet<>();
        Map<String, String> statusByKey = new HashMap<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore;
        do {
            StringBuilder url = new StringBuilder("/api/me/sync?limit=2");
            if (cursor != null) {
                url.append("&since=").append(cursor);
            }
            MvcResult res = mvc.perform(get(url.toString()).header("X-Dev-User", USER))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = json.readTree(res.getResponse().getContentAsString());

            assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(body.get("killSwitch").asBoolean()).isFalse();
            assertThat(body.has("serverTime")).isTrue();

            for (JsonNode change : body.get("changes")) {
                String key = change.get("collection").asText() + "/" + change.get("id").asText();
                String prior = statusByKey.put(key, change.get("status").asText());
                assertThat(prior).as("no duplicate change for " + key).isNull();
                if ("ARCHIVED".equals(change.get("status").asText())) {
                    assertThat(change.get("doc").isNull()).as("tombstone doc is null").isTrue();
                } else {
                    seenActive.add(key);
                    assertThat(change.get("doc").isNull()).isFalse();
                }
            }
            cursor = body.get("nextCursor").asText(null);
            hasMore = body.get("hasMore").asBoolean();
            pages++;
            assertThat(pages).as("pagination terminates").isLessThan(20);
        } while (hasMore);

        // b2 was archived after creation, so only its tombstone (latest state)
        // is emitted. Active set: b1, m1, m2, l1. Tombstone: bloodReadings/b2.
        assertThat(statusByKey).containsEntry("bloodReadings/b2", "ARCHIVED");
        assertThat(seenActive).containsExactlyInAnyOrder(
            "bloodReadings/b1", "medications/m1", "medications/m2", "locations/l1");
        assertThat(statusByKey).hasSize(5);
        assertThat(pages).isGreaterThan(1); // proves multi-page paging
    }

    @Test
    void malformedCursorReturns400() throws Exception {
        mvc.perform(get("/api/me/sync?since=%%%notbase64%%%").header("X-Dev-User", USER))
            .andExpect(status().isBadRequest());
    }

    @Test
    void emptyResultHasNoMore() throws Exception {
        MvcResult res = mvc.perform(get("/api/me/sync").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("changes")).isEmpty();
        assertThat(body.get("hasMore").asBoolean()).isFalse();
    }
}
