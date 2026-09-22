package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.LoadConvention;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resolves an exercise's {@link LoadConvention} — how its LOGGED weight maps to
 * TOTAL display load (IMPL-PROG-LOAD-01 §4.1, D3/D5/D6/D11). Reporting-only: the
 * progression engine never consults this (decision IL-3).
 *
 * <p>Resolution order (IL-1/IL-2):
 * <ol>
 *   <li>the user's explicit {@link ExerciseLoadingProfile#loadConventionOverride}
 *       when present;
 *   <li>else derive from the exercise:
 *       <ul>
 *         <li>{@code PER_HAND} when a required-equipment item is a DUMBBELL and
 *             {@code laterality == BILATERAL} (IL-5), or a DUAL/FUNCTIONAL cable
 *             trainer (IL-4);
 *         <li>{@code TOTAL} otherwise (barbell, machine, single-stack cable,
 *             bodyweight, single-arm dumbbell).
 *       </ul>
 * </ol>
 */
@Service
public class LoadConventionResolver {

    private final ExerciseRepository exercises;
    private final EquipmentRepository equipment;
    private final ExerciseLoadingProfileRepository overrides;

    public LoadConventionResolver(
        ExerciseRepository exercises,
        EquipmentRepository equipment,
        ExerciseLoadingProfileRepository overrides
    ) {
        this.exercises = exercises;
        this.equipment = equipment;
        this.overrides = overrides;
    }

    /** The convention for one (user, exercise); {@link LoadConvention#TOTAL} when unknown. */
    public LoadConvention resolve(String userId, String exerciseId) {
        Optional<ExerciseLoadingProfile> override = overrides.find(userId, exerciseId);
        if (override.isPresent() && override.get().loadConventionOverride() != null) {
            return override.get().loadConventionOverride();
        }
        Exercise ex = exercises.findById(exerciseId).orElse(null);
        if (ex == null) return LoadConvention.TOTAL;
        return derive(ex, equipmentFor(ex));
    }

    /** Convenience: the numeric factor (1 or 2) for one (user, exercise). */
    public int factor(String userId, String exerciseId) {
        return resolve(userId, exerciseId).factor();
    }

    /**
     * Batch factor lookup for a set of exercises (avoids per-exercise repo hits in
     * the stats scan). Every requested id maps to a factor; unknown ids map to 1.
     */
    public Map<String, Integer> factors(String userId, Collection<String> exerciseIds) {
        Map<String, Integer> out = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>(exerciseIds);
        if (ids.isEmpty()) return out;

        Map<String, Exercise> byId = new HashMap<>();
        for (Exercise e : exercises.findByIds(new ArrayList<>(ids))) {
            byId.put(e.exerciseId(), e);
        }
        // Gather every referenced equipment id once, then one catalog fetch.
        Set<String> equipIds = new LinkedHashSet<>();
        for (Exercise e : byId.values()) {
            equipIds.addAll(equipmentIdsOf(e));
        }
        Map<String, Equipment> equipById = new HashMap<>();
        if (!equipIds.isEmpty()) {
            for (Equipment eq : equipment.findByIds(new ArrayList<>(equipIds))) {
                equipById.put(eq.equipmentId(), eq);
            }
        }

        for (String id : ids) {
            LoadConvention override = overrideFor(userId, id);
            if (override != null) {
                out.put(id, override.factor());
                continue;
            }
            Exercise ex = byId.get(id);
            if (ex == null) {
                out.put(id, LoadConvention.TOTAL.factor());
                continue;
            }
            List<Equipment> gear = new ArrayList<>();
            for (String eqId : equipmentIdsOf(ex)) {
                Equipment eq = equipById.get(eqId);
                if (eq != null) gear.add(eq);
            }
            out.put(id, derive(ex, gear).factor());
        }
        return out;
    }

    private LoadConvention overrideFor(String userId, String exerciseId) {
        return overrides.find(userId, exerciseId)
            .map(ExerciseLoadingProfile::loadConventionOverride)
            .orElse(null);
    }

    private List<Equipment> equipmentFor(Exercise ex) {
        List<String> ids = equipmentIdsOf(ex);
        if (ids.isEmpty()) return List.of();
        return equipment.findByIds(ids);
    }

    // ---- pure derivation (exposed for unit tests) ----

    /**
     * Derive the convention from an exercise and the equipment it references.
     * Pure: no repository access. See IL-4 / IL-5 for the rules.
     */
    public static LoadConvention derive(Exercise ex, List<Equipment> equipment) {
        if (ex == null) return LoadConvention.TOTAL;
        boolean bilateral = ex.laterality() == null || ex.laterality() == Laterality.BILATERAL;
        boolean dumbbell = false;
        boolean dualCable = false;
        for (Equipment eq : equipment == null ? List.<Equipment>of() : equipment) {
            if (eq == null) continue;
            if (isDumbbell(eq)) dumbbell = true;
            if (isDualCable(eq)) dualCable = true;
        }
        // Name-based dumbbell fallback when the catalog binding is sparse.
        if (!dumbbell && nameHas(ex, "dumbbell", "db ")) dumbbell = true;
        if (!dualCable && nameHas(ex, "functional trainer", "dual cable", "dual-cable", "cable crossover")) {
            dualCable = true;
        }

        if (dualCable) return LoadConvention.PER_HAND;                 // IL-4
        if (dumbbell && bilateral) return LoadConvention.PER_HAND;     // IL-5
        return LoadConvention.TOTAL;
    }

    private static boolean isDumbbell(Equipment eq) {
        String sub = lower(eq.subcategory());
        String name = lower(eq.name());
        if (sub.contains("dumbbell") || name.contains("dumbbell")) return true;
        // WEIGHT_SET covers dumbbell/kettlebell sets; treat only dumbbell-named ones.
        return eq.specSchema() == SpecSchema.WEIGHT_SET && name.contains("dumbbell");
    }

    private static boolean isDualCable(Equipment eq) {
        if (eq.specSchema() != SpecSchema.CABLE) {
            // Some functional trainers may be catalogued without a CABLE schema;
            // fall back to the name/subcategory signal.
            String n = lower(eq.name()) + " " + lower(eq.subcategory());
            return n.contains("functional trainer") || n.contains("dual cable");
        }
        String sub = lower(eq.subcategory());
        String name = lower(eq.name());
        return sub.contains("dual") || sub.contains("multi")
            || name.contains("dual") || name.contains("functional") || name.contains("crossover");
    }

    private static List<String> equipmentIdsOf(Exercise ex) {
        List<String> ids = new ArrayList<>();
        if (ex.requiredEquipment() == null) return ids;
        for (EquipmentRequirement req : ex.requiredEquipment()) {
            if (req == null || req.anyOf() == null) continue;
            ids.addAll(req.anyOf());
        }
        return ids;
    }

    private static boolean nameHas(Exercise ex, String... needles) {
        String n = ex.nameLower() != null ? ex.nameLower() : lower(ex.name());
        for (String needle : needles) {
            if (n.contains(needle)) return true;
        }
        return false;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
