package com.gte619n.healthfitness.persistence.workoutprogram;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.firestore.Firestore;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression: {@code findByProgram} filters on the {@code date} field, which is
 * stored as an ISO string and compared lexicographically. The unbounded
 * "all sessions" call (used by the exercise-performance scan that powers the
 * logger's "same weight as last time" prefill, digests, and history) passes
 * {@link LocalDate#MIN}/{@link LocalDate#MAX}. Those stringify to signed 9-digit
 * years ("+999999999-…"), whose sign char sorts <em>outside</em> the digit range
 * — so a naive {@code date <= "+999999999-12-31"} bound excluded every real date
 * and the scan came back empty. The unbounded call must return all sessions;
 * real date ranges must still filter.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class FirestoreScheduledWorkoutRepositoryRangeTest {

    private static final String USER = "u1";
    private static final String PROGRAM = "p1";

    @Test
    void findByProgramWithMinMaxReturnsEveryDatedSession(Firestore firestore) throws Exception {
        seed(firestore, "2026-06-22_a", "2026-06-22", "COMPLETED");
        seed(firestore, "2026-07-08_b", "2026-07-08", "COMPLETED");
        seed(firestore, "2026-07-29_c", "2026-07-29", "PLANNED");

        FirestoreScheduledWorkoutRepository repo = new FirestoreScheduledWorkoutRepository(firestore);

        // Unbounded: the scan's call shape. Before the fix this returned nothing.
        assertThat(repo.findByProgram(USER, PROGRAM, LocalDate.MIN, LocalDate.MAX))
            .extracting(ScheduledWorkout::scheduledId)
            .containsExactly("2026-06-22_a", "2026-07-08_b", "2026-07-29_c");
    }

    @Test
    void findByProgramWithRealRangeStillFilters(Firestore firestore) throws Exception {
        seed(firestore, "2026-06-22_a", "2026-06-22", "COMPLETED");
        seed(firestore, "2026-07-08_b", "2026-07-08", "COMPLETED");
        seed(firestore, "2026-07-29_c", "2026-07-29", "PLANNED");

        FirestoreScheduledWorkoutRepository repo = new FirestoreScheduledWorkoutRepository(firestore);

        // Half-open sentinel on one side, a real bound on the other, and a fully
        // real window — each must still bracket by date.
        assertThat(repo.findByProgram(USER, PROGRAM, LocalDate.MIN, LocalDate.parse("2026-07-08")))
            .extracting(ScheduledWorkout::scheduledId)
            .containsExactly("2026-06-22_a", "2026-07-08_b");
        assertThat(repo.findByProgram(USER, PROGRAM, LocalDate.parse("2026-07-01"), LocalDate.MAX))
            .extracting(ScheduledWorkout::scheduledId)
            .containsExactly("2026-07-08_b", "2026-07-29_c");
        assertThat(repo.findByProgram(
                USER, PROGRAM, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10")))
            .extracting(ScheduledWorkout::scheduledId)
            .containsExactly("2026-07-08_b");
    }

    private void seed(Firestore fs, String scheduledId, String date, String status) throws Exception {
        fs.collection("users").document(USER)
            .collection("workoutPrograms").document(PROGRAM)
            .collection("scheduled").document(scheduledId)
            .set(Map.of("date", date, "status", status)).get();
    }
}
