package com.gte619n.healthfitness.core.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class ExerciseAvailabilityServiceTest {

    private static Exercise exercise(String id, List<EquipmentRequirement> reqs) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), reqs, List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null);
    }

    private static EquipmentRequirement anyOf(String... ids) {
        return new EquipmentRequirement(List.of(ids));
    }

    @Test
    void bodyweightExerciseIsAvailableAnywhere() {
        Exercise pushup = exercise("ex_pushup", List.of());
        assertTrue(ExerciseAvailabilityService.satisfiedBy(pushup, Set.of()));
        assertTrue(ExerciseAvailabilityService.satisfiedBy(pushup, Set.of("barbell")));
    }

    @Test
    void allGroupsMustBeSatisfied() {
        Exercise squat = exercise("ex_squat", List.of(anyOf("barbell"), anyOf("squat-rack", "power-rack")));
        assertTrue(ExerciseAvailabilityService.satisfiedBy(squat, Set.of("barbell", "power-rack", "plates")));
        // Missing a rack -> not satisfied.
        assertFalse(ExerciseAvailabilityService.satisfiedBy(squat, Set.of("barbell")));
        // any-of alternative satisfies the rack group.
        assertTrue(ExerciseAvailabilityService.satisfiedBy(squat, Set.of("barbell", "squat-rack")));
    }

    @Test
    void executableAtFiltersCatalogByGymGear() {
        FakeExerciseRepo exercises = new FakeExerciseRepo();
        exercises.save(exercise("ex_pushup", List.of()));
        exercises.save(exercise("ex_squat", List.of(anyOf("barbell"), anyOf("power-rack"))));
        exercises.save(exercise("ex_bench", List.of(anyOf("barbell"), anyOf("bench"))));

        FakeLocationRepo locations = new FakeLocationRepo();
        locations.put(location("u1", "hotel", List.of()));                 // bodyweight only
        locations.put(location("u1", "home", List.of("barbell", "power-rack", "plates")));

        ExerciseAvailabilityService svc = new ExerciseAvailabilityService(exercises, locations, true);

        assertEquals(List.of("ex_pushup"),
            svc.executableAt("u1", "hotel").stream().map(Exercise::exerciseId).toList());

        List<String> home = svc.executableAt("u1", "home").stream().map(Exercise::exerciseId).sorted().toList();
        assertEquals(List.of("ex_pushup", "ex_squat"), home);

        assertTrue(svc.isExecutableAt("ex_squat", "u1", "home"));
        assertFalse(svc.isExecutableAt("ex_squat", "u1", "hotel"));
    }

    private static Location location(String userId, String id, List<String> equipmentIds) {
        return new Location(userId, id, id, null, null, true, Map.of(), List.of(), equipmentIds,
            Map.of(), false, true, Instant.now(), Instant.now());
    }

    static class FakeExerciseRepo implements ExerciseRepository {
        final Map<String, Exercise> store = new ConcurrentHashMap<>();
        @Override public Optional<Exercise> findById(String id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<Exercise> findByIds(Collection<String> ids) {
            return ids.stream().map(store::get).filter(java.util.Objects::nonNull).toList();
        }
        @Override public List<Exercise> findPublished(String search, MovementPattern p, BlockType b, String m) {
            return List.copyOf(store.values());
        }
        @Override public List<Exercise> findAll() { return List.copyOf(store.values()); }
        @Override public List<Exercise> findByMediaStatus(ExerciseMediaStatus s) { return List.of(); }
        @Override public List<Exercise> findByPlanStatus(ExerciseMediaStatus s) { return List.of(); }
        @Override public void save(Exercise e) { store.put(e.exerciseId(), e); }
        @Override public void delete(String id) { store.remove(id); }
    }

    static class FakeLocationRepo implements LocationRepository {
        final Map<String, Location> store = new ConcurrentHashMap<>();
        void put(Location l) { store.put(l.userId() + "/" + l.locationId(), l); }
        @Override public Optional<Location> findById(String userId, String locationId) {
            return Optional.ofNullable(store.get(userId + "/" + locationId));
        }
        @Override public List<Location> findByUser(String userId, boolean includeInactive) { return List.of(); }
        @Override public void save(Location location) { put(location); }
        @Override public void delete(String userId, String locationId) { }
        @Override public void setDefault(String userId, String locationId) { }
        @Override public List<Location> findAllReferencing(String equipmentId) { return List.of(); }
    }
}
