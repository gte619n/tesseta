package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.testsupport.InMemoryBodyCompositionRepository;
import com.gte619n.healthfitness.testsupport.InMemoryEquipmentRepository;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.InMemoryLocationRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IMPL-PROG-LOAD-01 P3 gate — the smallest-real-jump increment resolution from
 * the user's actual gym equipment (D4/D7/IL-6), with the name-based fallback
 * (D12). Proves the engine reads the gym, not the exercise name, and never
 * yields an unloadable step.
 */
class LoadingProfileResolverTest {

    private static final String USER = "u";
    private static final String LOC = "gym1";

    private InMemoryExerciseRepository exercises;
    private InMemoryEquipmentRepository equipment;
    private InMemoryLocationRepository locations;
    private LoadingProfileResolver resolver;

    @BeforeEach
    void setUp() {
        exercises = new InMemoryExerciseRepository();
        equipment = new InMemoryEquipmentRepository();
        locations = new InMemoryLocationRepository();
        resolver = new LoadingProfileResolver(
            exercises, new InMemoryBodyCompositionRepository(),
            new InMemoryProgressionRepositories.Profiles(), equipment, locations);
    }

    @Test
    void dumbbellIncrementComesFromTheGymWeightSet() {
        exercises.save(ex("db-curl", "Dumbbell Curl", "db"));
        equipment.save(eq("db", "Free Weights", "Dumbbells", SpecSchema.WEIGHT_SET,
            Map.of("increment", 2.5))); // this gym's DBs jump by 2.5/hand
        saveGym("db");
        // Gym-driven 2.5, NOT the name-based default of 5.
        assertEquals(2.5, resolver.resolve(USER, "db-curl", LOC).loadIncrementLbs(), 1e-9);
    }

    @Test
    void barbellIncrementIsAPairOfTheSmallestPlate() {
        exercises.save(ex("bb-squat", "Barbell Squat", "bar"));
        equipment.save(eq("bar", "Free Weights", "Barbells", SpecSchema.PLATE_LOADED,
            Map.of("availablePlates", List.of(1.25, 2.5, 5, 10, 25, 45))));
        saveGym("bar");
        // Smallest jump = a pair of the 1.25 plate = 2.5.
        assertEquals(2.5, resolver.resolve(USER, "bb-squat", LOC).loadIncrementLbs(), 1e-9);
    }

    @Test
    void selectorizedStackIncrementComesFromTheStackStep() {
        exercises.save(ex("cable-row", "Cable Row", "stack"));
        equipment.save(eq("stack", "Machines - Strength", "Back", SpecSchema.SELECTORIZED,
            Map.of("increment", 15))); // 15 lb stack plates
        saveGym("stack");
        // Gym-driven 15, NOT the name-based machine default of 10.
        assertEquals(15.0, resolver.resolve(USER, "cable-row", LOC).loadIncrementLbs(), 1e-9);
    }

    @Test
    void weightSetWithoutIncrementInfersFromAvailableWeights() {
        exercises.save(ex("db-press", "Dumbbell Press", "db"));
        equipment.save(eq("db", "Free Weights", "Dumbbells", SpecSchema.WEIGHT_SET,
            Map.of("weights", List.of(10, 15, 20, 25)))); // smallest gap = 5
        saveGym("db");
        assertEquals(5.0, resolver.resolve(USER, "db-press", LOC).loadIncrementLbs(), 1e-9);
    }

    @Test
    void fallsBackToNameDefaultsWhenNoLocation() {
        exercises.save(ex("db-curl", "Dumbbell Curl", "db"));
        equipment.save(eq("db", "Free Weights", "Dumbbells", SpecSchema.WEIGHT_SET, Map.of("increment", 2.5)));
        saveGym("db");
        // No location on the session → name-based default (dumbbell → 5).
        assertEquals(5.0, resolver.resolve(USER, "db-curl", null).loadIncrementLbs(), 1e-9);
    }

    @Test
    void fallsBackToNameDefaultsWhenGymSpecUnusable() {
        exercises.save(ex("leg-press", "Leg Press Machine", "mch"));
        equipment.save(eq("mch", "Machines - Strength", "Legs", SpecSchema.SELECTORIZED, Map.of())); // no spec
        saveGym("mch");
        // Machine name default = 10 when the gym spec carries no increment.
        assertEquals(10.0, resolver.resolve(USER, "leg-press", LOC).loadIncrementLbs(), 1e-9);
    }

    // ---- fixtures ----

    private void saveGym(String... equipmentIds) {
        locations.save(new Location(USER, LOC, "Gym", null, null, true, Map.of(), List.of(),
            List.of(equipmentIds), Map.of(), true, true, Instant.now(), Instant.now()));
    }

    private static Exercise ex(String id, String name, String equipmentId) {
        return new Exercise(id, name, name.toLowerCase(), List.of(), MovementPattern.OTHER,
            List.of(), List.of(), Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(),
            List.of(new EquipmentRequirement(List.of(equipmentId))),
            List.of(BlockType.MAIN), null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }

    private static Equipment eq(
        String id, String category, String subcategory, SpecSchema schema, Map<String, Object> specs) {
        return new Equipment(id, id, category, subcategory, schema, specs, null, List.of(),
            ImageStatus.PENDING, null, EquipmentStatus.ACTIVE, "admin", 0, Instant.now(), Instant.now(), null);
    }
}
