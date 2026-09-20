package com.gte619n.healthfitness.core.workoutprogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit-tests the pure {@link WorkoutDurationEstimator} (IMPL-ADHOC-01 D5). */
class WorkoutDurationEstimatorTest {

    private static Prescription rep(int sets, int reps, int rest, String tempo) {
        return new Prescription("ex", 0, sets, reps, reps, null, null, rest, tempo, null, null, null);
    }

    private static Prescription timed(int sets, int durationSeconds, int rest) {
        return new Prescription("ex", 0, sets, null, null, durationSeconds, null, rest, null, null, null, null);
    }

    private static WorkoutDay day(Prescription... rxs) {
        return new WorkoutDay("d", "Day", null, null, 0,
            List.of(new Block("b", BlockType.MAIN, "Main", 0, List.of(rxs))));
    }

    @Test
    void nullOrEmptyDayIsZero() {
        assertThat(WorkoutDurationEstimator.estimateSeconds(null)).isZero();
        assertThat(WorkoutDurationEstimator.estimateSeconds(
            new WorkoutDay("d", "D", null, null, 0, List.of()))).isZero();
    }

    @Test
    void repSetHandComputed() {
        // 3 sets x 10 reps @3.5s/rep + 4s setup = 39s/set; 3*39=117 work;
        // rest (3-1)*60=120; +20 transition = 257.
        assertThat(WorkoutDurationEstimator.estimateSeconds(day(rep(3, 10, 60, null)))).isEqualTo(257);
    }

    @Test
    void timedSetUsesDuration() {
        // 3 sets x (30s hold + 4s setup)=34; 102 work; rest 120; +20 = 242.
        assertThat(WorkoutDurationEstimator.estimateSeconds(day(timed(3, 30, 60)))).isEqualTo(242);
    }

    @Test
    void tempoOverridesSecPerRep() {
        assertThat(WorkoutDurationEstimator.secPerRep("3-1-2-0")).isCloseTo(6.5, within(0.001));
        assertThat(WorkoutDurationEstimator.secPerRep("3-0-1")).isCloseTo(4.5, within(0.001));
        assertThat(WorkoutDurationEstimator.secPerRep(null))
            .isCloseTo(WorkoutDurationEstimator.DEFAULT_SEC_PER_REP, within(0.001));
        assertThat(WorkoutDurationEstimator.secPerRep("garbage"))
            .isCloseTo(WorkoutDurationEstimator.DEFAULT_SEC_PER_REP, within(0.001));
    }

    @Test
    void multipleExercisesSum() {
        int one = WorkoutDurationEstimator.estimateSeconds(day(rep(3, 10, 60, null)));
        int two = WorkoutDurationEstimator.estimateSeconds(day(rep(3, 10, 60, null), rep(3, 10, 60, null)));
        assertThat(two).isEqualTo(one * 2);
    }
}
