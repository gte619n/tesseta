package com.gte619n.healthfitness.core.exercise;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean requireApprovedMedia;

    public ExerciseAvailabilityService(
        ExerciseRepository exercises,
        LocationRepository locations,
        @Value("${app.exercises.require-approved-media:true}") boolean requireApprovedMedia
    ) {
        this.exercises = exercises;
        this.locations = locations;
        this.requireApprovedMedia = requireApprovedMedia;
    }

    /** Published exercises executable at the given gym (of the given user). */
    public List<Exercise> executableAt(String userId, String locationId) {
        Set<String> gear = gearAt(userId, locationId);
        return exercises.findPublished(null, null, null, null).stream()
            .filter(this::mediaOk)
            .filter(e -> satisfiedBy(e, gear))
            .toList();
    }

    /**
     * Executable exercises for several gyms at once, reading the published
     * catalog and the user's locations a single time each (no per-gym N+1).
     * Returns an entry for every requested id; gyms that don't resolve to a
     * known location map to an empty list. Order follows {@code locationIds}.
     */
    public Map<String, List<Exercise>> executableAt(String userId, Collection<String> locationIds) {
        Set<String> wanted = new LinkedHashSet<>(locationIds);
        Map<String, List<Exercise>> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return out;
        }
        List<Exercise> published = exercises.findPublished(null, null, null, null).stream()
            .filter(this::mediaOk)
            .toList();
        Map<String, Set<String>> gearByLoc = new HashMap<>();
        for (Location l : locations.findByUser(userId, true)) {
            if (wanted.contains(l.locationId())) {
                gearByLoc.put(l.locationId(),
                    new HashSet<>(l.equipmentIds() == null ? List.of() : l.equipmentIds()));
            }
        }
        for (String locId : wanted) {
            Set<String> gear = gearByLoc.get(locId);
            out.put(locId, gear == null ? List.of()
                : published.stream().filter(e -> satisfiedBy(e, gear)).toList());
        }
        return out;
    }

    /** True when the exercise's media passes the configured approval gate. */
    public boolean mediaOk(Exercise e) {
        return !requireApprovedMedia || e.mediaStatus() == ExerciseMediaStatus.APPROVED;
    }

    public boolean isExecutableAt(String exerciseId, String userId, String locationId) {
        Exercise exercise = exercises.findById(exerciseId).orElse(null);
        if (exercise == null) {
            return false;
        }
        return mediaOk(exercise) && satisfiedBy(exercise, gearAt(userId, locationId));
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
