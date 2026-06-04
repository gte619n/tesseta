package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.sync.InMemorySyncChangeReader;
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
 * IMPL-AND-20 #37 / D14 — the server-honored {@code recentSince} window bounds
 * the heavy time-series collections (bloodReadings, bodyComposition,
 * dailyMetrics, nutrition entries/daily logs, weeklyWorkoutAggregates) to docs
 * on or after the supplied date, while CRUD domains (medications, goals,
 * locations, …) always return in full. Also asserts cursor continuity: omitting
 * the param (the later unbounded backfill) surfaces the older heavy docs the
 * window skipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class SyncRecentWindowControllerTest {

    @Autowired MockMvc mvc;
    @Autowired InMemorySyncChangeReader reader;
    private final ObjectMapper json = new ObjectMapper();

    private static final String USER = "user-recent-window";

    @BeforeEach
    void reset() {
        reader.clear();
    }

    @Test
    void recentSinceBoundsHeavySeriesButNotCrud() throws Exception {
        // Heavy time-series: one OLD, one RECENT per representative collection.
        reader.put(USER, "bloodReadings", "bOld", Map.of("sampleDate", "2026-01-01"));
        reader.put(USER, "bloodReadings", "bNew", Map.of("sampleDate", "2026-05-30"));
        reader.put(USER, "dailyMetrics", "dOld", Map.of("date", "2026-01-02"));
        reader.put(USER, "dailyMetrics", "dNew", Map.of("date", "2026-05-29"));
        reader.put(USER, "nutritionDays/entries", "2026-01-03/e1", Map.of("date", "2026-01-03"));
        reader.put(USER, "nutritionDays/entries", "2026-05-28/e2", Map.of("date", "2026-05-28"));
        // CRUD domains: always returned in full, regardless of any date field.
        reader.put(USER, "medications", "m1", Map.of("dose", 5.0));
        reader.put(USER, "goals", "g1", Map.of("title", "Goal"));

        // Window from 2026-05-19 (≈ today − 14d for an early-June first sync).
        Set<String> windowed = collectKeys("/api/me/sync?recentSince=2026-05-19&limit=500");

        // Heavy: only the recent docs survive; the old ones are bounded out.
        assertThat(windowed).contains("bloodReadings/bNew", "dailyMetrics/dNew",
            "nutritionDays/entries/2026-05-28/e2");
        assertThat(windowed).doesNotContain("bloodReadings/bOld", "dailyMetrics/dOld",
            "nutritionDays/entries/2026-01-03/e1");
        // CRUD: present in full even though they predate / lack a sample date.
        assertThat(windowed).contains("medications/m1", "goals/g1");

        // Backfill: omit the param ⇒ the older heavy docs now appear, so the
        // cursor continues correctly into the unbounded history (no skip).
        Set<String> backfill = collectKeys("/api/me/sync?limit=500");
        assertThat(backfill).contains("bloodReadings/bOld", "dailyMetrics/dOld",
            "nutritionDays/entries/2026-01-03/e1", "bloodReadings/bNew",
            "medications/m1", "goals/g1");
    }

    /** Page fully through the given sync URL and return the set of collection/id keys. */
    private Set<String> collectKeys(String firstUrl) throws Exception {
        Set<String> keys = new HashSet<>();
        String cursor = null;
        boolean hasMore;
        int pages = 0;
        do {
            String url = firstUrl + (cursor != null ? "&since=" + cursor : "");
            MvcResult res = mvc.perform(get(url).header("X-Dev-User", USER))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = json.readTree(res.getResponse().getContentAsString());
            for (JsonNode c : body.get("changes")) {
                keys.add(c.get("collection").asText() + "/" + c.get("id").asText());
            }
            cursor = body.get("nextCursor").asText(null);
            hasMore = body.get("hasMore").asBoolean();
            assertThat(++pages).isLessThan(50);
        } while (hasMore);
        return keys;
    }
}
