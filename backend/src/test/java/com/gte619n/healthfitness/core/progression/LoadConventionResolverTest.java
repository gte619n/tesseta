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
import com.gte619n.healthfitness.core.exercise.LoadConvention;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.testsupport.InMemoryEquipmentRepository;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * IMPL-PROG-LOAD-01 P0 gate — the per-hand/total load convention derivation
 * (D5/D6/D11, IL-4/IL-5) plus the explicit override (IL-2).
 */
class LoadConventionResolverTest {

    // ---- pure derivation (IL-4/IL-5) ----

    @Test
    void bilateralDumbbellIsPerHand() {
        Exercise ex = ex("Dumbbell Bench Press", Laterality.BILATERAL, req("db"));
        assertEquals(LoadConvention.PER_HAND,
            LoadConventionResolver.derive(ex, List.of(dumbbell("db"))));
    }

    @Test
    void unilateralDumbbellIsTotal() {
        // Single-arm row: one hand → the logged number is already the total for that side.
        Exercise ex = ex("Single-Arm Dumbbell Row", Laterality.UNILATERAL, req("db"));
        assertEquals(LoadConvention.TOTAL,
            LoadConventionResolver.derive(ex, List.of(dumbbell("db"))));
    }

    @Test
    void dualCableTrainerIsPerHand() {
        Exercise ex = ex("Cable Fly", Laterality.BILATERAL, req("cbl"));
        Equipment dual = equipment("cbl", "Cable Systems", "Dual Cable", SpecSchema.CABLE);
        assertEquals(LoadConvention.PER_HAND, LoadConventionResolver.derive(ex, List.of(dual)));
    }

    @Test
    void singleStackCableIsTotal() {
        // Lat pulldown pin weight is already the total resistance (D5).
        Exercise ex = ex("Lat Pulldown", Laterality.BILATERAL, req("cbl"));
        Equipment single = equipment("cbl", "Cable Systems", "Single Cable", SpecSchema.CABLE);
        assertEquals(LoadConvention.TOTAL, LoadConventionResolver.derive(ex, List.of(single)));
    }

    @Test
    void barbellIsTotal() {
        Exercise ex = ex("Barbell Back Squat", Laterality.BILATERAL, req("bar"));
        Equipment bar = equipment("bar", "Free Weights", "Barbells", SpecSchema.PLATE_LOADED);
        assertEquals(LoadConvention.TOTAL, LoadConventionResolver.derive(ex, List.of(bar)));
    }

    @Test
    void machineIsTotal() {
        Exercise ex = ex("Leg Press", Laterality.BILATERAL, req("mch"));
        Equipment machine = equipment("mch", "Machines - Strength", "Legs", SpecSchema.SELECTORIZED);
        assertEquals(LoadConvention.TOTAL, LoadConventionResolver.derive(ex, List.of(machine)));
    }

    @Test
    void bodyweightIsTotal() {
        Exercise ex = ex("Pull-up", Laterality.BILATERAL, List.of());
        assertEquals(LoadConvention.TOTAL, LoadConventionResolver.derive(ex, List.of()));
    }

    // ---- override (IL-2) ----

    @Test
    void explicitOverrideBeatsDerivation() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        InMemoryEquipmentRepository equipment = new InMemoryEquipmentRepository();
        InMemoryProgressionRepositories.Profiles profiles = new InMemoryProgressionRepositories.Profiles();
        LoadConventionResolver resolver = new LoadConventionResolver(exercises, equipment, profiles);

        // Goblet squat is the known D6 edge: BILATERAL + one dumbbell → derivation
        // wrongly says PER_HAND; the override corrects it to TOTAL.
        Exercise goblet = ex("Goblet Squat", Laterality.BILATERAL, req("db"));
        exercises.save(goblet);
        equipment.save(dumbbell("db"));

        assertEquals(LoadConvention.PER_HAND, resolver.resolve("u", "Goblet Squat")); // derivation edge
        profiles.save(new ExerciseLoadingProfile("u", "Goblet Squat", 5.0, 0.0, true, LoadConvention.TOTAL));
        assertEquals(LoadConvention.TOTAL, resolver.resolve("u", "Goblet Squat")); // override wins
    }

    @Test
    void batchFactorsResolvePerHandAndTotal() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        InMemoryEquipmentRepository equipment = new InMemoryEquipmentRepository();
        LoadConventionResolver resolver = new LoadConventionResolver(
            exercises, equipment, new InMemoryProgressionRepositories.Profiles());
        exercises.save(ex("Dumbbell Bench Press", Laterality.BILATERAL, req("db")));
        exercises.save(ex("Barbell Bench Press", Laterality.BILATERAL, req("bar")));
        equipment.save(dumbbell("db"));
        equipment.save(equipment("bar", "Free Weights", "Barbells", SpecSchema.PLATE_LOADED));

        Map<String, Integer> factors = resolver.factors(
            "u", List.of("Dumbbell Bench Press", "Barbell Bench Press", "unknown"));
        assertEquals(2, factors.get("Dumbbell Bench Press"));
        assertEquals(1, factors.get("Barbell Bench Press"));
        assertEquals(1, factors.get("unknown")); // unknown → TOTAL
    }

    // ---- fixtures ----

    private static List<EquipmentRequirement> req(String... equipmentIds) {
        return List.of(new EquipmentRequirement(List.of(equipmentIds)));
    }

    private static Equipment dumbbell(String id) {
        return equipment(id, "Free Weights", "Dumbbells", SpecSchema.WEIGHT_SET);
    }

    private static Equipment equipment(String id, String category, String subcategory, SpecSchema schema) {
        return new Equipment(id, id, category, subcategory, schema, Map.of(), null, List.of(),
            ImageStatus.PENDING, null, EquipmentStatus.ACTIVE, "admin", 0, Instant.now(), Instant.now(), null);
    }

    /** Exercise id == name so tests read naturally; nameLower drives the name fallback. */
    private static Exercise ex(String name, Laterality laterality, List<EquipmentRequirement> reqs) {
        return new Exercise(name, name, name.toLowerCase(), List.of(), MovementPattern.OTHER,
            List.of(), List.of(), laterality, Mechanic.COMPOUND, null, List.of(), reqs,
            List.of(BlockType.MAIN), null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
