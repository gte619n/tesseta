package com.gte619n.healthfitness.api.workoutstats;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice for the IMPL-WEB-WORKOUT-01 read endpoints: the stats summary,
 * the e1RM history (200-with-empty for unknown ids), the {@code weeks} clamp,
 * and the single-session detail GET (owner-scoping 404 + prSetKeys + neighbors —
 * the BT-13 controller surface). Auth via the {@code X-Dev-User} test header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutStatsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;

    private static final String USER = "user-stats-ctl";

    @BeforeEach
    void setUp() {
        ((InMemoryWorkoutProgramRepository) programs).clear();
        ((InMemoryScheduledWorkoutRepository) scheduled).clear();
    }

    @Test
    void statsSummaryReturnsSeriesAndStreakShape() throws Exception {
        seedProgram("p1");
        LocalDate d = LocalDate.now().minusDays(3);
        seedCompleted("p1", d, List.of(new LoggedSet(135.0, 8, null, null, instant(d))));

        mvc.perform(get("/api/me/workout-stats?weeks=12").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.streak.weeklyTarget").value(4))
            .andExpect(jsonPath("$.weeklySeries.length()").value(12))
            .andExpect(jsonPath("$.trackedExercises.length()").value(1))
            .andExpect(jsonPath("$.trackedExercises[0].exerciseId").value("bench"));
    }

    @Test
    void weeksParamIsClampedToRange() throws Exception {
        seedProgram("p1");
        // Above the max (104) clamps down; below the min (4) clamps up.
        mvc.perform(get("/api/me/workout-stats?weeks=9999").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklySeries.length()").value(104));
        mvc.perform(get("/api/me/workout-stats?weeks=1").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklySeries.length()").value(4));
    }

    @Test
    void e1rmHistoryForUnknownExerciseIs200WithEmptyPoints() throws Exception {
        mvc.perform(get("/api/me/workout-stats/e1rm-history?exerciseId=nope").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exerciseId").value("nope"))
            .andExpect(jsonPath("$.points.length()").value(0))
            .andExpect(jsonPath("$.currentBelief").doesNotExist());
    }

    @Test
    void statsRequiresAuth() throws Exception {
        mvc.perform(get("/api/me/workout-stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionDetailReturnsPrKeysAndNeighborsAnd404sOnUnknown() throws Exception {
        seedProgram("p1");
        LocalDate d1 = LocalDate.now().minusWeeks(2);
        LocalDate d2 = LocalDate.now().minusWeeks(1);
        seedCompleted("p1", d1, List.of(new LoggedSet(185.0, 5, null, null, instant(d1))));
        // d2 is a PR (heavier) with the best set at index 0.
        seedCompleted("p1", d2, List.of(new LoggedSet(205.0, 5, null, null, instant(d2))));

        mvc.perform(get("/api/me/workout-programs/p1/sessions/" + d2 + "_d1").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.scheduledId").value(d2 + "_d1"))
            .andExpect(jsonPath("$.session.programTitle").value("p1"))
            .andExpect(jsonPath("$.prSetKeys[0]").value("b1:0:0"))
            .andExpect(jsonPath("$.prev.scheduledId").value(d1 + "_d1"))
            .andExpect(jsonPath("$.next").doesNotExist());

        // Unknown session → 404.
        mvc.perform(get("/api/me/workout-programs/p1/sessions/1999-01-01_d1").header("X-Dev-User", USER))
            .andExpect(status().isNotFound());
        // Unknown program → 404.
        mvc.perform(get("/api/me/workout-programs/nope/sessions/" + d2 + "_d1").header("X-Dev-User", USER))
            .andExpect(status().isNotFound());
    }

    // ---- fixtures ----

    private void seedProgram(String programId) {
        programs.save(new WorkoutProgram(USER, programId, programId, null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
    }

    private void seedCompleted(String programId, LocalDate date, List<LoggedSet> sets) {
        WorkoutDay day = new WorkoutDay("d1", "Day", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription("bench", 0, 3, 5, 8, null, null, 120, null, null, null, sets)))));
        scheduled.save(new ScheduledWorkout(
            USER, programId, date + "_d1", date, "ph1", "d1", "Day",
            1, false, "gym-1", ScheduledStatus.COMPLETED, day, instant(date), 3600, null));
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC);
    }
}
