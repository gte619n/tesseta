package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Post-generation equipment-constraint check + report (IMPL-ADHOC-01 D11). For a
 * generated {@link WorkoutDay} and the resolved available equipment set, flags
 * every prescription that references an exercise the user can't actually perform
 * — an unknown/unpublished exerciseId, or one whose {@code requiredEquipment}
 * isn't satisfied by the available gear. The generation flow uses this as the
 * belt over the AI's soft prompt-following.
 */
@Service
public class AdHocConstraintValidator {

    /** One flagged prescription. */
    public record Violation(String blockId, int orderIndex, String exerciseId, String reason) {}

    /** The full check outcome. {@code ok()} is true when nothing was flagged. */
    public record Report(List<Violation> violations) {
        public boolean ok() {
            return violations == null || violations.isEmpty();
        }
    }

    private final ExerciseService exercises;

    public AdHocConstraintValidator(ExerciseService exercises) {
        this.exercises = exercises;
    }

    /**
     * Validate a generated day against an available-equipment set, resolving the
     * catalog exercises it references in one batch.
     */
    public Report validate(WorkoutDay day, Set<String> availableEquipmentIds) {
        Map<String, Exercise> byId = new HashMap<>();
        for (Exercise e : exercises.findByIds(new ArrayList<>(exerciseIdsOf(day)))) {
            byId.put(e.exerciseId(), e);
        }
        return check(day, availableEquipmentIds, byId);
    }

    /**
     * Pure check core (unit-testable): given the resolved exercises, report
     * violations. A prescription with no exerciseId is skipped (nothing to run);
     * an unknown id or unsatisfied equipment is a violation.
     */
    public static Report check(
        WorkoutDay day, Set<String> availableEquipmentIds, Map<String, Exercise> exercisesById
    ) {
        Set<String> gear = availableEquipmentIds == null ? Set.of() : availableEquipmentIds;
        List<Violation> violations = new ArrayList<>();
        if (day == null || day.blocks() == null) {
            return new Report(violations);
        }
        for (Block b : day.blocks()) {
            if (b.prescriptions() == null) {
                continue;
            }
            for (Prescription rx : b.prescriptions()) {
                String exId = rx.exerciseId();
                if (exId == null || exId.isBlank()) {
                    continue;
                }
                Exercise ex = exercisesById.get(exId);
                if (ex == null) {
                    violations.add(new Violation(b.blockId(), rx.orderIndex(), exId,
                        "Exercise not found in the published catalog."));
                    continue;
                }
                if (!ExerciseAvailabilityService.satisfiedBy(ex, gear)) {
                    violations.add(new Violation(b.blockId(), rx.orderIndex(), exId,
                        "Requires equipment not available for this workout."));
                }
            }
        }
        return new Report(violations);
    }

    private static Set<String> exerciseIdsOf(WorkoutDay day) {
        Set<String> ids = new HashSet<>();
        if (day == null || day.blocks() == null) {
            return ids;
        }
        for (Block b : day.blocks()) {
            if (b.prescriptions() == null) {
                continue;
            }
            for (Prescription rx : b.prescriptions()) {
                if (rx.exerciseId() != null) {
                    ids.add(rx.exerciseId());
                }
            }
        }
        return ids;
    }
}
