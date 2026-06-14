package com.gte619n.healthfitness.api.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
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
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.workout.InMemoryWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutaggregate.InMemoryWeeklyWorkoutAggregateRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc tests for the ADR-0012 completion upsert
 * ({@code PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}}):
 * outcome + actuals land on the ScheduledWorkout, the fan-out (Workout record,
 * weekly aggregate) is visible, validation failures map to 400, missing
 * resources to 404, and the new LoggedSet actuals (rpe/restSeconds/completedAt)
 * serialize wherever loggedSets appear (upsert response, history).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutSessionLogControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;
    @Autowired WorkoutRepository workouts;
    @Autowired WeeklyWorkoutAggregateRepository aggregates;

    private static final String TEST_USER = "user-123";
    // 2026-06-03 is a Wednesday; its ISO week starts Monday 2026-06-01.
    private static final LocalDate DATE = LocalDate.of(2026, 6, 3);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 1);
    private static final Instant FINISHED = Instant.parse("2026-06-03T18:30:00Z");
    private static final String SCHEDULED_ID = DATE + "_d1";
    private static final String SESSIONS_URL =
        "/api/me/workout-programs/p1/sessions/" + SCHEDULED_ID;

    @BeforeEach
    void setUp() {
        // Spring caches the @SpringBootTest context across the run, so the
        // in-memory stores survive between tests; wipe them before each one.
        ((InMemoryWorkoutProgramRepository) programs).clear();
        ((InMemoryScheduledWorkoutRepository) scheduled).clear();
        ((InMemoryWorkoutRepository) workouts).clear();
        ((InMemoryWeeklyWorkoutAggregateRepository) aggregates).clear();
    }

    @Test
    void completePersistsActualsAndFansOut() throws Exception {
        seedPlannedSession();
        LogSessionRequest request = new LogSessionRequest(
            ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(135.0, 8, 8.5, 90, FINISHED.minusSeconds(600)),
                new LoggedSet(135.0, 8, null, null, null)))));

        mvc.perform(put(SESSIONS_URL)
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.programId").value("p1"))
            .andExpect(jsonPath("$.scheduledId").value(SCHEDULED_ID))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedAt").value("2026-06-03T18:30:00Z"))
            .andExpect(jsonPath("$.durationSeconds").value(3600))
            // Full actuals serialize on the embedded session snapshot (D3).
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets.length()").value(2))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].weightLbs").value(135.0))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].reps").value(8))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].rpe").value(8.5))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].restSeconds").value(90))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].completedAt")
                .value("2026-06-03T18:20:00Z"))
            // The un-logged prescription carries no actuals.
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[1].loggedSets").doesNotExist());

        // Fan-out: the session-level Workout record (D5).
        Workout w = workouts.findById(TEST_USER, "p1_" + SCHEDULED_ID).orElseThrow();
        assertEquals("STRENGTH", w.activityType());
        assertEquals("logger", w.source());
        assertEquals(FINISHED.minusSeconds(3600), w.startTime());

        // Fan-out: the ISO-week aggregate.
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(TEST_USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(2 * 135.0 * 8, agg.totalTonnage(), 1e-9);
    }

    @Test
    void completedSessionAppearsInHistoryWithFullActuals() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(135.0, 8, 8.5, 90, FINISHED.minusSeconds(600)))))))
            .andExpect(status().isOk());

        mvc.perform(get("/api/me/workout-history")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            // History rows span programs, so the web edit path (D6) addresses
            // the upsert with the row's own programId.
            .andExpect(jsonPath("$[0].programId").value("p1"))
            .andExpect(jsonPath("$[0].scheduledId").value(SCHEDULED_ID))
            .andExpect(jsonPath("$[0].session.blocks[0].prescriptions[0].loggedSets[0].rpe").value(8.5))
            .andExpect(jsonPath("$[0].session.blocks[0].prescriptions[0].loggedSets[0].restSeconds").value(90))
            .andExpect(jsonPath("$[0].session.blocks[0].prescriptions[0].loggedSets[0].completedAt")
                .value("2026-06-03T18:20:00Z"));
    }

    @Test
    void skipClearsActualsAndRemovesFannedOutWorkout() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0,
                List.of(new LoggedSet(135.0, 8, null, null, null))))))
            .andExpect(status().isOk());

        // Un-complete to SKIPPED (D4: transitions are permissive).
        putSession(new LogSessionRequest(ScheduledStatus.SKIPPED, null, null, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SKIPPED"))
            .andExpect(jsonPath("$.completedAt").doesNotExist())
            .andExpect(jsonPath("$.durationSeconds").doesNotExist())
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets").doesNotExist());

        assertEquals(true, workouts.findById(TEST_USER, "p1_" + SCHEDULED_ID).isEmpty());
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(TEST_USER, WEEK_START).orElseThrow();
        assertEquals(0, agg.sessionCount());
    }

    @Test
    void unknownLoggedKeyIsBadRequest() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("no-such-block", 0,
                List.of(new LoggedSet(95.0, 10, null, null, null))))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "No prescription at block 'no-such-block' / prescription 0."));

        // Nothing was upserted or fanned out.
        assertEquals(ScheduledStatus.PLANNED,
            scheduled.findById(TEST_USER, "p1", SCHEDULED_ID).orElseThrow().status());
        assertEquals(true, workouts.findByUser(TEST_USER).isEmpty());
    }

    @Test
    void outOfRangeSetActualsAreBadRequest() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0,
                List.of(new LoggedSet(-135.0, -8, 10.5, -90, null))))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "weightLbs must not be negative at block 'b1' / prescription 0, set 0.; "
                    + "reps must not be negative at block 'b1' / prescription 0, set 0.; "
                    + "rpe must be between 0 and 10 at block 'b1' / prescription 0, set 0.; "
                    + "restSeconds must not be negative at block 'b1' / prescription 0, set 0."));

        // Nothing was upserted or fanned out.
        assertEquals(ScheduledStatus.PLANNED,
            scheduled.findById(TEST_USER, "p1", SCHEDULED_ID).orElseThrow().status());
        assertEquals(true, workouts.findByUser(TEST_USER).isEmpty());
    }

    @Test
    void boundaryActualsAreAccepted() throws Exception {
        seedPlannedSession();
        // 0 weight (bodyweight), 0 reps/rest, and the RPE endpoints 0 and 10
        // are all legal values.
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(0.0, 0, 0.0, 0, null),
                new LoggedSet(0.0, 12, 10.0, 60, null))))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets.length()").value(2))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].weightLbs").value(0.0))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[0].rpe").value(0.0))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets[1].rpe").value(10.0));

        // Zero-weight sets count toward the session but contribute no tonnage.
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(TEST_USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(0.0, agg.totalTonnage(), 1e-9);
    }

    @Test
    void aggregateKeepsSessionsUnderTombstonedPrograms() throws Exception {
        // Complete a session under p1, tombstone p1, then trigger a recompute
        // of the same week from p2: p1's performed work is history and must
        // keep counting (review round 1, Q1).
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0,
                List.of(new LoggedSet(135.0, 8, null, null, null))))))
            .andExpect(status().isOk());
        programs.delete(TEST_USER, "p1");

        LocalDate fridayDate = DATE.plusDays(2); // same ISO week
        String p2ScheduledId = fridayDate + "_d1";
        programs.save(new WorkoutProgram(TEST_USER, "p2", "Other Block", null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
        WorkoutDay day = new WorkoutDay("d1", "Upper", DayOfWeek.FRI, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx("ohp", 0)))));
        scheduled.save(new ScheduledWorkout(
            TEST_USER, "p2", p2ScheduledId, fridayDate, "ph1", "d1", "Upper",
            1, false, "gym-1", ScheduledStatus.PLANNED, day, null, null));

        mvc.perform(put("/api/me/workout-programs/p2/sessions/" + p2ScheduledId)
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
                        List.of(new LoggedPrescription("b1", 0,
                            List.of(new LoggedSet(100.0, 5, null, null, null))))))))
            .andExpect(status().isOk());

        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(TEST_USER, WEEK_START).orElseThrow();
        assertEquals(2, agg.sessionCount());
        assertEquals(135.0 * 8 + 100.0 * 5, agg.totalTonnage(), 1e-9);
    }

    @Test
    void completedWithoutCompletedAtIsBadRequest() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, null, 3600,
            List.of(new LoggedPrescription("b1", 0,
                List.of(new LoggedSet(135.0, 8, null, null, null))))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "completedAt is required for a COMPLETED session."));
    }

    @Test
    void repeatPutReplacesActualsAndStaysIdempotent() throws Exception {
        seedPlannedSession();
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0,
                List.of(new LoggedSet(135.0, 8, null, null, null))))))
            .andExpect(status().isOk());

        // The edit path is the same upsert: corrected duration, sets moved to rx1.
        putSession(new LogSessionRequest(ScheduledStatus.COMPLETED, FINISHED, 3700,
            List.of(new LoggedPrescription("b1", 1,
                List.of(new LoggedSet(145.0, 6, null, null, null))))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.durationSeconds").value(3700))
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[0].loggedSets").doesNotExist())
            .andExpect(jsonPath("$.session.blocks[0].prescriptions[1].loggedSets[0].weightLbs").value(145.0));

        // Still exactly one Workout record and one counted session.
        assertEquals(1, workouts.findByUser(TEST_USER).size());
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(TEST_USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(145.0 * 6, agg.totalTonnage(), 1e-9);
    }

    @Test
    void unknownProgramIsNotFound() throws Exception {
        mvc.perform(put("/api/me/workout-programs/nope/sessions/" + SCHEDULED_ID)
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LogSessionRequest(ScheduledStatus.SKIPPED, null, null, null))))
            .andExpect(status().isNotFound());
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        seedPlannedSession();
        mvc.perform(put("/api/me/workout-programs/p1/sessions/" + DATE + "_missing")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LogSessionRequest(ScheduledStatus.SKIPPED, null, null, null))))
            .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuth() throws Exception {
        seedPlannedSession();
        mvc.perform(put(SESSIONS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LogSessionRequest(ScheduledStatus.SKIPPED, null, null, null))))
            .andExpect(status().isUnauthorized());
    }

    // ---- fixtures ----

    private org.springframework.test.web.servlet.ResultActions putSession(LogSessionRequest request)
        throws Exception {
        return mvc.perform(put(SESSIONS_URL)
            .header("X-Dev-User", TEST_USER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
    }

    private void seedPlannedSession() {
        programs.save(new WorkoutProgram(TEST_USER, "p1", "Strength Block", null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
        WorkoutDay day = new WorkoutDay("d1", "Lower", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx("sq", 0), rx("bp", 1)))));
        scheduled.save(new ScheduledWorkout(
            TEST_USER, "p1", SCHEDULED_ID, DATE, "ph1", "d1", "Lower",
            1, false, "gym-1", ScheduledStatus.PLANNED, day, null, null));
    }

    private static Prescription rx(String exerciseId, int orderIndex) {
        return new Prescription(exerciseId, orderIndex, 3, 5, 8, null, null, 120, null, null, null, null);
    }
}
