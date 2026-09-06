package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the week loop's deload decision — especially the deficit
 * suppression (IMPL-PROG-01 D11/§8.1): a FLAT trend in a deficit is SUCCESS and
 * must NOT deload, or the engine would deload the user repeatedly through a cut.
 */
class WeekLoopReviewTest {

    private static final String USER = "u1";
    private static final String EX = "bench";
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final MovementPattern PATTERN = MovementPattern.PUSH_HORIZONTAL;

    @Test
    void flatTrendInDeficitDoesNotDeload() {
        Harness h = new Harness();
        h.seedFlatTrend();
        h.blockParams.save(deficitBlock());  // HOLD_LOAD_AT_LOWER_RIR

        WeekLoop.PatternReview r = h.reviewFor(PATTERN);
        assertEquals(Trend.FLAT, r.trend());
        assertFalse(r.deload(), "flat in a deficit is success → no deload: " + r.reasoning());
    }

    @Test
    void flatTrendWhenRecoveredAddsASet() {
        Harness h = new Harness();
        h.seedFlatTrend();
        h.blockParams.save(gainingBlock());  // ADD_LOAD

        WeekLoop.PatternReview r = h.reviewFor(PATTERN);
        assertEquals(Trend.FLAT, r.trend());
        assertFalse(r.deload());
        assertTrue(r.proposedTarget() >= r.currentTarget(), "flat + recovered → +1 set");
    }

    @Test
    void fallingTrendDeloadsEvenInDeficit() {
        Harness h = new Harness();
        h.seedFallingTrend();
        h.blockParams.save(deficitBlock());

        WeekLoop.PatternReview r = h.reviewFor(PATTERN);
        assertEquals(Trend.FALLING, r.trend());
        assertTrue(r.deload(), "a real regression deloads even in a deficit");
    }

    // ---- harness ----

    private static final class Harness {
        final InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        final InMemoryProgressionRepositories.Observations observations = new InMemoryProgressionRepositories.Observations();
        final InMemoryProgressionRepositories.Block blockParams = new InMemoryProgressionRepositories.Block();
        final InMemoryProgressionRepositories.Week weekParams = new InMemoryProgressionRepositories.Week();
        final WeekLoop loop;

        Harness() {
            exercises.save(bench());
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            loop = new WeekLoop(observations, exercises, blockParams, weekParams, clock);
        }

        void seedFlatTrend() {
            // Same working e1RM at days -18,-12,-6,0 → slope ~0.
            double[] loads = {135, 135, 135, 135};
            seed(loads);
        }

        void seedFallingTrend() {
            double[] loads = {150, 143, 136, 129}; // clearly declining
            seed(loads);
        }

        void seed(double[] loads) {
            int day = 18;
            for (double load : loads) {
                Instant t = NOW.minusSeconds(day * 86400L);
                // 3 sets, last flagged, reps constant (so fatigue ~ flat), rir 2.
                for (int i = 1; i <= 3; i++) {
                    observations.save(new SetObservation(
                        "o-" + day + "-" + i, USER, "sess-" + day, EX, i, i == 3,
                        load, 8, RirSource.REPORTED, 2.0, null, t.plusSeconds(i * 60L), Set.of()));
                }
                day -= 6;
            }
        }

        WeekLoop.PatternReview reviewFor(MovementPattern p) {
            return loop.review(USER).stream().filter(r -> r.pattern() == p).findFirst().orElseThrow();
        }
    }

    private static BlockParameters deficitBlock() {
        BlockParameters d = BlockParameters.defaults(USER);
        return new BlockParameters(USER, BlockMode.MAINTENANCE, 0, d.repRangesByPattern(),
            d.rirCapsByMechanic(), d.weeklySetCeiling(), SuccessCriterion.HOLD_LOAD_AT_LOWER_RIR,
            false, NOW, 1);
    }

    private static BlockParameters gainingBlock() {
        BlockParameters d = BlockParameters.defaults(USER);
        return new BlockParameters(USER, BlockMode.GAINING, 0.1, d.repRangesByPattern(),
            d.rirCapsByMechanic(), d.weeklySetCeiling(), SuccessCriterion.ADD_LOAD, false, NOW, 1);
    }

    private static Exercise bench() {
        return new Exercise(EX, "Barbell Bench Press", "barbell bench press", List.of(),
            PATTERN, List.of("chest"), List.of(), Laterality.BILATERAL, Mechanic.COMPOUND, null,
            List.of(), List.of(), List.of(BlockType.MAIN), null, false, List.of(), null, null,
            ExerciseMediaStatus.APPROVED, null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
