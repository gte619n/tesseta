package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@link ExerciseLoadingProfile} for a (user, exercise): a stored
 * per-user override if present, otherwise derived defaults from the exercise
 * (IMPL-PROG-01 D8). Derivation classifies off the exercise NAME (reliable —
 * "Barbell…", "Dumbbell…", "…Machine") plus {@code isTimed}/movement pattern,
 * because the equipment binding is by opaque catalog ids at runtime (decision
 * L1 in the log).
 *
 * <p>Load convention (decision L2): this app logs the TOTAL external weight, so
 * barbell/dumbbell/machine offset is 0. Only bodyweight-loaded movements
 * (pull-ups, dips, push-ups) carry an offset = current bodyweight, sourced from
 * the body-composition module (§4.4) so bodyweight changes shift effective load
 * automatically.
 */
@Service
public class LoadingProfileResolver {

    private static final double KG_TO_LB = 2.2046226218;
    /** Default when bodyweight is unknown — a plausible adult mass so e1RM math stays sane. */
    private static final double DEFAULT_BODYWEIGHT_LB = 175.0;

    private final ExerciseRepository exercises;
    private final BodyCompositionRepository bodyComposition;
    private final ExerciseLoadingProfileRepository overrides;

    public LoadingProfileResolver(
        ExerciseRepository exercises,
        BodyCompositionRepository bodyComposition,
        ExerciseLoadingProfileRepository overrides
    ) {
        this.exercises = exercises;
        this.bodyComposition = bodyComposition;
        this.overrides = overrides;
    }

    public ExerciseLoadingProfile resolve(String userId, String exerciseId) {
        Optional<ExerciseLoadingProfile> override = overrides.find(userId, exerciseId);
        if (override.isPresent()) return override.get();
        Exercise ex = exercises.findById(exerciseId).orElse(null);
        return derive(userId, exerciseId, ex);
    }

    /** Derived (non-persisted) default profile — exposed for tests and the resolver. */
    ExerciseLoadingProfile derive(String userId, String exerciseId, Exercise ex) {
        if (ex == null) {
            return new ExerciseLoadingProfile(userId, exerciseId, 5.0, 0.0, true);
        }
        String n = ex.nameLower() == null ? "" : ex.nameLower();
        double increment = incrementFor(n);
        boolean bodyweight = isBodyweightLoaded(ex, n);
        double offset = bodyweight ? currentBodyweightLb(userId) : 0.0;
        boolean eligible = progressionEligible(ex);
        return new ExerciseLoadingProfile(userId, exerciseId, increment, offset, eligible);
    }

    /**
     * Smallest real step. Fixed dumbbells jump ~5 lb (the §6.5 high-increment
     * case that breaks naive percentage progression); machines/cables move in
     * ~10 lb stack plates; barbells ~5 lb (a pair of 2.5s). Default 5.
     */
    private static double incrementFor(String nameLower) {
        if (contains(nameLower, "dumbbell", "db ")) return 5.0;
        if (contains(nameLower, "machine", "cable", "pulldown", "pushdown",
            "pec deck", "leg press", "hack", "smith", "stack")) return 10.0;
        if (contains(nameLower, "barbell", "bench press", "squat", "deadlift")) return 5.0;
        return 5.0;
    }

    /**
     * Bodyweight-loaded movements carry an offset = bodyweight (§4.4). Delegates
     * to {@link BodyweightClassifier} so the resolver's offset decision and the
     * API's {@code isBodyweight} flag (IMPL-PROG-02 F6) never diverge.
     */
    private static boolean isBodyweightLoaded(Exercise ex, String nameLower) {
        return BodyweightClassifier.isBodyweight(ex);
    }

    /** Timed/mobility/stretch/cardio movements do not progress on load (D21). */
    private static boolean progressionEligible(Exercise ex) {
        if (ex.isTimed()) return false;
        MovementPattern p = ex.movementPattern();
        return p != MovementPattern.MOBILITY && p != MovementPattern.STRETCH && p != MovementPattern.CARDIO;
    }

    private double currentBodyweightLb(String userId) {
        return bodyComposition.findLatest(userId, BodyCompositionMetric.WEIGHT_KG)
            .map(m -> m.value() * KG_TO_LB)
            .orElse(DEFAULT_BODYWEIGHT_LB);
    }

    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
