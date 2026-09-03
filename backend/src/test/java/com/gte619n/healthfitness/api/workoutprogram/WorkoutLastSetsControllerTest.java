package com.gte619n.healthfitness.api.workoutprogram;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The resilient {@code POST .../last-sets}: previous-set prefill resolved from
 * exerciseIds the client supplies, so it works even when the current session
 * doesn't exist server-side yet (offline-first / ad-hoc run past the program's
 * materialized schedule). The GET-by-scheduledId variant 404s in that window;
 * this one must not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutLastSetsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;

    private static final String TEST_USER = "user-123";
    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        ((InMemoryWorkoutProgramRepository) programs).clear();
        ((InMemoryScheduledWorkoutRepository) scheduled).clear();
        seedProgram("p1");
        // The last time "bench" was done: two sets, five days ago.
        seedCompleted("p1", TODAY.minusDays(5), "bench",
            List.of(new LoggedSet(135.0, 8, 7.0, 90, instant(TODAY.minusDays(5))),
                new LoggedSet(185.0, 5, 9.0, 120, instant(TODAY.minusDays(5)))));
    }

    @Test
    void returnsLastSessionSetsForRequestedIdsWithoutAScheduledSession() throws Exception {
        // No session doc for today exists (this is the ad-hoc / not-yet-persisted
        // case that 404s the GET variant) — the POST must still answer.
        String body = objectMapper.writeValueAsString(
            new LastSetsRequest(List.of("bench", "never-done")));

        mvc.perform(post("/api/me/workout-programs/p1/last-sets")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bench.length()").value(2))
            .andExpect(jsonPath("$.bench[0].weightLbs").value(135.0))
            .andExpect(jsonPath("$.bench[0].reps").value(8))
            .andExpect(jsonPath("$.bench[1].weightLbs").value(185.0))
            // An exercise with no history is simply absent from the map.
            .andExpect(jsonPath("$['never-done']").doesNotExist());
    }

    @Test
    void emptyIdListReturnsAnEmptyMap() throws Exception {
        mvc.perform(post("/api/me/workout-programs/p1/last-sets")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LastSetsRequest(List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bench").doesNotExist());
    }

    // --- helpers ---

    private void seedProgram(String programId) {
        programs.save(new WorkoutProgram(TEST_USER, programId, programId, null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
    }

    private void seedCompleted(String programId, LocalDate date, String exerciseId, List<LoggedSet> sets) {
        WorkoutDay day = new WorkoutDay("d1", "Day", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription(exerciseId, 0, 3, 5, 8, null, null, 120, null, null, null, sets)))));
        scheduled.save(new ScheduledWorkout(
            TEST_USER, programId, date + "_d1", date, "ph1", "d1", "Day",
            1, false, "gym-1", ScheduledStatus.COMPLETED, day,
            instant(date), 3600, null));
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(18, 0).toInstant(ZoneOffset.UTC);
    }
}
