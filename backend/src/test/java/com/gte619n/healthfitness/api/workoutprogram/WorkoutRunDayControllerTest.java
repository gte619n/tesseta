package com.gte619n.healthfitness.api.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
 * The "run any workout as today" path (POST .../sessions): after a program's
 * 4-week window has elapsed, the user can still materialize any day as today's
 * session and — crucially — log it (the completion PUT no longer 404s because
 * the session now exists). Idempotent by the {@code "{date}_{dayId}"} id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutRunDayControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;

    private static final String TEST_USER = "user-123";
    // A program whose 4-week window ended weeks ago — nothing is scheduled today.
    private static final LocalDate START = LocalDate.now().minusWeeks(6);
    private static final String TODAY = LocalDate.now().toString();

    @BeforeEach
    void setUp() {
        ((InMemoryWorkoutProgramRepository) programs).clear();
        ((InMemoryScheduledWorkoutRepository) scheduled).clear();
        seedActiveProgram("p1");
    }

    @Test
    void runDayMaterializesTodaysSessionThenItCanBeLogged() throws Exception {
        String scheduledId = TODAY + "_d1";

        mvc.perform(post("/api/me/workout-programs/p1/sessions")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RunDayRequest("ph1", "d1", null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheduledId").value(scheduledId))
            .andExpect(jsonPath("$.date").value(TODAY))
            .andExpect(jsonPath("$.status").value("PLANNED"));

        // The whole point: the completion PUT succeeds (would 404 before, since
        // the session was never materialized in the program's past window).
        String log = objectMapper.writeValueAsString(new LogSessionRequest(
            ScheduledStatus.COMPLETED, Instant.now(), 1800, List.of()));
        mvc.perform(put("/api/me/workout-programs/p1/sessions/" + scheduledId)
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(log))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        ScheduledWorkout done = scheduled.findById(TEST_USER, "p1", scheduledId).orElseThrow();
        assertEquals(ScheduledStatus.COMPLETED, done.status());
        assertEquals(LocalDate.now(), done.date());
    }

    @Test
    void runDayDatesTheSessionInTheCallersTimeZone() throws Exception {
        // Two zones 26h apart: their local calendar day differs at every instant,
        // so this proves the returned date follows the X-Timezone header rather
        // than the server's own (UTC) clock — independent of when the test runs.
        String body = objectMapper.writeValueAsString(new RunDayRequest("ph1", "d1", null));

        String ahead = LocalDate.now(ZoneId.of("Pacific/Kiritimati")).toString(); // UTC+14
        mvc.perform(post("/api/me/workout-programs/p1/sessions")
                .header("X-Dev-User", TEST_USER)
                .header("X-Timezone", "Pacific/Kiritimati")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(ahead))
            .andExpect(jsonPath("$.scheduledId").value(ahead + "_d1"));

        String behind = LocalDate.now(ZoneId.of("Etc/GMT+12")).toString(); // UTC-12
        mvc.perform(post("/api/me/workout-programs/p1/sessions")
                .header("X-Dev-User", TEST_USER)
                .header("X-Timezone", "Etc/GMT+12")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(behind));

        assertNotEquals(ahead, behind);
    }

    @Test
    void runDayFallsBackToServerDayWhenTimeZoneHeaderIsInvalid() throws Exception {
        // A garbage header must not 500; it falls back to the server's own day
        // (the prior LocalDate.now() behaviour) rather than erroring.
        String serverToday = LocalDate.now().toString();
        mvc.perform(post("/api/me/workout-programs/p1/sessions")
                .header("X-Dev-User", TEST_USER)
                .header("X-Timezone", "Not/A_Zone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RunDayRequest("ph1", "d1", null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(serverToday));
    }

    @Test
    void runDayIsIdempotentForTheSameDayAndDate() throws Exception {
        String body = objectMapper.writeValueAsString(new RunDayRequest("ph1", "d1", null));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/me/workout-programs/p1/sessions")
                    .header("X-Dev-User", TEST_USER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledId").value(TODAY + "_d1"));
        }
        long count = scheduled.findByProgram(TEST_USER, "p1", LocalDate.MIN, LocalDate.MAX).stream()
            .filter(s -> s.scheduledId().equals(TODAY + "_d1")).count();
        assertEquals(1, count);
    }

    @Test
    void runDayWithUnknownDayReturns404() throws Exception {
        mvc.perform(post("/api/me/workout-programs/p1/sessions")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RunDayRequest("ph1", "nope", null))))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private void seedActiveProgram(String programId) {
        WorkoutDay day = new WorkoutDay("d1", "Lower", DayOfWeek.MON, "loc1", 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of())));
        ProgramPhase phase = new ProgramPhase("ph1", "Accumulation", "Hypertrophy", 0, null,
            4, null, null, null, null, List.of(day));
        ProgramSchedule schedule = new ProgramSchedule(List.of(DayOfWeek.MON), Map.of(DayOfWeek.MON, "loc1"));
        programs.save(new WorkoutProgram(
            TEST_USER, programId, "Original", null, null, ProgramStatus.ACTIVE,
            ProgramSource.AI_ASSISTED, START, schedule,
            List.of(phase.phaseId()), List.of(phase), null, null, null));
    }
}
