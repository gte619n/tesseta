package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@link ExerciseLoadingProfile} for a (user, exercise): a stored
 * per-user override if present, otherwise derived defaults from the exercise
 * (IMPL-PROG-01 D8).
 *
 * <p>IMPL-PROG-LOAD-01 (D7/IL-6): the smallest real load step is resolved from
 * the user's ACTUAL gym equipment (the session's location → equipment specs) so
 * the engine never prescribes an unloadable weight. When the location, an
 * equipment match, or a usable spec is missing, it falls back to the name-based
 * defaults (D12): dumbbell 5 / machine·cable 10 / barbell 5 / default 5.
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
    @Nullable private final EquipmentRepository equipment;
    @Nullable private final LocationRepository locations;

    @Autowired
    public LoadingProfileResolver(
        ExerciseRepository exercises,
        BodyCompositionRepository bodyComposition,
        ExerciseLoadingProfileRepository overrides,
        EquipmentRepository equipment,
        LocationRepository locations
    ) {
        this.exercises = exercises;
        this.bodyComposition = bodyComposition;
        this.overrides = overrides;
        this.equipment = equipment;
        this.locations = locations;
    }

    /**
     * Back-compat constructor (no gym equipment access) — increment resolution
     * falls back to the name-based defaults. Used by unit tests that don't
     * exercise the equipment path.
     */
    public LoadingProfileResolver(
        ExerciseRepository exercises,
        BodyCompositionRepository bodyComposition,
        ExerciseLoadingProfileRepository overrides
    ) {
        this(exercises, bodyComposition, overrides, null, null);
    }

    public ExerciseLoadingProfile resolve(String userId, String exerciseId) {
        return resolve(userId, exerciseId, null);
    }

    /**
     * Resolve the profile for a (user, exercise) performed at {@code locationId}
     * (nullable). The location, when known, provides the real load increment.
     */
    public ExerciseLoadingProfile resolve(String userId, String exerciseId, @Nullable String locationId) {
        Optional<ExerciseLoadingProfile> override = overrides.find(userId, exerciseId);
        if (override.isPresent()) return override.get();
        Exercise ex = exercises.findById(exerciseId).orElse(null);
        return derive(userId, exerciseId, ex, locationId);
    }

    /** Derived (non-persisted) default profile — exposed for tests and the resolver. */
    ExerciseLoadingProfile derive(String userId, String exerciseId, Exercise ex) {
        return derive(userId, exerciseId, ex, null);
    }

    ExerciseLoadingProfile derive(String userId, String exerciseId, Exercise ex, @Nullable String locationId) {
        if (ex == null) {
            return new ExerciseLoadingProfile(userId, exerciseId, 5.0, 0.0, true);
        }
        String n = ex.nameLower() == null ? "" : ex.nameLower();
        double increment = resolveIncrement(userId, ex, n, locationId);
        boolean bodyweight = isBodyweightLoaded(ex, n);
        double offset = bodyweight ? currentBodyweightLb(userId) : 0.0;
        boolean eligible = progressionEligible(ex);
        return new ExerciseLoadingProfile(userId, exerciseId, increment, offset, eligible);
    }

    // ---- increment resolution (D7/IL-6) ----

    /** Real gym increment when resolvable, else the name-based default (D12). */
    private double resolveIncrement(String userId, Exercise ex, String nameLower, @Nullable String locationId) {
        Double fromGym = incrementFromGym(userId, ex, locationId);
        if (fromGym != null && fromGym > 0) return fromGym;
        return incrementFor(nameLower);
    }

    /**
     * The smallest real load step for this exercise at the user's gym, or null
     * when it can't be determined (missing repo/location/match/spec). "Smallest
     * real-world jump" (D4): the minimum positive step across the exercise's
     * matched loading equipment at that gym.
     */
    @Nullable
    private Double incrementFromGym(String userId, Exercise ex, @Nullable String locationId) {
        if (equipment == null || locations == null || locationId == null) return null;
        Location loc = locations.findById(userId, locationId).orElse(null);
        if (loc == null) return null;
        Set<String> gym = new HashSet<>(loc.equipmentIds() == null ? List.of() : loc.equipmentIds());
        if (gym.isEmpty()) return null;

        List<String> matched = new ArrayList<>();
        if (ex.requiredEquipment() != null) {
            for (EquipmentRequirement req : ex.requiredEquipment()) {
                if (req == null || req.anyOf() == null) continue;
                for (String id : req.anyOf()) {
                    if (gym.contains(id)) matched.add(id);
                }
            }
        }
        if (matched.isEmpty()) return null;

        Double best = null;
        for (Equipment eq : equipment.findByIds(matched)) {
            if (eq == null) continue;
            Double step = stepFor(eq, loc);
            if (step != null && step > 0 && (best == null || step < best)) best = step;
        }
        return best;
    }

    /** Smallest step implied by one piece of equipment's specs; null if none. */
    @Nullable
    private static Double stepFor(Equipment eq, Location loc) {
        Map<String, Object> specs = effectiveSpecs(eq, loc);
        if (eq.specSchema() == null) return null;
        switch (eq.specSchema()) {
            case WEIGHT_SET:
            case SELECTORIZED: {
                Double inc = asDouble(specs.get("increment"));
                if (inc != null && inc > 0) return inc;
                return minGap(numberList(specs.get("weights")));
            }
            case CABLE: {
                Double inc = asDouble(specs.get("increment"));
                return inc != null && inc > 0 ? inc : null;
            }
            case PLATE_LOADED: {
                // Smallest jump = a pair of the smallest available plate.
                List<Double> plates = numberList(specs.get("availablePlates"));
                if (plates.isEmpty()) plates = numberList(specs.get("plates"));
                if (plates.isEmpty()) plates = numberList(specs.get("weights"));
                Double minPlate = min(plates);
                return minPlate == null ? null : minPlate * 2.0;
            }
            default:
                return null;
        }
    }

    private static Map<String, Object> effectiveSpecs(Equipment eq, Location loc) {
        Map<String, Object> merged = new HashMap<>();
        if (eq.specs() != null) merged.putAll(eq.specs());
        if (loc != null && loc.equipmentSpecs() != null) {
            Map<String, Object> override = loc.equipmentSpecs().get(eq.equipmentId());
            if (override != null) merged.putAll(override);
        }
        return merged;
    }

    /**
     * Smallest real step by name (fallback, D12). Fixed dumbbells jump ~5 lb;
     * machines/cables move in ~10 lb stack plates; barbells ~5 lb. Default 5.
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

    // ---- spec parsing helpers (defensive: any bad value → skip) ----

    @Nullable
    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<Double> numberList(Object o) {
        List<Double> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                Double d = asDouble(item);
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    @Nullable
    private static Double min(List<Double> xs) {
        Double m = null;
        for (Double x : xs) {
            if (x != null && x > 0 && (m == null || x < m)) m = x;
        }
        return m;
    }

    /** Smallest positive gap between consecutive distinct sorted values; null if <2 values. */
    @Nullable
    private static Double minGap(List<Double> xs) {
        List<Double> sorted = new ArrayList<>(new HashSet<>(xs));
        sorted.sort(Double::compareTo);
        Double gap = null;
        for (int i = 1; i < sorted.size(); i++) {
            double g = sorted.get(i) - sorted.get(i - 1);
            if (g > 0 && (gap == null || g < gap)) gap = g;
        }
        return gap;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
