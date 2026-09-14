package com.gte619n.healthfitness.api.nutrition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * XPLAT-001: the nutrition "today" endpoints resolve the caller's local calendar
 * day from the {@code X-Timezone} header (or an explicit {@code ?date=}), not the
 * server's UTC clock. This guards the evening-web-log-on-the-wrong-day class: an
 * instant near midnight UTC maps to different calendar days depending on the
 * caller's zone, and {@code GET /today} must follow the header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class NutritionTodayTimezoneTest {

    @Autowired MockMvc mvc;

    private static final String USER = "user-tz-today";

    // A zone far to the east (UTC+14) and far to the west (UTC-11); across the
    // whole 24h clock, for at least one of these the local calendar day differs
    // from the UTC day, exercising the boundary. We compute the expected day the
    // same way the controller does, so the assertion holds at any wall-clock time.
    private static final ZoneId FAR_EAST = ZoneId.of("Pacific/Kiritimati"); // UTC+14
    private static final ZoneId FAR_WEST = ZoneId.of("Pacific/Pago_Pago");  // UTC-11

    @Test
    void todayFollowsTheTimezoneHeader() throws Exception {
        assertTodayMatchesZone(FAR_EAST);
        assertTodayMatchesZone(FAR_WEST);
    }

    @Test
    void explicitDateParamWinsOverHeader() throws Exception {
        LocalDate pinned = LocalDate.of(2001, 2, 3);
        logDayTotals(pinned, 11);
        // Header says one zone, but ?date= is explicit and must win.
        mvc.perform(get("/api/me/nutrition/today")
                .header("X-Dev-User", USER)
                .header(RequestTimeZone.HEADER, FAR_EAST.getId())
                .param("date", pinned.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proteinGrams").value(11.0));
    }

    @Test
    void missingHeaderFallsBackToUtcDay() throws Exception {
        LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
        logDayTotals(utcToday, 22);
        mvc.perform(get("/api/me/nutrition/today")
                .header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proteinGrams").value(22.0));
    }

    private void assertTodayMatchesZone(ZoneId zone) throws Exception {
        LocalDate localToday = LocalDate.now(zone);
        double protein = zone.getId().hashCode() & 0xff; // distinct per zone
        logDayTotals(localToday, protein);
        mvc.perform(get("/api/me/nutrition/today")
                .header("X-Dev-User", USER)
                .header(RequestTimeZone.HEADER, zone.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proteinGrams").value(protein));
    }

    private void logDayTotals(LocalDate date, double protein) throws Exception {
        mvc.perform(post("/api/me/nutrition")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\",\"proteinGrams\":" + protein
                    + ",\"carbsGrams\":0,\"fatGrams\":0,\"caloriesKcal\":0}"))
            .andExpect(status().isCreated());
    }
}
