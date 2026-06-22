package com.gte619n.healthfitness.api.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.BlockResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.PrescriptionResponse;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient.ExerciseLine;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient.SessionRecap;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkoutSessionCoachTest {

    @Test
    void recapFor_completedSession_buildsContextAndDelegates() {
        AtomicReference<SessionRecap> captured = new AtomicReference<>();
        WorkoutCoachClient client = session -> {
            captured.set(session);
            return "Strong push day — nice work.";
        };
        WorkoutSessionCoach coach = new WorkoutSessionCoach(client);

        String recap = coach.recapFor(completedPushDay());

        assertEquals("Strong push day — nice work.", recap);
        SessionRecap ctx = captured.get();
        assertNotNull(ctx);
        assertEquals("Push", ctx.dayLabel());
        assertEquals(1800, ctx.durationSeconds());
        // Two logged sets on bench: 135x10 (1350) + 185x5 (925) = 2275.
        assertEquals(2275.0, ctx.totalVolumeLbs(), 0.001);
        assertEquals(2, ctx.setsCompleted());
        assertEquals(3, ctx.setsPrescribed());
        assertEquals(1, ctx.exercises().size());
        ExerciseLine bench = ctx.exercises().get(0);
        assertEquals("Bench Press", bench.name());
        assertEquals(2, bench.setsLogged());
        assertEquals(185.0, bench.topWeightLbs());
        assertEquals(5, bench.topReps());
        assertEquals(8.0, bench.avgRpe(), 0.001); // (7 + 9) / 2
    }

    @Test
    void recapFor_nonCompletedSession_returnsNullWithoutCallingClient() {
        WorkoutCoachClient client = session -> {
            throw new AssertionError("client must not be called for a skipped session");
        };
        WorkoutSessionCoach coach = new WorkoutSessionCoach(client);

        ScheduledWorkoutResponse skipped = base(ScheduledStatus.SKIPPED, null);
        assertNull(coach.recapFor(skipped));
    }

    @Test
    void recapFor_completedButClientUnavailable_returnsNull() {
        WorkoutCoachClient client = session -> null; // mirrors a missing API key
        WorkoutSessionCoach coach = new WorkoutSessionCoach(client);

        assertNull(coach.recapFor(completedPushDay()));
    }

    @Test
    void toRecap_prescriptionWithoutLoggedSets_countsPrescribedOnly() {
        PrescriptionResponse unlogged = prescription("Squat", 4, List.of());
        ScheduledWorkoutResponse r = base(ScheduledStatus.COMPLETED,
            day(List.of(block(List.of(unlogged)))));

        SessionRecap ctx = WorkoutSessionCoach.toRecap(r);
        assertEquals(0, ctx.setsCompleted());
        assertEquals(4, ctx.setsPrescribed());
        assertTrue(ctx.exercises().isEmpty());
        assertEquals(0.0, ctx.totalVolumeLbs(), 0.001);
    }

    // ---- fixtures ----

    private static ScheduledWorkoutResponse completedPushDay() {
        PrescriptionResponse bench = prescription("Bench Press", 3, List.of(
            new LoggedSet(135.0, 10, 7.0, 90, null),
            new LoggedSet(185.0, 5, 9.0, 120, null)));
        return base(ScheduledStatus.COMPLETED, day(List.of(block(List.of(bench)))));
    }

    private static PrescriptionResponse prescription(String name, int sets, List<LoggedSet> logged) {
        ExerciseSummary summary = new ExerciseSummary(
            name.toLowerCase().replace(' ', '-'), name, List.of(), List.of(), List.of());
        return new PrescriptionResponse(
            summary.exerciseId(), 0, sets, null, null, null, null, null, null, null, null,
            logged, summary, null, null);
    }

    private static BlockResponse block(List<PrescriptionResponse> prescriptions) {
        return new BlockResponse("b1", BlockType.MAIN, "Main", 0, prescriptions);
    }

    private static DayResponse day(List<BlockResponse> blocks) {
        return new DayResponse("d1", "Push", null, null, null, 0, blocks);
    }

    private static ScheduledWorkoutResponse base(ScheduledStatus status, DayResponse session) {
        return new ScheduledWorkoutResponse(
            "p1", "2026-06-22_d1", LocalDate.parse("2026-06-22"), "ph1", "d1", "Push",
            1, false, null, null, status, session, null, 1800, null);
    }
}
