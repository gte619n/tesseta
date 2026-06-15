package com.gte619n.healthfitness.core.workoutprogram;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Curated training-science scaffold (IMPL-18 S8): per-muscle weekly hard-set
 * volume landmarks (MEV/MAV/MRV) plus ramp-rate and deload cadence constants.
 * One source of truth used both to brief the designer (the prompt {@link #render()}s
 * it) and to enforce guardrails in {@link WorkoutProgramValidator}.
 *
 * <p>Landmarks are weekly hard-set ranges per muscle, in the spirit of the
 * Renaissance Periodization volume-landmark framework (MEV = minimum effective
 * volume, MAV = maximum adaptive volume, MRV = maximum recoverable volume).
 * Numbers are deliberately middle-of-the-road defaults; the validator only
 * <em>warns</em> on breaches (IMPL-18 R1) so they guide rather than block.
 */
@Component
public class TrainingScienceScaffold {

    /** Weekly hard-set landmarks for one muscle. */
    public record Landmark(int mev, int mav, int mrv) {}

    /** Week-over-week (phase-to-phase) weekly-set increase cap under normal progression. */
    public static final double MAX_WEEKLY_SET_INCREASE = 0.30;
    /** Tighter cap when easing in / coming off a layoff. */
    public static final double EASE_IN_WEEKLY_SET_INCREASE = 0.15;
    /** A phase at least this many weeks long should include a deload. */
    public static final int DELOAD_PHASE_WEEKS_THRESHOLD = 4;

    private static final Map<String, Landmark> LANDMARKS = new LinkedHashMap<>();

    static {
        LANDMARKS.put("chest", new Landmark(10, 16, 22));
        LANDMARKS.put("back", new Landmark(10, 18, 25));
        LANDMARKS.put("lats", new Landmark(10, 16, 22));
        LANDMARKS.put("traps", new Landmark(6, 14, 20));
        LANDMARKS.put("shoulders", new Landmark(8, 16, 22));
        LANDMARKS.put("front delts", new Landmark(6, 12, 18));
        LANDMARKS.put("side delts", new Landmark(8, 16, 26));
        LANDMARKS.put("rear delts", new Landmark(6, 14, 22));
        LANDMARKS.put("biceps", new Landmark(8, 16, 22));
        LANDMARKS.put("triceps", new Landmark(8, 14, 20));
        LANDMARKS.put("forearms", new Landmark(4, 10, 16));
        LANDMARKS.put("quadriceps", new Landmark(8, 16, 20));
        LANDMARKS.put("hamstrings", new Landmark(6, 14, 20));
        LANDMARKS.put("glutes", new Landmark(4, 12, 16));
        LANDMARKS.put("calves", new Landmark(8, 16, 22));
        LANDMARKS.put("abs", new Landmark(6, 16, 25));
        LANDMARKS.put("core", new Landmark(6, 16, 25));
        LANDMARKS.put("adductors", new Landmark(4, 10, 16));
        LANDMARKS.put("hip flexors", new Landmark(4, 8, 12));
    }

    /** Folds common muscle synonyms onto a canonical landmark key. */
    public String normalize(String muscle) {
        if (muscle == null) return null;
        String m = muscle.trim().toLowerCase();
        return switch (m) {
            case "quads", "quad", "quadricep", "quadriceps femoris" -> "quadriceps";
            case "hamstring", "hams" -> "hamstrings";
            case "glute", "gluteus", "gluteus maximus", "glute max" -> "glutes";
            case "pecs", "pec", "pectorals", "pectoralis", "pectoralis major" -> "chest";
            case "lat", "latissimus", "latissimus dorsi" -> "lats";
            case "delts", "deltoids", "deltoid", "shoulder" -> "shoulders";
            case "anterior deltoid", "anterior delts" -> "front delts";
            case "lateral deltoid", "lateral delts", "medial delts" -> "side delts";
            case "posterior deltoid", "posterior delts", "rear delt" -> "rear delts";
            case "bicep", "biceps brachii" -> "biceps";
            case "tricep", "triceps brachii" -> "triceps";
            case "calf", "gastrocnemius", "soleus" -> "calves";
            case "abdominals", "abdominis", "rectus abdominis", "obliques" -> "abs";
            case "trapezius", "trap" -> "traps";
            case "upper back" -> "back";
            default -> m;
        };
    }

    /** The landmark for a (possibly synonymous) muscle name, or null if unknown. */
    public Landmark landmarkFor(String muscle) {
        String key = normalize(muscle);
        return key == null ? null : LANDMARKS.get(key);
    }

    /** A compact, prompt-friendly rendering of the scaffold. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("TRAINING SCIENCE SCAFFOLD — weekly hard sets per primary muscle ")
            .append("(MEV = minimum effective, MAV = max adaptive, MRV = max recoverable):\n");
        LANDMARKS.forEach((muscle, l) -> sb.append("  ").append(muscle)
            .append(": MEV ").append(l.mev())
            .append(", MAV ").append(l.mav())
            .append(", MRV ").append(l.mrv()).append('\n'));
        sb.append("Rules: keep accumulation volume in MEV..MAV and never exceed MRV; ")
            .append("cap week-over-week weekly-set increases to about ")
            .append(Math.round(MAX_WEEKLY_SET_INCREASE * 100)).append("% (about ")
            .append(Math.round(EASE_IN_WEEKLY_SET_INCREASE * 100)).append("% when easing in); ")
            .append("include a deload in any phase of ").append(DELOAD_PHASE_WEEKS_THRESHOLD)
            .append("+ weeks.\n");
        return sb.toString();
    }
}
