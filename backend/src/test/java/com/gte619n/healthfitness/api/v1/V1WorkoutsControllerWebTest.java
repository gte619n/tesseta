package com.gte619n.healthfitness.api.v1;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// Web-layer contract for the /v1 read API (ADR-0020, D12): the {data, nextCursor,
// hasMore} envelope, entity->DTO mapping, cursor paging, and RFC 7807
// problem+json on a miss. Standalone MockMvc (no security) so it exercises the
// controller + V1ProblemAdvice without fighting the token decoder; scope
// enforcement is covered separately (V1ScopeEnforcementTest, PlatformAudienceFilterTest).
class V1WorkoutsControllerWebTest {

    private final WorkoutProgramRepository programs = Mockito.mock(WorkoutProgramRepository.class);
    private final ScheduledWorkoutRepository scheduled = Mockito.mock(ScheduledWorkoutRepository.class);
    private final CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
    private MockMvc mvc;

    private static WorkoutProgram program(String id, String title, String updatedAt) {
        return new WorkoutProgram(
            "user-1", id, title, "desc", null, ProgramStatus.ACTIVE, null,
            LocalDate.parse("2026-01-01"), null, null, List.of(),
            Instant.parse(updatedAt), Instant.parse(updatedAt), null, null);
    }

    @BeforeEach
    void setUp() {
        when(currentUser.get()).thenReturn(new CurrentUser("user-1", null, null, null));
        ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders
            .standaloneSetup(new V1WorkoutsController(currentUser, programs, scheduled))
            .setControllerAdvice(new V1ProblemAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
            .build();
    }

    @Test
    void listProgramsReturnsPagedEnvelopeNewestFirst() throws Exception {
        when(programs.findByUser("user-1")).thenReturn(List.of(
            program("p1", "Base", "2026-01-01T00:00:00Z"),
            program("p3", "Peak", "2026-03-01T00:00:00Z"),
            program("p2", "Build", "2026-02-01T00:00:00Z")));

        mvc.perform(get("/v1/programs").param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].id").value("p3"))
            .andExpect(jsonPath("$.data[0].title").value("Peak"))
            .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data[1].id").value("p2"))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void unknownProgramYieldsProblemJson404() throws Exception {
        when(programs.findById("user-1", "nope")).thenReturn(Optional.empty());

        mvc.perform(get("/v1/programs/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("program not found"));
    }

    @Test
    void badCursorYieldsProblemJson400() throws Exception {
        when(programs.findByUser("user-1")).thenReturn(List.of(
            program("p1", "Base", "2026-01-01T00:00:00Z")));

        mvc.perform(get("/v1/programs").param("cursor", "!!!not-valid!!!"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }
}
