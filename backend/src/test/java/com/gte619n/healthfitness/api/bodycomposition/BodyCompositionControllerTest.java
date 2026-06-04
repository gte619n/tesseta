package com.gte619n.healthfitness.api.bodycomposition;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Slice test for /api/me/body-composition. The interesting behaviour is the
// query-routing: only a fully-specified metric+from+to triple takes the
// indexed range query; anything else falls back to the whole-user read. This
// pins both branches and the enum/Instant query-param binding that selects
// them.
@WebMvcTest(BodyCompositionController.class)
@AutoConfigureMockMvc(addFilters = false)
class BodyCompositionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean BodyCompositionRepository measurements;
    @MockitoBean CurrentUserProvider currentUser;

    private static final CurrentUser CALLER =
        new CurrentUser("sub-1", "a@b.com", "A", null);

    private static BodyCompositionMeasurement weight(double kg) {
        return new BodyCompositionMeasurement(
            "sub-1", "rec-1", BodyCompositionMetric.WEIGHT_KG, kg,
            Instant.parse("2026-06-01T00:00:00Z"), "HEALTH_CONNECT", "MANUAL_ENTRY",
            null, null);
    }

    @Test
    void listWithoutParamsReadsWholeUserHistory() throws Exception {
        when(currentUser.get()).thenReturn(CALLER);
        when(measurements.findByUser("sub-1")).thenReturn(List.of(weight(82.5)));

        mvc.perform(get("/api/me/body-composition"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].recordId").value("rec-1"))
            .andExpect(jsonPath("$[0].metric").value("WEIGHT_KG"))
            .andExpect(jsonPath("$[0].value").value(82.5));

        verify(measurements).findByUser("sub-1");
        verify(measurements, never()).findByUserAndRange(
            ArgumentMatchers.any(), ArgumentMatchers.any(),
            ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void listWithMetricAndRangeUsesIndexedRangeQuery() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");
        when(currentUser.get()).thenReturn(CALLER);
        when(measurements.findByUserAndRange("sub-1", BodyCompositionMetric.WEIGHT_KG, from, to))
            .thenReturn(List.of(weight(81.0)));

        mvc.perform(get("/api/me/body-composition")
                .param("metric", "WEIGHT_KG")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-06-01T00:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].value").value(81.0));

        verify(measurements).findByUserAndRange(
            eq("sub-1"), eq(BodyCompositionMetric.WEIGHT_KG), eq(from), eq(to));
        verify(measurements, never()).findByUser(ArgumentMatchers.any());
    }
}
