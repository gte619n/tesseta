package com.gte619n.healthfitness.integrations.exercise;

import java.util.Random;

/**
 * Deterministically derives the demo actor's identity for an exercise so every
 * still and the video for that exercise feature the <em>same</em> person, while
 * the catalog as a whole spans a representative population (IMPL-15, ADR-0009).
 *
 * <p>Seeded by the exercise id, so a given exercise always yields the same
 * actor across runs. Distribution: 50/50 male/female; ethnicity weighted
 * White&nbsp;(non-Hispanic)&nbsp;57.5, Hispanic&nbsp;20, Black&nbsp;12,
 * Asian&nbsp;6.7, multiracial&nbsp;3.1.
 */
public final class ExerciseActor {

    public enum Sex { MALE, FEMALE }

    /** A resolved actor identity; {@link #describe()} renders the prompt phrase. */
    public record Identity(Sex sex, String ethnicity, String hair) {
        public String describe() {
            String person = sex == Sex.FEMALE ? "woman" : "man";
            return "a " + ethnicity + " " + person + " in their mid-30s, " + hair
                + ", with a lean, athletic build";
        }
    }

    private ExerciseActor() {}

    /** The stable actor for an exercise. Same id → same identity every time. */
    public static Identity forExercise(String exerciseId) {
        long seed = (exerciseId == null || exerciseId.isBlank())
            ? 0x1234_5678L : exerciseId.hashCode() * 0x9E3779B97F4A7C15L;
        Random r = new Random(seed);
        Sex sex = r.nextDouble() < 0.5 ? Sex.FEMALE : Sex.MALE;
        String ethnicity = pickEthnicity(r);
        String hair = pickHair(r, sex);
        return new Identity(sex, ethnicity, hair);
    }

    // Cumulative weights summing to 99.3; the remainder folds into the last bucket.
    private static String pickEthnicity(Random r) {
        double x = r.nextDouble() * 99.3;
        if (x < 57.5) return "White (non-Hispanic)";
        if (x < 77.5) return "Hispanic";
        if (x < 89.5) return "Black";
        if (x < 96.2) return "East Asian";
        return "multiracial";
    }

    private static String pickHair(Random r, Sex sex) {
        String[] male = {
            "with a short cropped haircut", "with a buzz cut",
            "with short hair", "with a short tapered fade"};
        String[] female = {
            "with hair tied back in a ponytail", "with shoulder-length hair tied back",
            "with a short bob", "with hair pulled back in a bun"};
        String[] opts = sex == Sex.FEMALE ? female : male;
        return opts[r.nextInt(opts.length)];
    }
}
