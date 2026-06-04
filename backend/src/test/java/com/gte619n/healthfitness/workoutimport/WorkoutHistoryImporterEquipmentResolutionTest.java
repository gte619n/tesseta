package com.gte619n.healthfitness.workoutimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import com.gte619n.healthfitness.core.workoutimport.FutureWorkouts;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter.ImportResult;
import com.gte619n.healthfitness.testsupport.InMemoryEquipmentRepository;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast, hermetic unit test (no {@code @SpringBootTest}, no API key) for the
 * equipment-name resolution in {@link WorkoutHistoryImporter#seedCatalog}. Drives
 * the importer with in-memory repos and a stub enricher emitting equipment names
 * with singular/plural/case drift, and asserts the resolver folds them onto the
 * curated catalog ids while reporting genuinely-absent names as unresolved.
 */
class WorkoutHistoryImporterEquipmentResolutionTest {

    private static Equipment equip(String id, String name) {
        return new Equipment(
            id, name, null, null, null, null, null, List.of(), null, null,
            EquipmentStatus.ACTIVE, null, null, null, null, null);
    }

    /** Enrichment returning a fixed equipment-name group mix; benign defaults for the rest. */
    private static final class StubEnricher implements ExerciseMetadataEnricher {
        @Override
        public Enrichment enrich(String exerciseName, List<String> allowedEquipmentNames) {
            return new Enrichment(
                MovementPattern.OTHER, List.of(), List.of(),
                Laterality.BILATERAL, Mechanic.COMPOUND, "Test.", List.of(),
                List.of(BlockType.MAIN), new RepRange(8, 12), false,
                List.of(
                    List.of("Dumbbell"),                 // singular drift -> Dumbbells
                    List.of("kettlebells"),              // wrong case -> Kettlebells
                    List.of("Adjustable Weight Bench"),  // exact
                    List.of("Barbell")));                // genuinely absent
        }
    }

    @Test
    void resolvesWithSingularAndCaseNormalizationAndReportsUnresolved() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        InMemoryEquipmentRepository equipment = new InMemoryEquipmentRepository();
        InMemoryWorkoutProgramRepository programs = new InMemoryWorkoutProgramRepository();
        InMemoryScheduledWorkoutRepository scheduled = new InMemoryScheduledWorkoutRepository();

        equipment.save(equip("eq-db", "Dumbbells"));
        equipment.save(equip("eq-kb", "Kettlebells"));
        equipment.save(equip("eq-bench", "Adjustable Weight Bench"));

        WorkoutHistoryImporter importer = new WorkoutHistoryImporter(
            exercises, equipment, new StubEnricher(), programs, scheduled);

        FutureWorkouts data = new FutureWorkouts(
            List.of(new FutureWorkouts.CatalogExercise("x1", "Test Move")),
            List.of());

        ImportResult result = importer.importAll("u", data);

        Exercise saved = exercises.findById("x1").orElseThrow();
        List<String> resolvedIds = saved.requiredEquipment().stream()
            .flatMap(r -> r.anyOf().stream())
            .toList();

        assertThat(resolvedIds)
            .as("Dumbbell (plural fold), kettlebells (case), Adjustable Weight Bench (exact) all resolve")
            .containsExactlyInAnyOrder("eq-db", "eq-kb", "eq-bench");

        // Each resolved name is its own any-of group; Barbell produced no group.
        assertThat(saved.requiredEquipment()).hasSize(3);
        for (EquipmentRequirement req : saved.requiredEquipment()) {
            assertThat(req.anyOf()).hasSize(1);
        }

        assertThat(result.unresolvedEquipmentNames())
            .as("Barbell is genuinely absent from the catalog")
            .contains("Barbell")
            .doesNotContain("Dumbbell", "kettlebells", "Adjustable Weight Bench");
    }
}
