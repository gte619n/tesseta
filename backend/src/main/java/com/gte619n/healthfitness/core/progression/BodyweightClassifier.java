package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.util.Set;

/**
 * Decides whether an exercise is <em>truly</em> bodyweight-loaded (pull-ups,
 * dips, push-ups) versus merely lacking a known load (IMPL-PROG-02 F6). The
 * client uses this to announce/label "body weight" ONLY for real bodyweight
 * movements — a weighted lift with no prediction yet (e.g. cable push-downs)
 * must never resolve to "body weight" just because its load is 0.
 *
 * <p>Single source of truth for the classification: {@link LoadingProfileResolver}
 * delegates here for its load-offset decision, and the API assembler calls it to
 * stamp {@code isBodyweight} onto the prescription sent to the client. Pure and
 * unit-tested.
 */
public final class BodyweightClassifier {

    private BodyweightClassifier() {}

    private static final Set<MovementPattern> BODYWEIGHT_PATTERNS = Set.of(
        MovementPattern.PULL_VERTICAL, MovementPattern.PUSH_VERTICAL,
        MovementPattern.PUSH_HORIZONTAL, MovementPattern.PULL_HORIZONTAL);

    /**
     * True iff this movement is loaded by the athlete's own bodyweight. Hard-coded
     * name patterns win (highest confidence); otherwise a movement with no
     * equipment requirement and a loaded (push/pull) pattern is treated as
     * bodyweight. Anything requiring equipment (barbell, dumbbell, cable, machine)
     * is NOT bodyweight, regardless of load.
     */
    public static boolean isBodyweight(Exercise ex) {
        if (ex == null) return false;
        String n = ex.nameLower() == null ? "" : ex.nameLower();
        if (containsAny(n, "pull-up", "pull up", "pullup", "chin-up", "chin up",
            "chinup", "dip", "push-up", "push up", "pushup", "muscle-up", "inverted row")) {
            return true;
        }
        boolean noEquipment = ex.requiredEquipment() == null || ex.requiredEquipment().isEmpty();
        return noEquipment && ex.movementPattern() != null
            && BODYWEIGHT_PATTERNS.contains(ex.movementPattern());
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
