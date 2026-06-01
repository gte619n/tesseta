package com.gte619n.healthfitness.core.exercise;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The single seam IMPL-15 depends on: which published exercises are executable
 * at a given gym, computed purely as a set operation over the gym's
 * {@code equipmentIds} and each exercise's {@code requiredEquipment} groups.
 *
 * <pre>
 * executableAt(location, exercise) :=
 *     every group g in exercise.requiredEquipment has
 *         (g.anyOf ∩ location.equipmentIds) ≠ ∅
 * </pre>
 *
 * A bodyweight exercise (no requirements) is executable everywhere.
 */
@Service
public class ExerciseAvailabilityService {

    private final ExerciseRepository exercises;
    private final LocationRepository locations;

    public ExerciseAvailabilityService(ExerciseRepository exercises, LocationRepository locations) {
        this.exercises = exercises;
        this.locations = locations;
    }

    /** Published exercises executable at the given gym (of the given user). */
    public List<Exercise> executableAt(String userId, String locationId) {
        Set<String> gear = gearAt(userId, locationId);
        return exercises.findPublished(null, null, null, null).stream()
            .filter(e -> satisfiedBy(e, gear))
            .toList();
    }

    public boolean isExecutableAt(String exerciseId, String userId, String locationId) {
        Exercise exercise = exercises.findById(exerciseId).orElse(null);
        if (exercise == null) {
            return false;
        }
        return satisfiedBy(exercise, gearAt(userId, locationId));
    }

    private Set<String> gearAt(String userId, String locationId) {
        Location location = locations.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found: " + locationId));
        return new HashSet<>(location.equipmentIds() == null ? List.of() : location.equipmentIds());
    }

    /** True iff every requirement group is satisfied by the available gear. */
    public static boolean satisfiedBy(Exercise exercise, Set<String> equipmentIds) {
        List<EquipmentRequirement> reqs = exercise.requiredEquipment();
        if (reqs == null || reqs.isEmpty()) {
            return true; // bodyweight
        }
        for (EquipmentRequirement req : reqs) {
            List<String> anyOf = req.anyOf();
            if (anyOf == null || anyOf.isEmpty()) {
                continue; // an empty group imposes no constraint
            }
            boolean groupSatisfied = anyOf.stream().anyMatch(equipmentIds::contains);
            if (!groupSatisfied) {
                return false;
            }
        }
        return true;
    }
}
